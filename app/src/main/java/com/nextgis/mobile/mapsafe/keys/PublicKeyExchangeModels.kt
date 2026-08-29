package com.nextgis.mobile.mapsafe.keys

/** Trust assigned to a public key discovered through a NextGIS key directory. */
enum class PublicKeyTrustState {
    DISCOVERED,
    ACCEPTED,
    CHANGE_PENDING,
    SUPERSEDED,
    REVOKED,
    MEMBER_REMOVED
}

data class PublicKeyDirectoryIdentity(
    val serverUrl: String,
    val accountName: String,
    val groupId: Long,
    val userId: Long
)

data class PublicKeyObservation(
    val identity: PublicKeyDirectoryIdentity,
    val displayName: String,
    val fingerprint: String,
    val keyVersion: Int,
    val bucketId: Long,
    val publishedAt: String,
    val directoryStatus: String = "active"
)

data class CachedPublicKeyRecord(
    val recordId: String,
    val identity: PublicKeyDirectoryIdentity,
    val displayName: String,
    val observedFingerprint: String,
    val acceptedFingerprint: String?,
    val previousFingerprints: Set<String>,
    val keyVersion: Int,
    val bucketId: Long,
    val publishedAt: String,
    val lastSeenAt: Long,
    val trustState: PublicKeyTrustState
) {
    val needsUserReview: Boolean
        get() = trustState == PublicKeyTrustState.DISCOVERED ||
            trustState == PublicKeyTrustState.CHANGE_PENDING

    /** Fingerprints retained for verification but forbidden for new encryption. */
    fun nonSelectableFingerprints(): Set<String> {
        val blocked = previousFingerprints.toMutableSet()
        if (trustState != PublicKeyTrustState.ACCEPTED) {
            blocked += observedFingerprint
            acceptedFingerprint?.let(blocked::add)
        }
        return blocked
    }
}

data class PublicKeySyncReport(
    val groupId: Long,
    val records: List<CachedPublicKeyRecord>,
    val missingMemberIds: Set<Long>,
    val invalidEntries: List<String>,
    val syncedAt: Long,
    val memberNames: Map<Long, String> = emptyMap()
) {
    val discoveredCount: Int
        get() = records.count { it.trustState == PublicKeyTrustState.DISCOVERED }

    val acceptedCount: Int
        get() = records.count { it.trustState == PublicKeyTrustState.ACCEPTED }

    val changedCount: Int
        get() = records.count { it.trustState == PublicKeyTrustState.CHANGE_PENDING }
}

/** Existing NextGIS account reused by MapSafe; no second credential store is created. */
data class NextGisAccountSummary(
    val accountName: String,
    val serverUrl: String,
    val login: String
)

/** Authentication group whose membership defines a MapSafe sharing community. */
data class NextGisGroupSummary(
    val id: Long,
    val displayName: String,
    val keyname: String,
    val memberIds: Set<Long>,
    val memberNames: Map<Long, String>,
    val currentUserId: Long
)

data class PublicKeyPublishResult(
    val groupId: Long,
    val userId: Long,
    val fingerprint: String,
    val keyVersion: Int,
    val directoryResourceId: Long,
    val bucketResourceId: Long
)

/** Pure trust-state transitions, kept independent of Android and network code. */
object PublicKeyTrustEvaluator {
    fun observe(
        recordId: String,
        previous: CachedPublicKeyRecord?,
        observation: PublicKeyObservation,
        observedAt: Long
    ): CachedPublicKeyRecord {
        val normalizedStatus = observation.directoryStatus.lowercase()
        val state = when {
            normalizedStatus == "revoked" -> PublicKeyTrustState.REVOKED
            normalizedStatus != "active" -> PublicKeyTrustState.SUPERSEDED
            previous?.acceptedFingerprint == null -> PublicKeyTrustState.DISCOVERED
            previous.acceptedFingerprint != observation.fingerprint -> PublicKeyTrustState.CHANGE_PENDING
            else -> PublicKeyTrustState.ACCEPTED
        }
        return CachedPublicKeyRecord(
            recordId = recordId,
            identity = observation.identity,
            displayName = observation.displayName,
            observedFingerprint = observation.fingerprint,
            acceptedFingerprint = previous?.acceptedFingerprint,
            previousFingerprints = previous?.previousFingerprints.orEmpty(),
            keyVersion = observation.keyVersion,
            bucketId = observation.bucketId,
            publishedAt = observation.publishedAt,
            lastSeenAt = observedAt,
            trustState = state
        )
    }

    fun accept(record: CachedPublicKeyRecord, acceptedAt: Long): CachedPublicKeyRecord {
        require(record.needsUserReview) { "Only discovered or changed keys can be accepted." }
        val previous = record.previousFingerprints.toMutableSet()
        record.acceptedFingerprint
            ?.takeIf { it != record.observedFingerprint }
            ?.let(previous::add)
        previous.remove(record.observedFingerprint)
        return record.copy(
            acceptedFingerprint = record.observedFingerprint,
            previousFingerprints = previous,
            lastSeenAt = acceptedAt,
            trustState = PublicKeyTrustState.ACCEPTED
        )
    }

    fun memberRemoved(record: CachedPublicKeyRecord, observedAt: Long): CachedPublicKeyRecord {
        return record.copy(lastSeenAt = observedAt, trustState = PublicKeyTrustState.MEMBER_REMOVED)
    }

    fun directoryEntryMissing(record: CachedPublicKeyRecord, observedAt: Long): CachedPublicKeyRecord {
        return record.copy(lastSeenAt = observedAt, trustState = PublicKeyTrustState.SUPERSEDED)
    }
}
