package com.phishtopia.ja2fieldkit.android.importing

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BoundedSaveReaderTest {
    @Test
    fun readsUnknownLengthThroughBoundedStream() {
        val bytes = byteArrayOf(1, 2, 3, 4)

        val result = BoundedSaveReader(4).read(ByteArrayInputStream(bytes), null)

        assertContentEquals(bytes, result.bytes)
        assertEquals(4, result.actualSizeBytes)
        assertEquals(
            "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a",
            result.sha256Hex,
        )
    }

    @Test
    fun acceptsExactlyTheLimitAndRejectsOneByteMore() {
        val reader = BoundedSaveReader(4)

        assertContentEquals(
            byteArrayOf(1, 2, 3, 4),
            reader.read(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), 4).bytes,
        )
        assertFailsWith<SaveTooLargeException> {
            reader.read(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)), null)
        }
    }

    @Test
    fun rejectsOversizedProviderMetadataBeforeReading() {
        val neverRead = object : InputStream() {
            override fun read(): Int = error("stream must not be read")
        }

        val error = assertFailsWith<SaveTooLargeException> {
            BoundedSaveReader(4).read(neverRead, 5)
        }
        assertEquals(4, error.maximumBytes)
    }

    @Test
    fun makesProgressWhenAProviderReturnsZeroFromBulkRead() {
        var bulkReads = 0
        val input = object : InputStream() {
            private val delegate = ByteArrayInputStream(byteArrayOf(7, 8))

            override fun read(): Int = delegate.read()

            override fun read(target: ByteArray, offset: Int, length: Int): Int =
                if (bulkReads++ == 0) 0 else delegate.read(target, offset, length)
        }

        assertContentEquals(byteArrayOf(7, 8), BoundedSaveReader(2).read(input, null).bytes)
    }
}
