package com.nextgis.mobile.mapsafe.blockchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapSafeMintCallCodecTest {
    private val hash = "ab".repeat(32)
    private val record = MapSafeIntegrityRecordCodec.encodeSha256(hash)

    @Test
    fun encodesAndStrictlyDecodesCanonicalRecord() {
        val encoded = MapSafeMintCallCodec.encode(record)
        val decoded = MapSafeMintCallCodec.decode(encoded) as MapSafeMintCallValidation.Valid

        assertTrue(encoded.startsWith("0xfb37e883"))
        assertEquals(record, decoded.recordValue)
    }

    @Test
    fun decodesLegacyQgisRecordArgument() {
        val legacy = "field_sites.zip_$hash"

        val decoded = MapSafeMintCallCodec.decode(MapSafeMintCallCodec.encode(legacy))
            as MapSafeMintCallValidation.Valid

        assertEquals(legacy, decoded.recordValue)
    }

    @Test
    fun rejectsDifferentFunctionSelector() {
        val encoded = MapSafeMintCallCodec.encode(record)
            .replaceRange(2, 10, "deadbeef")

        val result = MapSafeMintCallCodec.decode(encoded)

        assertTrue(result is MapSafeMintCallValidation.Invalid)
        assertTrue((result as MapSafeMintCallValidation.Invalid).message.contains("does not call"))
    }

    @Test
    fun rejectsNonCanonicalOffsetAndTrailingData() {
        val encoded = MapSafeMintCallCodec.encode(record)
        val wrongOffset = replaceWord(encoded, 0, 64)

        assertTrue(MapSafeMintCallCodec.decode(wrongOffset) is MapSafeMintCallValidation.Invalid)
        assertTrue(MapSafeMintCallCodec.decode(encoded + "00") is MapSafeMintCallValidation.Invalid)
    }

    @Test
    fun rejectsNonZeroPaddingAndMalformedUtf8() {
        val encoded = MapSafeMintCallCodec.encode(record)
        val nonZeroPadding = encoded.dropLast(2) + "01"
        val oneCharacter = MapSafeMintCallCodec.encode("x")
        val dataStart = 2 + 8 + 64 + 64
        val malformedUtf8 = oneCharacter.replaceRange(dataStart, dataStart + 2, "ff")

        assertTrue(MapSafeMintCallCodec.decode(nonZeroPadding) is MapSafeMintCallValidation.Invalid)
        assertTrue(MapSafeMintCallCodec.decode(malformedUtf8) is MapSafeMintCallValidation.Invalid)
    }

    private fun replaceWord(calldata: String, wordIndex: Int, value: Int): String {
        val wordStart = 2 + 8 + (wordIndex * 64)
        val replacement = value.toString(16).padStart(64, '0')
        return calldata.replaceRange(wordStart, wordStart + 64, replacement)
    }
}
