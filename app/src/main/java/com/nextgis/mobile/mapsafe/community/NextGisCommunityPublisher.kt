package com.nextgis.mobile.mapsafe.community

import android.content.Context
import com.nextgis.maplib.util.AccountUtil
import com.nextgis.maplib.util.HttpResponse
import com.nextgis.maplib.util.NGWUtil
import com.nextgis.maplib.util.NetworkUtil
import com.nextgis.mobile.mapsafe.keys.MapSafeSecurityPreferences
import com.nextgis.mobile.mapsafe.service.HashUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID

/** Types of MapSafe material that may be published to a configured community. */
enum class CommunityArtifactType(
    val wireName: String,
    val displayName: String,
    internal val storage: CommunityArtifactStorage
) {
    PUBLIC_KEY("public_key", "Public key", CommunityArtifactStorage.PUBLIC_KEYS),
    HALO_MASKED("halo_masked_geojson", "Halo-masked layer", CommunityArtifactStorage.NATIVE_LAYER),
    HEXBIN("hexbin_geojson", "Hexagonal-binned layer", CommunityArtifactStorage.NATIVE_LAYER),
    ENCRYPTED_PACKAGE("openpgp_package", "Encrypted package", CommunityArtifactStorage.PACKAGES)
}

internal enum class CommunityArtifactStorage {
    PUBLIC_KEYS,
    NATIVE_LAYER,
    PACKAGES
}

/** Blockchain location associated with an encrypted package, when one exists. */
data class CommunityBlockchainReference(
    val networkName: String? = null,
    val chainId: Long? = null,
    val contractAddress: String? = null,
    val transactionHash: String? = null,
    val explorerUrl: String? = null
) {
    val isRecorded: Boolean
        get() = !transactionHash.isNullOrBlank() && !explorerUrl.isNullOrBlank()
}

data class CommunityPublishResult(
    val artifactType: CommunityArtifactType,
    val communityName: String,
    val fileName: String,
    val sha256: String,
    val resourceId: Long,
    val featureId: Long? = null,
    val resourceWebUrl: String,
    val status: String
)

class NextGisCommunityPublishException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Publishes MapSafe outputs through ordinary NextGIS Web resources.
 *
 * Anonymised GeoJSON becomes a native vector resource. Public keys and OpenPGP
 * packages are arbitrary-file attachments on metadata-only vector records. No
 * private key material is accepted by this API.
 */
class NextGisCommunityPublisher(context: Context) {
    private val context = context.applicationContext

    fun publishGeoJson(
        selection: MapSafeSecurityPreferences.Selection,
        source: File,
        fileName: String,
        artifactType: CommunityArtifactType
    ): CommunityPublishResult {
        require(artifactType.storage == CommunityArtifactStorage.NATIVE_LAYER) {
            "Only anonymised GeoJSON layers can be published as native vector resources."
        }
        requireReadableFile(source)
        val resolved = resolve(selection)
        val hierarchy = ensureHierarchy(resolved)
        requireCreatePermission(resolved.account, hierarchy.layers.id, "publish an anonymised layer")

        val safeName = safeFileName(fileName, "mapsafe-layer.geojson")
        val sha256 = HashUtils.sha256(source)
        val upload = uploadFile(
            resolved.account,
            safeName,
            source,
            MIME_GEOJSON
        ).put("encoding", "utf-8")
        val recordId = UUID.randomUUID().toString()
        val status = STATUS_PUBLISHED
        val payload = JSONObject()
            .put(
                "resource",
                JSONObject()
                    .put("cls", VECTOR_LAYER_CLASS)
                    .put("parent", JSONObject().put("id", hierarchy.layers.id))
                    .put("display_name", displayNameWithoutExtension(safeName))
                    .put("keyname", NextGisCommunityNames.artifactKey(resolved.group.id, resolved.user.id, recordId))
                    .put("description", "${artifactType.displayName} published by MapSafe.")
            )
            .put(
                "vector_layer",
                JSONObject()
                    .put("source", upload)
                    .put("srs", JSONObject().put("id", 3857))
                    .put("fix_errors", "SAFE")
                    .put("skip_errors", false)
            )
            .put(
                "resmeta",
                metadata(
                    artifactType = artifactType,
                    groupId = resolved.group.id,
                    userId = resolved.user.id,
                    recordId = recordId,
                    fileName = safeName,
                    mimeType = MIME_GEOJSON,
                    sha256 = sha256,
                    status = status,
                    blockchain = null
                )
            )
        val id = createResource(resolved.account, payload, "publish $safeName")
        return CommunityPublishResult(
            artifactType = artifactType,
            communityName = resolved.group.displayName,
            fileName = safeName,
            sha256 = sha256,
            resourceId = id,
            resourceWebUrl = resourceWebUrl(resolved.account, id),
            status = status
        )
    }

