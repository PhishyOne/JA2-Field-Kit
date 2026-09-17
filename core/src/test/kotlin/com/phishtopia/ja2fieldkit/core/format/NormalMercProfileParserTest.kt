package com.phishtopia.ja2fieldkit.core.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** All bytes are test-authored format sentinels; offsets deliberately do not use production fields. */
class NormalMercProfileParserTest {
    @Test
    fun parsesExactly170RecordsAtIndependentlyAnchoredOffsetsAndEncodings() {
        val plaintext = ByteArray(121_720)
        repeat(170) { recordIndex ->
            val start = recordIndex * 716
            plaintext.putUtf16Le(start, 30, "Synthetic Profile $recordIndex")
            plaintext.putUtf16Le(start + 60, 10, "P$recordIndex\uD83D\uDE80")
            plaintext[start + 261] = (11 + recordIndex).toByte()
            plaintext[start + 296] = (12 + recordIndex).toByte()
            plaintext[start + 297] = (13 + recordIndex).toByte()
            plaintext[start + 334] = (14 + recordIndex).toByte()
            plaintext[start + 335] = (15 + recordIndex).toByte()
            plaintext[start + 339] = (16 + recordIndex).toByte()
            plaintext[start + 341] = (17 + recordIndex).toByte()
            plaintext[start + 352] = (18 + recordIndex).toByte()
            plaintext[start + 353] = (19 + recordIndex).toByte()
            plaintext[start + 355] = (20 + recordIndex).toByte()
            plaintext[start + 405] = (21 + recordIndex).toByte()
            plaintext[start + 411] = (22 + recordIndex).toByte()
        }

        val profiles = NormalMercProfileParser.parseBuild041202(plaintext)

        assertEquals(170, profiles.size)
        assertEquals((0 until 170).toList(), profiles.map { it.profileId })
        assertEquals("Synthetic Profile 0", profiles.first().name)
        assertEquals("P0\uD83D\uDE80", profiles.first().nickname)
        with(profiles.first()) {
            assertEquals(14, life)
            assertEquals(13, lifeMax)
            assertEquals(21, agility)
            assertEquals(15, dexterity)
            assertEquals(12, strength)
            assertEquals(17, leadership)
            assertEquals(20, wisdom)
            assertEquals(19, marksmanship)
            assertEquals(16, explosives)
            assertEquals(22, mechanical)
            assertEquals(11, medical)
            assertEquals(18, experienceLevel)
        }
        assertEquals("Synthetic Profile 169", profiles.last().name)
        // Serialized fields are signed bytes and must not be silently coerced or clamped.
        assertEquals((11 + 169).toByte().toInt(), profiles.last().medical)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (profiles as MutableList).clear()
        }
    }

    @Test
    fun rejectsEveryNonExactTableSizeWithoutPartialIteration() {
        for (size in listOf(0, 715, 716, 121_719, 121_721)) {
            val error = assertFailsWith<MercProfileParseException> {
                NormalMercProfileParser.parseBuild041202(ByteArray(size))
            }
            assertEquals(MercProfileParseFailure.INVALID_BLOCK_SIZE, error.reason)
            assertEquals(size, error.actualSize)
            assertEquals(121_720, error.requiredSize)
            assertEquals(null, error.recordIndex)
        }
    }

    @Test
    fun rejectsMalformedUtf16WithOnlyStructuralDiagnostics() {
        val plaintext = ByteArray(121_720)
        val record = 42
        val absoluteOffset = record * 716 + 60
        plaintext[absoluteOffset] = 0x00
        plaintext[absoluteOffset + 1] = 0xdc.toByte()

        val error = assertFailsWith<MercProfileParseException> {
            NormalMercProfileParser.parseBuild041202(plaintext)
        }

        assertEquals(MercProfileParseFailure.INVALID_UTF16, error.reason)
        assertEquals(record, error.recordIndex)
        assertEquals(MercProfileTextField.NICKNAME, error.field)
        assertEquals(60, error.byteOffset)
        assertEquals(null, error.actualSize)
        assertEquals(
            "Normal Build 04.12.02 merc-profile parse: INVALID_UTF16 " +
                "(size=null, required=121720, record=42, field=NICKNAME, offset=60)",
            error.message,
        )
    }

    private fun ByteArray.putUtf16Le(offset: Int, codeUnits: Int, value: String) {
        val encoded = value.toCharArray()
        require(encoded.size < codeUnits)
        repeat(codeUnits * 2) { this[offset + it] = 0 }
        encoded.forEachIndexed { index, character ->
            this[offset + index * 2] = character.code.toByte()
            this[offset + index * 2 + 1] = (character.code ushr 8).toByte()
        }
    }
}
