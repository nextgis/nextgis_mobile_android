package com.nextgis.mobile.mapsafe.keys

import android.content.Context
import android.util.AtomicFile
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpException
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyCodec
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

/** App-private cache for discovered keys and their explicit trust decisions. */
class PublicKeyExchangeRepository(context: Context) {
    private val root = File(
        context.applicationContext.noBackupFilesDir,
        "mapsafe/openpgp/key-directory"
    )
    private val candidateDirectory = File(root, "candidates")
    private val recordsFile = File(root, "records.json")

    init {
        candidateDirectory.mkdirs()
    }

    @Synchronized
    fun records(): List<CachedPublicKeyRecord> = readRecords()

    @Synchronized
    fun records(serverUrl: String, groupId: Long): List<CachedPublicKeyRecord> {
        val normalized = normalizeServerUrl(serverUrl)
        return readRecords().filter {
            normalizeServerUrl(it.identity.serverUrl) == normalized && it.identity.groupId == groupId
        }
    }

    @Synchronized
    fun observe(observation: PublicKeyObservation, publicKey: ByteArray, observedAt: Long): CachedPublicKeyRecord {
        val keyRing = OpenPgpKeyCodec.decodePublicKeyRing(publicKey)
        val actualFingerprint = OpenPgpKeyCodec.fingerprint(keyRing.publicKey.fingerprint)
        if (actualFingerprint != observation.fingerprint) {
            throw OpenPgpException("The downloaded public key does not match its published fingerprint.")
        }
        if (observation.directoryStatus.equals("active", ignoreCase = true) &&
            OpenPgpKeyCodec.findEncryptionKey(keyRing) == null
        ) {
            throw OpenPgpException("The downloaded public key has no usable encryption key.")
        }

        val all = readRecords().toMutableList()
        val recordId = recordId(observation.identity)
        val index = all.indexOfFirst { it.recordId == recordId }
        val previous = all.getOrNull(index)
        val updated = PublicKeyTrustEvaluator.observe(recordId, previous, observation, observedAt)
        writeCandidate(updated.recordId, updated.observedFingerprint, publicKey)
        if (index >= 0) all[index] = updated else all += updated
        writeRecords(all)
        return updated
    }

    @Synchronized
    fun markDirectoryPresence(
        serverUrl: String,
        groupId: Long,
        activeMemberIds: Set<Long>,
        publishedMemberIds: Set<Long>,
        observedAt: Long
    ) {
        val normalized = normalizeServerUrl(serverUrl)
        val all = readRecords().toMutableList()
        var changed = false
        all.indices.forEach { index ->
            val record = all[index]
            if (normalizeServerUrl(record.identity.serverUrl) != normalized || record.identity.groupId != groupId) {
                return@forEach
            }
            val updated = when {
                record.identity.userId !in activeMemberIds ->
                    PublicKeyTrustEvaluator.memberRemoved(record, observedAt)
                record.identity.userId !in publishedMemberIds ->
                    PublicKeyTrustEvaluator.directoryEntryMissing(record, observedAt)
                else -> record
            }
            if (updated != record) {
                all[index] = updated
                changed = true
            }
        }
        if (changed) writeRecords(all)
    }

    @Synchronized
    fun accept(recordId: String, keyRepository: OpenPgpKeyRepository, acceptedAt: Long): CachedPublicKeyRecord {
        val all = readRecords().toMutableList()
        val index = all.indexOfFirst { it.recordId == recordId }
        if (index < 0) throw OpenPgpException("The selected directory key is no longer cached.")
        val current = all[index]
        if (!current.needsUserReview) {
            throw OpenPgpException("This key is not awaiting fingerprint confirmation.")
        }
        val bytes = candidateFile(current.recordId, current.observedFingerprint)
            .takeIf(File::isFile)
            ?.readBytes()
            ?: throw OpenPgpException("The downloaded key material is no longer available. Synchronize again.")
        val info = keyRepository.importPublicKeys(ByteArrayInputStream(bytes)).singleOrNull()
            ?: throw OpenPgpException("Expected one cached OpenPGP public key.")
        if (info.fingerprint != current.observedFingerprint) {
            throw OpenPgpException("The cached key changed before it could be accepted.")
        }
        val accepted = PublicKeyTrustEvaluator.accept(current, acceptedAt)
        all[index] = accepted
        writeRecords(all)
        return accepted
    }