    fun publishAttachedFile(
        selection: MapSafeSecurityPreferences.Selection,
        source: File,
        fileName: String,
        mimeType: String,
        artifactType: CommunityArtifactType,
        fingerprint: String? = null,
        blockchain: CommunityBlockchainReference? = null
    ): CommunityPublishResult {
        require(
            artifactType.storage == CommunityArtifactStorage.PUBLIC_KEYS ||
                artifactType.storage == CommunityArtifactStorage.PACKAGES
        ) { "This artifact must be published as a native vector resource." }
        if (artifactType == CommunityArtifactType.PUBLIC_KEY) {
            require(!fingerprint.isNullOrBlank()) { "A public-key fingerprint is required." }
        }
        requireReadableFile(source)
        val resolved = resolve(selection)
        val hierarchy = ensureHierarchy(resolved)
        val parent = when (artifactType.storage) {
            CommunityArtifactStorage.PUBLIC_KEYS -> hierarchy.publicKeys
            CommunityArtifactStorage.PACKAGES -> hierarchy.packages
            CommunityArtifactStorage.NATIVE_LAYER -> error("Native layers use publishGeoJson().")
        }
        val registry = ensurePublisherRegistry(resolved, parent, artifactType.storage)
        requireDataWritePermission(resolved.account, registry.id, "publish ${artifactType.displayName.lowercase()}")

        val safeName = safeFileName(
            fileName,
            if (artifactType == CommunityArtifactType.PUBLIC_KEY) "public-key.asc" else "mapsafe-package.pgp"
        )
        val sha256 = HashUtils.sha256(source)
        val upload = uploadFile(resolved.account, safeName, source, mimeType)
        val recordId = UUID.randomUUID().toString()
        val status = when {
            artifactType == CommunityArtifactType.PUBLIC_KEY -> STATUS_ACTIVE
            blockchain?.isRecorded == true -> STATUS_NOTARISED
            else -> STATUS_HASH_CALCULATED
        }
        val fields = JSONObject()
            .put(FIELD_RECORD_ID, recordId)
            .put(FIELD_ARTIFACT_TYPE, artifactType.wireName)
            .put(FIELD_FILE_NAME, safeName)
            .put(FIELD_MIME_TYPE, mimeType)
            .put(FIELD_SHA256, sha256)
            .put(FIELD_PUBLISHER_ID, resolved.user.id)
            .put(FIELD_GROUP_ID, resolved.group.id)
            .put(FIELD_CREATED_AT, Instant.now().toString())
            .put(FIELD_STATUS, status)
            .put(FIELD_FINGERPRINT, fingerprint.orEmpty())
            .put(FIELD_NETWORK, blockchain?.networkName.orEmpty())
            .put(FIELD_CHAIN_ID, blockchain?.chainId?.toString().orEmpty())
            .put(FIELD_CONTRACT_ADDRESS, blockchain?.contractAddress.orEmpty())
            .put(FIELD_TRANSACTION_HASH, blockchain?.transactionHash.orEmpty())
            .put(FIELD_BLOCKCHAIN_URL, blockchain?.explorerUrl.orEmpty())
        val featureResponse = postJson(
            resolved.account,
            "${resourceApiUrl(resolved.account, registry.id)}/feature/",
            JSONObject()
                .put("geom", "POINT (0 0)")
                .put("fields", fields)
        )
        if (!featureResponse.isOk) {
            throw httpError("Could not create the MapSafe community record", featureResponse)
        }
        val featureId = responseObject(featureResponse, "NextGIS did not return the record ID.")
            .getLong("id")
        val attachmentPayload = JSONObject()
            .put("name", safeName)
            .put("size", upload.optLong("size", source.length()))
            .put("mime_type", upload.optString("mime_type", mimeType))
            .put("file_upload", JSONObject(upload.toString()))
        val attachmentResponse = postJson(
            resolved.account,
            "${resourceApiUrl(resolved.account, registry.id)}/feature/$featureId/attachment/",
            attachmentPayload
        )
        if (!attachmentResponse.isOk) {
            runCatching {
                NetworkUtil.delete(
                    "${resourceApiUrl(resolved.account, registry.id)}/feature/$featureId",
                    resolved.account.login,
                    resolved.account.password,
                    true
                )
            }
            throw httpError("Could not attach $safeName to its community record", attachmentResponse)
        }
        return CommunityPublishResult(
            artifactType = artifactType,
            communityName = resolved.group.displayName,
            fileName = safeName,
            sha256 = sha256,
            resourceId = registry.id,
            featureId = featureId,
            resourceWebUrl = resourceWebUrl(resolved.account, registry.id),
            status = status
        )
    }

