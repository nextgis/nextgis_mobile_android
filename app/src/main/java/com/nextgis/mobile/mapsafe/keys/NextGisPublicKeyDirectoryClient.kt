package com.nextgis.mobile.mapsafe.keys

import android.accounts.AccountManager
import android.content.Context
import com.nextgis.maplib.api.IGISApplication
import com.nextgis.maplib.util.AccountUtil
import com.nextgis.maplib.util.HttpResponse
import com.nextgis.maplib.util.NGWUtil
import com.nextgis.maplib.util.NetworkUtil
import com.nextgis.mobile.mapsafe.community.CommunityArtifactType
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpException
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyCodec
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyRepository
import com.nextgis.mobile.mapsafe.community.NextGisCommunityNames
import com.nextgis.mobile.mapsafe.community.NextGisCommunityPublicKeyClient
import com.nextgis.mobile.mapsafe.community.NextGisCommunityPublisher
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
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
        val selection = MapSafeSecurityPreferences.Selection(
            accountName = accountName,
            serverUrl = normalizeServer(account.url),
            groupId = groupId,
            groupName = authGroup.displayName,
            currentUserId = currentUser.id,
            groupMemberCount = authGroup.memberIds.size
        )
        val existingRecords = NextGisCommunityPublicKeyClient(context)
            .listPublicKeys(selection)
            .filter { it.publisherId == currentUser.id }
        val current = existingRecords
            .filter { it.status == "active" }
            .maxWithOrNull(compareBy({ it.keyVersion }, { it.createdAt }, { it.featureId }))
        if (current?.fingerprint == fingerprint) {
            return PublicKeyPublishResult(
                groupId = groupId,
                userId = currentUser.id,
                fingerprint = fingerprint,
                keyVersion = current.keyVersion,
                directoryResourceId = directory.id,
                bucketResourceId = current.registryResourceId
            )
        }
        val keyVersion = (existingRecords.maxOfOrNull { it.keyVersion } ?: 0) + 1
        val publicFile = File.createTempFile("mapsafe-public-", ".asc", context.cacheDir)
        val published = try {
            publicFile.writeBytes(publicKey)
            NextGisCommunityPublisher(context).publishAttachedFile(
                selection = selection,
                source = publicFile,
                fileName = PUBLIC_KEY_FILE,
                mimeType = "application/pgp-keys",
                artifactType = CommunityArtifactType.PUBLIC_KEY,
                fingerprint = fingerprint,
                keyVersion = keyVersion
            )
        } finally {
            publicFile.delete()
        }
        return PublicKeyPublishResult(
            groupId = groupId,
            userId = currentUser.id,
            fingerprint = fingerprint,
            keyVersion = keyVersion,
            directoryResourceId = directory.id,
            bucketResourceId = published.resourceId
        )
    }

    fun sync(accountName: String, groupId: Long): PublicKeySyncReport {
        require(groupId > 0) { "NextGIS group ID must be greater than zero." }
        val account = account(accountName)
        val group = authGroup(account, groupId)
        val signedInUser = currentUser(account)
        val memberNames = memberNames(account, group.memberIds)
        val syncedAt = System.currentTimeMillis()
        val selection = MapSafeSecurityPreferences.Selection(
            accountName = accountName,
            serverUrl = normalizeServer(account.url),
            groupId = groupId,
            groupName = group.displayName,
            currentUserId = signedInUser.id,
            groupMemberCount = group.memberIds.size
        )
        val publicKeyClient = NextGisCommunityPublicKeyClient(context)
        val newestByUser = publicKeyClient.listPublicKeys(selection)
            .filter { it.publisherId in group.memberIds }
            .groupBy { it.publisherId }
            .mapNotNull { (_, records) ->
                records.maxWithOrNull(compareBy({ it.keyVersion }, { it.createdAt }, { it.featureId }))
            }
        val invalid = mutableListOf<String>()
        val records = newestByUser.mapNotNull { remote ->
            runCatching {
                val bytes = publicKeyClient.downloadPublicKey(selection, remote)
                val ring = OpenPgpKeyCodec.decodePublicKeyRing(bytes)
                val actual = OpenPgpKeyCodec.fingerprint(ring.publicKey.fingerprint)
                if (actual != remote.fingerprint) {
                    throw OpenPgpException("published fingerprint does not match the public key")
                }
                if (remote.status == "active" && OpenPgpKeyCodec.findEncryptionKey(ring) == null) {
                    throw OpenPgpException("public key has no usable encryption key")
                }
                exchangeRepository.observe(
                    PublicKeyObservation(
                        identity = PublicKeyDirectoryIdentity(
                            serverUrl = account.url,
                            accountName = accountName,
                            groupId = groupId,
                            userId = remote.publisherId
                        ),
                        displayName = remote.publisherName,
                        fingerprint = remote.fingerprint,
                        keyVersion = remote.keyVersion,
                        bucketId = remote.registryResourceId,
                        publishedAt = remote.createdAt,
                        directoryStatus = remote.status
                    ),
                    bytes,
                    syncedAt
                )
            }.onFailure { error ->
                invalid += "Public-key record ${remote.recordId}: ${error.message ?: "invalid key entry"}"
            }.getOrNull()
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

    private fun findOrCreateDirectory(account: AccountUtil.AccountData, group: NextGisAuthGroup): NextGisResource {
        val root = findOrCreateResourceGroup(
            account = account,
            parentId = ROOT_RESOURCE_ID,
            displayName = "MapSafe",
            keyname = NextGisCommunityNames.rootKey,
            description = "MapSafe community resources. Private keys and passphrases are never stored here.",
            permissions = JSONArray().put(permission(group.id, "read", propagate = false))
        )
        val community = findOrCreateResourceGroup(
            account = account,
            parentId = root.id,
            displayName = group.displayName,
            keyname = NextGisCommunityNames.communityKey(group.id),
            description = "MapSafe resources for NextGIS authentication group ${group.id}.",
            permissions = JSONArray()
                .put(permission(group.id, "read", propagate = false))
                .put(permission(group.id, "create", propagate = false))
        )
        return findOrCreateResourceGroup(
            account = account,
            parentId = community.id,
            displayName = "Public Keys",
            keyname = directoryKey(group.id),
            description = "Public OpenPGP keys for NextGIS authentication group ${group.id}. No private keys.",
            permissions = JSONArray()
                .put(permission(group.id, "read", propagate = false))
                .put(permission(group.id, "create", propagate = false))
        )
    }

    private fun findOrCreateResourceGroup(
        account: AccountUtil.AccountData,
        parentId: Long,
        displayName: String,
        keyname: String,
        description: String,
        permissions: JSONArray? = null
    ): NextGisResource {
        findResourceByKey(account, keyname)?.let { existing ->
            validateResourceGroup(existing, parentId, keyname)
            return existing
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
                validateResourceGroup(raced, parentId, keyname)
                return raced
            }
            throw httpError(
                "Could not create the $displayName Web GIS resource group. " +
                    "Ask the community administrator to create the MapSafe hierarchy and grant access",
                response
            )
        }
        return getResource(account, JSONObject(response.responseBody).getLong("id"))
    }

    private fun validateResourceGroup(resource: NextGisResource, parentId: Long, keyname: String) {
        if (resource.cls != RESOURCE_GROUP_CLASS || resource.parentId != parentId) {
            throw OpenPgpException(
                "The reserved NextGIS resource $keyname has an unexpected type or parent."
            )
        }
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
    private fun directoryKey(groupId: Long): String = NextGisCommunityNames.publicKeysKey(groupId)
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
    companion object {
        private const val ROOT_RESOURCE_ID = 0L
        private const val PUBLIC_KEY_FILE = "public-key.asc"
        private const val RESOURCE_GROUP_CLASS = "resource_group"
    }
}