    @Synchronized
    fun nonSelectableFingerprints(): Set<String> {
        return readRecords().flatMapTo(mutableSetOf()) { it.nonSelectableFingerprints() }
    }

    @Synchronized
    fun trustStateForFingerprint(fingerprint: String): PublicKeyTrustState? {
        return readRecords().firstNotNullOfOrNull { record ->
            when (fingerprint) {
                record.observedFingerprint,
                record.acceptedFingerprint -> record.trustState
                in record.previousFingerprints -> PublicKeyTrustState.SUPERSEDED
                else -> null
            }
        }
    }

    private fun readRecords(): List<CachedPublicKeyRecord> {
        if (!recordsFile.isFile) return emptyList()
        return runCatching {
            val rootJson = JSONObject(recordsFile.readText(Charsets.UTF_8))
            val array = rootJson.optJSONArray("records") ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    add(recordFromJson(array.getJSONObject(index)))
                }
            }
        }.getOrElse { error ->
            throw OpenPgpException("The cached public-key directory is damaged.", error)
        }
    }

    private fun writeRecords(records: List<CachedPublicKeyRecord>) {
        val array = JSONArray()
        records.sortedBy { it.recordId }.forEach { array.put(recordToJson(it)) }
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put("records", array)
            .toString(2)
            .toByteArray(Charsets.UTF_8)
        writeAtomic(recordsFile, json)
    }

    private fun recordToJson(record: CachedPublicKeyRecord): JSONObject {
        return JSONObject()
            .put("recordId", record.recordId)
            .put("serverUrl", record.identity.serverUrl)
            .put("accountName", record.identity.accountName)
            .put("groupId", record.identity.groupId)
            .put("userId", record.identity.userId)
            .put("displayName", record.displayName)
            .put("observedFingerprint", record.observedFingerprint)
            .put("acceptedFingerprint", record.acceptedFingerprint ?: JSONObject.NULL)
            .put("previousFingerprints", JSONArray(record.previousFingerprints.sorted()))
            .put("keyVersion", record.keyVersion)
            .put("bucketId", record.bucketId)
            .put("publishedAt", record.publishedAt)
            .put("lastSeenAt", record.lastSeenAt)
            .put("trustState", record.trustState.name)
    }

    private fun recordFromJson(json: JSONObject): CachedPublicKeyRecord {
        val previousJson = json.optJSONArray("previousFingerprints") ?: JSONArray()
        val previous = buildSet {
            for (index in 0 until previousJson.length()) add(previousJson.getString(index))
        }
        return CachedPublicKeyRecord(
            recordId = json.getString("recordId"),
            identity = PublicKeyDirectoryIdentity(
                serverUrl = json.getString("serverUrl"),
                accountName = json.getString("accountName"),
                groupId = json.getLong("groupId"),
                userId = json.getLong("userId")
            ),
            displayName = json.getString("displayName"),
            observedFingerprint = json.getString("observedFingerprint"),
            acceptedFingerprint = json.optString("acceptedFingerprint")
                .takeIf { !json.isNull("acceptedFingerprint") && it.isNotBlank() },
            previousFingerprints = previous,
            keyVersion = json.getInt("keyVersion"),
            bucketId = json.getLong("bucketId"),
            publishedAt = json.optString("publishedAt"),
            lastSeenAt = json.getLong("lastSeenAt"),
            trustState = PublicKeyTrustState.valueOf(json.getString("trustState"))
        )
    }

    private fun writeCandidate(recordId: String, fingerprint: String, bytes: ByteArray) {
        writeAtomic(candidateFile(recordId, fingerprint), bytes)
    }

    private fun candidateFile(recordId: String, fingerprint: String): File {
        return File(candidateDirectory, "$recordId-$fingerprint.asc")
    }

    private fun recordId(identity: PublicKeyDirectoryIdentity): String {
        val source = buildString {
            append(normalizeServerUrl(identity.serverUrl)).append('\n')
            append(identity.groupId).append('\n').append(identity.userId)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(24)
    }

    private fun normalizeServerUrl(url: String): String = url.trim().trimEnd('/').lowercase()

    private fun writeAtomic(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw OpenPgpException("Could not save the public-key trust cache.", error)
        }
    }
}
