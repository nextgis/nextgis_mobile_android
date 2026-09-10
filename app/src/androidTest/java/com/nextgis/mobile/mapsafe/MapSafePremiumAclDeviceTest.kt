package com.nextgis.mobile.mapsafe

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.nextgis.maplib.util.AccountUtil
import com.nextgis.maplib.util.NGWUtil
import com.nextgis.maplib.util.NetworkUtil
import com.nextgis.mobile.MainApplication
import com.nextgis.mobile.mapsafe.community.CommunityArtifactAudience
import com.nextgis.mobile.mapsafe.community.CommunityArtifactType
import com.nextgis.mobile.mapsafe.community.CommunityAudienceMember
import com.nextgis.mobile.mapsafe.community.NextGisCommunityLayerClient
import com.nextgis.mobile.mapsafe.community.NextGisCommunityPackageClient
import com.nextgis.mobile.mapsafe.community.NextGisCommunityPublicKeyClient
import com.nextgis.mobile.mapsafe.community.NextGisCommunityPublisher
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpEngine
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyCodec
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyGenerator
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyMaterial
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpSignatureStatus
import com.nextgis.mobile.mapsafe.keys.MapSafeSecurityPreferences
import com.nextgis.mobile.mapsafe.keys.NextGisPublicKeyDirectoryClient
import com.nextgis.mobile.mapsafe.keys.PublicKeyExchangeRepository
import com.nextgis.mobile.mapsafe.safeguard.anonymise.DonutMasking
import com.nextgis.mobile.mapsafe.safeguard.anonymise.PortableHexGrid
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant

