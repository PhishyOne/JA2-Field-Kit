package com.phishtopia.ja2fieldkit.core.format

class InvalidExpectedPlaintextBlockSizeException(
    val expectedSize: Int,
) : SaveEncryptionException("Expected plaintext block size must be non-negative; got $expectedSize")

class InvalidPlaintextBlockSizeException(
    val actualSize: Int,
    val expectedSize: Int,
) : SaveEncryptionException(
    "Plaintext block requires exactly $expectedSize bytes; input has $actualSize",
)

/** Algebraic inverse of the decryptor. Feedback and rotation position reset on every call. */
object NormalSaveBlockEncryptor {
    fun encryptBlock(
        plaintextBlock: ByteArray,
        expectedBlockSize: Int,
        rotationTable: SaveRotationTable,
    ): ByteArray {
        if (expectedBlockSize < 0) throw InvalidExpectedPlaintextBlockSizeException(expectedBlockSize)
        if (plaintextBlock.size != expectedBlockSize) {
            throw InvalidPlaintextBlockSizeException(plaintextBlock.size, expectedBlockSize)
        }
        var feedback = 0
        return ByteArray(plaintextBlock.size) { index ->
            feedback = (feedback + (plaintextBlock[index].toInt() and 0xff) +
                rotationTable.unsignedByteAt(index % SaveRotationTable.REQUIRED_LENGTH)) and 0xff
            feedback.toByte()
        }
    }
}
