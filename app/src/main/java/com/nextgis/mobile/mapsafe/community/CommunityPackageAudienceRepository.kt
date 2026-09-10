package com.nextgis.mobile.mapsafe.community

import android.content.Context
import android.util.AtomicFile
import com.nextgis.mobile.mapsafe.keys.CachedPublicKeyRecord
import com.nextgis.mobile.mapsafe.keys.MapSafeSecurityPreferences
import com.nextgis.mobile.mapsafe.keys.PublicKeyTrustState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class CommunityPackageAudienceRecord(
    val sha256: String,
    val fileName: String,
    val serverUrl: String,
    val groupId: Long,
    val publisherUserId: Long,
    val audience: CommunityArtifactAudience,
    val recordedAt: Long
)

/** App-private, non-secret link between an encrypted package and its intended Web GIS audience. */
class CommunityPackageAudienceRepository(context: Context) {
    private val recordsFile = File(
        context.applicationContext.noBackupFilesDir,
        "mapsafe/community/package-audiences.json"
    )

    @Synchronized
    fun record(value: CommunityPackageAudienceRecord) {
        require(SHA_256.matches(value.sha256.lowercase(Locale.ROOT))) {
            "A package audience must be linked to a valid SHA-256."
        }
        require(value.groupId > 0L && value.publisherUserId > 0L) {
            "A package audience must belong to a NextGIS community member."
        }
        val values = readRecords().toMutableList()
        values.removeAll {
            it.sha256.equals(value.sha256, ignoreCase = true) &&
                normalizeServer(it.serverUrl) == normalizeServer(value.serverUrl) &&
                it.groupId == value.groupId
        }
        values += value.copy(sha256 = value.sha256.lowercase(Locale.ROOT))
        writeRecords(values)
    }

    @Synchronized
    fun find(
        sha256: String,
        selection: MapSafeSecurityPreferences.Selection
    ): CommunityPackageAudienceRecord? {
        if (!selection.hasGroup) return null
        return readRecords().firstOrNull {
            it.sha256.equals(sha256, ignoreCase = true) &&
                normalizeServer(it.serverUrl) == normalizeServer(requireNotNull(selection.serverUrl)) &&
                it.groupId == selection.groupId
        }
    }

    companion object {
        private val SHA_256 = Regex("^[0-9a-fA-F]{64}$")

        fun fromEncryptionRecipients(
            sha256: String,
            fileName: String,
            selection: MapSafeSecurityPreferences.Selection,
            recipientFingerprints: Collection<String>,
            localFingerprint: String?,
            directoryRecords: Collection<CachedPublicKeyRecord>,
            recordedAt: Long = System.currentTimeMillis()
        ): CommunityPackageAudienceRecord? {
            if (!selection.hasGroup || selection.currentUserId == null) return null
            val normalizedServer = normalizeServer(requireNotNull(selection.serverUrl))
            val groupId = requireNotNull(selection.groupId)
            val local = localFingerprint?.let(::normalizeFingerprint)
            val accepted = directoryRecords
                .filter {
                    it.trustState == PublicKeyTrustState.ACCEPTED &&
                        normalizeServer(it.identity.serverUrl) == normalizedServer &&
                        it.identity.groupId == groupId
                }
                .associateBy { normalizeFingerprint(it.observedFingerprint) }
            val members = mutableListOf<CommunityAudienceMember>()
            val unmapped = linkedSetOf<String>()
            recipientFingerprints.map(::normalizeFingerprint).distinct().forEach { fingerprint ->
                when {
                    fingerprint == local -> members += CommunityAudienceMember(
                        userId = selection.currentUserId,
                        displayName = selection.accountName ?: "Current NextGIS user",
                        fingerprint = fingerprint
                    )
                    accepted[fingerprint] != null -> {
                        val record = requireNotNull(accepted[fingerprint])
                        members += CommunityAudienceMember(
                            userId = record.identity.userId,
                            displayName = record.displayName,
                            fingerprint = fingerprint
                        )
                    }
                    else -> unmapped += fingerprint
                }
            }
            return CommunityPackageAudienceRecord(
                sha256 = sha256.lowercase(Locale.ROOT),
                fileName = fileName,
                serverUrl = requireNotNull(selection.serverUrl),
                groupId = groupId,
                publisherUserId = selection.currentUserId,
                audience = CommunityArtifactAudience.selectedRecipients(members, unmapped),
                recordedAt = recordedAt
            )
        }

        private fun normalizeFingerprint(value: String): String = value
            .replace(Regex("[^0-9A-Fa-f]"), "")
            .uppercase(Locale.ROOT)

        private fun normalizeServer(value: String): String = value.trim().trimEnd('/').lowercase(Locale.ROOT)
    }

