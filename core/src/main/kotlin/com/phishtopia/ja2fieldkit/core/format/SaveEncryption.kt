package com.phishtopia.ja2fieldkit.core.format

sealed class SaveEncryptionException(message: String) : IllegalArgumentException(message)

class InvalidEncryptionHeaderInputException(
    val fieldName: String,
    val rawValue: Any,
    detail: String,
) : SaveEncryptionException("Invalid encryption selector input '$fieldName' ($rawValue): $detail")

class InvalidRotationTableIndexException(
    val actualIndex: Int,
) : SaveEncryptionException(
        "Rotation-table index must be in 0 until ${NormalSaveEncryptionSelector.ROTATION_TABLE_COUNT}; " +
            "got $actualIndex",
    )

class InvalidRotationTableLengthException(
    val actualLength: Int,
    val requiredLength: Int = SaveRotationTable.REQUIRED_LENGTH,
) : SaveEncryptionException(
        "A save rotation table must contain exactly $requiredLength bytes; got $actualLength",
    )

class InvalidRotationTableByteException(
    val byteIndex: Int,
    val rawValue: Int,
) : SaveEncryptionException(
        "Rotation-table value at index $byteIndex must be in 0..255; got $rawValue",
    )

class InvalidExpectedBlockSizeException(
    val expectedSize: Int,
) : SaveEncryptionException("Expected encrypted block size must be non-negative; got $expectedSize")

class InvalidEncryptedBlockSizeException(
    val actualSize: Int,
    val expectedSize: Int,
) : SaveEncryptionException(
        "Encrypted block requires exactly $expectedSize bytes; input has $actualSize",
    )

class MissingRotationTableException(
    val tableIndex: NormalRotationTableIndex,
) : SaveEncryptionException("No rotation table is available for index ${tableIndex.value}")

/** Difficulty banks used by the evidenced normal, non-German selector. */
enum class NormalSaveDifficulty(internal val tableBankOffset: Int) {
    EASY(0),
    MEDIUM(19),
    HARD(38),
}

/**
 * Only the Build 04.12.02 header values used by the normal, non-German selector.
 *
 * This value does not identify the save's family. [fromBuild041202] accepts only
 * the evidenced header identity and rejects unsupported BOOLEAN encodings and
 * difficulty levels rather than guessing their meaning.
 */
data class NormalEncryptionHeaderInputs(
    val balance: Int,
    val playerMercCount: Int,
    val sectorZ: Int,
    val loadScreenId: Int,
    val alternateSector: Boolean,
    val perSaveRandom: Long,
    val worldDay: Long,
    val gunNut: Boolean,
    val sciFi: Boolean,
    val difficulty: NormalSaveDifficulty,
) {
    init {
        requireUnsignedByte("playerMercCount", playerMercCount)
        if (sectorZ !in Byte.MIN_VALUE..Byte.MAX_VALUE) {
            throw InvalidEncryptionHeaderInputException(
                fieldName = "sectorZ",
                rawValue = sectorZ,
                detail = "must fit in one signed byte",
            )
        }
        requireUnsignedByte("loadScreenId", loadScreenId)
        requireUnsignedInt("perSaveRandom", perSaveRandom)
        requireUnsignedInt("worldDay", worldDay)
    }

    companion object {
        private const val MAX_UNSIGNED_INT = 0xffff_ffffL

        fun fromBuild041202(header: SaveHeader): NormalEncryptionHeaderInputs {
            if (
                header.saveVersion != SaveHeaderParser.SUPPORTED_SAVE_VERSION ||
                header.gameVersion != SaveHeaderParser.SUPPORTED_GAME_VERSION
            ) {
                throw InvalidEncryptionHeaderInputException(
                    fieldName = "headerIdentity",
                    rawValue = "${header.saveVersion}/${header.gameVersion}",
                    detail = "requires save version 103 and game version Build 04.12.02",
                )
            }

            return NormalEncryptionHeaderInputs(
                balance = header.balance,
                playerMercCount = header.playerMercCount,
                sectorZ = header.sector.z,
                loadScreenId = header.loadScreenId,
                alternateSector = header.alternateSector.requireSupported("alternateSector"),
                perSaveRandom = header.perSaveRandom,
                worldDay = header.day,
                gunNut = header.initialGameOptions.gunNut.requireSupported("gunNut"),
                sciFi = header.initialGameOptions.sciFi.requireSupported("sciFi"),
                difficulty =
                    when (header.initialGameOptions.difficultyLevel) {
                        1 -> NormalSaveDifficulty.EASY
                        2 -> NormalSaveDifficulty.MEDIUM
                        3 -> NormalSaveDifficulty.HARD
                        else ->
                            throw InvalidEncryptionHeaderInputException(
                                fieldName = "difficultyLevel",
                                rawValue = header.initialGameOptions.difficultyLevel,
                                detail = "normal selector supports only 1 (easy), 2 (medium), or 3 (hard)",
                            )
                    },
            )
        }

        private fun requireUnsignedByte(fieldName: String, value: Int) {
            if (value !in 0..0xff) {
                throw InvalidEncryptionHeaderInputException(
                    fieldName = fieldName,
                    rawValue = value,
                    detail = "must fit in one unsigned byte",
                )
            }
        }

        private fun requireUnsignedInt(fieldName: String, value: Long) {
            if (value !in 0..MAX_UNSIGNED_INT) {
                throw InvalidEncryptionHeaderInputException(
                    fieldName = fieldName,
                    rawValue = value,
                    detail = "must fit in one unsigned 32-bit value",
                )
            }
        }

        private fun EncodedBoolean.requireSupported(fieldName: String): Boolean =
            value ?: throw InvalidEncryptionHeaderInputException(
                fieldName = fieldName,
                rawValue = rawValue,
                detail = "selector BOOLEAN must be encoded as 0 or 1",
            )
    }
}

