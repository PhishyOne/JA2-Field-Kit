package com.phishtopia.ja2fieldkit.core.catalog

import kotlin.test.*

class BaseItemCatalogTest {
    private fun put32(bytes: ByteArray, at: Int, value: Long) {
        repeat(4) { bytes[at + it] = (value ushr (it * 8)).toByte() }
    }
    private fun putPath(bytes: ByteArray, at: Int, value: String) {
        value.toByteArray(Charsets.US_ASCII).copyInto(bytes, at)
    }
    private fun field(bytes: ByteArray, at: Int, value: String) {
        value.forEachIndexed { index, c ->
            val encoded = if (c.code > 33) c.code + 1 else c.code
            bytes[at + index * 2] = encoded.toByte()
            bytes[at + index * 2 + 1] = (encoded ushr 8).toByte()
        }
    }
    private fun archive(names: List<String> = listOf("Test Wrench", "Example Pistol"),
                        paths: List<String> = listOf("itemdesc.edt")): ByteArray {
        val payload = names.size * 800
        return ByteArray(532 + payload + paths.size * 280).also { b ->
            putPath(b, 0, "Synthetic library")
            put32(b, 512, paths.size.toLong())
            paths.forEachIndexed { i, path ->
                val entry = 532 + payload + i * 280
                putPath(b, entry, path)
                put32(b, entry + 256, 532)
                put32(b, entry + 260, payload.toLong())
            }
            names.forEachIndexed { i, name ->
                field(b, 532 + i * 800, "Invented short")
                field(b, 532 + i * 800 + 160, name)
                field(b, 532 + i * 800 + 320, "Invented description")
            }
        }
    }
    private fun names(bytes: ByteArray) = assertIs<CatalogResult.Loaded>(GogEnglishItemCatalog.parseAdmitted(bytes)).catalog.names
    private fun rejected(bytes: ByteArray) = assertIs<CatalogResult.Rejected>(GogEnglishItemCatalog.parseAdmitted(bytes))

    @Test fun exactIdentityIsLockedAndCannotBeBypassedBySyntheticInput() {
        assertEquals(2_047_959, GogEnglishItemCatalog.SIZE)
        assertEquals("ffd1c49977c891d9c7ffc7756a25f741", GogEnglishItemCatalog.MD5)
        assertEquals("GOG English v1.12 (Build 04.12.02)", GogEnglishItemCatalog.LABEL)
        assertEquals(CatalogFailure.WRONG_SIZE, assertIs<CatalogResult.Rejected>(GogEnglishItemCatalog.admit(archive())).reason)
        assertEquals(CatalogFailure.WRONG_IDENTITY, assertIs<CatalogResult.Rejected>(GogEnglishItemCatalog.admit(ByteArray(GogEnglishItemCatalog.SIZE))).reason)
    }
    @Test fun fullNameMappingRotAndNormalizedPaths() {
        listOf("itemdesc.edt", "ITEMDESC.EDT", "BINARYDATA\\ItEmDeSc.EdT", "binarydata/itemdesc.edt").forEach {
            val b = archive(paths = listOf(it))
            putPath(b, 256, "BINARYDATA\\")
            assertEquals(mapOf(0 to "Test Wrench", 1 to "Example Pistol"), names(b))
        }
    }
    @Test fun targetMustBeUniqueAndCurrent() {
        listOf("other.edt", "itemdesc.edt/", "../itemdesc.edt", "./itemdesc.edt", "").forEach {
            rejected(archive(paths = listOf(it)))
        }
        rejected(archive(paths = listOf("itemdesc.edt", "ITEMDESC.EDT")))
        val b = archive(paths = listOf("itemdesc.edt", "ITEMDESC.EDT"))
        b[b.size - 280 + 264] = 1
        assertEquals(2, names(b).size)
        b[b.size - 560 + 264] = 1
        rejected(b)
    }
    @Test fun countsAndTablesFailClosedWithoutOverflow() {
        listOf(0L, -1L, Int.MAX_VALUE.toLong(), 0x40000000L, 8L).forEach { count ->
            rejected(archive().also { put32(it, 512, count) })
        }
        rejected(ByteArray(531))
        rejected(archive().also { b -> repeat(256) { b[b.size - 280 + it] = 65 } })
        rejected(archive().also { b -> repeat(256) { b[256 + it] = 65 } })
    }
    @Test fun resourceRangesAndLengthsFailClosed() {
        listOf(0L, 531L, 2132L, 2412L, 0xffffffffL).forEach { offset ->
            rejected(archive().also { put32(it, it.size - 280 + 256, offset) })
        }
        listOf(0L, 799L, 801L, 0xffffffffL).forEach { length ->
            rejected(archive().also { put32(it, it.size - 280 + 260, length) })
        }
    }
    @Test fun reservedUnitAndEarlyNulNeverSurface() {
        val b = archive(listOf("X".repeat(80), "Test"))
        assertEquals("X".repeat(79), names(b)[0])
        field(b, 532 + 800 + 160 + 12, "Hidden")
        assertEquals("Test", names(b)[1])
    }
    @Test fun unusableIndividualNamesAreOmittedWithoutDiscardingGoodNames() {
        val b = archive(listOf("", "   ", "Bad\nName", "\uD800", "\uDC00", "Test Wrench", "\uD83D\uDE00"))
        assertEquals(mapOf(5 to "Test Wrench", 6 to "\uD83D\uDE00"), names(b))
    }
    @Test fun collectionsAreSnapshotsAndArraysAreNotRetained() {
        val b = archive()
        val catalog = assertIs<CatalogResult.Loaded>(GogEnglishItemCatalog.parseAdmitted(b)).catalog
        b.fill(0)
        assertEquals("Test Wrench", catalog.names[0])
        val source = mutableMapOf(1 to "Test")
        val copy = BaseItemCatalog(source)
        source.clear()
        assertEquals("Test", copy.names[1])
        assertFailsWith<UnsupportedOperationException> { (copy.names as MutableMap)[2] = "Other" }
        assertFalse(BaseItemCatalog::class.java.declaredFields.any { it.type == ByteArray::class.java })
    }
}