    private fun resolve(selection: MapSafeSecurityPreferences.Selection): ResolvedCommunity {
        if (!selection.hasGroup) {
            throw NextGisCommunityPublishException(
                "Choose a connected NextGIS account and community in Security & Sharing first."
            )
        }
        val accountName = requireNotNull(selection.accountName)
        val groupId = requireNotNull(selection.groupId)
        val account = account(accountName)
        val userJson = getObject(account, "${server(account)}/api/component/auth/current_user")
        val user = NextGisUser(
            id = userJson.getLong("id"),
            displayName = userJson.optString("display_name")
                .ifBlank { userJson.optString("keyname").ifBlank { "NextGIS user" } }
        )
        val groupJson = getObject(account, "${server(account)}/api/component/auth/group/$groupId")
        val memberIds = groupJson.optJSONArray("members") ?: JSONArray()
        if ((0 until memberIds.length()).none { memberIds.getLong(it) == user.id }) {
            throw NextGisCommunityPublishException(
                "The signed-in NextGIS user is no longer a member of ${selection.groupName ?: "the selected community"}."
            )
        }
        val group = NextGisGroup(
            id = groupId,
            displayName = groupJson.optString("display_name")
                .ifBlank { selection.groupName ?: "Community $groupId" }
        )
        return ResolvedCommunity(account, user, group)
    }

    private fun ensureHierarchy(resolved: ResolvedCommunity): CommunityHierarchy {
        val root = ensureResourceGroup(
            resolved.account,
            parentId = ROOT_RESOURCE_ID,
            displayName = "MapSafe",
            keyname = NextGisCommunityNames.rootKey,
            description = "MapSafe community resources. Private keys and passphrases are never stored here.",
            permissions = JSONArray().put(permission(resolved.group.id, "read", propagate = false))
        )
        val community = ensureResourceGroup(
            resolved.account,
            parentId = root.id,
            displayName = resolved.group.displayName,
            keyname = NextGisCommunityNames.communityKey(resolved.group.id),
            description = "MapSafe resources for NextGIS authentication group ${resolved.group.id}.",
            permissions = JSONArray()
                .put(permission(resolved.group.id, "read", propagate = true))
                .put(permission(resolved.group.id, "read", propagate = true, scope = "data"))
        )
        val publicKeys = ensureResourceGroup(
            resolved.account,
            community.id,
            "Public Keys",
            NextGisCommunityNames.publicKeysKey(resolved.group.id),
            "Public OpenPGP keys only. No private key material."
        )
        val layers = ensureResourceGroup(
            resolved.account,
            community.id,
            "Anonymised Layers",
            NextGisCommunityNames.layersKey(resolved.group.id),
            "Halo-masked and hexagonal-binned MapSafe vector layers."
        )
        val packages = ensureResourceGroup(
            resolved.account,
            community.id,
            "Encrypted Packages",
            NextGisCommunityNames.packagesKey(resolved.group.id),
            "OpenPGP packages and their integrity/notarisation metadata."
        )
        return CommunityHierarchy(root, community, publicKeys, layers, packages)
    }

