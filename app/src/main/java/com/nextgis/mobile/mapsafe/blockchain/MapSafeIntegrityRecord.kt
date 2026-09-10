package com.nextgis.mobile.mapsafe.blockchain

import java.util.Locale

enum class MapSafeIntegrityRecordFormat {
    MAPSAFE_V1,
    FILENAME_HASH
}

data class MapSafeIntegrityRecord(
    val sha256: String,
    val format: MapSafeIntegrityRecordFormat,
    val fileName: String? = null
)

sealed class MapSafeIntegrityRecordValidation {
    data class Valid(val record: MapSafeIntegrityRecord) : MapSafeIntegrityRecordValidation()
    data class Invalid(val message: String) : MapSafeIntegrityRecordValidation()
}

/**
 * Encodes and parses MapSafe integrity records.
 *
 * New records bind a safe package basename to its SHA-256 as `<filename>_<hash>`.
 * The earlier hash-only MapSafe v1 form remains read-only compatible.
 */
object MapSafeIntegrityRecordCodec {
    const val CANONICAL_PREFIX = "mapsafe:v1:sha256:"
    const val MAX_MINTED_FILENAME_LENGTH = 120
    private const val MAX_PARSED_FILENAME_LENGTH = 255
    private val sha256Pattern = Regex("^[0-9a-fA-F]{64}$")
    private val unsafeMintedFileNameCharacters = Regex("[^A-Za-z0-9._ -]+")

    fun encodeSha256(sha256: String): String {
        require(sha256Pattern.matches(sha256)) {
            "SHA-256 must contain exactly 64 hexadecimal characters."
        }
        return CANONICAL_PREFIX + sha256.lowercase(Locale.US)
    }

    fun encodeFileHash(fileName: String, sha256: String): String {
        require(sha256Pattern.matches(sha256)) {
            "SHA-256 must contain exactly 64 hexadecimal characters."
        }
        return "${normalizeFileName(fileName)}_${sha256.lowercase(Locale.US)}"
    }

    fun normalizeFileName(value: String): String {
        val baseName = value.substringAfterLast('/').substringAfterLast('\\')
        val normalized = baseName
            .replace(unsafeMintedFileNameCharacters, "_")
            .trim('.', ' ')
            .take(MAX_MINTED_FILENAME_LENGTH)
            .trimEnd('.', ' ')
        require(normalized.isNotBlank()) { "The encrypted package filename is invalid." }
        return normalized
    }

    fun parse(value: String): MapSafeIntegrityRecordValidation {
        if (value.startsWith(CANONICAL_PREFIX)) {
            val hash = value.removePrefix(CANONICAL_PREFIX)
            if (!sha256Pattern.matches(hash)) {
                return MapSafeIntegrityRecordValidation.Invalid(
                    "The MapSafe v1 record contains an invalid SHA-256 hash."
                )
            }
            return MapSafeIntegrityRecordValidation.Valid(
                MapSafeIntegrityRecord(
                    sha256 = hash.lowercase(Locale.US),
                    format = MapSafeIntegrityRecordFormat.MAPSAFE_V1
                )
            )
        }

        val separator = value.lastIndexOf('_')
        if (separator <= 0 || separator == value.lastIndex) {
            return MapSafeIntegrityRecordValidation.Invalid(
                "The on-chain value is not a recognised MapSafe integrity record."
            )
        }
        val fileName = value.substring(0, separator)
        val hash = value.substring(separator + 1)
        if (fileName.length > MAX_PARSED_FILENAME_LENGTH ||
            fileName.any(Char::isISOControl) ||
            fileName.contains('/') ||
            fileName.contains('\\') ||
            !sha256Pattern.matches(hash)
        ) {
            return MapSafeIntegrityRecordValidation.Invalid(
                "The on-chain value is not a valid filename-bound MapSafe record."
            )
        }
        return MapSafeIntegrityRecordValidation.Valid(
            MapSafeIntegrityRecord(
                sha256 = hash.lowercase(Locale.US),
                format = MapSafeIntegrityRecordFormat.FILENAME_HASH,
                fileName = fileName
            )
        )
    }
}

internal object MapSafeContractAbiValidator {
    fun missingRuntimeSelectors(
        contractInterface: MapSafeContractInterface,
        bytecode: String
    ): List<String> {
        EthereumHex.codeByteCount(bytecode)
        val normalizedCode = bytecode.removePrefix("0x").lowercase(Locale.US)
        return contractInterface.requiredRuntimeSelectors.filterNot { selector ->
            normalizedCode.contains(selector.removePrefix("0x").lowercase(Locale.US))
        }
    }
}
