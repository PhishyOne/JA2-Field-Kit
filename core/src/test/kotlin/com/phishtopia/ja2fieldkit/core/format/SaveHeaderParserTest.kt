package com.phishtopia.ja2fieldkit.core.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SaveHeaderParserTest {
    @Test
    fun parsesEveryMappedFieldAtItsDocumentedOffset() {
        val bytes = syntheticBuild041202Header()

        val header = SaveHeaderParser.parseBuild041202(bytes)

        assertEquals(103L, header.saveVersion)
        assertEquals("Build 04.12.02", header.gameVersion)
        assertEquals("Synthetic ✓", header.saveDescription)
        assertEquals(0x1020_3040L, header.day)
        assertEquals(0x12, header.hour)
        assertEquals(0x34, header.minute)
        assertEquals(SaveSector(x = 0x1234, y = -0x1234, z = -2), header.sector)
        assertEquals(0x5a, header.playerMercCount)
        assertEquals(-123_456_789, header.balance)
        assertEquals(0x89ab_cdefL, header.currentScreenId)
        assertEquals(EncodedBoolean(1), header.alternateSector)
        assertEquals(true, header.alternateSector.value)
        assertEquals(EncodedBoolean(0), header.worldLoaded)
        assertEquals(false, header.worldLoaded.value)
        assertEquals(0xbc, header.loadScreenId)
        assertEquals(
            InitialGameOptions(
                gunNut = EncodedBoolean(1),
                sciFi = EncodedBoolean(0),
                difficultyLevel = 2,
                turnTimeLimit = EncodedBoolean(1),
                saveMode = 2,
            ),
            header.initialGameOptions,
        )
        assertEquals(0xfedc_ba98L, header.perSaveRandom)
        assertEquals(0x7654_3210L, header.saveStateSize)

        assertEquals(listOf(276, 308, 315, 324), header.opaqueRanges.map { it.offset })
        assertEquals(listOf(280, 315, 316, 432), header.opaqueRanges.map { it.endExclusive })
        assertEquals(listOf(0xde, 0xad, 0xbe, 0xef), header.opaqueRanges[0].bytes)
        assertEquals((0xa0..0xa6).toList(), header.opaqueRanges[1].bytes)
        assertEquals(listOf(0x7f), header.opaqueRanges[2].bytes)
        assertEquals(
            (324 until 432).map { (it * 37 + 11) and 0xff },
            header.opaqueRanges[3].bytes,
        )
    }

    @Test
    fun parsingIsDeterministicAndDoesNotRetainMutableInput() {
        val bytes = syntheticBuild041202Header()
        val first = SaveHeaderParser.parseBuild041202(bytes)
        val second = SaveHeaderParser.parseBuild041202(bytes.copyOf())

        assertEquals(first, second)

        bytes[324] = 0
        assertEquals((324 * 37 + 11) and 0xff, first.opaqueRanges.last().bytes.first())
    }

    @Test
    fun rejectsEveryTruncatedHeaderLength() {
        for (size in 0 until 432) {
            val error = assertFailsWith<TruncatedSaveHeaderException> {
                SaveHeaderParser.parseBuild041202(ByteArray(size))
            }
            assertEquals(size, error.actualSize)
            assertEquals(432, error.requiredSize)
        }
    }

    @Test
    fun readsOnlyTheHeaderWhenBodyBytesFollow() {
        val headerBytes = syntheticBuild041202Header()
        val bytes = headerBytes + ByteArray(64) { 0xff.toByte() }

        assertEquals(
            SaveHeaderParser.parseBuild041202(headerBytes),
            SaveHeaderParser.parseBuild041202(bytes),
        )
    }

    @Test
    fun rejectsUnmappedSaveVersionOrBuildInsteadOfGuessing() {
        val wrongVersion = syntheticBuild041202Header().apply { putU32Le(0, 102) }
        val versionError = assertFailsWith<UnsupportedSaveHeaderException> {
            SaveHeaderParser.parseBuild041202(wrongVersion)
        }
        assertEquals(102L, versionError.saveVersion)

        val wrongBuild = syntheticBuild041202Header().apply {
            putSingleByteString(4, 16, "Build 99.99.99")
        }
        val buildError = assertFailsWith<UnsupportedSaveHeaderException> {
            SaveHeaderParser.parseBuild041202(wrongBuild)
        }
        assertEquals("Build 99.99.99", buildError.gameVersion)
    }

    @Test
    fun preservesNonBooleanEncodingsWithoutInventingAMeaning() {
        val bytes = syntheticBuild041202Header().apply {
            this[300] = 2
            this[303] = 0xff.toByte()
        }

        val header = SaveHeaderParser.parseBuild041202(bytes)

        assertEquals(2, header.alternateSector.rawValue)
        assertNull(header.alternateSector.value)
        assertEquals(0xff, header.initialGameOptions.gunNut.rawValue)
        assertNull(header.initialGameOptions.gunNut.value)
    }

    /**
     * Inline project-authored bytes under the fixture-policy exemption. Campaign
     * values are sentinels, not values copied from or claimed for a real save.
     * Literal offsets intentionally do not reuse production constants so drift
     * in the parser breaks this test.
     */
    private fun syntheticBuild041202Header(): ByteArray =
        ByteArray(432).apply {
            putU32Le(0, 103)
            putSingleByteString(4, 16, "Build 04.12.02")
            putUtf16LeString(20, 128, "Synthetic ✓")
            putBytes(276, 0xde, 0xad, 0xbe, 0xef)
            putU32Le(280, 0x1020_3040)
            this[284] = 0x12
            this[285] = 0x34
            putU16Le(286, 0x1234)
            putU16Le(288, -0x1234)
            this[290] = (-2).toByte()
            this[291] = 0x5a
            putU32Le(292, -123_456_789)
            putU32Le(296, 0x89ab_cdefL)
            this[300] = 1
            this[301] = 0
            this[302] = 0xbc.toByte()
            this[303] = 1
            this[304] = 0
            this[305] = 2
            this[306] = 1
            this[307] = 2
            for (offset in 308..314) this[offset] = (0xa0 + offset - 308).toByte()
            this[315] = 0x7f
            putU32Le(316, 0xfedc_ba98L)
            putU32Le(320, 0x7654_3210)
            for (offset in 324 until 432) this[offset] = ((offset * 37 + 11) and 0xff).toByte()
        }

    private fun ByteArray.putSingleByteString(offset: Int, length: Int, value: String) {
        require(value.length < length)
        value.forEachIndexed { index, char -> this[offset + index] = char.code.toByte() }
        this[offset + value.length] = 0
    }

    private fun ByteArray.putUtf16LeString(offset: Int, codeUnits: Int, value: String) {
        require(value.length < codeUnits)
        value.forEachIndexed { index, char -> putU16Le(offset + index * 2, char.code) }
        putU16Le(offset + value.length * 2, 0)
    }

    private fun ByteArray.putU16Le(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.putU32Le(offset: Int, value: Int) = putU32Le(offset, value.toLong())

    private fun ByteArray.putU32Le(offset: Int, value: Long) {
        for (index in 0 until 4) this[offset + index] = (value ushr (index * 8)).toByte()
    }

    private fun ByteArray.putBytes(offset: Int, vararg values: Int) {
        values.forEachIndexed { index, value -> this[offset + index] = value.toByte() }
    }
}
