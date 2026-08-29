package com.nextgis.mobile.mapsafe.blockchain

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

internal sealed class MapSafeMintCallValidation {
    data class Valid(val recordValue: String) : MapSafeMintCallValidation()
    data class Invalid(val message: String) : MapSafeMintCallValidation()
}

/** Strict ABI codec for the Location NFT v1 `mintNFT(string)` call. */
internal object MapSafeMintCallCodec {
    private const val SELECTOR_BYTES = 4
    private const val WORD_BYTES = 32
    private const val STRING_OFFSET = WORD_BYTES
    private const val STRING_DATA_START = SELECTOR_BYTES + (2 * WORD_BYTES)
    private const val MAX_RECORD_BYTES = 512
    private val selectorPattern = Regex("^0x[0-9a-fA-F]{8}$")
    private val dataPattern = Regex("^0x(?:[0-9a-fA-F]{2})*$")

    fun encode(
        recordValue: String,
        contractInterface: MapSafeContractInterface = MapSafeContractInterface.LOCATION_NFT_V1
    ): String {
        val selector = selectorBytes(contractInterface)
        val recordBytes = recordValue.toByteArray(Charsets.UTF_8)
        require(recordBytes.size <= MAX_RECORD_BYTES) {
            "The MapSafe integrity record is too large."
        }
        val paddedLength = paddedWordLength(recordBytes.size)
        val encoded = ByteArray(STRING_DATA_START + paddedLength)
        selector.copyInto(encoded, 0)
        writeBoundedWord(encoded, SELECTOR_BYTES, STRING_OFFSET)
        writeBoundedWord(encoded, SELECTOR_BYTES + STRING_OFFSET, recordBytes.size)
        recordBytes.copyInto(encoded, STRING_DATA_START)
        return "0x" + encoded.joinToString("") { byte ->
            "%02x".format(Locale.US, byte.toInt() and 0xff)
        }
    }

    fun decode(
        calldata: String,
        contractInterface: MapSafeContractInterface = MapSafeContractInterface.LOCATION_NFT_V1
    ): MapSafeMintCallValidation {
        if (!dataPattern.matches(calldata)) {
            return invalid("The transaction input is not valid Ethereum hex data.")
        }
        val bytes = decodeHex(calldata.substring(2))
        if (bytes.size < STRING_DATA_START) {
            return invalid("The transaction input is too short for mintNFT(string).")
        }
        if (bytes.size > STRING_DATA_START + paddedWordLength(MAX_RECORD_BYTES)) {
            return invalid("The transaction input is unexpectedly large.")
        }

        val expectedSelector = try {
            selectorBytes(contractInterface)
        } catch (_: IllegalArgumentException) {
            return invalid("The configured mint function selector is invalid.")
        }
        if (!bytes.copyOfRange(0, SELECTOR_BYTES).contentEquals(expectedSelector)) {
            return invalid(
                "The transaction does not call ${contractInterface.mintFunctionSignature}."
            )
        }

        val offset = readBoundedWord(bytes, SELECTOR_BYTES)
            ?: return invalid("The mintNFT string offset is invalid.")
        if (offset != STRING_OFFSET) {
            return invalid("The mintNFT string argument is not canonically ABI encoded.")
        }
        val recordLength = readBoundedWord(bytes, SELECTOR_BYTES + offset)
            ?: return invalid("The mintNFT string length is invalid.")
        if (recordLength > MAX_RECORD_BYTES) {
            return invalid("The MapSafe integrity record is too large.")
        }
        val paddedLength = paddedWordLength(recordLength)
        val expectedSize = STRING_DATA_START + paddedLength
        if (bytes.size != expectedSize) {
            return invalid("The mintNFT call contains missing or trailing ABI data.")
        }
        val recordEnd = STRING_DATA_START + recordLength
        if (bytes.copyOfRange(recordEnd, expectedSize).any { it != 0.toByte() }) {
            return invalid("The mintNFT string has non-zero ABI padding.")
        }

        val recordValue = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, STRING_DATA_START, recordLength))
                .toString()
        } catch (_: Exception) {
            return invalid("The mintNFT string is not valid UTF-8.")
        }
        return MapSafeMintCallValidation.Valid(recordValue)
    }

    private fun selectorBytes(contractInterface: MapSafeContractInterface): ByteArray {
        val selector = contractInterface.mintFunctionSelector
        require(selectorPattern.matches(selector)) {
            "The configured mint function selector is invalid."
        }
        return decodeHex(selector.substring(2))
    }

    private fun paddedWordLength(length: Int): Int =
        if (length == 0) 0 else ((length + WORD_BYTES - 1) / WORD_BYTES) * WORD_BYTES

    private fun writeBoundedWord(target: ByteArray, start: Int, value: Int) {
        for (index in 0 until Int.SIZE_BYTES) {
            target[start + WORD_BYTES - 1 - index] = (value ushr (index * 8)).toByte()
        }
    }

    private fun readBoundedWord(source: ByteArray, start: Int): Int? {
        if (start < 0 || start + WORD_BYTES > source.size) return null
        if (source.copyOfRange(start, start + WORD_BYTES - Int.SIZE_BYTES).any { it != 0.toByte() }) {
            return null
        }
        var value = 0L
        for (index in start + WORD_BYTES - Int.SIZE_BYTES until start + WORD_BYTES) {
            value = (value shl 8) or (source[index].toLong() and 0xff)
        }
        return value.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    private fun decodeHex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun invalid(message: String): MapSafeMintCallValidation.Invalid =
        MapSafeMintCallValidation.Invalid(message)
}
