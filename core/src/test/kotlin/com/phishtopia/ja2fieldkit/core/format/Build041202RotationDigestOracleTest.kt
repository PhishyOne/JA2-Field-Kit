package com.phishtopia.ja2fieldkit.core.format

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Build041202RotationDigestOracleTest {
    @Test
    fun validSyntheticSelectorOutsideThePreviouslyCoveredPairHasAnIdentity() {
        val index = NormalSaveEncryptionSelector.select(
            NormalEncryptionHeaderInputs(
                balance = 0,
                playerMercCount = 0,
                sectorZ = 0,
                loadScreenId = 0,
                alternateSector = false,
                perSaveRandom = 1,
                worldDay = 0,
                gunNut = false,
                sciFi = false,
                difficulty = NormalSaveDifficulty.EASY,
            ),
        )
        assertEquals(NormalRotationTableIndex(0), index)
        val oracle: RotationDigestOracle = Build041202RotationDigestOracle
        assertNotNull(oracle.digestFor(index))
    }

    @Test
    fun completeDomainHasUniqueLowercaseFingerprintsWithPinnedIndexAlignment() {
        assertEquals(228, NormalSaveEncryptionSelector.ROTATION_TABLE_COUNT)
        val oracle: RotationDigestOracle = Build041202RotationDigestOracle
        val digests = (0..227).map { index ->
            assertNotNull(oracle.digestFor(NormalRotationTableIndex(index))).hexadecimal
        }
        assertEquals(228, digests.size)
        assertTrue(digests.all { it.matches(Regex("[0-9a-f]{64}")) })
        assertEquals(228, digests.toSet().size)
        assertEquals(
            "384d8f0b52fe4413ea361c3027a3293b54d1763eb9828cc1cb0feb483c964306",
            digests[124],
        )
        assertEquals(
            "b9cf6efc03ac27c7c1293f83df845ae077922af388f4cb149e9041edbf5f68bc",
            digests[139],
        )
        val canonical = buildString {
            for (index in 0..227) append("$index:${digests[index]}\n")
        }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it) }
        assertEquals("594442e0803a44774ad919af5addabed5d114d4262695b433a942ee4a5f945fe", hash)
    }
}
