package com.phishtopia.ja2fieldkit.core.format

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SaveBlockDecryptorTest {
    @Test
    fun decryptsProjectAuthoredVectorByteForByteAndCyclesAfterFortyNineBytes() {
        val rotationTable = SaveRotationTable.fromBytes(ByteArray(49) { (it + 1).toByte() })
        val ciphertext =
            bytesOf(
                0x08, 0x2e, 0x72, 0xd4, 0x54, 0xf2, 0xae, 0x88,
                0x80, 0x96, 0xca, 0x1c, 0x8c, 0x1a, 0xc6, 0x90,
                0x78, 0x7e, 0xa2, 0xe4, 0x44, 0xc2, 0x5e, 0x18,
                0xf0, 0xe6, 0xfa, 0x2c, 0x7c, 0xea, 0x76, 0x20,
                0xe8, 0xce, 0xd2, 0xf4, 0x34, 0x92, 0x0e, 0xa8,
                0x60, 0x36, 0x2a, 0x3c, 0x6c, 0xba, 0x26, 0xb0,
                0x58, 0xed, 0xa0, 0x71,
            )
        val expectedPlaintext = ByteArray(52) { ((it * 29 + 7) and 0xff).toByte() }

        assertContentEquals(
            expectedPlaintext,
            NormalSaveBlockDecryptor.decryptBlock(
                encryptedBlock = ciphertext,
                expectedBlockSize = 52,
                rotationTable = rotationTable,
            ),
        )
    }

    @Test
    fun resetsFeedbackAndRotationPositionForEveryBlockCall() {
        val rotationTable = SaveRotationTable.fromBytes(ByteArray(49) { 1 })

        val first =
            NormalSaveBlockDecryptor.decryptBlock(
                encryptedBlock = bytesOf(1, 3, 6, 6),
                expectedBlockSize = 4,
                rotationTable = rotationTable,
            )
        val second =
            NormalSaveBlockDecryptor.decryptBlock(
                encryptedBlock = bytesOf(10, 19),
                expectedBlockSize = 2,
                rotationTable = rotationTable,
            )

        assertContentEquals(bytesOf(0, 1, 2, 0xff), first)
        assertContentEquals(bytesOf(9, 8), second)
    }

    @Test
    fun handlesUnsignedByteWraparoundInBothSubtractions() {
        val rotationTable = SaveRotationTable.fromBytes(ByteArray(49) { 0xff.toByte() })

        assertContentEquals(
            bytesOf(1, 1),
            NormalSaveBlockDecryptor.decryptBlock(
                encryptedBlock = bytesOf(0, 0),
                expectedBlockSize = 2,
                rotationTable = rotationTable,
            ),
        )
    }

    @Test
    fun rejectsMalformedRotationTables() {
        listOf(0, 48, 50).forEach { size ->
            val error = assertFailsWith<InvalidRotationTableLengthException> {
                SaveRotationTable.fromBytes(ByteArray(size))
            }
            assertEquals(size, error.actualLength)
            assertEquals(49, error.requiredLength)
        }

        val lowError = assertFailsWith<InvalidRotationTableByteException> {
            SaveRotationTable.fromUnsignedBytes(List(49) { if (it == 7) -1 else 0 })
        }
        assertEquals(7, lowError.byteIndex)

        val highError = assertFailsWith<InvalidRotationTableByteException> {
            SaveRotationTable.fromUnsignedBytes(List(49) { if (it == 12) 256 else 0 })
        }
        assertEquals(12, highError.byteIndex)
    }

    @Test
    fun acceptsAnEmptyBlockAsAnExactSizeNoOp() {
        val rotationTable = SaveRotationTable.fromBytes(ByteArray(49))

        assertContentEquals(
            ByteArray(0),
            NormalSaveBlockDecryptor.decryptBlock(ByteArray(0), 0, rotationTable),
        )
    }

    @Test
    fun rejectsNegativeTruncatedOrOversizedBlockInputs() {
        val rotationTable = SaveRotationTable.fromBytes(ByteArray(49))

        val negative = assertFailsWith<InvalidExpectedBlockSizeException> {
            NormalSaveBlockDecryptor.decryptBlock(ByteArray(0), -1, rotationTable)
        }
        assertEquals(-1, negative.expectedSize)

        val truncated = assertFailsWith<InvalidEncryptedBlockSizeException> {
            NormalSaveBlockDecryptor.decryptBlock(ByteArray(715), 716, rotationTable)
        }
        assertEquals(715, truncated.actualSize)
        assertEquals(716, truncated.expectedSize)

        val oversized = assertFailsWith<InvalidEncryptedBlockSizeException> {
            NormalSaveBlockDecryptor.decryptBlock(ByteArray(717), 716, rotationTable)
        }
        assertEquals(717, oversized.actualSize)
    }

    @Test
    fun providerBoundaryFailsClosedWhenSelectedTableDataIsUnavailable() {
        val index = NormalRotationTableIndex(227)
        val missingProvider = RotationTableProvider { null }

        val error = assertFailsWith<MissingRotationTableException> {
            NormalSaveBlockDecryptor.decryptBlock(
                encryptedBlock = byteArrayOf(1),
                expectedBlockSize = 1,
                tableIndex = index,
                tableProvider = missingProvider,
            )
        }

        assertEquals(index, error.tableIndex)
    }

    @Test
    fun providerSuppliesOnlyTheSelectedValidatedTable() {
        val requested = mutableListOf<NormalRotationTableIndex>()
        val table = SaveRotationTable.fromBytes(ByteArray(49) { 1 })
        val provider = RotationTableProvider { index ->
            requested += index
            table
        }

        val plaintext =
            NormalSaveBlockDecryptor.decryptBlock(
                encryptedBlock = bytesOf(1, 3),
                expectedBlockSize = 2,
                tableIndex = NormalRotationTableIndex(42),
                tableProvider = provider,
            )

        assertEquals(listOf(NormalRotationTableIndex(42)), requested)
        assertContentEquals(bytesOf(0, 1), plaintext)
    }

    @Test
    fun rotationTableAndOutputDoNotRetainMutableInputs() {
        val sourceTable = ByteArray(49) { 1 }
        val table = SaveRotationTable.fromBytes(sourceTable)
        sourceTable[0] = 2

        val output = NormalSaveBlockDecryptor.decryptBlock(byteArrayOf(1), 1, table)
        assertContentEquals(byteArrayOf(0), output)

        val exported = table.toByteArray()
        exported[0] = 3
        assertContentEquals(byteArrayOf(0), NormalSaveBlockDecryptor.decryptBlock(byteArrayOf(1), 1, table))
    }

    private fun bytesOf(vararg values: Int): ByteArray =
        ByteArray(values.size) { index -> values[index].toByte() }
}
