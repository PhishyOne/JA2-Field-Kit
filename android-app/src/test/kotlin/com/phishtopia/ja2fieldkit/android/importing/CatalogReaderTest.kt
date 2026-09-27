package com.phishtopia.ja2fieldkit.android.importing

import com.phishtopia.ja2fieldkit.core.catalog.*
import java.io.InputStream
import kotlin.test.*

class CatalogReaderTest {
    @Test fun oversizeNeverReadsBeyondExpectedPlusOne() {
        var consumed = 0
        val input = object : InputStream() {
            override fun read(): Int { consumed++; return 1 }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                consumed += len
                b.fill(1, off, off + len)
                return len
            }
        }
        assertEquals(CatalogFailure.WRONG_SIZE, assertIs<CatalogResult.Rejected>(CatalogReader.read("content") { input }).reason)
        assertEquals(GogEnglishItemCatalog.SIZE + 1, consumed)
    }
    @Test fun nonContentNeverOpensAndShortInputReachesAdmission() {
        listOf(null, "file", "https", "CONTENT").forEach { scheme ->
            assertEquals(CatalogFailure.NOT_CONTENT_URI,
                assertIs<CatalogResult.Rejected>(CatalogReader.read(scheme) { error("must not open") }).reason)
        }
        assertEquals(CatalogFailure.WRONG_SIZE, assertIs<CatalogResult.Rejected>(CatalogReader.read("content") { byteArrayOf(1).inputStream() }).reason)
        assertEquals(CatalogFailure.UNAVAILABLE, assertIs<CatalogResult.Rejected>(CatalogReader.read("content") { null }).reason)
    }
    @Test fun zeroLengthReadsProgressAndStreamCloses() {
        var closed = false
        var consumed = 0
        val input = object : InputStream() {
            override fun read(b: ByteArray, off: Int, len: Int) = 0
            override fun read(): Int { consumed++; return 0 }
            override fun close() { closed = true }
        }
        CatalogReader.read("content") { input }
        assertTrue(closed)
        assertEquals(GogEnglishItemCatalog.SIZE + 1, consumed)
    }
}
