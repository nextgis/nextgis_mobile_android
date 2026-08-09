package com.nextgis.mobile.mapsafe.keys

import android.content.Context

/** Small, non-secret selection state shared by setup and encryption screens. */
object MapSafeSecurityPreferences {
    private const val FILE_NAME = "mapsafe-security"
    private const val KEY_ACCOUNT = "selected-account"
    private const val KEY_SERVER = "selected-server"
    private const val KEY_GROUP_ID = "selected-group-id"
    private const val KEY_GROUP_NAME = "selected-group-name"
    private const val KEY_CURRENT_USER_ID = "selected-group-current-user-id"
    private const val KEY_GROUP_MEMBER_COUNT = "selected-group-member-count"

    data class Selection(
        val accountName: String?,
        val serverUrl: String?,
        val groupId: Long?,
        val groupName: String?,
        val currentUserId: Long?,
        val groupMemberCount: Int
    ) {
        val hasGroup: Boolean
            get() = !accountName.isNullOrBlank() && !serverUrl.isNullOrBlank() &&
                groupId != null && groupId > 0
    }

    fun read(context: Context): Selection {
        val preferences = context.applicationContext
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        return Selection(
            accountName = preferences.getString(KEY_ACCOUNT, null),
            serverUrl = preferences.getString(KEY_SERVER, null),
            groupId = preferences.getLong(KEY_GROUP_ID, 0L).takeIf { it > 0L },
            groupName = preferences.getString(KEY_GROUP_NAME, null),
            currentUserId = preferences.getLong(KEY_CURRENT_USER_ID, 0L).takeIf { it > 0L },
            groupMemberCount = preferences.getInt(KEY_GROUP_MEMBER_COUNT, 0)
        )
    }

    fun selectAccount(context: Context, account: NextGisAccountSummary) {
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCOUNT, account.accountName)
            .putString(KEY_SERVER, account.serverUrl)
            .remove(KEY_GROUP_ID)
            .remove(KEY_GROUP_NAME)
            .remove(KEY_CURRENT_USER_ID)
            .remove(KEY_GROUP_MEMBER_COUNT)
            .apply()
    }

    fun selectGroup(context: Context, account: NextGisAccountSummary, group: NextGisGroupSummary) {
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCOUNT, account.accountName)
            .putString(KEY_SERVER, account.serverUrl)
            .putLong(KEY_GROUP_ID, group.id)
            .putString(KEY_GROUP_NAME, group.displayName)
            .putLong(KEY_CURRENT_USER_ID, group.currentUserId)
            .putInt(KEY_GROUP_MEMBER_COUNT, group.memberIds.size)
            .apply()
    }
}
