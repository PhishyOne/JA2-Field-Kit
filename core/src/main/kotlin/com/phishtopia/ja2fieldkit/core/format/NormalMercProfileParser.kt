package com.phishtopia.ja2fieldkit.core.format

import com.phishtopia.ja2fieldkit.core.io.LittleEndianReader
import com.phishtopia.ja2fieldkit.core.model.MercProfile
import java.util.Collections

enum class MercProfileParseFailure {
    INVALID_BLOCK_SIZE,
    INVALID_UTF16,
}

enum class MercProfileTextField {
    NAME,
    NICKNAME,
}

/** Structural diagnostics only: no profile text or plaintext bytes are included. */
class MercProfileParseException(
    val reason: MercProfileParseFailure,
    val actualSize: Int? = null,
    val requiredSize: Int = NormalProfileRotationRecovery.BLOCK_SIZE,
    val recordIndex: Int? = null,
    val field: MercProfileTextField? = null,
    val byteOffset: Int? = null,
) : IllegalArgumentException(
    "Normal Build 04.12.02 merc-profile parse: $reason " +
        "(size=$actualSize, required=$requiredSize, record=$recordIndex, " +
        "field=$field, offset=$byteOffset)",
)

/**
 * Parser for an already-decrypted canonical normal v103 / Build 04.12.02 profile table.
 *
 * Exactly 170 consecutive 716-byte records are required. Only the v0.1 fields evidenced by the
 * pinned serializer are interpreted; every other byte remains opaque and no roster meaning is
 * inferred. Signed serialized stat bytes are preserved as signed [Int] values without clamping.
 */
object NormalMercProfileParser {
    fun parseBuild041202(decryptedProfiles: ByteArray): List<MercProfile> {
        if (decryptedProfiles.size != NormalProfileRotationRecovery.BLOCK_SIZE) {
            throw MercProfileParseException(
                reason = MercProfileParseFailure.INVALID_BLOCK_SIZE,
                actualSize = decryptedProfiles.size,
            )
        }

        // Bound allocation before snapshotting. No later read touches caller-owned bytes.
        val snapshot = decryptedProfiles.copyOf()
        val profiles = ArrayList<MercProfile>(NormalProfileRotationRecovery.RECORD_COUNT)
        repeat(NormalProfileRotationRecovery.RECORD_COUNT) { profileId ->
            val recordStart = profileId * NormalProfileRotationRecovery.RECORD_SIZE
            val reader = LittleEndianReader(
                snapshot.copyOfRange(
                    recordStart,
                    recordStart + NormalProfileRotationRecovery.RECORD_SIZE,
                ),
            )
            profiles += MercProfile(
                profileId = profileId,
                name = readUtf16Le(reader, profileId, MercProfileTextField.NAME, 0, 30),
                nickname = readUtf16Le(reader, profileId, MercProfileTextField.NICKNAME, 60, 10),
                life = reader.i8(334).toInt(),
                lifeMax = reader.i8(297).toInt(),
                agility = reader.i8(405).toInt(),
                dexterity = reader.i8(335).toInt(),
                strength = reader.i8(296).toInt(),
                leadership = reader.i8(341).toInt(),
                wisdom = reader.i8(355).toInt(),
                marksmanship = reader.i8(353).toInt(),
                explosives = reader.i8(339).toInt(),
                mechanical = reader.i8(411).toInt(),
                medical = reader.i8(261).toInt(),
                experienceLevel = reader.i8(352).toInt(),
            )
        }
        return Collections.unmodifiableList(profiles)
    }

    private fun readUtf16Le(
        reader: LittleEndianReader,
        recordIndex: Int,
        field: MercProfileTextField,
        offset: Int,
        codeUnits: Int,
    ): String {
        val result = StringBuilder(codeUnits)
        var index = 0
        while (index < codeUnits) {
            val value = reader.u16(offset + index * 2)
            if (value == 0) break
            val character = value.toChar()
            when {
                Character.isHighSurrogate(character) -> {
                    val nextIndex = index + 1
                    if (nextIndex >= codeUnits) {
                        invalidUtf16(recordIndex, field, offset + index * 2)
                    }
                    val nextValue = reader.u16(offset + nextIndex * 2)
                    val low = nextValue.toChar()
                    if (nextValue == 0 || !Character.isLowSurrogate(low)) {
                        invalidUtf16(recordIndex, field, offset + index * 2)
                    }
                    result.appendCodePoint(Character.toCodePoint(character, low))
                    index += 2
                }

                Character.isLowSurrogate(character) ->
                    invalidUtf16(recordIndex, field, offset + index * 2)

                else -> {
                    result.append(character)
                    index++
                }
            }
        }
        return result.toString()
    }

    private fun invalidUtf16(
        recordIndex: Int,
        field: MercProfileTextField,
        byteOffset: Int,
    ): Nothing = throw MercProfileParseException(
        reason = MercProfileParseFailure.INVALID_UTF16,
        recordIndex = recordIndex,
        field = field,
        byteOffset = byteOffset,
    )
}
