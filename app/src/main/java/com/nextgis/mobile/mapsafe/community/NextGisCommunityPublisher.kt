package com.nextgis.mobile.mapsafe.community

import android.content.Context
import com.nextgis.maplib.util.AccountUtil
import com.nextgis.maplib.util.HttpResponse
import com.nextgis.maplib.util.NGWUtil
import com.nextgis.maplib.util.NetworkUtil
import com.nextgis.mobile.mapsafe.blockchain.MapSafeIntegrityRecordCodec
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

data class CommunityNotarisationUpdateResult(
    val communityName: String,
    val updatedRecords: Int
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
        artifactType: CommunityArtifactType,
        audience: CommunityArtifactAudience = CommunityArtifactAudience.community()
    ): CommunityPublishResult {
        require(artifactType.storage == CommunityArtifactStorage.NATIVE_LAYER) {
            "Only anonymised GeoJSON layers can be published as native vector resources."
        }
        requireReadableFile(source)
        require(audience.mode == CommunityAudienceMode.COMMUNITY) {
            "An anonymised layer is published to the selected community audience."
        }
        val resolved = resolve(selection)
        val hierarchy = ensureHierarchy(resolved)
        val publisherFolder = publisherFolder(
            resolved,
            hierarchy.layers,
            CommunityArtifactStorage.NATIVE_LAYER
        )
        requireCreatePermission(resolved.account, publisherFolder.id, "publish an anonymised layer")

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
                    .put("parent", JSONObject().put("id", publisherFolder.id))
                    .put("display_name", displayNameWithoutExtension(safeName))
                    .put("keyname", NextGisCommunityNames.artifactKey(resolved.group.id, resolved.user.id, recordId))
                    .put("description", "${artifactType.displayName} published by MapSafe.")
            )
            .put(
                "vector_layer",
                JSONObject()
                    .put("source", upload)
                    // NextGIS Web currently requires EPSG:3857 as the target SRS
                    // when a vector layer is created from an uploaded data source.
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
        repairSelectedPrincipalsPermissions(
            resolved.account,
            id,
            permissionsJson(NextGisCommunityAccessPolicy.communityReadableArtifact(resolved.group.id))
        )
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
        keyVersion: Int? = null,
        blockchain: CommunityBlockchainReference? = null,
        audience: CommunityArtifactAudience? = null
    ): CommunityPublishResult {
        require(
            artifactType.storage == CommunityArtifactStorage.PUBLIC_KEYS ||
                artifactType.storage == CommunityArtifactStorage.PACKAGES
        ) { "This artifact must be published as a native vector resource." }
        if (artifactType == CommunityArtifactType.PUBLIC_KEY) {
            require(!fingerprint.isNullOrBlank()) { "A public-key fingerprint is required." }
        }
        if (artifactType == CommunityArtifactType.ENCRYPTED_PACKAGE) {
            require(audience?.isReadyForRestrictedUpload == true) {
                "Confirm an audience whose OpenPGP recipients all map to accepted members of the selected NextGIS community."
            }
        }
        requireReadableFile(source)
        val resolved = resolve(selection)
        val hierarchy = ensureHierarchy(resolved)
        val artifactParent = when (artifactType.storage) {
            CommunityArtifactStorage.PUBLIC_KEYS -> hierarchy.publicKeys
            CommunityArtifactStorage.PACKAGES -> hierarchy.packages
            CommunityArtifactStorage.NATIVE_LAYER -> error("Native layers use publishGeoJson().")
        }
        val parent = publisherFolder(resolved, artifactParent, artifactType.storage)
        val safeName = if (artifactType == CommunityArtifactType.ENCRYPTED_PACKAGE) {
            runCatching { MapSafeIntegrityRecordCodec.normalizeFileName(fileName) }
                .getOrDefault("mapsafe-package.pgp")
        } else {
            safeFileName(fileName, "public-key.asc")
        }
        val recordId = UUID.randomUUID().toString()
        val registry = ensurePublisherRegistry(
            resolved = resolved,
            parent = parent,
            storage = artifactType.storage,
            recordId = recordId,
            fileName = safeName,
            audience = audience
        )
        requireDataWritePermission(resolved.account, registry.id, "publish ${artifactType.displayName.lowercase()}")
        val sha256 = HashUtils.sha256(source)
        val upload = uploadFile(resolved.account, safeName, source, mimeType)
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
            .put(FIELD_KEY_VERSION, keyVersion?.coerceAtLeast(1)?.toString().orEmpty())
            .put(FIELD_NETWORK, blockchain?.networkName.orEmpty())
            .put(FIELD_CHAIN_ID, blockchain?.chainId?.toString().orEmpty())
            .put(FIELD_CONTRACT_ADDRESS, blockchain?.contractAddress.orEmpty())
            .put(FIELD_TRANSACTION_HASH, blockchain?.transactionHash.orEmpty())
            .put(FIELD_BLOCKCHAIN_URL, blockchain?.explorerUrl.orEmpty())
            .put(
                FIELD_RECIPIENT_USER_IDS,
                audience?.recipientUserIds?.sorted()?.joinToString(",").orEmpty()
            )
            .put(
                FIELD_RECIPIENT_FINGERPRINTS,
                audience?.members?.map { it.fingerprint }?.sorted()?.joinToString(",").orEmpty()
            )
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

    /**
     * Adds a confirmed blockchain reference to this publisher's matching package records.
     *
     * A package may be notarised after it was uploaded. Matching by the immutable SHA-256
     * lets the Notarise screen complete that metadata without retaining a fragile feature ID.
     */
    fun updateEncryptedPackageNotarisation(
        selection: MapSafeSecurityPreferences.Selection,
        sha256: String,
        blockchain: CommunityBlockchainReference
    ): CommunityNotarisationUpdateResult {
        val normalisedHash = sha256.lowercase()
        require(SHA_256.matches(normalisedHash)) { "A valid encrypted-package SHA-256 is required." }
        require(blockchain.isRecorded) { "A confirmed blockchain transaction reference is required." }
        val resolved = resolve(selection)
        val community = findResourceByKey(
            resolved.account,
            NextGisCommunityNames.communityKey(resolved.group.id)
        ) ?: return CommunityNotarisationUpdateResult(resolved.group.displayName, 0)
        val packages = findResourceByKey(
            resolved.account,
            NextGisCommunityNames.packagesKey(resolved.group.id)
        ) ?: return CommunityNotarisationUpdateResult(resolved.group.displayName, 0)
        if (packages.cls != RESOURCE_GROUP_CLASS || packages.parentId != community.id) {
            throw NextGisCommunityPublishException(
                "The selected community's encrypted-package directory is invalid."
            )
        }
        var updated = 0
        val registryPrefix = NextGisCommunityNames.packageRegistryPrefix(
            resolved.group.id,
            resolved.user.id
        )
        val registries = childResources(resolved.account, packages.id).filter {
            it.cls == VECTOR_LAYER_CLASS &&
                it.ownerUserId == resolved.user.id &&
                it.keyname?.startsWith(registryPrefix) == true
        }
        for (registry in registries) {
            requireDataWritePermission(resolved.account, registry.id, "update the package notarisation")
            val features = getArray(
                resolved.account,
                "${resourceApiUrl(resolved.account, registry.id)}/feature/?dt_format=iso"
            )
            for (index in 0 until features.length()) {
                val feature = features.getJSONObject(index)
                val featureId = feature.optLong("id", -1L)
                val fields = feature.optJSONObject("fields") ?: continue
                val matches = featureId > 0L &&
                    fields.optString(FIELD_ARTIFACT_TYPE) == CommunityArtifactType.ENCRYPTED_PACKAGE.wireName &&
                    fields.optString(FIELD_SHA256).equals(normalisedHash, ignoreCase = true) &&
                    fields.optLong(FIELD_PUBLISHER_ID, -1L) == resolved.user.id &&
                    fields.optLong(FIELD_GROUP_ID, -1L) == resolved.group.id
                if (!matches) continue

                fields
                    .put(FIELD_STATUS, STATUS_NOTARISED)
                    .put(FIELD_NETWORK, blockchain.networkName.orEmpty())
                    .put(FIELD_CHAIN_ID, blockchain.chainId?.toString().orEmpty())
                    .put(FIELD_CONTRACT_ADDRESS, blockchain.contractAddress.orEmpty())
                    .put(FIELD_TRANSACTION_HASH, blockchain.transactionHash.orEmpty())
                    .put(FIELD_BLOCKCHAIN_URL, blockchain.explorerUrl.orEmpty())
                val response = NetworkUtil.put(
                    "${resourceApiUrl(resolved.account, registry.id)}/feature/$featureId",
                    JSONObject().put("fields", fields).toString(),
                    resolved.account.login,
                    resolved.account.password,
                    true
                )
                if (!response.isOk) {
                    throw httpError("Could not update the MapSafe package notarisation", response)
                }
                updated++
            }
        }
        return CommunityNotarisationUpdateResult(resolved.group.displayName, updated)
    }

    private fun resolve(selection: MapSafeSecurityPreferences.Selection): ResolvedCommunity {
        val membership = NextGisCommunityMembershipResolver(context).resolve(selection)
        val user = NextGisUser(
            id = membership.currentUserId,
            displayName = membership.currentUserDisplayName
        )
        val group = NextGisGroup(
            id = membership.groupId,
            displayName = membership.groupName
        )
        return ResolvedCommunity(
            membership.account,
            user,
            group,
            membership.authoritativeMemberIds
        )
    }

    private fun ensureHierarchy(resolved: ResolvedCommunity): CommunityHierarchy {
        ensurePrivateRootTraversal(resolved)
        val root = ensureResourceGroup(
            resolved.account,
            parentId = ROOT_RESOURCE_ID,
            displayName = "MapSafe",
            keyname = NextGisCommunityNames.rootKey,
            description = "MapSafe community resources. Private keys and passphrases are never stored here.",
            permissions = permissionsJson(NextGisCommunityAccessPolicy.rootPath(resolved.group.id)),
            repairDescriptor = resolved.authoritativeMemberIds != null
        )
        val community = ensureResourceGroup(
            resolved.account,
            parentId = root.id,
            displayName = resolved.group.displayName,
            keyname = NextGisCommunityNames.communityKey(resolved.group.id),
            description = "MapSafe resources for NextGIS authentication group ${resolved.group.id}.",
            permissions = permissionsJson(
                NextGisCommunityAccessPolicy.communityFolder(resolved.group.id)
            ),
            repairDescriptor = resolved.authoritativeMemberIds != null
        )
        val publicKeys = ensureResourceGroup(
            resolved.account,
            community.id,
            "Public Keys",
            NextGisCommunityNames.publicKeysKey(resolved.group.id),
            "Public OpenPGP keys only. No private key material.",
            permissionsJson(NextGisCommunityAccessPolicy.artifactFolder(resolved.group.id)),
            repairDescriptor = resolved.authoritativeMemberIds != null
        )
        val layers = ensureResourceGroup(
            resolved.account,
            community.id,
            "Anonymised Datasets",
            NextGisCommunityNames.layersKey(resolved.group.id),
            "Halo-masked and hexagonal-binned MapSafe datasets.",
            permissionsJson(NextGisCommunityAccessPolicy.artifactFolder(resolved.group.id)),
            repairDescriptor = resolved.authoritativeMemberIds != null
        )
        val packages = ensureResourceGroup(
            resolved.account,
            community.id,
            "Encrypted Packages",
            NextGisCommunityNames.packagesKey(resolved.group.id),
            "OpenPGP packages and their integrity/notarisation metadata.",
            permissionsJson(NextGisCommunityAccessPolicy.artifactFolder(resolved.group.id)),
            repairDescriptor = resolved.authoritativeMemberIds != null
        )
        val hierarchy = CommunityHierarchy(root, community, publicKeys, layers, packages)
        resolved.authoritativeMemberIds?.forEach { memberId ->
            ensureMemberFolder(resolved, publicKeys, CommunityArtifactStorage.PUBLIC_KEYS, memberId)
            ensureMemberFolder(resolved, layers, CommunityArtifactStorage.NATIVE_LAYER, memberId)
            ensureMemberFolder(resolved, packages, CommunityArtifactStorage.PACKAGES, memberId)
        }
        return hierarchy
    }

    private fun publisherFolder(
        resolved: ResolvedCommunity,
        parent: NextGisResource,
        storage: CommunityArtifactStorage
    ): NextGisResource {
        val keyname = NextGisCommunityNames.memberFolderKey(
            resolved.group.id,
            resolved.user.id,
            storage
        )
        findResourceByKey(resolved.account, keyname)?.let { existing ->
            validateResource(existing, RESOURCE_GROUP_CLASS, parent.id, keyname)
            return existing
        }
        if (resolved.authoritativeMemberIds?.contains(resolved.user.id) == true) {
            return ensureMemberFolder(resolved, parent, storage, resolved.user.id)
        }
        throw NextGisCommunityPublishException(
            "Your private publishing folder has not been provisioned. Ask the community administrator to open Security & Sharing and prepare the selected community."
        )
    }

    private fun ensureMemberFolder(
        resolved: ResolvedCommunity,
        parent: NextGisResource,
        storage: CommunityArtifactStorage,
        memberId: Long
    ): NextGisResource {
        val kind = when (storage) {
            CommunityArtifactStorage.PUBLIC_KEYS -> "public keys"
            CommunityArtifactStorage.PACKAGES -> "encrypted packages"
            CommunityArtifactStorage.NATIVE_LAYER -> "anonymised datasets"
        }
        return ensureResourceGroup(
            account = resolved.account,
            parentId = parent.id,
            displayName = "Member $memberId — $kind",
            keyname = NextGisCommunityNames.memberFolderKey(
                resolved.group.id,
                memberId,
                storage
            ),
            description = "MapSafe publishing area for NextGIS user $memberId.",
            permissions = permissionsJson(
                NextGisCommunityAccessPolicy.publisherFolder(resolved.group.id, memberId)
            ),
            repairDescriptor = true
        )
    }

    /**
     * Free-plan Web GIS instances can retain an `Everyone` read rule that propagates from
     * resource 0 after Premium is activated. If left in place, that inherited permission makes
     * every descendant visible regardless of the narrower MapSafe ACLs. The community
     * administrator therefore removes only that legacy propagated root-read rule and adds a
     * non-propagating traversal rule for the selected community group. Other administrator,
     * user, group, and service permissions are preserved.
     */
    private fun ensurePrivateRootTraversal(resolved: ResolvedCommunity) {
        val rootContainer = getObject(resolved.account, resourceApiUrl(resolved.account, ROOT_RESOURCE_ID))
        val rootResource = rootContainer.optJSONObject("resource") ?: rootContainer
        val current = rootResource.optJSONArray("permissions") ?: JSONArray()
        val everyoneIds = everyonePrincipalIds(resolved.account)
        val merged = JSONArray()
        for (index in 0 until current.length()) {
            val rule = current.optJSONObject(index) ?: continue
            val principalId = rule.optJSONObject("principal")?.optLong("id", -1L) ?: -1L
            val isLegacyPublicRead = principalId in everyoneIds &&
                rule.optString("action") == "allow" &&
                rule.optString("scope") == "resource" &&
                rule.optString("permission") == "read" &&
                rule.optBoolean("propagate", false)
            val isSelectedGroupTraversal = principalId == resolved.group.id &&
                rule.optString("scope") == "resource" &&
                rule.optString("permission") == "read"
            if (!isLegacyPublicRead && !isSelectedGroupTraversal) {
                merged.put(JSONObject(rule.toString()))
            }
        }
        merged.put(
            JSONObject()
                .put("action", "allow")
                .put("principal", JSONObject().put("id", resolved.group.id))
                .put("identity", "")
                .put("scope", "resource")
                .put("permission", "read")
                .put("propagate", false)
        )
        if (normalisedPermissionSet(current) == normalisedPermissionSet(merged)) return
        val response = NetworkUtil.put(
            resourceApiUrl(resolved.account, ROOT_RESOURCE_ID),
            JSONObject().put("resource", JSONObject().put("permissions", merged)).toString(),
            resolved.account.login,
            resolved.account.password,
            true
        )
        if (!response.isOk) {
            throw httpError(
                "Could not make the MapSafe community path private at the Web GIS root",
                response
            )
        }
    }

    private fun everyonePrincipalIds(account: AccountUtil.AccountData): Set<Long> {
        val explain = getObject(account, "${resourceApiUrl(account, ROOT_RESOURCE_ID)}/permission/explain")
        val entries = explain.optJSONObject("resource")
            ?.optJSONObject("read")
            ?.optJSONArray("explain")
            ?: JSONArray()
        return buildSet {
            for (index in 0 until entries.length()) {
                val principal = entries.optJSONObject(index)
                    ?.optJSONObject("acl_rule")
                    ?.optJSONObject("principal")
                    ?: continue
                if (principal.optString("keyname").equals("everyone", ignoreCase = true)) {
                    principal.optLong("id", -1L).takeIf { it > 0L }?.let(::add)
                }
            }
        }
    }

    private fun ensureResourceGroup(
        account: AccountUtil.AccountData,
        parentId: Long,
        displayName: String,
        keyname: String,
        description: String,
        permissions: JSONArray? = null,
        repairDescriptor: Boolean = false
    ): NextGisResource {
        findResourceByKey(account, keyname)?.let { existing ->
            validateResource(existing, RESOURCE_GROUP_CLASS, parentId, keyname)
            val repaired = if (repairDescriptor) {
                repairResourceDescriptor(account, existing, displayName, keyname, description)
            } else {
                existing
            }
            permissions?.let { repairSelectedPrincipalsPermissions(account, existing.id, it) }
            return repaired
        }
        childResources(account, parentId)
            .singleOrNull { it.cls == RESOURCE_GROUP_CLASS && it.displayName == displayName }
            ?.let { existing ->
                val repaired = if (repairDescriptor) {
                    repairResourceDescriptor(account, existing, displayName, keyname, description)
                } else {
                    existing
                }
                permissions?.let { repairSelectedPrincipalsPermissions(account, existing.id, it) }
                return repaired
            }
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

    private fun repairResourceDescriptor(
        account: AccountUtil.AccountData,
        resource: NextGisResource,
        displayName: String,
        keyname: String,
        description: String
    ): NextGisResource {
        if (
            resource.displayName == displayName &&
            resource.keyname == keyname &&
            resource.description == description
        ) {
            return resource
        }
        val response = NetworkUtil.put(
            resourceApiUrl(account, resource.id),
            JSONObject()
                .put(
                    "resource",
                    JSONObject()
                        .put("display_name", displayName)
                        .put("keyname", keyname)
                        .put("description", description)
                )
                .toString(),
            account.login,
            account.password,
            true
        )
        if (!response.isOk) {
            throw httpError("Could not refresh the MapSafe community description", response)
        }
        return getResource(account, resource.id)
    }

    private fun ensurePublisherRegistry(
        resolved: ResolvedCommunity,
        parent: NextGisResource,
        storage: CommunityArtifactStorage,
        recordId: String,
        fileName: String,
        audience: CommunityArtifactAudience?
    ): NextGisResource {
        val keyname = when (storage) {
            CommunityArtifactStorage.PUBLIC_KEYS -> NextGisCommunityNames.publisherRegistryKey(
                resolved.group.id,
                resolved.user.id,
                storage
            )
            CommunityArtifactStorage.PACKAGES -> NextGisCommunityNames.packageRegistryKey(
                resolved.group.id,
                resolved.user.id,
                recordId
            )
            CommunityArtifactStorage.NATIVE_LAYER -> error("Native layers do not use a registry.")
        }
        findResourceByKey(resolved.account, keyname)?.let { existing ->
            // Premium migration: registries created before member publishing folders were
            // introduced remain direct children of the shared artifact folder. Reuse those
            // resources after the same owner and ACL checks instead of orphaning their records.
            val expectedParents = setOf(parent.id, parent.parentId)
            if (existing.cls != VECTOR_LAYER_CLASS || existing.parentId !in expectedParents) {
                throw NextGisCommunityPublishException(
                    "The reserved NextGIS resource $keyname has an unexpected type or parent."
                )
            }
            if (existing.ownerUserId != resolved.user.id) {
                throw NextGisCommunityPublishException(
                    "The reserved MapSafe publisher resource is owned by a different NextGIS user."
                )
            }
            val accessRules = when (storage) {
                CommunityArtifactStorage.PUBLIC_KEYS ->
                    NextGisCommunityAccessPolicy.communityReadableArtifact(resolved.group.id)
                CommunityArtifactStorage.PACKAGES -> NextGisCommunityAccessPolicy.encryptedPackage(
                    resolved.user.id,
                    requireNotNull(audience)
                )
                CommunityArtifactStorage.NATIVE_LAYER -> error("Native layers do not use a registry.")
            }
            repairSelectedPrincipalsPermissions(
                resolved.account,
                existing.id,
                permissionsJson(accessRules)
            )
            return existing
        }
        requireCreatePermission(resolved.account, parent.id, "create the MapSafe publisher registry")
        val label = when (storage) {
            CommunityArtifactStorage.PUBLIC_KEYS -> "Public keys"
            CommunityArtifactStorage.PACKAGES -> "Protected package: ${displayNameWithoutExtension(fileName)}"
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
            stringField(FIELD_KEY_VERSION)
            stringField(FIELD_NETWORK)
            stringField(FIELD_CHAIN_ID)
            stringField(FIELD_CONTRACT_ADDRESS)
            stringField(FIELD_TRANSACTION_HASH)
            stringField(FIELD_BLOCKCHAIN_URL)
            stringField(FIELD_RECIPIENT_USER_IDS)
            stringField(FIELD_RECIPIENT_FINGERPRINTS)
        }
        val accessRules = when (storage) {
            CommunityArtifactStorage.PUBLIC_KEYS ->
                NextGisCommunityAccessPolicy.communityReadableArtifact(resolved.group.id)
            CommunityArtifactStorage.PACKAGES -> NextGisCommunityAccessPolicy.encryptedPackage(
                resolved.user.id,
                requireNotNull(audience)
            )
            CommunityArtifactStorage.NATIVE_LAYER -> error("Native layers do not use a registry.")
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
        repairSelectedPrincipalsPermissions(
            resolved.account,
            id,
            permissionsJson(accessRules)
        )
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

    private fun permissionsJson(rules: Collection<NextGisPermissionRule>): JSONArray =
        JSONArray().apply {
            rules.forEach { rule ->
                put(JSONObject()
                    .put("action", "allow")
                    .put("principal", JSONObject().put("id", rule.principalId))
                    .put("identity", "")
                    .put("scope", rule.scope)
                    .put("permission", rule.permission)
                    .put("propagate", rule.propagate))
            }
        }

    /**
     * Replaces rules for the principals governed by this MapSafe resource while preserving
     * unrelated administrator rules. This removes legacy propagated rules created by the
     * earlier Free-plan prototype when the hierarchy is first used on a Premium instance.
     */
    private fun repairSelectedPrincipalsPermissions(
        account: AccountUtil.AccountData,
        resourceId: Long,
        desired: JSONArray
    ) {
        val desiredPrincipalIds = buildSet {
            for (index in 0 until desired.length()) {
                desired.optJSONObject(index)
                    ?.optJSONObject("principal")
                    ?.optLong("id", -1L)
                    ?.takeIf { it > 0L }
                    ?.let(::add)
            }
        }
        if (desiredPrincipalIds.isEmpty()) return
        val container = getObject(account, resourceApiUrl(account, resourceId))
        val resource = container.optJSONObject("resource") ?: container
        val current = resource.optJSONArray("permissions") ?: JSONArray()
        val merged = JSONArray()
        for (index in 0 until current.length()) {
            val rule = current.optJSONObject(index) ?: continue
            val principalId = rule.optJSONObject("principal")?.optLong("id", -1L) ?: -1L
            if (principalId !in desiredPrincipalIds) merged.put(JSONObject(rule.toString()))
        }
        for (index in 0 until desired.length()) {
            merged.put(JSONObject(desired.getJSONObject(index).toString()))
        }
        if (normalisedPermissionSet(current) == normalisedPermissionSet(merged)) return
        val response = NetworkUtil.put(
            resourceApiUrl(account, resourceId),
            JSONObject().put("resource", JSONObject().put("permissions", merged)).toString(),
            account.login,
            account.password,
            true
        )
        if (!response.isOk) {
            throw httpError("Could not apply the MapSafe community access rules", response)
        }
    }

    private fun normalisedPermissionSet(value: JSONArray): Set<String> = buildSet {
        for (index in 0 until value.length()) {
            val rule = value.optJSONObject(index) ?: continue
            add(
                listOf(
                    rule.optString("action"),
                    rule.optJSONObject("principal")?.optLong("id", -1L).toString(),
                    rule.optString("scope"),
                    rule.optString("permission"),
                    rule.optBoolean("propagate", false).toString()
                ).joinToString("|")
            )
        }
    }

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
            displayName = resource.optString("display_name"),
            description = resource.optString("description").takeIf { !resource.isNull("description") }
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
        val group: NextGisGroup,
        val authoritativeMemberIds: Set<Long>?
    )

    private data class NextGisUser(val id: Long, val displayName: String)
    private data class NextGisGroup(val id: Long, val displayName: String)
    private data class NextGisResource(
        val id: Long,
        val cls: String,
        val parentId: Long,
        val ownerUserId: Long,
        val keyname: String?,
        val displayName: String,
        val description: String?
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
        private val SHA_256 = Regex("^[0-9a-f]{64}$")

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
        private const val FIELD_KEY_VERSION = NextGisCommunityRecordSchema.FIELD_KEY_VERSION
        private const val FIELD_NETWORK = NextGisCommunityRecordSchema.FIELD_NETWORK
        private const val FIELD_CHAIN_ID = NextGisCommunityRecordSchema.FIELD_CHAIN_ID
        private const val FIELD_CONTRACT_ADDRESS = NextGisCommunityRecordSchema.FIELD_CONTRACT_ADDRESS
        private const val FIELD_TRANSACTION_HASH = NextGisCommunityRecordSchema.FIELD_TRANSACTION_HASH
        private const val FIELD_BLOCKCHAIN_URL = NextGisCommunityRecordSchema.FIELD_BLOCKCHAIN_URL
        private const val FIELD_RECIPIENT_USER_IDS = NextGisCommunityRecordSchema.FIELD_RECIPIENT_USER_IDS
        private const val FIELD_RECIPIENT_FINGERPRINTS =
            NextGisCommunityRecordSchema.FIELD_RECIPIENT_FINGERPRINTS
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
    const val FIELD_KEY_VERSION = "key_version"
    const val FIELD_NETWORK = "network_name"
    const val FIELD_CHAIN_ID = "chain_id"
    const val FIELD_CONTRACT_ADDRESS = "contract_address"
    const val FIELD_TRANSACTION_HASH = "transaction_hash"
    const val FIELD_BLOCKCHAIN_URL = "blockchain_url"
    const val FIELD_RECIPIENT_USER_IDS = "recipient_user_ids"
    const val FIELD_RECIPIENT_FINGERPRINTS = "recipient_fingerprints"
}

/** Stable, globally unique NextGIS resource keynames used by the MapSafe schema. */
object NextGisCommunityNames {
    const val rootKey = "mapsafe_root"

    private val communityKeyPattern = Regex("^mapsafe_community_g([1-9][0-9]*)$")

    fun communityKey(groupId: Long): String = "mapsafe_community_g$groupId"
    fun communityGroupId(keyname: String?): Long? = keyname
        ?.let(communityKeyPattern::matchEntire)
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()
    fun publicKeysKey(groupId: Long): String = "mapsafe_public_keys_g$groupId"
    fun layersKey(groupId: Long): String = "mapsafe_layers_g$groupId"
    fun packagesKey(groupId: Long): String = "mapsafe_packages_g$groupId"
    internal fun memberFolderKey(
        groupId: Long,
        userId: Long,
        storage: CommunityArtifactStorage
    ): String = "${memberFolderPrefix(groupId, storage)}u$userId"

    internal fun memberFolderPrefix(groupId: Long, storage: CommunityArtifactStorage): String {
        val kind = when (storage) {
            CommunityArtifactStorage.PUBLIC_KEYS -> "keys"
            CommunityArtifactStorage.PACKAGES -> "packages"
            CommunityArtifactStorage.NATIVE_LAYER -> "layers"
        }
        return "mapsafe_member_${kind}_g${groupId}_"
    }
    fun publicKeyRegistryKey(groupId: Long, userId: Long): String = "mapsafe_keys_g${groupId}_u$userId"
    fun packageRegistryPrefix(groupId: Long, userId: Long): String =
        "mapsafe_packages_g${groupId}_u$userId"

    fun packageRegistryKey(groupId: Long, userId: Long, recordId: String): String =
        "${packageRegistryPrefix(groupId, userId)}_r${recordId.replace("-", "").take(16)}"

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
        return when (storage) {
            CommunityArtifactStorage.PUBLIC_KEYS -> publicKeyRegistryKey(groupId, userId)
            CommunityArtifactStorage.PACKAGES -> packageRegistryPrefix(groupId, userId)
            CommunityArtifactStorage.NATIVE_LAYER -> "mapsafe_${kind}_g${groupId}_u$userId"
        }
    }

    fun artifactKey(groupId: Long, userId: Long, recordId: String): String {
        val suffix = recordId.replace("-", "").take(16)
        return "mapsafe_artifact_g${groupId}_u${userId}_$suffix"
    }
}
