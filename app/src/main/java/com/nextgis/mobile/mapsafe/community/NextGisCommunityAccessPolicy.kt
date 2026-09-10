package com.nextgis.mobile.mapsafe.community

/** How a published artifact is exposed inside a NextGIS community. */
enum class CommunityAudienceMode {
    COMMUNITY,
    SELECTED_RECIPIENTS
}

/** A NextGIS member whose identity is bound to an accepted OpenPGP fingerprint. */
data class CommunityAudienceMember(
    val userId: Long,
    val displayName: String,
    val fingerprint: String
) {
    init {
        require(userId > 0L) { "A community audience member must have a NextGIS user ID." }
        require(displayName.isNotBlank()) { "A community audience member must have a display name." }
        require(fingerprint.isNotBlank()) { "A community audience member must have an OpenPGP fingerprint." }
    }
}

/** Audience retained with an encrypted output before it is uploaded. */
data class CommunityArtifactAudience(
    val mode: CommunityAudienceMode,
    val members: List<CommunityAudienceMember>,
    val unmappedFingerprints: Set<String> = emptySet()
) {
    val recipientUserIds: Set<Long>
        get() = members.mapTo(linkedSetOf()) { it.userId }

    val isReadyForRestrictedUpload: Boolean
        get() = mode == CommunityAudienceMode.SELECTED_RECIPIENTS &&
            members.isNotEmpty() && unmappedFingerprints.isEmpty()

    companion object {
        fun community(): CommunityArtifactAudience = CommunityArtifactAudience(
            mode = CommunityAudienceMode.COMMUNITY,
            members = emptyList()
        )

        fun selectedRecipients(
            members: Collection<CommunityAudienceMember>,
            unmappedFingerprints: Collection<String> = emptySet()
        ): CommunityArtifactAudience = CommunityArtifactAudience(
            mode = CommunityAudienceMode.SELECTED_RECIPIENTS,
            members = members.distinctBy { it.userId }.sortedBy { it.userId },
            unmappedFingerprints = unmappedFingerprints.toSortedSet()
        )
    }
}

internal data class NextGisPermissionRule(
    val principalId: Long,
    val scope: String,
    val permission: String,
    val propagate: Boolean = false
)

/** Pure ACL policy so it can be verified before a Premium Web GIS is activated. */
internal object NextGisCommunityAccessPolicy {
    fun rootPath(groupId: Long): List<NextGisPermissionRule> = listOf(
        rule(groupId, "resource", "read")
    )

    fun communityFolder(groupId: Long): List<NextGisPermissionRule> = listOf(
        rule(groupId, "resource", "read"),
        rule(groupId, "resource", "create")
    )

    fun artifactFolder(groupId: Long): List<NextGisPermissionRule> = listOf(
        rule(groupId, "resource", "read"),
        rule(groupId, "resource", "create")
    )

    fun communityReadableArtifact(groupId: Long): List<NextGisPermissionRule> = listOf(
        rule(groupId, "resource", "read"),
        rule(groupId, "data", "read")
    )

    fun encryptedPackage(
        publisherUserId: Long,
        audience: CommunityArtifactAudience
    ): List<NextGisPermissionRule> {
        require(audience.isReadyForRestrictedUpload) {
            "Every encrypted-package recipient must map to an accepted member of the selected NextGIS community."
        }
        return (audience.recipientUserIds + publisherUserId)
            .filter { it > 0L }
            .distinct()
            .sorted()
            .flatMap { userId ->
                listOf(
                    rule(userId, "resource", "read"),
                    rule(userId, "data", "read")
                )
            }
    }

    private fun rule(
        principalId: Long,
        scope: String,
        permission: String
    ): NextGisPermissionRule {
        require(principalId > 0L) { "A NextGIS permission principal must be a real user or group." }
        return NextGisPermissionRule(principalId, scope, permission, propagate = false)
    }
}
