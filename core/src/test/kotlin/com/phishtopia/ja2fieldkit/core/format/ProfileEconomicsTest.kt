package com.phishtopia.ja2fieldkit.core.format

import com.phishtopia.ja2fieldkit.core.model.*
import kotlin.test.*

class ProfileEconomicsTest {
    @Test fun literalOffsetsPreserveSignedExtremaUnsignedHighBitsAndRecordIsolation() {
        for ((salary, status, billing) in listOf(Triple(-32768, -128, Int.MIN_VALUE),
            Triple(32767, 127, Int.MAX_VALUE), Triple(-1, -9, -1))) {
            val bytes = ByteArray(170 * 716)
            val start = 168 * 716
            fun put(offset: Int, value: Long, width: Int) {
                repeat(width) { bytes[start + offset + it] = (value ushr (8 * it)).toByte() }
            }
            put(292, 0xffff_ffffL, 4); put(332, salary.toLong(), 2)
            put(540, 0x8000_0000L, 4); put(544, 0xfedc_ba98L, 4)
            put(548, -128, 1); put(552, 65535, 2); put(574, 32768, 2)
            put(652, status.toLong(), 1); put(704, billing.toLong(), 4)
            // Adjacent unrelated facts/padding must not leak into economics.
            put(549, 127, 1); put(550, 0xffff, 2); put(554, 99, 1)
            put(651, 127, 1); put(653, 127, 1); put(708, 123456, 4)
            val table = NormalMercProfileParser.parseBuild041202(bytes)
            val expected = ProfileEconomicsFacts(0xffff_ffffL, salary, 0x8000_0000L,
                0xfedc_ba98L, -128, 65535, 32768, status, billing)
            assertEquals(expected, table[168].economics)
            assertEquals(ProfileEconomicsFacts(), table[167].economics)
            assertEquals(ProfileEconomicsFacts(), table[169].economics)
            assertEquals(123456L, table[168].career.totalCostPaid)
            bytes.fill(0)
            assertEquals(expected, table[168].economics)
        }
    }

    @Test fun everyStandardCategoryIdAndBoundaryMatchesPinnedDefaultContent() {
        val expected = listOf(40 to StandardProfileCategory.AIM, 11 to StandardProfileCategory.MERC,
            6 to StandardProfileCategory.IMP, 18 to StandardProfileCategory.RPC,
            85 to StandardProfileCategory.NPC, 4 to StandardProfileCategory.VEHICLE,
            6 to StandardProfileCategory.NOT_USED).flatMap { (count, category) -> List(count) { category } }
        assertEquals(170, expected.size)
        assertEquals(expected, (0..169).map(StandardProfileCategory::fromProfileId))
        for (id in listOf(-1, 170, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { StandardProfileCategory.fromProfileId(id) }
        }
    }

    @Test fun economicsPublicModelIsImmutableAndContainsOnlyNinePrimitiveFacts() {
        val fields = ProfileEconomicsFacts::class.java.declaredFields.filterNot { it.isSynthetic }
        assertEquals(9, fields.size)
        assertTrue(fields.all { java.lang.reflect.Modifier.isFinal(it.modifiers) })
        assertTrue(fields.all { it.type == Int::class.javaPrimitiveType || it.type == Long::class.javaPrimitiveType })
        assertFalse(fields.any { it.name.contains("totalCost", ignoreCase = true) })
    }
}
