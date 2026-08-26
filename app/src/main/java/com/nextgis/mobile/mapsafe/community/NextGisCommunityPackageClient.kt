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

/** Read-only description of an encrypted package published to a MapSafe community. */
data class CommunityPackageRecord(
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
    val blockchain: CommunityBlockchainReference,
    internal val registryResourceId: Long,
    internal val featureId: Long,
    internal val attachmentId: Long
) {
    val isNotarised: Boolean
        get() = blockchain.isRecorded
}

data class CommunityPackageDownload(
    val record: CommunityPackageRecord,
    val uri: Uri,
    val fileName: String,
    val calculatedSha256: String,
    val displayLocation: String
)

/** Lists and downloads encrypted package attachments visible to the selected NextGIS group. */
class NextGisCommunityPackageClient(context: Context) {
    private val context = context.applicationContext

    fun listPackages(selection: MapSafeSecurityPreferences.Selection): List<CommunityPackageRecord> {
        val resolved = resolve(selection)
        val community = findResourceByKey(
            resolved.account,
            NextGisCommunityNames.communityKey(resolved.groupId)
        ) ?: return emptyList()
        val packages = findResourceByKey(
            resolved.account,
            NextGisCommunityNames.packagesKey(resolved.groupId)
        ) ?: return emptyList()
        if (packages.parentId != community.id || packages.cls != RESOURCE_GROUP_CLASS) {
            throw NextGisCommunityPublishException(
                "The selected community's encrypted-package directory is invalid."
            )
        }

        return childResources(resolved.account, packages.id)
            .asSequence()
            .filter { resource ->
                resource.cls == VECTOR_LAYER_CLASS &&
                    resource.keyname?.startsWith("mapsafe_packages_g${resolved.groupId}_u") == true
            }
            .flatMap { registry ->
                packageFeatures(resolved.account, registry, resolved.groupId).asSequence()
            }
            .sortedWith(
                compareByDescending<CommunityPackageRecord> {
                    runCatching { Instant.parse(it.createdAt) }.getOrNull()
                }.thenByDescending { it.featureId }
            )
            .toList()
    }

    fun downloadPackage(
        selection: MapSafeSecurityPreferences.Selection,
        selected: CommunityPackageRecord
    ): CommunityPackageDownload {
        val resolved = resolve(selection)
        if (selected.communityId != resolved.groupId) {
            throw NextGisCommunityPublishException(
                "This package belongs to a different NextGIS community."
            )
        }

        val feature = getObject(
            resolved.account,
            "${resourceApiUrl(resolved.account, selected.registryResourceId)}/feature/" +
                "${selected.featureId}?dt_format=iso&extensions=attachment"
        )
        val current = parsePackageFeature(
            registry = getResource(resolved.account, selected.registryResourceId),
            feature = feature,
            expectedGroupId = resolved.groupId
        ) ?: throw NextGisCommunityPublishException(
            "The selected community package is no longer available or its metadata is invalid."
        )
        if (current.recordId != selected.recordId || current.sha256 != selected.sha256) {
            throw NextGisCommunityPublishException(
                "The selected package record changed after the community list was refreshed. Refresh and choose it again."
            )
        }

        val saved = MapSafeSaveFolderRepository.save(
            context,
            current.mimeType.ifBlank { MIME_OPENPGP },
            current.fileName
        ) { outputUri ->
            val calculated = context.contentResolver.openOutputStream(outputUri, "w")?.use { output ->
                downloadAttachment(resolved.account, current, output)
            } ?: throw NextGisCommunityPublishException(
                "Android could not create the downloaded package in Downloads/MapSafe."
            )
            if (!calculated.equals(current.sha256, ignoreCase = true)) {
                throw NextGisCommunityPublishException(
                    "The downloaded package does not match the SHA-256 published by the community."
                )
            }
            calculated.lowercase(Locale.ROOT)
        }
        return CommunityPackageDownload(
            record = current,
            uri = saved.uri,
            fileName = saved.fileName,
            calculatedSha256 = saved.value,
            displayLocation = saved.displayLocation
        )
    }

    private fun packageFeatures(
        account: AccountUtil.AccountData,
        registry: NextGisResource,
        groupId: Long
    ): List<CommunityPackageRecord> {
        val features = getArray(
            account,
            "${resourceApiUrl(account, registry.id)}/feature/?dt_format=iso&extensions=attachment"
        )
        return buildList {
            for (index in 0 until features.length()) {
                parsePackageFeature(registry, features.getJSONObject(index), groupId)?.let(::add)
            }
        }
    }