    private fun ensureResourceGroup(
        account: AccountUtil.AccountData,
        parentId: Long,
        displayName: String,
        keyname: String,
        description: String,
        permissions: JSONArray? = null
    ): NextGisResource {
        findResourceByKey(account, keyname)?.let { existing ->
            validateResource(existing, RESOURCE_GROUP_CLASS, parentId, keyname)
            return existing
        }
        childResources(account, parentId)
            .singleOrNull { it.cls == RESOURCE_GROUP_CLASS && it.displayName == displayName }
            ?.let { return it }
        val resource = JSONObject()
            .put("cls", RESOURCE_GROUP_CLASS)
            .put("parent", JSONObject().put("id", parentId))
            .put("display_name", displayName)
            .put("keyname", keyname)
            .put("description", description)
        permissions?.let { resource.put("permissions", it) }
        val response = postJson(
            account,
            resourceCollectionUrl(account),
            JSONObject().put("resource", resource)
        )
        if (!response.isOk) {
            findResourceByKey(account, keyname)?.let { raced ->
                validateResource(raced, RESOURCE_GROUP_CLASS, parentId, keyname)
                return raced
            }
            throw httpError("Could not create the $displayName Web GIS resource group", response)
        }
        return getResource(account, responseObject(response, "NextGIS did not return a resource ID.").getLong("id"))
    }

    private fun ensurePublisherRegistry(
        resolved: ResolvedCommunity,
        parent: NextGisResource,
        storage: CommunityArtifactStorage
    ): NextGisResource {
        val keyname = NextGisCommunityNames.publisherRegistryKey(
            resolved.group.id,
            resolved.user.id,
            storage
        )
        findResourceByKey(resolved.account, keyname)?.let { existing ->
            validateResource(existing, VECTOR_LAYER_CLASS, parent.id, keyname)
            if (existing.ownerUserId != resolved.user.id) {
                throw NextGisCommunityPublishException(
                    "The reserved MapSafe publisher resource is owned by a different NextGIS user."
                )
            }
            return existing
        }
        requireCreatePermission(resolved.account, parent.id, "create the MapSafe publisher registry")
        val label = when (storage) {
            CommunityArtifactStorage.PUBLIC_KEYS -> "Public keys"
            CommunityArtifactStorage.PACKAGES -> "Encrypted packages"
            CommunityArtifactStorage.NATIVE_LAYER -> error("Native layers do not use a registry.")
        }
        val fields = JSONArray().apply {
            stringField(FIELD_RECORD_ID)
            stringField(FIELD_ARTIFACT_TYPE)
            stringField(FIELD_FILE_NAME)
            stringField(FIELD_MIME_TYPE)
            stringField(FIELD_SHA256)
            bigintField(FIELD_PUBLISHER_ID)
            bigintField(FIELD_GROUP_ID)
            stringField(FIELD_CREATED_AT)
            stringField(FIELD_STATUS)
            stringField(FIELD_FINGERPRINT)
            stringField(FIELD_NETWORK)
            stringField(FIELD_CHAIN_ID)
            stringField(FIELD_CONTRACT_ADDRESS)
            stringField(FIELD_TRANSACTION_HASH)
            stringField(FIELD_BLOCKCHAIN_URL)
        }
        val payload = JSONObject()
            .put(
                "resource",
                JSONObject()
                    .put("cls", VECTOR_LAYER_CLASS)
                    .put("parent", JSONObject().put("id", parent.id))
                    .put("display_name", "$label — ${resolved.user.displayName}")
                    .put("keyname", keyname)
                    .put("description", "MapSafe attachment registry owned by NextGIS user ${resolved.user.id}.")
            )
            .put(
                "vector_layer",
                JSONObject()
                    .put("srs", JSONObject().put("id", 3857))
                    .put("geometry_type", "POINT")
                    .put("fields", fields)
            )
            .put(
                "resmeta",
                JSONObject().put(
                    "items",
                    JSONObject()
                        .put("mapsafe.schema", RECORD_SCHEMA)
                        .put("mapsafe.nextgis_group_id", resolved.group.id)
                        .put("mapsafe.nextgis_user_id", resolved.user.id)
                )
            )
        val id = createResource(resolved.account, payload, "create the $label registry")
        return getResource(resolved.account, id)
    }