/** Validated index into the 228-table normal save-encryption family. */
data class NormalRotationTableIndex(val value: Int) {
    init {
        if (value !in 0 until NormalSaveEncryptionSelector.ROTATION_TABLE_COUNT) {
            throw InvalidRotationTableIndexException(value)
        }
    }
}

/** Project-authored expression of the documented normal, non-German selector facts. */
object NormalSaveEncryptionSelector {
    const val TABLES_PER_BANK = 19
    const val ROTATION_TABLE_COUNT = TABLES_PER_BANK * 12
    private const val UINT32_MASK = 0xffff_ffffL
    private val RANDOM_CONTRIBUTIONS =
        listOf(
            DivisibilityContribution(divisor = 2L, amount = 1L),
            DivisibilityContribution(divisor = 14L, amount = 1L),
            DivisibilityContribution(divisor = 322L, amount = 1L),
            DivisibilityContribution(divisor = 1_106L, amount = 2L),
        )

    fun select(inputs: NormalEncryptionHeaderInputs): NormalRotationTableIndex {
        var accumulator = wrapUnsigned32(inputs.balance.toLong())
        accumulator =
            wrapUnsigned32(accumulator * (inputs.playerMercCount.toLong() + 1L))
        accumulator = wrapUnsigned32(accumulator + inputs.sectorZ.toLong() * 3L)
        accumulator = wrapUnsigned32(accumulator + inputs.loadScreenId.toLong())

        if (inputs.alternateSector) accumulator = wrapUnsigned32(accumulator + 7L)
        val randomContribution = RANDOM_CONTRIBUTIONS.sumOf { contribution ->
            if (inputs.perSaveRandom % contribution.divisor == 0L) contribution.amount else 0L
        }
        accumulator = wrapUnsigned32(accumulator + randomContribution)

        val positionWithinBank =
            ((accumulator % 10L + inputs.worldDay / 10L) % TABLES_PER_BANK).toInt()
        val optionBankOffset =
            (if (inputs.gunNut) TABLES_PER_BANK * 6 else 0) +
                (if (inputs.sciFi) TABLES_PER_BANK * 3 else 0) +
                inputs.difficulty.tableBankOffset

        return NormalRotationTableIndex(optionBankOffset + positionWithinBank)
    }

    private fun wrapUnsigned32(value: Long): Long = value and UINT32_MASK

    private data class DivisibilityContribution(val divisor: Long, val amount: Long)
}

/** Immutable, defensively copied rotation bytes for one table. */
class SaveRotationTable private constructor(private val bytes: ByteArray) {
    fun toByteArray(): ByteArray = bytes.copyOf()

    internal fun unsignedByteAt(index: Int): Int = bytes[index].toInt() and 0xff

    override fun equals(other: Any?): Boolean =
        this === other || (other is SaveRotationTable && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "SaveRotationTable(${bytes.size} bytes)"

    companion object {
        const val REQUIRED_LENGTH = 49

        fun fromBytes(bytes: ByteArray): SaveRotationTable {
            requireLength(bytes.size)
            return SaveRotationTable(bytes.copyOf())
        }

        fun fromUnsignedBytes(values: List<Int>): SaveRotationTable {
            requireLength(values.size)
            val bytes = ByteArray(values.size)
            values.forEachIndexed { index, value ->
                if (value !in 0..0xff) throw InvalidRotationTableByteException(index, value)
                bytes[index] = value.toByte()
            }
            return SaveRotationTable(bytes)
        }

        private fun requireLength(actualLength: Int) {
            if (actualLength != REQUIRED_LENGTH) {
                throw InvalidRotationTableLengthException(actualLength)
            }
        }
    }
}

/** Boundary for rotation-table data that is intentionally not bundled in this slice. */
fun interface RotationTableProvider {
    fun tableFor(index: NormalRotationTableIndex): SaveRotationTable?
}

/**
 * Byte-wise decryption for one encrypted block operation.
 *
 * Feedback and rotation position deliberately start at zero on every call.
 * An empty block is a valid no-op when `expectedBlockSize` is also zero.
 * This object exposes no encryption-for-write operation.
 */
object NormalSaveBlockDecryptor {
    fun decryptBlock(
        encryptedBlock: ByteArray,
        expectedBlockSize: Int,
        rotationTable: SaveRotationTable,
    ): ByteArray {
        validateBlockSize(encryptedBlock.size, expectedBlockSize)

        var previousCiphertext = 0
        return ByteArray(encryptedBlock.size) { index ->
            val ciphertext = encryptedBlock[index].toInt() and 0xff
            val plaintext =
                (ciphertext -
                    previousCiphertext -
                    rotationTable.unsignedByteAt(index % SaveRotationTable.REQUIRED_LENGTH)) and 0xff
            previousCiphertext = ciphertext
            plaintext.toByte()
        }
    }

    fun decryptBlock(
        encryptedBlock: ByteArray,
        expectedBlockSize: Int,
        tableIndex: NormalRotationTableIndex,
        tableProvider: RotationTableProvider,
    ): ByteArray {
        val rotationTable = tableProvider.tableFor(tableIndex)
            ?: throw MissingRotationTableException(tableIndex)
        return decryptBlock(encryptedBlock, expectedBlockSize, rotationTable)
    }

    private fun validateBlockSize(actualSize: Int, expectedSize: Int) {
        if (expectedSize < 0) throw InvalidExpectedBlockSizeException(expectedSize)
        if (actualSize != expectedSize) {
            throw InvalidEncryptedBlockSizeException(actualSize, expectedSize)
        }
    }
}
