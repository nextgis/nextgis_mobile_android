package com.nextgis.mobile.mapsafe.community

import android.content.Context
import com.nextgis.maplib.util.AccountUtil
import com.nextgis.maplib.util.NGWUtil
import com.nextgis.maplib.util.NetworkUtil
import com.nextgis.mobile.mapsafe.keys.MapSafeSecurityPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

/** Public OpenPGP key stored as an attachment on a hosted-compatible vector registry record. */
data class CommunityPublicKeyRecord(
    val recordId: String,
    val communityId: Long,
    val publisherId: Long,
    val publisherName: String,
    val createdAt: String,
    val status: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val fingerprint: String,
    val keyVersion: Int,
    val registryResourceId: Long,
    val featureId: Long,
    internal val attachmentId: Long
)

/** Lists and downloads public-key attachments visible to the selected NextGIS group. */
class NextGisCommunityPublicKeyClient(context: Context) {
    private val context = context.applicationContext

    fun listPublicKeys(
        selection: MapSafeSecurityPreferences.Selection
    ): List<CommunityPublicKeyRecord> {
        val resolved = resolve(selection)
        val community = findResourceByKey(
            resolved.account,
            NextGisCommunityNames.communityKey(resolved.groupId)
        ) ?: return emptyList()
        val keys = findResourceByKey(
            resolved.account,
            NextGisCommunityNames.publicKeysKey(resolved.groupId)
        ) ?: return emptyList()
        if (keys.parentId != community.id || keys.cls != RESOURCE_GROUP_CLASS) {
            throw NextGisCommunityPublishException(
                "The selected community's public-key directory is invalid."
            )
        }

        return publisherResources(
            resolved.account,
            keys.id,
            resolved.groupId,
            CommunityArtifactStorage.PUBLIC_KEYS
        )
            .asSequence()
            .filter { resource ->
                resource.cls == VECTOR_LAYER_CLASS &&
                    resource.keyname?.startsWith("mapsafe_keys_g${resolved.groupId}_u") == true
            }
            .flatMap { registry ->
                publicKeyFeatures(resolved.account, registry, resolved.groupId).asSequence()
            }
            .sortedWith(
                compareByDescending<CommunityPublicKeyRecord> { it.keyVersion }
                    .thenByDescending { runCatching { Instant.parse(it.createdAt) }.getOrNull() }
                    .thenByDescending { it.featureId }
            )
            .toList()
    }

    private fun publisherResources(
        account: AccountUtil.AccountData,
        directoryId: Long,
        groupId: Long,
        storage: CommunityArtifactStorage
    ): List<NextGisResource> {
        val direct = childResources(account, directoryId)
        val memberPrefix = NextGisCommunityNames.memberFolderPrefix(groupId, storage)
        return direct + direct
            .filter { it.cls == RESOURCE_GROUP_CLASS && it.keyname?.startsWith(memberPrefix) == true }
            .flatMap { childResources(account, it.id) }
    }