    private fun parsePackageFeature(
        registry: NextGisResource,
        feature: JSONObject,
        expectedGroupId: Long
    ): CommunityPackageRecord? {
        val featureId = feature.optLong("id", -1L).takeIf { it > 0L } ?: return null
        val fields = feature.optJSONObject("fields") ?: return null
        if (fields.optString(NextGisCommunityRecordSchema.FIELD_ARTIFACT_TYPE) !=
            CommunityArtifactType.ENCRYPTED_PACKAGE.wireName
        ) return null
        val communityId = fields.optLong(NextGisCommunityRecordSchema.FIELD_GROUP_ID, -1L)
        if (communityId != expectedGroupId) return null
        val publisherId = fields.optLong(NextGisCommunityRecordSchema.FIELD_PUBLISHER_ID, -1L)
        if (publisherId <= 0L || (registry.ownerUserId > 0L && registry.ownerUserId != publisherId)) return null
        val recordId = fields.optString(NextGisCommunityRecordSchema.FIELD_RECORD_ID)
            .takeIf(String::isNotBlank) ?: return null
        val sha256 = fields.optString(NextGisCommunityRecordSchema.FIELD_SHA256)
            .lowercase(Locale.ROOT)
        if (!SHA_256.matches(sha256)) return null
        val fileName = safeFileName(
            fields.optString(NextGisCommunityRecordSchema.FIELD_FILE_NAME),
            "mapsafe-package-$featureId.pgp"
        )
        val attachments = feature.optJSONObject("extensions")
            ?.optJSONArray("attachment") ?: return null
        val attachment = (0 until attachments.length())
            .map { attachments.getJSONObject(it) }
            .firstOrNull { it.optString("name") == fileName }
            ?: return null
        val attachmentId = attachment.optLong("id", -1L).takeIf { it > 0L } ?: return null
        val status = fields.optString(NextGisCommunityRecordSchema.FIELD_STATUS)
        if (status !in PACKAGE_STATUSES) return null
        val createdAt = fields.optString(NextGisCommunityRecordSchema.FIELD_CREATED_AT)
            .takeIf { runCatching { Instant.parse(it) }.isSuccess } ?: return null
        val publisherName = registry.displayName.substringAfter('—', "Member $publisherId").trim()
            .ifBlank { "Member $publisherId" }
        return CommunityPackageRecord(
            recordId = recordId,
            communityId = communityId,
            publisherId = publisherId,
            publisherName = publisherName,
            createdAt = createdAt,
            status = status,
            fileName = fileName,
            mimeType = fields.optString(NextGisCommunityRecordSchema.FIELD_MIME_TYPE)
                .ifBlank { attachment.optString("mime_type").ifBlank { MIME_OPENPGP } },
            sizeBytes = attachment.optLong("size", 0L).coerceAtLeast(0L),
            sha256 = sha256,
            blockchain = CommunityBlockchainReference(
                networkName = fields.optString(NextGisCommunityRecordSchema.FIELD_NETWORK)
                    .takeIf(String::isNotBlank),
                chainId = fields.optString(NextGisCommunityRecordSchema.FIELD_CHAIN_ID)
                    .toLongOrNull(),
                contractAddress = fields.optString(NextGisCommunityRecordSchema.FIELD_CONTRACT_ADDRESS)
                    .takeIf(String::isNotBlank),
                transactionHash = fields.optString(NextGisCommunityRecordSchema.FIELD_TRANSACTION_HASH)
                    .takeIf(String::isNotBlank),
                explorerUrl = fields.optString(NextGisCommunityRecordSchema.FIELD_BLOCKCHAIN_URL)
                    .takeIf(String::isNotBlank)
            ),
            registryResourceId = registry.id,
            featureId = featureId,
            attachmentId = attachmentId
        )
    }

    private fun downloadAttachment(
        account: AccountUtil.AccountData,
        record: CommunityPackageRecord,
        output: OutputStream
    ): String {
        val url = server(account) + NextGisCommunityNames.attachmentDownloadPath(
            record.registryResourceId,
            record.featureId,
            record.attachmentId
        )
        val connection = NetworkUtil.getHttpConnection("GET", url, account.login, account.password)
            ?: throw NextGisCommunityPublishException("Could not open the NextGIS package download.")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw NextGisCommunityPublishException(
                    "NextGIS returned HTTP ${connection.responseCode} while downloading the package."
                )
            }
            val declaredLength = connection.contentLengthLong
            if (declaredLength > MAX_PACKAGE_BYTES) {
                throw NextGisCommunityPublishException("The community package exceeds the download size limit.")
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
                        if (total > MAX_PACKAGE_BYTES) {
                            throw NextGisCommunityPublishException(
                                "The community package exceeds the download size limit."
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
        if (!selection.hasGroup) {
            throw NextGisCommunityPublishException(
                "Choose a connected NextGIS account and community in Security & Sharing first."
            )
        }
        val account = account(requireNotNull(selection.accountName))
        val groupId = requireNotNull(selection.groupId)
        val currentUser = getObject(account, "${server(account)}/api/component/auth/current_user")
        val group = getObject(account, "${server(account)}/api/component/auth/group/$groupId")
        val memberIds = group.optJSONArray("members") ?: JSONArray()
        val currentUserId = currentUser.optLong("id", -1L)
        if ((0 until memberIds.length()).none { memberIds.optLong(it, -1L) == currentUserId }) {
            throw NextGisCommunityPublishException(
                "The signed-in NextGIS user is no longer a member of ${selection.groupName ?: "the selected community"}."
            )
        }
        return ResolvedCommunity(account, groupId)
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
        private const val MIME_OPENPGP = "application/pgp-encrypted"
        private const val MAX_PACKAGE_BYTES = 512L * 1024L * 1024L
        private val SHA_256 = Regex("[0-9a-f]{64}")
        private val PACKAGE_STATUSES = setOf("hash_calculated", "notarised")
    }
}
