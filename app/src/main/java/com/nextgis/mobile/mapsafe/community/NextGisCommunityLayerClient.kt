package com.nextgis.mobile.mapsafe.community

import android.content.Context
import android.net.Uri
import com.nextgis.maplib.util.AccountUtil
import com.nextgis.maplib.util.NGWUtil
import com.nextgis.maplib.util.NetworkUtil
import com.nextgis.mobile.mapsafe.keys.MapSafeSecurityPreferences
import com.nextgis.mobile.mapsafe.service.MapSafeSaveFolderRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

/** An anonymised vector dataset visible through the selected community resource ACL. */
data class CommunityLayerRecord(
    val recordId: String,
    val artifactType: CommunityArtifactType,
    val communityId: Long,
    val publisherId: Long,
    val publisherName: String,
    val createdAt: String,
    val status: String,
    val fileName: String,
    val sourceSha256: String,
    val resourceId: Long
)

data class CommunityLayerDownload(
    val record: CommunityLayerRecord,
    val uri: Uri,
    val fileName: String,
    val calculatedSha256: String,
    val displayLocation: String
)

/** Lists and exports halo-masked and hexagonal-binned NextGIS vector resources. */
class NextGisCommunityLayerClient(context: Context) {
    private val context = context.applicationContext

    fun listLayers(selection: MapSafeSecurityPreferences.Selection): List<CommunityLayerRecord> {
        val resolved = resolve(selection)
        val community = findResourceByKey(
            resolved.account,
            NextGisCommunityNames.communityKey(resolved.groupId)
        ) ?: return emptyList()
        val directory = findResourceByKey(
            resolved.account,
            NextGisCommunityNames.layersKey(resolved.groupId)
        ) ?: return emptyList()
        if (directory.parentId != community.id || directory.cls != RESOURCE_GROUP_CLASS) {
            throw NextGisCommunityPublishException(
                "The selected community's anonymised-dataset directory is invalid."
            )
        }

        val publisherNames = runCatching {
            NextGisCommunityPublicKeyClient(context)
                .listPublicKeys(selection)
                .groupBy { it.publisherId }
                .mapValues { (_, records) ->
                    records.maxWithOrNull(compareBy({ it.keyVersion }, { it.createdAt }, { it.featureId }))
                        ?.publisherName
                        .orEmpty()
                }
        }.getOrDefault(emptyMap())

        return publisherResources(
            resolved.account,
            directory.id,
            resolved.groupId,
            CommunityArtifactStorage.NATIVE_LAYER
        )
            .asSequence()
            .filter { resource ->
                resource.cls == VECTOR_LAYER_CLASS &&
                    resource.keyname?.startsWith("mapsafe_artifact_g${resolved.groupId}_u") == true
            }
            .mapNotNull { resource ->
                parseLayer(
                    resolved.account,
                    resource,
                    resolved.groupId,
                    publisherNames
                )
            }
            .sortedWith(
                compareByDescending<CommunityLayerRecord> {
                    runCatching { Instant.parse(it.createdAt) }.getOrNull()
                }.thenByDescending { it.resourceId }
            )
            .toList()
    }

    private fun publisherResources(
        account: AccountUtil.AccountData,
        directoryId: Long,
        groupId: Long,
        storage: CommunityArtifactStorage
    ): List<NextGisLayerResource> {
        val direct = childResources(account, directoryId)
        val memberPrefix = NextGisCommunityNames.memberFolderPrefix(groupId, storage)
        return direct + direct
            .filter { it.cls == RESOURCE_GROUP_CLASS && it.keyname?.startsWith(memberPrefix) == true }
            .flatMap { childResources(account, it.id) }
    }

    fun downloadLayer(
        selection: MapSafeSecurityPreferences.Selection,
        selected: CommunityLayerRecord
    ): CommunityLayerDownload {
        val resolved = resolve(selection)
        if (selected.communityId != resolved.groupId) {
            throw NextGisCommunityPublishException(
                "This anonymised dataset belongs to a different NextGIS community."
            )
        }
        val current = parseLayer(
            resolved.account,
            getResource(resolved.account, selected.resourceId),
            resolved.groupId,
            mapOf(selected.publisherId to selected.publisherName)
        ) ?: throw NextGisCommunityPublishException(
            "The selected anonymised dataset is no longer available or its metadata is invalid."
        )
        if (current.recordId != selected.recordId || current.sourceSha256 != selected.sourceSha256) {
            throw NextGisCommunityPublishException(
                "The selected dataset record changed after the community list was refreshed. Refresh and choose it again."
            )
        }

        val saved = MapSafeSaveFolderRepository.save(
            context,
            MIME_GEOJSON,
            current.fileName
        ) { outputUri ->
            context.contentResolver.openOutputStream(outputUri, "w")?.use { output ->
                downloadGeoJson(resolved.account, current.resourceId, output)
            } ?: throw NextGisCommunityPublishException(
                "Android could not create the downloaded dataset in Downloads/MapSafe."
            )
        }
        return CommunityLayerDownload(
            record = current,
            uri = saved.uri,
            fileName = saved.fileName,
            calculatedSha256 = saved.value,
            displayLocation = saved.displayLocation
        )
    }