    fun downloadPublicKey(
        selection: MapSafeSecurityPreferences.Selection,
        selected: CommunityPublicKeyRecord
    ): ByteArray {
        val resolved = resolve(selection)
        if (selected.communityId != resolved.groupId) {
            throw NextGisCommunityPublishException(
                "This public key belongs to a different NextGIS community."
            )
        }
        val feature = getObject(
            resolved.account,
            "${resourceApiUrl(resolved.account, selected.registryResourceId)}/feature/" +
                "${selected.featureId}?dt_format=iso&extensions=attachment"
        )
        val current = parsePublicKeyFeature(
            getResource(resolved.account, selected.registryResourceId),
            feature,
            resolved.groupId
        ) ?: throw NextGisCommunityPublishException(
            "The selected community public key is no longer available or its metadata is invalid."
        )
        if (current.recordId != selected.recordId ||
            current.fingerprint != selected.fingerprint ||
            current.sha256 != selected.sha256
        ) {
            throw NextGisCommunityPublishException(
                "The selected public-key record changed after the directory was refreshed."
            )
        }

        val url = server(resolved.account) + NextGisCommunityNames.attachmentDownloadPath(
            current.registryResourceId,
            current.featureId,
            current.attachmentId
        )
        val connection = NetworkUtil.getHttpConnection(
            "GET",
            url,
            resolved.account.login,
            resolved.account.password
        ) ?: throw NextGisCommunityPublishException("Could not open the NextGIS public-key download.")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw NextGisCommunityPublishException(
                    "NextGIS returned HTTP ${connection.responseCode} while downloading the public key."
                )
            }
            if (connection.contentLengthLong > MAX_PUBLIC_KEY_BYTES) {
                throw NextGisCommunityPublishException("The community public key exceeds the size limit.")
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_PUBLIC_KEY_BYTES) {
                        throw NextGisCommunityPublishException(
                            "The community public key exceeds the size limit."
                        )
                    }
                    output.write(buffer, 0, count)
                }
            }
            val bytes = output.toByteArray()
            val actual = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
            if (actual != current.sha256) {
                throw NextGisCommunityPublishException(
                    "The downloaded public key does not match its published SHA-256."
                )
            }
            return bytes
        } finally {
            connection.disconnect()
        }
    }

    private fun publicKeyFeatures(
        account: AccountUtil.AccountData,
        registry: NextGisResource,
        groupId: Long
    ): List<CommunityPublicKeyRecord> {
        val features = getArray(
            account,
            "${resourceApiUrl(account, registry.id)}/feature/?dt_format=iso&extensions=attachment"
        )
        return buildList {
            for (index in 0 until features.length()) {
                parsePublicKeyFeature(registry, features.getJSONObject(index), groupId)?.let(::add)
            }
        }
    }

    private fun parsePublicKeyFeature(
        registry: NextGisResource,
        feature: JSONObject,
        expectedGroupId: Long
    ): CommunityPublicKeyRecord? {
        val featureId = feature.optLong("id", -1L).takeIf { it > 0L } ?: return null
        val fields = feature.optJSONObject("fields") ?: return null
        if (fields.optString(NextGisCommunityRecordSchema.FIELD_ARTIFACT_TYPE) !=
            CommunityArtifactType.PUBLIC_KEY.wireName
        ) return null
        val communityId = fields.optLong(NextGisCommunityRecordSchema.FIELD_GROUP_ID, -1L)
        if (communityId != expectedGroupId) return null
        val publisherId = fields.optLong(NextGisCommunityRecordSchema.FIELD_PUBLISHER_ID, -1L)
        if (publisherId <= 0L || (registry.ownerUserId > 0L && registry.ownerUserId != publisherId)) {
            return null
        }
        val recordId = fields.optString(NextGisCommunityRecordSchema.FIELD_RECORD_ID)
            .takeIf(String::isNotBlank) ?: return null
        val sha256 = fields.optString(NextGisCommunityRecordSchema.FIELD_SHA256)
            .lowercase(Locale.ROOT)
        if (!SHA_256.matches(sha256)) return null
        val fingerprint = fields.optString(NextGisCommunityRecordSchema.FIELD_FINGERPRINT)
            .replace(Regex("[^0-9A-Fa-f]"), "")
            .uppercase(Locale.ROOT)
        if (!OPENPGP_FINGERPRINT.matches(fingerprint)) return null
        val keyVersion = fields.optString(NextGisCommunityRecordSchema.FIELD_KEY_VERSION)
            .toIntOrNull()?.coerceAtLeast(1) ?: 1
        val status = fields.optString(NextGisCommunityRecordSchema.FIELD_STATUS)
        if (status !in KEY_STATUSES) return null
        val createdAt = fields.optString(NextGisCommunityRecordSchema.FIELD_CREATED_AT)
            .takeIf { runCatching { Instant.parse(it) }.isSuccess } ?: return null
        val fileName = safeFileName(
            fields.optString(NextGisCommunityRecordSchema.FIELD_FILE_NAME),
            "public-key-$publisherId.asc"
        )
        val attachments = feature.optJSONObject("extensions")
            ?.optJSONArray("attachment") ?: return null
        val attachment = (0 until attachments.length())
            .map { attachments.getJSONObject(it) }
            .firstOrNull { it.optString("name") == fileName }
            ?: return null
        val attachmentId = attachment.optLong("id", -1L).takeIf { it > 0L } ?: return null
        val publisherName = registry.displayName.substringAfter('—', "Member $publisherId").trim()
            .ifBlank { "Member $publisherId" }
        return CommunityPublicKeyRecord(
            recordId = recordId,
            communityId = communityId,
            publisherId = publisherId,
            publisherName = publisherName,
            createdAt = createdAt,
            status = status,
            fileName = fileName,
            mimeType = fields.optString(NextGisCommunityRecordSchema.FIELD_MIME_TYPE)
                .ifBlank { attachment.optString("mime_type").ifBlank { MIME_OPENPGP_KEY } },
            sizeBytes = attachment.optLong("size", 0L).coerceAtLeast(0L),
            sha256 = sha256,
            fingerprint = fingerprint,
            keyVersion = keyVersion,
            registryResourceId = registry.id,
            featureId = featureId,
            attachmentId = attachmentId
        )
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

    private fun findResourceByKey(account: AccountUtil.AccountData, keyname: String): NextGisResource? {
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

    private fun childResources(account: AccountUtil.AccountData, parentId: Long): List<NextGisResource> {
        val resources = getArray(account, "${server(account)}/api/resource/?parent=$parentId")
        return buildList {
            for (index in 0 until resources.length()) add(resourceFromJson(resources.getJSONObject(index)))
        }
    }

    private fun getResource(account: AccountUtil.AccountData, id: Long): NextGisResource =
        resourceFromJson(getObject(account, resourceApiUrl(account, id)))

    private fun resourceFromJson(container: JSONObject): NextGisResource {
        val resource = container.optJSONObject("resource") ?: container
        return NextGisResource(
            id = resource.optLong("id", -1L),
            cls = resource.optString("cls"),
            parentId = resource.optJSONObject("parent")?.optLong("id", -1L) ?: -1L,
            ownerUserId = resource.optJSONObject("owner_user")?.optLong("id", -1L) ?: -1L,
            keyname = resource.optString("keyname").takeIf { !resource.isNull("keyname") },
            displayName = resource.optString("display_name")
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

    private data class NextGisResource(
        val id: Long,
        val cls: String,
        val parentId: Long,
        val ownerUserId: Long,
        val keyname: String?,
        val displayName: String
    )

    companion object {
        private const val RESOURCE_GROUP_CLASS = "resource_group"
        private const val VECTOR_LAYER_CLASS = "vector_layer"
        private const val MIME_OPENPGP_KEY = "application/pgp-keys"
        private const val MAX_PUBLIC_KEY_BYTES = 2L * 1024L * 1024L
        private val SHA_256 = Regex("[0-9a-f]{64}")
        private val OPENPGP_FINGERPRINT = Regex("(?:[0-9A-F]{40}|[0-9A-F]{64})")
        private val KEY_STATUSES = setOf("active", "superseded", "revoked")
    }
}