    private fun metadata(
        artifactType: CommunityArtifactType,
        groupId: Long,
        userId: Long,
        recordId: String,
        fileName: String,
        mimeType: String,
        sha256: String,
        status: String,
        blockchain: CommunityBlockchainReference?
    ): JSONObject = JSONObject().put(
        "items",
        JSONObject()
            .put("mapsafe.schema", RECORD_SCHEMA)
            .put("mapsafe.record_id", recordId)
            .put("mapsafe.artifact_type", artifactType.wireName)
            .put("mapsafe.nextgis_group_id", groupId)
            .put("mapsafe.nextgis_user_id", userId)
            .put("mapsafe.file_name", fileName)
            .put("mapsafe.mime_type", mimeType)
            .put("mapsafe.sha256", sha256)
            .put("mapsafe.created_at", Instant.now().toString())
            .put("mapsafe.status", status)
            .put("mapsafe.blockchain_network", blockchain?.networkName.orEmpty())
            .put("mapsafe.blockchain_chain_id", blockchain?.chainId?.toString().orEmpty())
            .put("mapsafe.blockchain_contract", blockchain?.contractAddress.orEmpty())
            .put("mapsafe.blockchain_transaction", blockchain?.transactionHash.orEmpty())
            .put("mapsafe.blockchain_url", blockchain?.explorerUrl.orEmpty())
    )

    private fun uploadFile(
        account: AccountUtil.AccountData,
        fileName: String,
        file: File,
        mimeType: String
    ): JSONObject {
        val response = NetworkUtil.postFileOld(
            NGWUtil.getFileUploadUrlViaTus(account.url),
            fileName,
            file,
            mimeType,
            account.login,
            account.password,
            true
        )
        if (!response.isOk) throw httpError("Could not upload $fileName", response)
        val body = responseObject(response, "NextGIS returned no upload metadata for $fileName.")
        val uploaded = if (body.has("upload_meta")) {
            body.getJSONArray("upload_meta").getJSONObject(0)
        } else {
            body
        }
        return JSONObject(uploaded.toString()).put("name", fileName)
    }

    private fun requireCreatePermission(account: AccountUtil.AccountData, resourceId: Long, action: String) {
        val permissions = getObject(account, "${resourceApiUrl(account, resourceId)}/permission")
        if (!permissions.optJSONObject("resource").orEmpty().optBoolean("create")) {
            throw NextGisCommunityPublishException(
                "Your NextGIS account is not permitted to $action in the selected community."
            )
        }
    }

    private fun requireDataWritePermission(account: AccountUtil.AccountData, resourceId: Long, action: String) {
        val permissions = getObject(account, "${resourceApiUrl(account, resourceId)}/permission")
        if (!permissions.optJSONObject("data").orEmpty().optBoolean("write")) {
            throw NextGisCommunityPublishException(
                "Your NextGIS account is not permitted to $action in the selected community."
            )
        }
    }

