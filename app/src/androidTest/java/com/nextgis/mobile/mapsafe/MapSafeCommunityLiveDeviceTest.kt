package com.nextgis.mobile.mapsafe

import android.Manifest
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.nextgis.maplib.util.AccountUtil
import com.nextgis.maplib.util.NGWUtil
import com.nextgis.maplib.util.NetworkUtil
import com.nextgis.mobile.MainApplication
import com.nextgis.mobile.mapsafe.community.CommunityArtifactType
import com.nextgis.mobile.mapsafe.community.CommunityArtifactAudience
import com.nextgis.mobile.mapsafe.community.CommunityAudienceMember
import com.nextgis.mobile.mapsafe.community.NextGisCommunityLayerClient
import com.nextgis.mobile.mapsafe.community.NextGisCommunityNames
import com.nextgis.mobile.mapsafe.community.NextGisCommunityPackageClient
import com.nextgis.mobile.mapsafe.community.NextGisCommunityPublisher
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpEngine
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyGenerator
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyRepository
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpSignatureStatus
import com.nextgis.mobile.mapsafe.keys.MapSafeSecurityPreferences
import com.nextgis.mobile.mapsafe.keys.NextGisPublicKeyDirectoryClient
import com.nextgis.mobile.mapsafe.keys.PublicKeyExchangeRepository
import com.nextgis.mobile.mapsafe.safeguard.anonymise.DonutMasking
import com.nextgis.mobile.mapsafe.safeguard.anonymise.PortableHexGrid
import com.nextgis.mobile.mapsafe.service.HashUtils
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.time.Instant

