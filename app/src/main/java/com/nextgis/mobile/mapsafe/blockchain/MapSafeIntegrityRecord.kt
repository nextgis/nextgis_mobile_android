package com.nextgis.mobile.mapsafe.blockchain

import java.util.Locale

enum class MapSafeIntegrityRecordFormat {
    MAPSAFE_V1,
    LEGACY_QGIS_FILENAME_HASH
}

data class MapSafeIntegrityRecord(
    val sha256: String,
    val format: MapSafeIntegrityRecordFormat,
    val legacyFileName: String? = null
)

sealed class MapSafeIntegrityRecordValidation {
    data class Valid(val record: MapSafeIntegrityRecord) : MapSafeIntegrityRecordValidation()
    data class Invalid(val message: String) : MapSafeIntegrityRecordValidation()
}

/**
 * Canonical on-chain payload for new MapSafe records.
 *
 * File names are deliberately excluded because blockchain data is public and
 * permanent. The legacy QGIS filename_hash form remains read-only compatible.
 */
object MapSafeIntegrityRecordCodec {
    const val CANONICAL_PREFIX = "mapsafe:v1:sha256:"
    private const val MAX_LEGACY_FILENAME_LENGTH = 255
    private val sha256Pattern = Regex("^[0-9a-fA-F]{64}$")

    fun encodeSha256(sha256: String): String {
        require(sha256Pattern.matches(sha256)) {
            "SHA-256 must contain exactly 64 hexadecimal characters."
        }
        return CANONICAL_PREFIX + sha256.lowercase(Locale.US)
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
        if (fileName.length > MAX_LEGACY_FILENAME_LENGTH ||
            fileName.any(Char::isISOControl) ||
            !sha256Pattern.matches(hash)
        ) {
            return MapSafeIntegrityRecordValidation.Invalid(
                "The on-chain value is not a valid legacy MapSafe record."
            )
        }
        return MapSafeIntegrityRecordValidation.Valid(
            MapSafeIntegrityRecord(
                sha256 = hash.lowercase(Locale.US),
                format = MapSafeIntegrityRecordFormat.LEGACY_QGIS_FILENAME_HASH,
                legacyFileName = fileName
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