    private fun parseLayer(
        account: AccountUtil.AccountData,
        summary: NextGisLayerResource,
        expectedGroupId: Long,
        knownPublisherNames: Map<Long, String> = emptyMap()
    ): CommunityLayerRecord? {
        val container = getObject(account, resourceApiUrl(account, summary.id))
        val resource = resourceFromJson(container)
        if (resource.cls != VECTOR_LAYER_CLASS || resource.parentId != summary.parentId) return null
        val metadata = container.optJSONObject("resmeta")?.optJSONObject("items") ?: return null
        if (metadata.optString(META_SCHEMA) != NextGisCommunityRecordSchema.RECORD_SCHEMA) return null
        val artifactType = when (metadata.optString(META_ARTIFACT_TYPE)) {
            CommunityArtifactType.HALO_MASKED.wireName -> CommunityArtifactType.HALO_MASKED
            CommunityArtifactType.HEXBIN.wireName -> CommunityArtifactType.HEXBIN
            else -> return null
        }
        val communityId = metadata.optLong(META_GROUP_ID, -1L)
        if (communityId != expectedGroupId) return null
        val publisherId = metadata.optLong(META_USER_ID, -1L)
        if (publisherId <= 0L || (resource.ownerUserId > 0L && resource.ownerUserId != publisherId)) return null
        val recordId = metadata.optString(META_RECORD_ID).takeIf(String::isNotBlank) ?: return null
        val createdAt = metadata.optString(META_CREATED_AT)
            .takeIf { runCatching { Instant.parse(it) }.isSuccess } ?: return null
        val sourceSha256 = metadata.optString(META_SHA256).lowercase(Locale.ROOT)
        if (!SHA_256.matches(sourceSha256)) return null
        val fileName = safeFileName(
            metadata.optString(META_FILE_NAME),
            "mapsafe-anonymised-${resource.id}.geojson"
        ).let { if (it.lowercase(Locale.ROOT).endsWith(".geojson")) it else "$it.geojson" }
        return CommunityLayerRecord(
            recordId = recordId,
            artifactType = artifactType,
            communityId = communityId,
            publisherId = publisherId,
            publisherName = knownPublisherNames[publisherId]
                ?.takeIf(String::isNotBlank)
                ?: resource.ownerDisplayName.ifBlank { "Member $publisherId" },
            createdAt = createdAt,
            status = metadata.optString(META_STATUS).ifBlank { "published" },
            fileName = fileName,
            sourceSha256 = sourceSha256,
            resourceId = resource.id
        )
    }

    private fun downloadGeoJson(
        account: AccountUtil.AccountData,
        resourceId: Long,
        output: OutputStream
    ): String {
        val url = "${resourceApiUrl(account, resourceId)}/geojson"
        val connection = NetworkUtil.getHttpConnection("GET", url, account.login, account.password)
            ?: throw NextGisCommunityPublishException("Could not open the NextGIS dataset download.")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw NextGisCommunityPublishException(
                    "NextGIS returned HTTP ${connection.responseCode} while downloading the dataset."
                )
            }
            val declaredLength = connection.contentLengthLong
            if (declaredLength > MAX_LAYER_BYTES) {
                throw NextGisCommunityPublishException("The community dataset exceeds the download size limit.")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            DigestOutputStream(output, digest).use { digestOutput ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_LAYER_BYTES) {
                            throw NextGisCommunityPublishException(
                                "The community dataset exceeds the download size limit."
                            )
                        }
                        digestOutput.write(buffer, 0, count)
                    }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun resolve(selection: MapSafeSecurityPreferences.Selection): ResolvedCommunity {
        val membership = NextGisCommunityMembershipResolver(context).resolve(selection)
        return ResolvedCommunity(membership.account, membership.groupId)
    }

