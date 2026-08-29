package com.nextgis.mobile.mapsafe.keys

import android.accounts.AccountManager
import android.content.Context
import com.nextgis.maplib.api.IGISApplication
import com.nextgis.maplib.util.AccountUtil
import com.nextgis.maplib.util.HttpResponse
import com.nextgis.maplib.util.NGWUtil
import com.nextgis.maplib.util.NetworkUtil
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpException
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyCodec
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID

/** NextGIS Web adapter for publishing and discovering MapSafe public keys. */
class NextGisPublicKeyDirectoryClient(
    context: Context,
    private val keyRepository: OpenPgpKeyRepository,
    private val exchangeRepository: PublicKeyExchangeRepository
) {
    private val context = context.applicationContext

    fun accountNames(): List<String> {
        val app = context as? IGISApplication ?: return emptyList()
        return AccountManager.get(context)
            .getAccountsByType(app.accountsType)
            .map { it.name }
            .distinct()
            .sorted()
    }

    fun accountSummaries(): List<NextGisAccountSummary> {
        return accountNames().map { accountName ->
            val account = account(accountName)
            NextGisAccountSummary(
                accountName = accountName,
                serverUrl = normalizeServer(account.url),
                login = account.login
            )
        }
    }

    /**
     * Resolves group membership from the signed-in NextGIS user instead of
     * asking a MapSafe user to know or type a numeric authentication-group ID.
     */
    fun membershipGroups(accountName: String): List<NextGisGroupSummary> {
        val account = account(accountName)
        val currentUser = currentUser(account)
        val user = getObject(account, "${server(account)}/api/component/auth/user/${currentUser.id}")
        val memberships = user.optJSONArray("member_of") ?: JSONArray()
        return buildList {
            for (index in 0 until memberships.length()) {
                val group = authGroup(account, memberships.getLong(index))
                add(groupSummary(account, group, currentUser.id))
            }
        }.sortedBy { it.displayName.lowercase() }
    }

    /** Creates a real NextGIS authentication group with the current user as its first member. */
    fun createGroup(accountName: String, displayName: String, description: String?): NextGisGroupSummary {
        val cleanName = displayName.trim()
        require(cleanName.isNotEmpty()) { "Enter a group name." }
        val account = account(accountName)
        val currentUser = currentUser(account)
        val payload = JSONObject()
            .put("display_name", cleanName)
            .put("keyname", groupKeyname(cleanName))
            .put("members", JSONArray().put(currentUser.id))
        description?.trim()?.takeIf { it.isNotEmpty() }?.let { payload.put("description", it) }

        val response = postJson(account, "${server(account)}/api/component/auth/group/", payload)
        if (!response.isOk) {
            val action = if (response.responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                "Your NextGIS account is not permitted to create authentication groups"
            } else {
                "Could not create the MapSafe group"
            }
            throw httpError(action, response)
        }
        val id = JSONObject(response.responseBody ?: "{}").optLong("id", -1L)
        if (id <= 0L) throw OpenPgpException("NextGIS did not return the new group ID.")
        return groupSummary(account, authGroup(account, id), currentUser.id)
    }

    /** Validates the public-only payload before the first network request. */
    fun publish(accountName: String, groupId: Long): PublicKeyPublishResult {
        require(groupId > 0) { "NextGIS group ID must be greater than zero." }
        val ring = keyRepository.loadLocalPublicKeyRing()
            ?: throw OpenPgpException("Create or import a local OpenPGP identity first.")
        if (OpenPgpKeyCodec.findEncryptionKey(ring) == null) {
            throw OpenPgpException("The local public key has no usable encryption subkey.")
        }
        val publicKey = OpenPgpKeyCodec.encodePublicKeyRing(ring)
        val reparsed = OpenPgpKeyCodec.decodePublicKeyRing(publicKey)
        val fingerprint = OpenPgpKeyCodec.fingerprint(reparsed.publicKey.fingerprint)
        if (fingerprint != OpenPgpKeyCodec.fingerprint(ring.publicKey.fingerprint)) {
            throw OpenPgpException("The public-key export failed its local fingerprint check.")
        }

        val account = account(accountName)
        val currentUser = currentUser(account)
        val authGroup = authGroup(account, groupId)
        if (currentUser.id !in authGroup.memberIds) {
            throw OpenPgpException("The signed-in NextGIS user is not a member of group $groupId.")
        }

        val directory = findOrCreateDirectory(account, authGroup)
        val bucketKey = personalBucketKey(groupId, currentUser.id)
        val existingBucket = findResourceByKey(account, bucketKey)?.also { resource ->
            if (resource.cls != FILE_BUCKET_CLASS || resource.parentId != directory.id) {
                throw OpenPgpException("The reserved MapSafe key resource has an unexpected type or parent.")
            }
            if (resource.ownerUserId != currentUser.id) {
                throw OpenPgpException("Another user owns the reserved key bucket. Ask the NextGIS administrator to resolve it.")
            }
        }
        val previousManifest = existingBucket?.let { bucket ->
            runCatching { readManifest(account, bucket.id) }.getOrNull()
        }
        val keyVersion = when {
            previousManifest == null -> 1
            previousManifest.fingerprint == fingerprint -> previousManifest.keyVersion.coerceAtLeast(1)
            else -> previousManifest.keyVersion.coerceAtLeast(1) + 1
        }
        val publishedAt = Instant.now().toString()
        val manifest = KeyDirectoryManifest(
            identityId = UUID.nameUUIDFromBytes(
                "${normalizeServer(account.url)}|$groupId|${currentUser.id}".toByteArray(Charsets.UTF_8)
            ).toString(),
            nextgisUserId = currentUser.id,
            nextgisGroupId = groupId,
            nextgisKeyname = currentUser.keyname,
            displayName = currentUser.displayName,
            fingerprint = fingerprint,
            keyVersion = keyVersion,
            algorithm = "OpenPGP-${ring.publicKey.bitStrength}",
            createdAt = ring.publicKey.creationTime.toInstant().toString(),
            publishedAt = publishedAt,
            status = "active",
            publicKeyFile = PUBLIC_KEY_FILE
        )

        val uploads = uploadDirectoryFiles(account, publicKey, manifest.toJson().toString(2).toByteArray())
        val bucketId = if (existingBucket == null) {
            createFileBucket(account, directory.id, bucketKey, currentUser, manifest, uploads)
        } else {
            updateFileBucket(account, existingBucket.id, manifest, uploads)
            existingBucket.id
        }
        return PublicKeyPublishResult(
            groupId = groupId,
            userId = currentUser.id,
            fingerprint = fingerprint,
            keyVersion = keyVersion,
            directoryResourceId = directory.id,
            bucketResourceId = bucketId
        )
    }

    fun sync(accountName: String, groupId: Long): PublicKeySyncReport {
        require(groupId > 0) { "NextGIS group ID must be greater than zero." }
        val account = account(accountName)
        val group = authGroup(account, groupId)
        val memberNames = memberNames(account, group.memberIds)
        val directory = findResourceByKey(account, directoryKey(groupId))
        val syncedAt = System.currentTimeMillis()
        if (directory == null) {
            exchangeRepository.markDirectoryPresence(
                account.url,
                groupId,
                group.memberIds,
                emptySet(),
                syncedAt
            )
            return PublicKeySyncReport(
                groupId,
                exchangeRepository.records(account.url, groupId),
                group.memberIds,
                emptyList(),
                syncedAt,
                memberNames
            )
        }
        if (directory.cls != RESOURCE_GROUP_CLASS) {
            throw OpenPgpException("The reserved MapSafe key directory is not a NextGIS resource group.")
        }

        val invalid = mutableListOf<String>()
        val candidates = mutableListOf<DirectoryCandidate>()
        childResources(account, directory.id)
            .filter { it.cls == FILE_BUCKET_CLASS }
            .forEach { bucket ->
                runCatching {
                    val manifest = readManifest(account, bucket.id)
                    validateManifest(manifest, bucket, group)
                    val bytes = downloadResourceFile(account, bucket.id, manifest.publicKeyFile, MAX_PUBLIC_KEY_BYTES)
                    val ring = OpenPgpKeyCodec.decodePublicKeyRing(bytes)
                    val actual = OpenPgpKeyCodec.fingerprint(ring.publicKey.fingerprint)
                    if (actual != manifest.fingerprint) {
                        throw OpenPgpException("published fingerprint does not match the key")
                    }
                    if (manifest.status == "active" && OpenPgpKeyCodec.findEncryptionKey(ring) == null) {
                        throw OpenPgpException("public key has no usable encryption key")
                    }
                    DirectoryCandidate(bucket, manifest, bytes)
                }.onSuccess(candidates::add).onFailure { error ->
                    invalid += "Bucket ${bucket.id}: ${error.message ?: "invalid key entry"}"
                }
            }

        val duplicateUsers = candidates.groupingBy { it.manifest.nextgisUserId }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (duplicateUsers.isNotEmpty()) {
            invalid += duplicateUsers.sorted().map { "User $it has duplicate public-key buckets." }
        }

        val records = candidates
            .filterNot { it.manifest.nextgisUserId in duplicateUsers }
            .map { candidate ->
                val manifest = candidate.manifest
                exchangeRepository.observe(
                    PublicKeyObservation(
                        identity = PublicKeyDirectoryIdentity(
                            serverUrl = account.url,
                            accountName = accountName,
                            groupId = groupId,
                            userId = manifest.nextgisUserId
                        ),
                        displayName = manifest.displayName,
                        fingerprint = manifest.fingerprint,
                        keyVersion = manifest.keyVersion,
                        bucketId = candidate.bucket.id,
                        publishedAt = manifest.publishedAt,
                        directoryStatus = manifest.status
                    ),
                    candidate.publicKey,
                    syncedAt
                )
            }
        val observedUsers = records.mapTo(mutableSetOf()) { it.identity.userId }
        exchangeRepository.markDirectoryPresence(
            account.url,
            groupId,
            group.memberIds,
            observedUsers,
            syncedAt
        )
        return PublicKeySyncReport(
            groupId = groupId,
            records = exchangeRepository.records(account.url, groupId),
            missingMemberIds = group.memberIds - observedUsers,
            invalidEntries = invalid,
            syncedAt = syncedAt,
            memberNames = memberNames
        )
    }

    private fun validateManifest(
        manifest: KeyDirectoryManifest,
        bucket: NextGisResource,
        group: NextGisAuthGroup
    ) {
        if (manifest.nextgisGroupId != group.id) throw OpenPgpException("manifest group ID is incorrect")
        if (manifest.nextgisUserId !in group.memberIds) throw OpenPgpException("publisher is not a current group member")
        if (manifest.nextgisUserId != bucket.ownerUserId) throw OpenPgpException("manifest user does not own the bucket")
        if (manifest.keyVersion < 1) throw OpenPgpException("manifest key version is invalid")
        if (manifest.status !in DIRECTORY_STATUSES) throw OpenPgpException("manifest status is invalid")
        if (manifest.publicKeyFile != PUBLIC_KEY_FILE) throw OpenPgpException("manifest public-key filename is invalid")
    }

    private fun findOrCreateDirectory(account: AccountUtil.AccountData, group: NextGisAuthGroup): NextGisResource {
        findResourceByKey(account, directoryKey(group.id))?.let { existing ->
            if (existing.cls != RESOURCE_GROUP_CLASS) {
                throw OpenPgpException("The reserved MapSafe key directory name is used by another resource type.")
            }
            return existing
        }
        val permissions = JSONArray()
            .put(permission(group.id, "read", propagate = true))
            .put(permission(group.id, "read", propagate = true, scope = "data"))
            .put(permission(group.id, "create", propagate = false))
            .put(permission(group.id, "manage_children", propagate = false))
        val payload = JSONObject().put(
            "resource",
            JSONObject()
                .put("cls", RESOURCE_GROUP_CLASS)
                .put("parent", JSONObject().put("id", 0))
                .put("display_name", "MapSafe public keys — ${group.displayName}")
                .put("keyname", directoryKey(group.id))
                .put("description", "Public OpenPGP keys for NextGIS authentication group ${group.id}. No private keys.")
                .put("permissions", permissions)
        )
        val response = postJson(account, resourceCollectionUrl(account), payload)
        if (!response.isOk) {
            findResourceByKey(account, directoryKey(group.id))?.let { raced ->
                if (raced.cls != RESOURCE_GROUP_CLASS) {
                    throw OpenPgpException("The reserved MapSafe key directory name is used by another resource type.")
                }
                return raced
            }
            throw httpError(
                "Could not create the MapSafe key directory. Ask an administrator to create it and grant group access",
                response
            )
        }
        val id = JSONObject(response.responseBody).getLong("id")
        return getResource(account, id)
    }

    private fun createFileBucket(
        account: AccountUtil.AccountData,
        parentId: Long,
        keyname: String,
        user: NextGisUser,
        manifest: KeyDirectoryManifest,
        uploads: JSONArray
    ): Long {
        val resource = JSONObject()
            .put("cls", FILE_BUCKET_CLASS)
            .put("parent", JSONObject().put("id", parentId))
            .put("display_name", "MapSafe public key — ${user.displayName}")
            .put("keyname", keyname)
            .put("description", "Public OpenPGP key for NextGIS user ${user.id}. No private key material.")
        val payload = JSONObject()
            .put("resource", resource)
            .put("file_bucket", JSONObject().put("files", uploads))
            .put("resmeta", metadata(manifest))
        val response = postJson(account, resourceCollectionUrl(account), payload)
        if (!response.isOk) throw httpError("Could not create the user's NextGIS file bucket", response)
        return JSONObject(response.responseBody).getLong("id")
    }

    private fun updateFileBucket(
        account: AccountUtil.AccountData,
        bucketId: Long,
        manifest: KeyDirectoryManifest,
        uploads: JSONArray
    ) {
        val payload = JSONObject()
            .put("file_bucket", JSONObject().put("files", uploads))
            .put("resmeta", metadata(manifest))
        val response = putJson(account, resourceUrl(account, bucketId), payload)
        if (!response.isOk) throw httpError("Could not update the user's NextGIS public-key bucket", response)
    }

    private fun metadata(manifest: KeyDirectoryManifest): JSONObject {
        return JSONObject().put(
            "items",
            JSONObject()
                .put("mapsafe.schema", MANIFEST_SCHEMA)
                .put("mapsafe.nextgis_group_id", manifest.nextgisGroupId)
                .put("mapsafe.nextgis_user_id", manifest.nextgisUserId)
                .put("mapsafe.fingerprint", manifest.fingerprint)
                .put("mapsafe.key_version", manifest.keyVersion)
                .put("mapsafe.status", manifest.status)
        )
    }

    private fun permission(
        principalId: Long,
        permission: String,
        propagate: Boolean,
        scope: String = "resource"
    ): JSONObject {
        return JSONObject()
            .put("action", "allow")
            .put("principal", JSONObject().put("id", principalId))
            .put("identity", "")
            .put("scope", scope)
            .put("permission", permission)
            .put("propagate", propagate)
    }

    private fun uploadDirectoryFiles(
        account: AccountUtil.AccountData,
        publicKey: ByteArray,
        manifest: ByteArray
    ): JSONArray {
        val publicFile = File.createTempFile("mapsafe-public-", ".asc", context.cacheDir)
        val manifestFile = File.createTempFile("mapsafe-key-metadata-", ".json", context.cacheDir)
        return try {
            publicFile.writeBytes(publicKey)
            manifestFile.writeBytes(manifest)
            JSONArray()
                .put(uploadFile(account, PUBLIC_KEY_FILE, publicFile, "application/pgp-keys"))
                .put(uploadFile(account, MANIFEST_FILE, manifestFile, "application/json"))
        } finally {
            publicFile.delete()
            manifestFile.delete()
        }
    }

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
        val body = JSONObject(response.responseBody)
        val uploaded = if (body.has("upload_meta")) {
            body.getJSONArray("upload_meta").getJSONObject(0)
        } else {
            body
        }
        return JSONObject(uploaded.toString()).put("name", fileName)
    }

    private fun readManifest(account: AccountUtil.AccountData, bucketId: Long): KeyDirectoryManifest {
        val bytes = downloadResourceFile(account, bucketId, MANIFEST_FILE, MAX_MANIFEST_BYTES)
        return KeyDirectoryManifest.fromJson(JSONObject(bytes.toString(Charsets.UTF_8)))
    }

    private fun downloadResourceFile(
        account: AccountUtil.AccountData,
        resourceId: Long,
        fileName: String,
        maximumBytes: Int
    ): ByteArray {
        val encoded = URLEncoder.encode(fileName, Charsets.UTF_8.name()).replace("+", "%20")
        val url = "${resourceUrl(account, resourceId)}/file/$encoded"
        val connection = NetworkUtil.getHttpConnection("GET", url, account.login, account.password)
            ?: throw OpenPgpException("Could not open the NextGIS public-key download.")
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw OpenPgpException("NextGIS returned HTTP $status while downloading $fileName.")
            }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maximumBytes) throw OpenPgpException("Downloaded $fileName exceeds the size limit.")
                    output.write(buffer, 0, count)
                }
                return output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun currentUser(account: AccountUtil.AccountData): NextGisUser {
        val json = getObject(account, "${server(account)}/api/component/auth/current_user")
        return NextGisUser(
            id = json.getLong("id"),
            keyname = json.getString("keyname"),
            displayName = json.optString("display_name").ifBlank { json.getString("keyname") }
        )
    }

    private fun authGroup(account: AccountUtil.AccountData, groupId: Long): NextGisAuthGroup {
        val json = getObject(account, "${server(account)}/api/component/auth/group/$groupId")
        val members = json.optJSONArray("members") ?: JSONArray()
        return NextGisAuthGroup(
            id = json.getLong("id"),
            displayName = json.optString("display_name").ifBlank { "Group $groupId" },
            keyname = json.optString("keyname").ifBlank { "group_$groupId" },
            memberIds = buildSet {
                for (index in 0 until members.length()) add(members.getLong(index))
            }
        )
    }

    private fun groupSummary(
        account: AccountUtil.AccountData,
        group: NextGisAuthGroup,
        currentUserId: Long
    ): NextGisGroupSummary {
        return NextGisGroupSummary(
            id = group.id,
            displayName = group.displayName,
            keyname = group.keyname,
            memberIds = group.memberIds,
            memberNames = memberNames(account, group.memberIds),
            currentUserId = currentUserId
        )
    }

    private fun memberNames(account: AccountUtil.AccountData, memberIds: Set<Long>): Map<Long, String> {
        return memberIds.associateWith { memberId ->
            runCatching {
                val json = getObject(account, "${server(account)}/api/component/auth/user/$memberId")
                json.optString("display_name").ifBlank {
                    json.optString("keyname").ifBlank { "Member $memberId" }
                }
            }.getOrDefault("Member $memberId")
        }
    }

    private fun findResourceByKey(account: AccountUtil.AccountData, keyname: String): NextGisResource? {
        val encoded = URLEncoder.encode(keyname, Charsets.UTF_8.name())
        val array = getArray(account, "${server(account)}/api/resource/search/?keyname=$encoded&serialization=full")
        if (array.length() == 0) return null
        if (array.length() > 1) throw OpenPgpException("NextGIS returned duplicate resources for keyname $keyname.")
        return resourceFromJson(array.getJSONObject(0))
    }

    private fun childResources(account: AccountUtil.AccountData, parentId: Long): List<NextGisResource> {
        val array = getArray(account, "${server(account)}/api/resource/?parent=$parentId")
        return buildList {
            for (index in 0 until array.length()) add(resourceFromJson(array.getJSONObject(index)))
        }
    }

    private fun getResource(account: AccountUtil.AccountData, id: Long): NextGisResource {
        return resourceFromJson(getObject(account, resourceUrl(account, id)))
    }

    private fun resourceFromJson(container: JSONObject): NextGisResource {
        val resource = container.optJSONObject("resource") ?: container
        return NextGisResource(
            id = resource.getLong("id"),
            cls = resource.getString("cls"),
            parentId = resource.optJSONObject("parent")?.optLong("id", -1) ?: -1,
            ownerUserId = resource.optJSONObject("owner_user")?.optLong("id", -1) ?: -1,
            keyname = resource.optString("keyname").takeIf { !resource.isNull("keyname") }
        )
    }

    private fun getObject(account: AccountUtil.AccountData, url: String): JSONObject {
        return JSONObject(requireOk(NetworkUtil.get(url, account.login, account.password, true), "NextGIS request failed"))
    }

    private fun getArray(account: AccountUtil.AccountData, url: String): JSONArray {
        return JSONArray(requireOk(NetworkUtil.get(url, account.login, account.password, true), "NextGIS request failed"))
    }

    private fun postJson(account: AccountUtil.AccountData, url: String, json: JSONObject): HttpResponse {
        return NetworkUtil.post(url, json.toString(), account.login, account.password, true)
    }

    private fun putJson(account: AccountUtil.AccountData, url: String, json: JSONObject): HttpResponse {
        return NetworkUtil.put(url, json.toString(), account.login, account.password, true)
    }

    private fun requireOk(response: HttpResponse, action: String): String {
        if (!response.isOk) throw httpError(action, response)
        return response.responseBody ?: throw OpenPgpException("$action: NextGIS returned an empty response.")
    }

    private fun httpError(action: String, response: HttpResponse): OpenPgpException {
        return OpenPgpException("$action (HTTP ${response.responseCode}).")
    }

    private fun account(name: String): AccountUtil.AccountData {
        return try {
            AccountUtil.getAccountData(context, name)
        } catch (error: Exception) {
            throw OpenPgpException("The selected NextGIS account is no longer available.", error)
        }
    }

    private fun server(account: AccountUtil.AccountData): String = normalizeServer(account.url)
    private fun resourceCollectionUrl(account: AccountUtil.AccountData): String = "${server(account)}/api/resource/"
    private fun resourceUrl(account: AccountUtil.AccountData, id: Long): String = "${server(account)}/api/resource/$id"
    private fun normalizeServer(url: String): String = NGWUtil.getServerUrl(url).trimEnd('/')
    private fun directoryKey(groupId: Long): String = "mapsafe_keys_$groupId"
    private fun personalBucketKey(groupId: Long, userId: Long): String = "mapsafe_pk_g${groupId}_u$userId"
    private fun groupKeyname(displayName: String): String {
        val stem = displayName.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(28)
            .ifBlank { "group" }
            .let { if (it.first().isDigit()) "g_$it" else it }
        return "mapsafe_${stem}_${UUID.randomUUID().toString().take(8)}"
    }

    private data class NextGisUser(val id: Long, val keyname: String, val displayName: String)
    private data class NextGisAuthGroup(
        val id: Long,
        val displayName: String,
        val keyname: String,
        val memberIds: Set<Long>
    )
    private data class NextGisResource(
        val id: Long,
        val cls: String,
        val parentId: Long,
        val ownerUserId: Long,
        val keyname: String?
    )
    private data class DirectoryCandidate(
        val bucket: NextGisResource,
        val manifest: KeyDirectoryManifest,
        val publicKey: ByteArray
    )

    private data class KeyDirectoryManifest(
        val identityId: String,
        val nextgisUserId: Long,
        val nextgisGroupId: Long,
        val nextgisKeyname: String,
        val displayName: String,
        val fingerprint: String,
        val keyVersion: Int,
        val algorithm: String,
        val createdAt: String,
        val publishedAt: String,
        val status: String,
        val publicKeyFile: String
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("schema", MANIFEST_SCHEMA)
            .put("identityId", identityId)
            .put("nextgisUserId", nextgisUserId)
            .put("nextgisGroupId", nextgisGroupId)
            .put("nextgisKeyname", nextgisKeyname)
            .put("displayName", displayName)
            .put("fingerprint", fingerprint)
            .put("keyVersion", keyVersion)
            .put("algorithm", algorithm)
            .put("createdAt", createdAt)
            .put("publishedAt", publishedAt)
            .put("status", status)
            .put("publicKeyFile", publicKeyFile)

        companion object {
            fun fromJson(json: JSONObject): KeyDirectoryManifest {
                if (json.getString("schema") != MANIFEST_SCHEMA) {
                    throw OpenPgpException("unsupported key manifest schema")
                }
                val fingerprint = json.getString("fingerprint").replace(" ", "").uppercase()
                if (!fingerprint.matches(Regex("(?:[0-9A-F]{40}|[0-9A-F]{64})"))) {
                    throw OpenPgpException("manifest fingerprint is invalid")
                }
                val identityId = json.getString("identityId")
                val createdAt = json.getString("createdAt")
                val publishedAt = json.getString("publishedAt")
                runCatching { UUID.fromString(identityId) }
                    .getOrElse { throw OpenPgpException("manifest identity ID is invalid", it) }
                runCatching { Instant.parse(createdAt) }
                    .getOrElse { throw OpenPgpException("manifest creation timestamp is invalid", it) }
                runCatching { Instant.parse(publishedAt) }
                    .getOrElse { throw OpenPgpException("manifest publication timestamp is invalid", it) }
                return KeyDirectoryManifest(
                    identityId = identityId,
                    nextgisUserId = json.getLong("nextgisUserId"),
                    nextgisGroupId = json.getLong("nextgisGroupId"),
                    nextgisKeyname = json.getString("nextgisKeyname"),
                    displayName = json.getString("displayName"),
                    fingerprint = fingerprint,
                    keyVersion = json.getInt("keyVersion"),
                    algorithm = json.getString("algorithm"),
                    createdAt = createdAt,
                    publishedAt = publishedAt,
                    status = json.getString("status").lowercase(),
                    publicKeyFile = json.getString("publicKeyFile")
                )
            }
        }
    }

    companion object {
        private const val MANIFEST_SCHEMA = "mapsafe-key-directory/v1"
        private const val PUBLIC_KEY_FILE = "public-key.asc"
        private const val MANIFEST_FILE = "key-metadata.json"
        private const val RESOURCE_GROUP_CLASS = "resource_group"
        private const val FILE_BUCKET_CLASS = "file_bucket"
        private const val MAX_PUBLIC_KEY_BYTES = 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private val DIRECTORY_STATUSES = setOf("active", "superseded", "revoked")
    }
}