/**
 * Opt-in smoke test for a real NextGIS Web instance.
 *
 * Credentials are never stored in this source file. Supply these instrumentation arguments:
 * mapsafe.live.server, mapsafe.live.account, mapsafe.live.login and mapsafe.live.password.
 * The test intentionally leaves the created Community A hierarchy and timestamped synthetic
 * artifacts online so they can be inspected from MapSafe's Community Packages screen.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MapSafeCommunityLiveDeviceTest {

    @Test
    fun uploadListDownloadAndValidateEveryCommunityArtifactType() {
        val arguments = InstrumentationRegistry.getArguments()
        val server = arguments.getString(ARG_SERVER)?.trimEnd('/').orEmpty()
        val accountName = arguments.getString(ARG_ACCOUNT).orEmpty()
        val login = arguments.getString(ARG_LOGIN).orEmpty()
        val password = arguments.getString(ARG_PASSWORD).orEmpty()
        assumeTrue("Live server argument was not supplied.", server.startsWith("https://"))
        assumeTrue("Live account argument was not supplied.", accountName.isNotBlank())
        assumeTrue("Live login argument was not supplied.", login.isNotBlank())
        assumeTrue("Live password argument was not supplied.", password.isNotBlank())

        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.GET_ACCOUNTS
            )
        }
        val existing = context.getAccount(accountName)
        if (existing == null) {
            assertTrue(
                "Could not add the live NextGIS account to Android AccountManager.",
                context.addAccount(accountName, server, login, password, "")
            )
        } else {
            context.setUserData(accountName, "url", server)
            context.setUserData(accountName, "login", login)
            context.setPassword(accountName, password)
        }

        val keyRepository = OpenPgpKeyRepository(context)
        val generatedIdentity = OpenPgpKeyGenerator.generate(
            "MapSafe live community test <mapsafe-live@example.invalid>",
            TEST_PASSPHRASE.copyOf(),
            rsaBits = 2048
        )
        if (!keyRepository.hasLocalIdentity()) {
            keyRepository.saveLocalIdentity(generatedIdentity)
        }
        val localFingerprint = requireNotNull(keyRepository.localIdentityInfo()).fingerprint
        val directoryClient = NextGisPublicKeyDirectoryClient(
            context,
            keyRepository,
            PublicKeyExchangeRepository(context)
        )
        val account = directoryClient.accountSummaries().single { it.accountName == accountName }
        val community = directoryClient.membershipGroups(accountName)
            .firstOrNull { it.displayName == COMMUNITY_NAME }
            ?: directoryClient.createGroup(
                accountName,
                COMMUNITY_NAME,
                "MapSafe community for exchanging public keys and protected geospatial artifacts."
            )
        MapSafeSecurityPreferences.selectGroup(context, account, community)
        val selection = MapSafeSecurityPreferences.read(context)
        assertTrue(selection.hasGroup)
        if (arguments.getString(ARG_BOOTSTRAP_PUBLIC_TEST).toBoolean()) {
            ensureSyntheticFreePlanHierarchy(context, accountName, community.id, community.displayName)
        }

        val keyPublish = directoryClient.publish(accountName, community.id)
        assertEquals(localFingerprint, keyPublish.fingerprint)
        val keySync = directoryClient.sync(accountName, community.id)
        assertTrue(keySync.records.any { it.observedFingerprint == localFingerprint })

        val paperDemo = arguments.getString(ARG_PAPER_DEMO).toBoolean()
        val runId = Instant.now().toString().replace(Regex("[^0-9]"), "").take(14)
        val root = File(context.cacheDir, "mapsafe-community-live/$runId").apply { mkdirs() }
        val originalGeoJson = context.assets.open("mapsafe/north_whangarei_infected_trees.geojson")
            .bufferedReader()
            .use { it.readText() }
        val haloName = if (paperDemo) {
            "North Whangarei infected trees - halo masked.geojson"
        } else {
            "mapsafe-live-halo-$runId.geojson"
        }
        val hexbinName = if (paperDemo) {
            "North Whangarei infected trees - hexagonal aggregate.geojson"
        } else {
            "mapsafe-live-hexbin-$runId.geojson"
        }
        val packageName = if (paperDemo) {
            "North Whangarei infected trees - protected original.pgp"
        } else {
            "mapsafe-live-package-$runId.pgp"
        }
        val halo = File(root, haloName).apply {
            writeText(maskedGeoJson(originalGeoJson))
        }
        val hexbin = File(root, hexbinName).apply {
            writeText(hexbinGeoJson(originalGeoJson))
        }
        val publisher = NextGisCommunityPublisher(context)
        val haloPublished = publisher.publishGeoJson(
            selection,
            halo,
            halo.name,
            CommunityArtifactType.HALO_MASKED
        )
        val hexbinPublished = publisher.publishGeoJson(
            selection,
            hexbin,
            hexbin.name,
            CommunityArtifactType.HEXBIN
        )

        val layerClient = NextGisCommunityLayerClient(context)
        val layers = layerClient.listLayers(selection)
        val haloRecord = layers.single { it.resourceId == haloPublished.resourceId }
        val hexbinRecord = layers.single { it.resourceId == hexbinPublished.resourceId }
        val haloDownloaded = layerClient.downloadLayer(selection, haloRecord)
        val hexbinDownloaded = layerClient.downloadLayer(selection, hexbinRecord)
        assertEquals(23, featureCount(readUri(context, haloDownloaded.uri)))
        assertTrue(featureCount(readUri(context, hexbinDownloaded.uri)) > 1)

        val protectedInput = originalGeoJson.toByteArray()
        val encryptedOutput = ByteArrayOutputStream()
        OpenPgpEngine.encrypt(
            input = ByteArrayInputStream(protectedInput),
            output = encryptedOutput,
            originalFileName = "North Whangarei infected trees - original.geojson",
            recipients = listOf(generatedIdentity.publicKeyRing),
            signingKeyRing = generatedIdentity.secretKeyRing,
            signingPassphrase = TEST_PASSPHRASE.copyOf()
        )
        val encryptedBytes = encryptedOutput.toByteArray()
        val encrypted = File(root, packageName).apply {
            writeBytes(encryptedBytes)
        }
        val packagePublished = publisher.publishAttachedFile(
            selection = selection,
            source = encrypted,
            fileName = encrypted.name,
            mimeType = "application/pgp-encrypted",
            artifactType = CommunityArtifactType.ENCRYPTED_PACKAGE,
            audience = CommunityArtifactAudience.selectedRecipients(
                listOf(
                    CommunityAudienceMember(
                        userId = requireNotNull(selection.currentUserId),
                        displayName = "Live-test publisher",
                        fingerprint = generatedIdentity.info.fingerprint
                    )
                )
            )
        )
        val packageClient = NextGisCommunityPackageClient(context)
        val packages = packageClient.listPackages(selection)
        val packageRecord = packages.single { it.featureId == packagePublished.featureId }
        assertEquals(HashUtils.sha256(encrypted), packageRecord.sha256)
        val packageDownloaded = packageClient.downloadPackage(selection, packageRecord)
        val downloadedBytes = readUri(context, packageDownloaded.uri)
        assertArrayEquals(encryptedBytes, downloadedBytes)
        assertEquals(packageRecord.sha256, packageDownloaded.calculatedSha256)

        val decrypted = ByteArrayOutputStream()
        val decryptResult = OpenPgpEngine.decrypt(
            input = ByteArrayInputStream(downloadedBytes),
            output = decrypted,
            secretKeyRings = listOf(generatedIdentity.secretKeyRing),
            passphrase = TEST_PASSPHRASE.copyOf(),
            verificationKeyRings = listOf(generatedIdentity.publicKeyRing)
        )
        assertEquals(OpenPgpSignatureStatus.VALID, decryptResult.signatureStatus)
        assertArrayEquals(protectedInput, decrypted.toByteArray())

        Log.i(
            TAG,
            "PASS server=$server community=${community.id} keyBucket=${keyPublish.bucketResourceId} " +
                "halo=${haloPublished.resourceId} hexbin=${hexbinPublished.resourceId} " +
                "packageRegistry=${packagePublished.resourceId} packageFeature=${packagePublished.featureId}"
        )
    }

    private fun readUri(context: MainApplication, uri: android.net.Uri): ByteArray =
        requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }

    private fun featureCount(bytes: ByteArray): Int =
        JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("features").length()

    /**
     * Free NextGIS Web instances do not support resource ACLs. This helper is deliberately
     * confined to the live synthetic smoke test: it stages the expected hierarchy without
     * permissions so transport can be exercised, while production code remains fail-closed.
     */
    private fun ensureSyntheticFreePlanHierarchy(
        context: MainApplication,
        accountName: String,
        groupId: Long,
        groupName: String
    ) {
        val account = AccountUtil.getAccountData(context, accountName)
        val server = NGWUtil.getServerUrl(account.url).trimEnd('/')

        fun find(keyname: String): Pair<Long, Long>? {
            val encoded = URLEncoder.encode(keyname, Charsets.UTF_8.name())
            val response = NetworkUtil.get(
                "$server/api/resource/search/?keyname=$encoded&serialization=full",
                account.login,
                account.password,
                true
            )
            check(response.isOk) { "Could not search for $keyname (HTTP ${response.responseCode})." }
            val entries = JSONArray(response.responseBody ?: "[]")
            if (entries.length() == 0) return null
            check(entries.length() == 1) { "Duplicate resource keyname $keyname." }
            val container = entries.getJSONObject(0)
            val resource = container.optJSONObject("resource") ?: container
            return resource.getLong("id") to
                (resource.optJSONObject("parent")?.optLong("id", -1L) ?: -1L)
        }

        fun ensure(parentId: Long, displayName: String, keyname: String): Long {
            find(keyname)?.let { (id, existingParent) ->
                check(existingParent == parentId) { "$keyname has an unexpected parent." }
                return id
            }
            val payload = JSONObject().put(
                "resource",
                JSONObject()
                    .put("cls", "resource_group")
                    .put("parent", JSONObject().put("id", parentId))
                    .put("display_name", displayName)
                    .put("keyname", keyname)
                    .put(
                        "description",
                        "Synthetic MapSafe live-test hierarchy. No ACLs: NextGIS Free plan test only."
                    )
            )
            val response = NetworkUtil.post(
                "$server/api/resource/",
                payload.toString(),
                account.login,
                account.password,
                true
            )
            check(response.isOk) { "Could not create $displayName (HTTP ${response.responseCode})." }
            return JSONObject(response.responseBody ?: "{}").getLong("id")
        }

        val root = ensure(0L, "MapSafe", NextGisCommunityNames.rootKey)
        val community = ensure(root, groupName, NextGisCommunityNames.communityKey(groupId))
        ensure(community, "Public Keys", NextGisCommunityNames.publicKeysKey(groupId))
        ensure(community, "Anonymised Datasets", NextGisCommunityNames.layersKey(groupId))
        ensure(community, "Encrypted Packages", NextGisCommunityNames.packagesKey(groupId))
        Log.w(TAG, "Using a synthetic public hierarchy because resource ACLs require Premium.")
    }

    private fun maskedGeoJson(source: String): String {
        val input = JSONObject(source)
        val sourceFeatures = input.getJSONArray("features")
        val maskedFeatures = JSONArray()
        for (index in 0 until sourceFeatures.length()) {
            val feature = JSONObject(sourceFeatures.getJSONObject(index).toString())
            val coordinates = feature.getJSONObject("geometry").getJSONArray("coordinates")
            val masked = DonutMasking.maskPoint(
                longitude = coordinates.getDouble(0),
                latitude = coordinates.getDouble(1),
                minDistanceMetres = 100.0,
                maxDistanceMetres = 2_000.0
            )
            coordinates.put(0, masked.longitude)
            coordinates.put(1, masked.latitude)
            feature.getJSONObject("properties")
                .put("mapsafe_representation", "halo_masked")
                .put("mapsafe_displacement_m", masked.distanceMetres)
            maskedFeatures.put(feature)
        }
        return JSONObject()
            .put("type", "FeatureCollection")
            .put("name", "North Whangarei infected trees - halo masked")
            .put("features", maskedFeatures)
            .toString(2)
    }

    private fun hexbinGeoJson(source: String): String {
        val sourceFeatures = JSONObject(source).getJSONArray("features")
        val counts = linkedMapOf<String, Int>()
        for (index in 0 until sourceFeatures.length()) {
            val coordinates = sourceFeatures.getJSONObject(index)
                .getJSONObject("geometry")
                .getJSONArray("coordinates")
            val cell = PortableHexGrid.pointToCell(
                latitude = coordinates.getDouble(1),
                longitude = coordinates.getDouble(0),
                resolution = 8
            )
            counts[cell] = (counts[cell] ?: 0) + 1
        }
        val features = JSONArray()
        counts.forEach { (cell, count) ->
            val ring = JSONArray()
            PortableHexGrid.cellBoundaryToLatLon(cell).forEach { (latitude, longitude) ->
                ring.put(JSONArray().put(longitude).put(latitude))
            }
            val first = ring.getJSONArray(0)
            ring.put(JSONArray().put(first.getDouble(0)).put(first.getDouble(1)))
            features.put(
                JSONObject()
                    .put("type", "Feature")
                    .put(
                        "properties",
                        JSONObject()
                            .put("cell_id", cell)
                            .put("point_count", count)
                            .put("mapsafe_representation", "hexagonal_aggregate")
                    )
                    .put(
                        "geometry",
                        JSONObject()
                            .put("type", "Polygon")
                            .put("coordinates", JSONArray().put(ring))
                    )
            )
        }
        return JSONObject()
            .put("type", "FeatureCollection")
            .put("name", "North Whangarei infected trees - hexagonal aggregate")
            .put("features", features)
            .toString(2)
    }

    companion object {
        private const val TAG = "MapSafeCommunityLive"
        private const val COMMUNITY_NAME = "Community A"
        private const val ARG_SERVER = "mapsafe.live.server"
        private const val ARG_ACCOUNT = "mapsafe.live.account"
        private const val ARG_LOGIN = "mapsafe.live.login"
        private const val ARG_PASSWORD = "mapsafe.live.password"
        private const val ARG_BOOTSTRAP_PUBLIC_TEST = "mapsafe.live.bootstrap_public_test"
        private const val ARG_PAPER_DEMO = "mapsafe.live.paper_demo"
        private val TEST_PASSPHRASE = "mapsafe live community smoke test".toCharArray()
    }
}