/**
 * Opt-in multi-account acceptance test for the time-limited NextGIS Premium trial.
 *
 * It never creates users or changes group membership. Before running it, add four existing
 * NextGIS accounts to the Android device and configure the first three as members of the same
 * authentication group. Credentials remain in Android AccountManager and are not test arguments.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MapSafePremiumAclDeviceTest {

    @Test
    fun verifyRoleSeparatedCommunityExchange() {
        val arguments = InstrumentationRegistry.getArguments()
        val guardianAccount = arguments.getString(ARG_GUARDIAN_ACCOUNT).orEmpty()
        val preciseAccount = arguments.getString(ARG_PRECISE_ACCOUNT).orEmpty()
        val anonymisedAccount = arguments.getString(ARG_ANONYMISED_ACCOUNT).orEmpty()
        val outsiderAccount = arguments.getString(ARG_OUTSIDER_ACCOUNT).orEmpty()
        val communityName = arguments.getString(ARG_COMMUNITY_NAME).orEmpty()
        assumeTrue("Guardian Android account was not supplied.", guardianAccount.isNotBlank())
        assumeTrue("Precise-recipient Android account was not supplied.", preciseAccount.isNotBlank())
        assumeTrue("Anonymised-only Android account was not supplied.", anonymisedAccount.isNotBlank())
        assumeTrue("Outsider Android account was not supplied.", outsiderAccount.isNotBlank())
        assumeTrue("Community name was not supplied.", communityName.isNotBlank())

        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        val directory = NextGisPublicKeyDirectoryClient(
            context,
            com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyRepository(context),
            PublicKeyExchangeRepository(context)
        )
        val guardian = selectionFor(directory, guardianAccount, communityName)
        val precise = selectionFor(directory, preciseAccount, communityName)
        val anonymisedOnly = selectionFor(directory, anonymisedAccount, communityName)
        assertEquals(guardian.groupId, precise.groupId)
        assertEquals(guardian.groupId, anonymisedOnly.groupId)
        assertTrue(guardian.groupMemberCount >= 3)
        assertTrue(
            "The outsider must not belong to the test community.",
            directory.membershipGroups(outsiderAccount).none { it.id == guardian.groupId }
        )

        val guardianIdentity = identity("Steven (field data custodian)")
        val preciseIdentity = identity("Amber (authorised researcher)")
        val anonymisedIdentity = identity("BMA representative (anonymised-data recipient)")
        val publisher = NextGisCommunityPublisher(context)
        publishPublicKey(context, publisher, guardian, guardianIdentity, "steven")
        publishPublicKey(context, publisher, precise, preciseIdentity, "amber")
        publishPublicKey(context, publisher, anonymisedOnly, anonymisedIdentity, "bma-representative")

        val publicKeys = NextGisCommunityPublicKeyClient(context).listPublicKeys(guardian)
        assertTrue(publicKeys.any { it.fingerprint == guardianIdentity.info.fingerprint })
        assertTrue(publicKeys.any { it.fingerprint == preciseIdentity.info.fingerprint })
        assertTrue(publicKeys.any { it.fingerprint == anonymisedIdentity.info.fingerprint })

        val runId = Instant.now().toString().replace(Regex("[^0-9]"), "").take(14)
        val root = File(context.cacheDir, "mapsafe-premium-acl/$runId").apply { mkdirs() }
        val originalText = context.assets.open("mapsafe/north_whangarei_infected_trees.geojson")
            .bufferedReader()
            .use { it.readText() }
        val originalBytes = originalText.toByteArray()
        val halo = File(root, "North Whangarei infected trees - halo masked $runId.geojson")
            .apply { writeText(maskedGeoJson(originalText)) }
        val hexbin = File(root, "North Whangarei infected trees - hexagonal aggregate $runId.geojson")
            .apply { writeText(hexbinGeoJson(originalText)) }
        val haloPublished = publisher.publishGeoJson(
            guardian,
            halo,
            halo.name,
            CommunityArtifactType.HALO_MASKED
        )
        val hexbinPublished = publisher.publishGeoJson(
            guardian,
            hexbin,
            hexbin.name,
            CommunityArtifactType.HEXBIN
        )

        val encryptedBytes = ByteArrayOutputStream().use { output ->
            OpenPgpEngine.encrypt(
                input = ByteArrayInputStream(originalBytes),
                output = output,
                originalFileName = "North Whangarei infected trees - original.geojson",
                recipients = listOf(guardianIdentity.publicKeyRing, preciseIdentity.publicKeyRing),
                signingKeyRing = guardianIdentity.secretKeyRing,
                signingPassphrase = PASSPHRASE.copyOf()
            )
            output.toByteArray()
        }
        val encrypted = File(root, "North Whangarei infected trees - protected original $runId.pgp")
            .apply { writeBytes(encryptedBytes) }
        val packagePublished = publisher.publishAttachedFile(
            selection = guardian,
            source = encrypted,
            fileName = encrypted.name,
            mimeType = "application/pgp-encrypted",
            artifactType = CommunityArtifactType.ENCRYPTED_PACKAGE,
            audience = CommunityArtifactAudience.selectedRecipients(
                listOf(
                    guardian.member(guardianIdentity, "Steven (field data custodian)"),
                    precise.member(preciseIdentity, "Amber (authorised researcher)")
                )
            )
        )

        val layerClient = NextGisCommunityLayerClient(context)
        val preciseLayers = layerClient.listLayers(precise)
        val anonymisedLayers = layerClient.listLayers(anonymisedOnly)
        assertTrue(preciseLayers.any { it.resourceId == haloPublished.resourceId })
        assertTrue(preciseLayers.any { it.resourceId == hexbinPublished.resourceId })
        assertTrue(anonymisedLayers.any { it.resourceId == haloPublished.resourceId })
        assertTrue(anonymisedLayers.any { it.resourceId == hexbinPublished.resourceId })

        val packageClient = NextGisCommunityPackageClient(context)
        val guardianPackage = packageClient.listPackages(guardian)
            .single { it.registryResourceId == packagePublished.resourceId }
        val precisePackage = packageClient.listPackages(precise)
            .single { it.registryResourceId == packagePublished.resourceId }
        assertTrue(guardianPackage.recipientUserIds.contains(requireNotNull(precise.currentUserId)))
        assertTrue(packageClient.listPackages(anonymisedOnly).none {
            it.registryResourceId == packagePublished.resourceId
        })
        assertFalse(canReadResource(context, anonymisedAccount, packagePublished.resourceId))
        assertFalse(canReadResource(context, outsiderAccount, packagePublished.resourceId))
        assertFalse(canReadResource(context, outsiderAccount, haloPublished.resourceId))
        assertFalse(canReadResource(context, outsiderAccount, hexbinPublished.resourceId))

        val downloaded = packageClient.downloadPackage(precise, precisePackage)
        val downloadedBytes = requireNotNull(context.contentResolver.openInputStream(downloaded.uri))
            .use { it.readBytes() }
        assertArrayEquals(encryptedBytes, downloadedBytes)
        val decrypted = ByteArrayOutputStream()
        val decryptResult = OpenPgpEngine.decrypt(
            input = ByteArrayInputStream(downloadedBytes),
            output = decrypted,
            secretKeyRings = listOf(preciseIdentity.secretKeyRing),
            passphrase = PASSPHRASE.copyOf(),
            verificationKeyRings = listOf(guardianIdentity.publicKeyRing)
        )
        assertEquals(OpenPgpSignatureStatus.VALID, decryptResult.signatureStatus)
        assertArrayEquals(originalBytes, decrypted.toByteArray())
    }

    private fun selectionFor(
        directory: NextGisPublicKeyDirectoryClient,
        accountName: String,
        communityName: String
    ): MapSafeSecurityPreferences.Selection {
        val account = directory.accountSummaries().single { it.accountName == accountName }
        val group = directory.membershipGroups(accountName).single { it.displayName == communityName }
        return MapSafeSecurityPreferences.Selection(
            accountName = account.accountName,
            serverUrl = account.serverUrl,
            groupId = group.id,
            groupName = group.displayName,
            currentUserId = group.currentUserId,
            groupMemberCount = group.memberIds.size
        )
    }

    private fun identity(role: String): OpenPgpKeyMaterial = OpenPgpKeyGenerator.generate(
        "$role <${role.substringBefore(' ').lowercase()}@mapsafe.example.invalid>",
        PASSPHRASE.copyOf(),
        rsaBits = 2048
    )

    private fun MapSafeSecurityPreferences.Selection.member(
        identity: OpenPgpKeyMaterial,
        displayName: String
    ): CommunityAudienceMember = CommunityAudienceMember(
        userId = requireNotNull(currentUserId),
        displayName = displayName,
        fingerprint = identity.info.fingerprint
    )

    private fun publishPublicKey(
        context: MainApplication,
        publisher: NextGisCommunityPublisher,
        selection: MapSafeSecurityPreferences.Selection,
        identity: OpenPgpKeyMaterial,
        role: String
    ) {
        val file = File.createTempFile("mapsafe-$role-public-", ".asc", context.cacheDir)
        try {
            file.writeBytes(OpenPgpKeyCodec.encodePublicKeyRing(identity.publicKeyRing))
            publisher.publishAttachedFile(
                selection = selection,
                source = file,
                fileName = "$role-public-key.asc",
                mimeType = "application/pgp-keys",
                artifactType = CommunityArtifactType.PUBLIC_KEY,
                fingerprint = identity.info.fingerprint,
                keyVersion = 1
            )
        } finally {
            file.delete()
        }
    }

    private fun canReadResource(context: MainApplication, accountName: String, resourceId: Long): Boolean {
        val account = AccountUtil.getAccountData(context, accountName)
        val server = NGWUtil.getServerUrl(account.url).trimEnd('/')
        return NetworkUtil.get(
            "$server/api/resource/$resourceId",
            account.login,
            account.password,
            true
        ).isOk
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
        private const val ARG_GUARDIAN_ACCOUNT = "mapsafe.premium.guardian_account"
        private const val ARG_PRECISE_ACCOUNT = "mapsafe.premium.precise_account"
        private const val ARG_ANONYMISED_ACCOUNT = "mapsafe.premium.anonymised_account"
        private const val ARG_OUTSIDER_ACCOUNT = "mapsafe.premium.outsider_account"
        private const val ARG_COMMUNITY_NAME = "mapsafe.premium.community_name"
        private val PASSPHRASE = "MapSafe Premium ACL acceptance 2026!".toCharArray()
    }
}
