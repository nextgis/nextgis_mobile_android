package com.nextgis.mobile.mapsafe.community

import android.content.Context
import com.nextgis.maplib.util.AccountUtil
import com.nextgis.maplib.util.HttpResponse
import com.nextgis.maplib.util.NGWUtil
import com.nextgis.maplib.util.NetworkUtil
import com.nextgis.mobile.mapsafe.keys.MapSafeSecurityPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder

/**
 * Resolves MapSafe community access without requiring ordinary members to read the
 * administrator-only NextGIS user and group administration endpoints.
 *
 * An administrator still gets an authoritative membership check from the auth-group API.
 * For an ordinary member, successful discovery of the ACL-protected community resource is
 * the membership proof: NextGIS itself has already evaluated the group's resource ACL.
 */
internal class NextGisCommunityMembershipResolver(context: Context) {
    private val context = context.applicationContext

    fun visibleCommunities(accountName: String): List<NextGisVisibleCommunity> {
        val account = account(accountName)
        val user = currentUser(account)
        val root = findResourceByKey(account, NextGisCommunityNames.rootKey) ?: return emptyList()
        return childResources(account, root.id)
            .mapNotNull { resource ->
                val groupId = NextGisCommunityNames.communityGroupId(resource.keyname)
                    ?: return@mapNotNull null
                if (resource.cls != RESOURCE_GROUP_CLASS || resource.parentId != root.id) {
                    return@mapNotNull null
                }
                NextGisVisibleCommunity(
                    groupId = groupId,
                    displayName = resource.displayName.ifBlank { "Community $groupId" },
                    keyname = resource.keyname.orEmpty(),
                    resourceId = resource.id,
                    currentUserId = user.id,
                    currentUserDisplayName = user.displayName
                )
            }
            .sortedBy { it.displayName.lowercase() }
    }

    fun resolve(selection: MapSafeSecurityPreferences.Selection): NextGisResolvedCommunity {
        if (!selection.hasGroup) {
            throw NextGisCommunityPublishException(
                "Choose a connected NextGIS account and community in Security & Sharing first."
            )
        }
        val accountName = requireNotNull(selection.accountName)
        val groupId = requireNotNull(selection.groupId)
        val account = account(accountName)
        val user = currentUser(account)

        val groupResponse = NetworkUtil.get(
            "${server(account)}/api/component/auth/group/$groupId",
            account.login,
            account.password,
            true
        )
        if (groupResponse.isOk) {
            val group = JSONObject(groupResponse.responseBody ?: "{}")
            val members = group.optJSONArray("members") ?: JSONArray()
            if ((0 until members.length()).none { members.optLong(it, -1L) == user.id }) {
                throw membershipError(selection)
            }
            return NextGisResolvedCommunity(
                account = account,
                groupId = groupId,
                groupName = group.optString("display_name")
                    .ifBlank { selection.groupName ?: "Community $groupId" },
                currentUserId = user.id,
                currentUserDisplayName = user.displayName,
                authoritativeMemberIds = buildSet {
                    for (index in 0 until members.length()) {
                        members.optLong(index, -1L).takeIf { it > 0L }?.let(::add)
                    }
                }
            )
        }
        if (groupResponse.responseCode != HttpURLConnection.HTTP_FORBIDDEN) {
            throw httpError("Could not validate the selected NextGIS community", groupResponse)
        }

        val visible = visibleCommunities(accountName).singleOrNull { it.groupId == groupId }
            ?: throw membershipError(selection)
        return NextGisResolvedCommunity(
            account = account,
            groupId = visible.groupId,
            groupName = visible.displayName,
            currentUserId = visible.currentUserId,
            currentUserDisplayName = visible.currentUserDisplayName,
            authoritativeMemberIds = null
        )
    }

    private fun membershipError(selection: MapSafeSecurityPreferences.Selection) =
        NextGisCommunityPublishException(
            "The signed-in NextGIS user cannot access " +
                "${selection.groupName ?: "the selected community"}. Ask its administrator to check membership and MapSafe resource permissions."
        )

    private fun currentUser(account: AccountUtil.AccountData): CurrentUser {
        val response = NetworkUtil.get(
            "${server(account)}/api/component/auth/current_user",
            account.login,
            account.password,
            true
        )
        if (!response.isOk) throw httpError("Could not identify the signed-in NextGIS user", response)
        val json = JSONObject(response.responseBody ?: "{}")
        return CurrentUser(
            id = json.getLong("id"),
            displayName = json.optString("display_name")
                .ifBlank { json.optString("keyname").ifBlank { "NextGIS user" } }
        )
    }

    private fun findResourceByKey(
        account: AccountUtil.AccountData,
        keyname: String
    ): VisibleResource? {
        val encoded = URLEncoder.encode(keyname, Charsets.UTF_8.name())
        val response = NetworkUtil.get(
            "${server(account)}/api/resource/search/?keyname=$encoded&serialization=full",
            account.login,
            account.password,
            true
        )
        if (response.responseCode == HttpURLConnection.HTTP_FORBIDDEN) return null
        if (!response.isOk) throw httpError("Could not discover MapSafe communities", response)
        val resources = JSONArray(response.responseBody ?: "[]")
        if (resources.length() == 0) return null
        if (resources.length() > 1) {
            throw NextGisCommunityPublishException("NextGIS returned duplicate resources for $keyname.")
        }
        return resourceFromJson(resources.getJSONObject(0))
    }

    private fun childResources(
        account: AccountUtil.AccountData,
        parentId: Long
    ): List<VisibleResource> {
        val response = NetworkUtil.get(
            "${server(account)}/api/resource/?parent=$parentId",
            account.login,
            account.password,
            true
        )
        if (response.responseCode == HttpURLConnection.HTTP_FORBIDDEN) return emptyList()
        if (!response.isOk) throw httpError("Could not list MapSafe communities", response)
        val resources = JSONArray(response.responseBody ?: "[]")
        return buildList {
            for (index in 0 until resources.length()) {
                add(resourceFromJson(resources.getJSONObject(index)))
            }
        }
    }

    private fun resourceFromJson(container: JSONObject): VisibleResource {
        val resource = container.optJSONObject("resource") ?: container
        return VisibleResource(
            id = resource.getLong("id"),
            cls = resource.getString("cls"),
            parentId = resource.optJSONObject("parent")?.optLong("id", -1L) ?: -1L,
            keyname = resource.optString("keyname").takeIf { !resource.isNull("keyname") },
            displayName = resource.optString("display_name")
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

    private fun server(account: AccountUtil.AccountData): String =
        NGWUtil.getServerUrl(account.url).trimEnd('/')

    private fun httpError(action: String, response: HttpResponse) =
        NextGisCommunityPublishException("$action (HTTP ${response.responseCode}).")

    private data class CurrentUser(val id: Long, val displayName: String)

    private data class VisibleResource(
        val id: Long,
        val cls: String,
        val parentId: Long,
        val keyname: String?,
        val displayName: String
    )

    private companion object {
        const val RESOURCE_GROUP_CLASS = "resource_group"
    }
}

internal data class NextGisVisibleCommunity(
    val groupId: Long,
    val displayName: String,
    val keyname: String,
    val resourceId: Long,
    val currentUserId: Long,
    val currentUserDisplayName: String
)

internal data class NextGisResolvedCommunity(
    val account: AccountUtil.AccountData,
    val groupId: Long,
    val groupName: String,
    val currentUserId: Long,
    val currentUserDisplayName: String,
    val authoritativeMemberIds: Set<Long>?
)
