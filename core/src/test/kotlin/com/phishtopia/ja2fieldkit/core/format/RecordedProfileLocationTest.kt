package com.phishtopia.ja2fieldkit.core.format

import com.phishtopia.ja2fieldkit.core.model.RecordedProfileLocation
import com.phishtopia.ja2fieldkit.core.io.LittleEndianReader
import kotlin.test.*

class RecordedProfileLocationTest {
    private fun table(x: Int = 9, y: Int = 1, z: Int = 0, life: Int = 70,
        status: Int = 0, flags: Int = 0, flags2: Int = 0): ByteArray = ByteArray(170 * 716).also {
        fun put(offset: Int, value: Int, width: Int = 1) {
            repeat(width) { i -> it[57 * 716 + offset + i] = (value ushr (i * 8)).toByte() }
        }
        put(288, x, 2); put(290, y, 2); put(669, z); put(334, life)
        put(652, status); put(233, flags); put(231, flags2)
    }
    @Test fun canonicalCornersAndDepthsAreImmutableAndIsolated() {
        for (x in listOf(1, 9, 16)) for (y in listOf(1, 16)) for (z in 0..3) {
            val bytes = table(x, y, z)
            val profiles = NormalMercProfileParser.parseBuild041202(bytes)
            bytes.fill(0)
            assertEquals(RecordedProfileLocation.Sector(x, y, z), profiles[57].recordedProfileLocation)
            assertEquals(RecordedProfileLocation.Unavailable, profiles[56].recordedProfileLocation)
            assertEquals(RecordedProfileLocation.Unavailable, profiles[58].recordedProfileLocation)
        }
    }
    @Test fun invalidCoordinatesAndEachSuppressionFactYieldUnavailableWithoutRejectingProfile() {
        val cases = listOf(table(x=0), table(y=0), table(x=17), table(y=17),
            table(x=32768), table(y=65535), table(z=-1), table(z=4), table(z=-128),
            table(life=0), table(life=-1), table(status=-5), table(flags=1), table(flags=64),
            table(flags2=1), table(flags2=2))
        for (bytes in cases) assertEquals(RecordedProfileLocation.Unavailable,
            NormalMercProfileParser.parseBuild041202(bytes)[57].recordedProfileLocation)
        assertEquals(32768, LittleEndianReader(table(x=32768)).u16(57 * 716 + 288))
        assertEquals(65535, LittleEndianReader(table(y=65535)).u16(57 * 716 + 290))
        assertEquals(RecordedProfileLocation.Sector(9, 1, 0),
            NormalMercProfileParser.parseBuild041202(table(flags=128, flags2=128, status=-7))[57].recordedProfileLocation)
    }
    @Test fun sectorRejectsInvalidConstruction() {
        for ((x,y,z) in listOf(Triple(0,1,0), Triple(1,17,0), Triple(1,1,4)))
            assertFailsWith<IllegalArgumentException> { RecordedProfileLocation.Sector(x,y,z) }
    }
}
