package com.phishtopia.ja2fieldkit.core.format

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NormalProfileWritePrimitivesTest {
    @Test
    fun independentKnownVectorCrossesRotationBoundary() {
        // The existing project-authored decryptor vector, not output from the new encryptor.
        val expected = listOf(
            0x08, 0x2e, 0x72, 0xd4, 0x54, 0xf2, 0xae, 0x88,
            0x80, 0x96, 0xca, 0x1c, 0x8c, 0x1a, 0xc6, 0x90,
            0x78, 0x7e, 0xa2, 0xe4, 0x44, 0xc2, 0x5e, 0x18,
            0xf0, 0xe6, 0xfa, 0x2c, 0x7c, 0xea, 0x76, 0x20,
            0xe8, 0xce, 0xd2, 0xf4, 0x34, 0x92, 0x0e, 0xa8,
            0x60, 0x36, 0x2a, 0x3c, 0x6c, 0xba, 0x26, 0xb0,
            0x58, 0xed, 0xa0, 0x71,
        ).map(Int::toByte).toByteArray()
        val plain = ByteArray(52) { (it * 29 + 7).toByte() }
        val original = plain.copyOf()
        val key = SaveRotationTable.fromBytes(ByteArray(49) { (it + 1).toByte() })
        repeat(2) {
            assertContentEquals(expected, NormalSaveBlockEncryptor.encryptBlock(plain, 52, key))
            NormalSaveBlockEncryptor.encryptBlock(ByteArray(716) { 127 }, 716, key)
        }
        assertContentEquals(original, plain)
    }

    @Test
    fun inversePropertiesAndResetAcrossFullRecordsAndByteBoundaries() {
        for (seed in listOf(0, 1, 127, 128, 255)) {
            val key = SaveRotationTable.fromBytes(ByteArray(49) { (seed + it * 73).toByte() })
            for (size in listOf(0, 1, 48, 49, 50, 716)) {
                val plain = ByteArray(size) { (seed + it * 17).toByte() }
                val encrypted = NormalSaveBlockEncryptor.encryptBlock(plain, size, key)
                assertContentEquals(plain, NormalSaveBlockDecryptor.decryptBlock(encrypted, size, key))
                assertContentEquals(encrypted, NormalSaveBlockEncryptor.encryptBlock(plain, size, key))
                val arbitraryCipher = plain
                assertContentEquals(arbitraryCipher, NormalSaveBlockEncryptor.encryptBlock(
                    NormalSaveBlockDecryptor.decryptBlock(arbitraryCipher, size, key), size, key,
                ))
            }
        }
    }

    @Test
    fun encryptorRejectsSizesWithStructuredDiagnostics() {
        val key = SaveRotationTable.fromBytes(ByteArray(49))
        assertEquals(-1, assertFailsWith<InvalidExpectedPlaintextBlockSizeException> {
            NormalSaveBlockEncryptor.encryptBlock(ByteArray(0), -1, key)
        }.expectedSize)
        for (size in listOf(0, 715, 717)) {
            val error = assertFailsWith<InvalidPlaintextBlockSizeException> {
                NormalSaveBlockEncryptor.encryptBlock(ByteArray(size), 716, key)
            }
            assertEquals(size, error.actualSize)
            assertEquals(716, error.expectedSize)
        }
    }

    @Test
    fun checksumIndependentHandVectorsIncludeSignedAndUnsignedWrap() {
        val record = ByteArray(716)
        assertEquals(6L, NormalProfileChecksum.calculate(record))
        // Five applications of (s+128)*128, reduced mod 2^32; inventory initially zero.
        val stats = listOf(334, 297, 405, 335, 296, 353, 261, 411, 339, 352)
        stats.forEach { record[it] = 127 }
        assertEquals(270_548_992L, NormalProfileChecksum.calculate(record))
        record.fill(0xff.toByte(), 416, 454)
        record.fill(0xff.toByte(), 377, 396)
        assertEquals(271_799_002L, NormalProfileChecksum.calculate(record))
        record.fill(0)
        // Five applications of (s-127)*(-127), reduced mod 2^32.
        stats.forEach { record[it] = -128 }
        assertEquals(2_590_716_806L, NormalProfileChecksum.calculate(record))
        val original = record.copyOf()
        record.fill(0x55, 696, 700) // Stored checksum is not an input.
        assertEquals(2_590_716_806L, NormalProfileChecksum.calculate(record))
        record.fill(0, 696, 700)
        assertContentEquals(original, record)
        for (size in listOf(0, 715, 717)) {
            val error = assertFailsWith<InvalidProfileChecksumRecordSizeException> {
                NormalProfileChecksum.calculate(ByteArray(size))
            }
            assertEquals(size, error.actualSize)
            assertEquals(716, error.requiredSize)
        }
    }

    @Test
    fun encryptionAndChecksumMatchAllIndependentManifestedPythonRecords() {
        val vector = checkNotNull(javaClass.getResourceAsStream(
            "/fixtures/synthetic-build-04.12.02-profile-recovery-v1.bin",
        )).use { it.readBytes() }
        val key = SaveRotationTable.fromBytes(vector.copyOfRange(0, 49))
        repeat(170) { id ->
            val plain = vector.copyOfRange(49 + id * 716, 49 + (id + 1) * 716)
            val cipher = vector.copyOfRange(121769 + id * 716, 121769 + (id + 1) * 716)
            var expected = 0L
            repeat(4) { expected += (plain[696 + it].toLong() and 255) shl (8 * it) }
            assertEquals(expected, NormalProfileChecksum.calculate(plain))
            assertContentEquals(cipher, NormalSaveBlockEncryptor.encryptBlock(plain, 716, key))
        }
    }
}