    private fun JSONObject?.orEmpty(): JSONObject = this ?: JSONObject()

    private fun permission(
        principalId: Long,
        permission: String,
        propagate: Boolean,
        scope: String = "resource"
    ): JSONObject = JSONObject()
        .put("action", "allow")
        .put("principal", JSONObject().put("id", principalId))
        .put("identity", "")
        .put("scope", scope)
        .put("permission", permission)
        .put("propagate", propagate)

    private fun JSONArray.stringField(keyname: String) {
        put(JSONObject().put("keyname", keyname).put("datatype", "STRING"))
    }

    private fun JSONArray.bigintField(keyname: String) {
        put(JSONObject().put("keyname", keyname).put("datatype", "BIGINT"))
    }

    private fun createResource(
        account: AccountUtil.AccountData,
        payload: JSONObject,
        action: String
    ): Long {
        val response = postJson(account, resourceCollectionUrl(account), payload)
        if (!response.isOk) throw httpError("Could not $action", response)
        return responseObject(response, "NextGIS did not return the new resource ID.").getLong("id")
    }

    private fun findResourceByKey(account: AccountUtil.AccountData, keyname: String): NextGisResource? {
        val encoded = URLEncoder.encode(keyname, Charsets.UTF_8.name())
        val array = getArray(
            account,
            "${server(account)}/api/resource/search/?keyname=$encoded&serialization=full"
        )
        if (array.length() == 0) return null
        if (array.length() > 1) {
            throw NextGisCommunityPublishException("NextGIS returned duplicate resources for $keyname.")
        }
        return resourceFromJson(array.getJSONObject(0))
    }

    private fun childResources(account: AccountUtil.AccountData, parentId: Long): List<NextGisResource> {
        val array = getArray(account, "${server(account)}/api/resource/?parent=$parentId")
        return buildList {
            for (index in 0 until array.length()) add(resourceFromJson(array.getJSONObject(index)))
        }
    }

    private fun getResource(account: AccountUtil.AccountData, id: Long): NextGisResource =
        resourceFromJson(getObject(account, resourceApiUrl(account, id)))

    private fun resourceFromJson(container: JSONObject): NextGisResource {
        val resource = container.optJSONObject("resource") ?: container
        return NextGisResource(
            id = resource.getLong("id"),
            cls = resource.getString("cls"),
            parentId = resource.optJSONObject("parent")?.optLong("id", -1L) ?: -1L,
            ownerUserId = resource.optJSONObject("owner_user")?.optLong("id", -1L) ?: -1L,
            keyname = resource.optString("keyname").takeIf { !resource.isNull("keyname") },
            displayName = resource.optString("display_name")
        )
    }

    private fun validateResource(
        resource: NextGisResource,
        expectedClass: String,
        expectedParentId: Long,
        keyname: String
    ) {
        if (resource.cls != expectedClass || resource.parentId != expectedParentId) {
            throw NextGisCommunityPublishException(
                "The reserved NextGIS resource $keyname has an unexpected type or parent."
            )
        }
    }

    private fun getObject(account: AccountUtil.AccountData, url: String): JSONObject =
        JSONObject(requireOk(NetworkUtil.get(url, account.login, account.password, true), "NextGIS request failed"))

    private fun getArray(account: AccountUtil.AccountData, url: String): JSONArray =
        JSONArray(requireOk(NetworkUtil.get(url, account.login, account.password, true), "NextGIS request failed"))

    private fun postJson(account: AccountUtil.AccountData, url: String, payload: JSONObject): HttpResponse =
        NetworkUtil.post(url, payload.toString(), account.login, account.password, true)

