package com.phishtopia.ja2fieldkit.core.format

import com.phishtopia.ja2fieldkit.core.Ja2SaveInspector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The whole-save inputs are assembled only from admitted project-authored synthetic resources. */
class NormalNonLinuxProfileDecoderTest {
    private val header = resource("/fixtures/synthetic-build-04.12.02-header-v1.bin")
    private val vector = resource("/fixtures/synthetic-build-04.12.02-profile-recovery-v1.bin")
    private val key = vector.copyOfRange(0, 49)
    private val basePlaintext = vector.copyOfRange(49, 121_769)

    @Test
    fun publicApiFramesRecoversDecryptsAndParsesEveryRecord() {
        val plaintext = withValidSyntheticNames(basePlaintext)
        val save = syntheticSave(encryptRecords(plaintext), eventCount = 2)

        val profiles = Ja2SaveInspector().parseBuild041202NormalNonLinuxProfiles(save)

        assertEquals(170, profiles.size)
        assertEquals((0 until 170).toList(), profiles.map { it.profileId })
        assertEquals("Synthetic 0", profiles.first().name)
        assertEquals("P0\uD83D\uDE80", profiles.first().nickname)
        assertEquals("Synthetic 169", profiles.last().name)
        with(profiles[73]) {
            val start = 73 * 716
            assertEquals(plaintext[start + 334].toInt(), life)
            assertEquals(plaintext[start + 297].toInt(), lifeMax)
            assertEquals(plaintext[start + 405].toInt(), agility)
            assertEquals(plaintext[start + 335].toInt(), dexterity)
            assertEquals(plaintext[start + 296].toInt(), strength)
            assertEquals(plaintext[start + 341].toInt(), leadership)
            assertEquals(plaintext[start + 355].toInt(), wisdom)
            assertEquals(plaintext[start + 353].toInt(), marksmanship)
            assertEquals(plaintext[start + 339].toInt(), explosives)
            assertEquals(plaintext[start + 411].toInt(), mechanical)
            assertEquals(plaintext[start + 261].toInt(), medical)
            assertEquals(plaintext[start + 352].toInt(), experienceLevel)
        }
    }

    @Test
    fun oneByteWrongRecordAlignmentCannotSilentlyParse() {
        val ciphertext = encryptRecords(withValidSyntheticNames(basePlaintext))
        val shifted = ByteArray(ciphertext.size) { index ->
            if (index + 1 < ciphertext.size) ciphertext[index + 1] else 0
        }

        assertFailsWith<ProfileRotationRecoveryException> {
            Ja2SaveInspector().parseBuild041202NormalNonLinuxProfiles(syntheticSave(shifted, 0))
        }
    }

    @Test
    fun publicApiRejectsMalformedEncryptionSelectorHeaderInputs() {
        val ciphertext = encryptRecords(withValidSyntheticNames(basePlaintext))
        val malformedInputs =
            listOf(
                Triple(300, 2, "alternateSector"),
                Triple(303, 2, "gunNut"),
                Triple(304, 0xff, "sciFi"),
                Triple(305, 0, "difficultyLevel"),
            )

        malformedInputs.forEach { (headerOffset, rawValue, fieldName) ->
            val save = syntheticSave(ciphertext, eventCount = 0)
            save[headerOffset] = rawValue.toByte()

            val error = assertFailsWith<InvalidEncryptionHeaderInputException>(fieldName) {
                Ja2SaveInspector().parseBuild041202NormalNonLinuxProfiles(save)
            }

            assertEquals(fieldName, error.fieldName)
            assertEquals(rawValue, error.rawValue)
        }
    }

    private fun withValidSyntheticNames(source: ByteArray): ByteArray =
        source.copyOf().also { plaintext ->
            repeat(170) { record ->
                val start = record * 716
                plaintext.putUtf16Le(start, 30, "Synthetic $record")
                plaintext.putUtf16Le(start + 60, 10, "P$record\uD83D\uDE80")
            }
        }

    // Test-owned forward transform, independent of the read-only production decryptor.
    private fun encryptRecords(plaintext: ByteArray): ByteArray {
        require(plaintext.size == 121_720)
        val encrypted = ByteArray(plaintext.size)
        repeat(170) { record ->
            val start = record * 716
            var previousCiphertext = 0
            repeat(716) { offset ->
                previousCiphertext = (
                    previousCiphertext +
                        (plaintext[start + offset].toInt() and 0xff) +
                        (key[offset % 49].toInt() and 0xff)
                    ) and 0xff
                encrypted[start + offset] = previousCiphertext.toByte()
            }
        }
        return encrypted
    }

    private fun syntheticSave(encryptedProfiles: ByteArray, eventCount: Int): ByteArray {
        require(encryptedProfiles.size == 121_720)
        val eventDataStart = 819
        val laptopStart = eventDataStart + eventCount * 28
        val profileStart = laptopStart + 7_440
        return ByteArray(profileStart + 121_720 + 17).also { save ->
            header.copyInto(save)
            save.putU32Le(815, eventCount)
            save[laptopStart + 7_276] = 9
            save[laptopStart + 7_277] = 0
            save[laptopStart + 7_284] = 7
            save[laptopStart + 7_285] = 0
            encryptedProfiles.copyInto(save, profileStart)
        }
    }

    private fun ByteArray.putUtf16Le(offset: Int, codeUnits: Int, value: String) {
        val encoded = value.toCharArray()
        require(encoded.size < codeUnits)
        repeat(codeUnits * 2) { this[offset + it] = 0 }
        encoded.forEachIndexed { index, character ->
            this[offset + index * 2] = character.code.toByte()
            this[offset + index * 2 + 1] = (character.code ushr 8).toByte()
        }
    }

    private fun ByteArray.putU32Le(offset: Int, value: Int) {
        repeat(4) { index -> this[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private fun resource(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(path)) { "Synthetic test resource is missing" }
            .use { it.readBytes() }
}