    private fun account(name: String): AccountUtil.AccountData = try {
        AccountUtil.getAccountData(context, name)
    } catch (error: Exception) {
        throw NextGisCommunityPublishException(
            "The selected NextGIS account is no longer available. Sign in again.",
            error
        )
    }

    private fun findResourceByKey(account: AccountUtil.AccountData, keyname: String): NextGisLayerResource? {
        val encoded = URLEncoder.encode(keyname, Charsets.UTF_8.name())
        val resources = getArray(
            account,
            "${server(account)}/api/resource/search/?keyname=$encoded&serialization=full"
        )
        if (resources.length() == 0) return null
        if (resources.length() > 1) {
            throw NextGisCommunityPublishException("NextGIS returned duplicate resources for $keyname.")
        }
        return resourceFromJson(resources.getJSONObject(0))
    }

    private fun childResources(account: AccountUtil.AccountData, parentId: Long): List<NextGisLayerResource> {
        val resources = getArray(account, "${server(account)}/api/resource/?parent=$parentId")
        return buildList {
            for (index in 0 until resources.length()) add(resourceFromJson(resources.getJSONObject(index)))
        }
    }

    private fun getResource(account: AccountUtil.AccountData, id: Long): NextGisLayerResource =
        resourceFromJson(getObject(account, resourceApiUrl(account, id)))

    private fun resourceFromJson(container: JSONObject): NextGisLayerResource {
        val resource = container.optJSONObject("resource") ?: container
        return NextGisLayerResource(
            id = resource.optLong("id", -1L),
            cls = resource.optString("cls"),
            parentId = resource.optJSONObject("parent")?.optLong("id", -1L) ?: -1L,
            ownerUserId = resource.optJSONObject("owner_user")?.optLong("id", -1L) ?: -1L,
            ownerDisplayName = resource.optJSONObject("owner_user")
                ?.optString("display_name")
                .orEmpty(),
            keyname = resource.optString("keyname").takeIf { !resource.isNull("keyname") }
        )
    }

    private fun getObject(account: AccountUtil.AccountData, url: String): JSONObject =
        JSONObject(requireOk(NetworkUtil.get(url, account.login, account.password, true)))

    private fun getArray(account: AccountUtil.AccountData, url: String): JSONArray =
        JSONArray(requireOk(NetworkUtil.get(url, account.login, account.password, true)))

    private fun requireOk(response: com.nextgis.maplib.util.HttpResponse): String {
        if (!response.isOk) {
            throw NextGisCommunityPublishException(
                "NextGIS request failed (HTTP ${response.responseCode})."
            )
        }
        return response.responseBody
            ?: throw NextGisCommunityPublishException("NextGIS returned an empty response.")
    }

    private fun safeFileName(value: String, fallback: String): String =
        value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]+"), "_")
            .trim('.', ' ')
            .ifBlank { fallback }
            .take(120)

    private fun server(account: AccountUtil.AccountData): String =
        NGWUtil.getServerUrl(account.url).trimEnd('/')

    private fun resourceApiUrl(account: AccountUtil.AccountData, id: Long): String =
        "${server(account)}/api/resource/$id"

    private data class ResolvedCommunity(
        val account: AccountUtil.AccountData,
        val groupId: Long
    )

    private data class NextGisLayerResource(
        val id: Long,
        val cls: String,
        val parentId: Long,
        val ownerUserId: Long,
        val ownerDisplayName: String,
        val keyname: String?
    )

    companion object {
        private const val RESOURCE_GROUP_CLASS = "resource_group"
        private const val VECTOR_LAYER_CLASS = "vector_layer"
        private const val MIME_GEOJSON = "application/geo+json"
        private const val MAX_LAYER_BYTES = 100L * 1024L * 1024L
        private val SHA_256 = Regex("[0-9a-f]{64}")

        private const val META_SCHEMA = "mapsafe.schema"
        private const val META_RECORD_ID = "mapsafe.record_id"
        private const val META_ARTIFACT_TYPE = "mapsafe.artifact_type"
        private const val META_GROUP_ID = "mapsafe.nextgis_group_id"
        private const val META_USER_ID = "mapsafe.nextgis_user_id"
        private const val META_FILE_NAME = "mapsafe.file_name"
        private const val META_SHA256 = "mapsafe.sha256"
        private const val META_CREATED_AT = "mapsafe.created_at"
        private const val META_STATUS = "mapsafe.status"
    }
}