    private fun requireOk(response: HttpResponse, action: String): String {
        if (!response.isOk) throw httpError(action, response)
        return response.responseBody
            ?: throw NextGisCommunityPublishException("$action: NextGIS returned an empty response.")
    }

    private fun responseObject(response: HttpResponse, emptyMessage: String): JSONObject {
        val body = response.responseBody
            ?: throw NextGisCommunityPublishException(emptyMessage)
        return JSONObject(body)
    }

    private fun httpError(action: String, response: HttpResponse): NextGisCommunityPublishException {
        val detail = response.responseBody
            ?.take(400)
            ?.takeIf(String::isNotBlank)
            ?.let { ": $it" }
            .orEmpty()
        val permissionHint = if (response.responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
            " Check the selected community's NextGIS resource permissions."
        } else {
            ""
        }
        return NextGisCommunityPublishException(
            "$action (HTTP ${response.responseCode}).$permissionHint$detail"
        )
    }

    private fun account(name: String): AccountUtil.AccountData = try {
        AccountUtil.getAccountData(context, name)
    } catch (error: Exception) {
        throw NextGisCommunityPublishException(
            "The selected NextGIS account is no longer available. Sign in again.",
            error
        )
    }

    private fun requireReadableFile(file: File) {
        if (!file.isFile || file.length() <= 0L) {
            throw NextGisCommunityPublishException("The MapSafe file is empty or unavailable.")
        }
    }

    private fun safeFileName(value: String, fallback: String): String =
        value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]+"), "_")
            .trim('.', ' ')
            .ifBlank { fallback }
            .take(120)

    private fun displayNameWithoutExtension(value: String): String = when {
        value.lowercase().endsWith(".geojson") -> value.dropLast(8)
        else -> value.substringBeforeLast('.', value)
    }.ifBlank { "MapSafe layer" }

    private fun server(account: AccountUtil.AccountData): String = NGWUtil.getServerUrl(account.url).trimEnd('/')
    private fun resourceCollectionUrl(account: AccountUtil.AccountData): String = "${server(account)}/api/resource/"
    private fun resourceApiUrl(account: AccountUtil.AccountData, id: Long): String = "${server(account)}/api/resource/$id"
    private fun resourceWebUrl(account: AccountUtil.AccountData, id: Long): String = "${server(account)}/resource/$id"

    private data class ResolvedCommunity(
        val account: AccountUtil.AccountData,
        val user: NextGisUser,
        val group: NextGisGroup
    )

    private data class NextGisUser(val id: Long, val displayName: String)
    private data class NextGisGroup(val id: Long, val displayName: String)
    private data class NextGisResource(
        val id: Long,
        val cls: String,
        val parentId: Long,
        val ownerUserId: Long,
        val keyname: String?,
        val displayName: String
    )

    private data class CommunityHierarchy(
        val root: NextGisResource,
        val community: NextGisResource,
        val publicKeys: NextGisResource,
        val layers: NextGisResource,
        val packages: NextGisResource
    )

    companion object {
        private const val ROOT_RESOURCE_ID = 0L
        private const val RESOURCE_GROUP_CLASS = "resource_group"
        private const val VECTOR_LAYER_CLASS = "vector_layer"
        private const val MIME_GEOJSON = "application/geo+json"
        private const val RECORD_SCHEMA = NextGisCommunityRecordSchema.RECORD_SCHEMA
        private const val STATUS_PUBLISHED = "published"
        private const val STATUS_ACTIVE = "active"
        private const val STATUS_HASH_CALCULATED = "hash_calculated"
        private const val STATUS_NOTARISED = "notarised"

        private const val FIELD_RECORD_ID = NextGisCommunityRecordSchema.FIELD_RECORD_ID
        private const val FIELD_ARTIFACT_TYPE = NextGisCommunityRecordSchema.FIELD_ARTIFACT_TYPE
        private const val FIELD_FILE_NAME = NextGisCommunityRecordSchema.FIELD_FILE_NAME
        private const val FIELD_MIME_TYPE = NextGisCommunityRecordSchema.FIELD_MIME_TYPE
        private const val FIELD_SHA256 = NextGisCommunityRecordSchema.FIELD_SHA256
        private const val FIELD_PUBLISHER_ID = NextGisCommunityRecordSchema.FIELD_PUBLISHER_ID
        private const val FIELD_GROUP_ID = NextGisCommunityRecordSchema.FIELD_GROUP_ID
        private const val FIELD_CREATED_AT = NextGisCommunityRecordSchema.FIELD_CREATED_AT
        private const val FIELD_STATUS = NextGisCommunityRecordSchema.FIELD_STATUS
        private const val FIELD_FINGERPRINT = NextGisCommunityRecordSchema.FIELD_FINGERPRINT
        private const val FIELD_NETWORK = NextGisCommunityRecordSchema.FIELD_NETWORK
        private const val FIELD_CHAIN_ID = NextGisCommunityRecordSchema.FIELD_CHAIN_ID
        private const val FIELD_CONTRACT_ADDRESS = NextGisCommunityRecordSchema.FIELD_CONTRACT_ADDRESS
        private const val FIELD_TRANSACTION_HASH = NextGisCommunityRecordSchema.FIELD_TRANSACTION_HASH
        private const val FIELD_BLOCKCHAIN_URL = NextGisCommunityRecordSchema.FIELD_BLOCKCHAIN_URL
    }
}

