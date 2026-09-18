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

        assertContentEquals(bytes, BoundedSaveReader(4).read(ByteArrayInputStream(bytes), null))
    }

    @Test
    fun acceptsExactlyTheLimitAndRejectsOneByteMore() {
        val reader = BoundedSaveReader(4)

        assertContentEquals(byteArrayOf(1, 2, 3, 4), reader.read(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), 4))
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

        assertContentEquals(byteArrayOf(7, 8), BoundedSaveReader(2).read(input, null))
    }
}