    private fun readRecords(): List<CommunityPackageAudienceRecord> {
        if (!recordsFile.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(recordsFile.readText(Charsets.UTF_8))
            val values = root.optJSONArray("records") ?: JSONArray()
            buildList {
                for (index in 0 until values.length()) {
                    add(recordFromJson(values.getJSONObject(index)))
                }
            }
        }.getOrElse { error ->
            throw NextGisCommunityPublishException("The local package-audience registry is damaged.", error)
        }
    }

    private fun writeRecords(records: List<CommunityPackageAudienceRecord>) {
        val bytes = JSONObject()
            .put("schemaVersion", 1)
            .put("records", JSONArray().apply {
                records.sortedWith(compareBy({ it.serverUrl }, { it.groupId }, { it.sha256 }))
                    .forEach { put(recordToJson(it)) }
            })
            .toString(2)
            .toByteArray(Charsets.UTF_8)
        recordsFile.parentFile?.mkdirs()
        val atomic = AtomicFile(recordsFile)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw NextGisCommunityPublishException("Could not save the package audience.", error)
        }
    }

    private fun recordToJson(record: CommunityPackageAudienceRecord): JSONObject = JSONObject()
        .put("sha256", record.sha256)
        .put("fileName", record.fileName)
        .put("serverUrl", record.serverUrl)
        .put("groupId", record.groupId)
        .put("publisherUserId", record.publisherUserId)
        .put("recordedAt", record.recordedAt)
        .put("mode", record.audience.mode.name)
        .put("unmappedFingerprints", JSONArray(record.audience.unmappedFingerprints.sorted()))
        .put("members", JSONArray().apply {
            record.audience.members.forEach { member ->
                put(JSONObject()
                    .put("userId", member.userId)
                    .put("displayName", member.displayName)
                    .put("fingerprint", member.fingerprint))
            }
        })

    private fun recordFromJson(json: JSONObject): CommunityPackageAudienceRecord {
        val memberJson = json.optJSONArray("members") ?: JSONArray()
        val members = buildList {
            for (index in 0 until memberJson.length()) {
                val value = memberJson.getJSONObject(index)
                add(CommunityAudienceMember(
                    userId = value.getLong("userId"),
                    displayName = value.getString("displayName"),
                    fingerprint = value.getString("fingerprint")
                ))
            }
        }
        val unmappedJson = json.optJSONArray("unmappedFingerprints") ?: JSONArray()
        val unmapped = buildSet {
            for (index in 0 until unmappedJson.length()) add(unmappedJson.getString(index))
        }
        return CommunityPackageAudienceRecord(
            sha256 = json.getString("sha256"),
            fileName = json.getString("fileName"),
            serverUrl = json.getString("serverUrl"),
            groupId = json.getLong("groupId"),
            publisherUserId = json.getLong("publisherUserId"),
            audience = CommunityArtifactAudience(
                mode = CommunityAudienceMode.valueOf(json.getString("mode")),
                members = members,
                unmappedFingerprints = unmapped
            ),
            recordedAt = json.getLong("recordedAt")
        )
    }
}
