package com.phishtopia.ja2fieldkit.android.importing

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Reads one user-selected stream into memory without paths, seeking, or unbounded allocation. */
class BoundedSaveReader(
    private val maximumBytes: Int = MAXIMUM_SAVE_BYTES,
) {
    init {
        require(maximumBytes > 0)
    }

    fun read(input: InputStream, declaredSizeBytes: Long?): ByteArray {
        if (declaredSizeBytes != null && declaredSizeBytes > maximumBytes) {
            throw SaveTooLargeException(maximumBytes)
        }

        val initialCapacity = declaredSizeBytes
            ?.takeIf { it in 1..maximumBytes.toLong() }
            ?.toInt()
            ?: DEFAULT_INITIAL_CAPACITY
        val output = ByteArrayOutputStream(initialCapacity)
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0

        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            if (count == 0) {
                val oneByte = input.read()
                if (oneByte == -1) break
                if (total == maximumBytes) throw SaveTooLargeException(maximumBytes)
                output.write(oneByte)
                total += 1
                continue
            }
            if (count > maximumBytes - total) throw SaveTooLargeException(maximumBytes)
            output.write(buffer, 0, count)
            total += count
        }
        return output.toByteArray()
    }

    companion object {
        /**
         * The admitted Android save is 2,563,321 bytes. Six-times-plus headroom accommodates
         * ordinary save growth while keeping a malicious or mistaken provider response bounded.
         */
        const val MAXIMUM_SAVE_BYTES: Int = 16 * 1024 * 1024

        private const val BUFFER_SIZE = 64 * 1024
        private const val DEFAULT_INITIAL_CAPACITY = 256 * 1024
    }
}

class SaveTooLargeException(
    val maximumBytes: Int,
) : Exception("Selected content exceeds the configured save-size limit")