/** Field names shared by the community publisher and the read-only package client. */
internal object NextGisCommunityRecordSchema {
    const val RECORD_SCHEMA = "mapsafe-community-v1"
    const val FIELD_RECORD_ID = "record_id"
    const val FIELD_ARTIFACT_TYPE = "artifact_type"
    const val FIELD_FILE_NAME = "file_name"
    const val FIELD_MIME_TYPE = "mime_type"
    const val FIELD_SHA256 = "sha256"
    const val FIELD_PUBLISHER_ID = "publisher_id"
    const val FIELD_GROUP_ID = "community_id"
    const val FIELD_CREATED_AT = "created_at"
    const val FIELD_STATUS = "record_status"
    const val FIELD_FINGERPRINT = "fingerprint"
    const val FIELD_NETWORK = "network_name"
    const val FIELD_CHAIN_ID = "chain_id"
    const val FIELD_CONTRACT_ADDRESS = "contract_address"
    const val FIELD_TRANSACTION_HASH = "transaction_hash"
    const val FIELD_BLOCKCHAIN_URL = "blockchain_url"
}

/** Stable, globally unique NextGIS resource keynames used by the MapSafe schema. */
object NextGisCommunityNames {
    const val rootKey = "mapsafe_root"

    fun communityKey(groupId: Long): String = "mapsafe_community_g$groupId"
    fun publicKeysKey(groupId: Long): String = "mapsafe_public_keys_g$groupId"
    fun layersKey(groupId: Long): String = "mapsafe_layers_g$groupId"
    fun packagesKey(groupId: Long): String = "mapsafe_packages_g$groupId"

    fun attachmentDownloadPath(resourceId: Long, featureId: Long, attachmentId: Long): String =
        "/api/resource/$resourceId/feature/$featureId/attachment/$attachmentId/download"

    internal fun publisherRegistryKey(
        groupId: Long,
        userId: Long,
        storage: CommunityArtifactStorage
    ): String {
        val kind = when (storage) {
            CommunityArtifactStorage.PUBLIC_KEYS -> "keys"
            CommunityArtifactStorage.PACKAGES -> "packages"
            CommunityArtifactStorage.NATIVE_LAYER -> "layers"
        }
        return "mapsafe_${kind}_g${groupId}_u$userId"
    }

    fun artifactKey(groupId: Long, userId: Long, recordId: String): String {
        val suffix = recordId.replace("-", "").take(16)
        return "mapsafe_artifact_g${groupId}_u${userId}_$suffix"
    }
}
