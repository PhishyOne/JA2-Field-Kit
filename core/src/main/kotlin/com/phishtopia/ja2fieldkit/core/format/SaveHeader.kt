package com.phishtopia.ja2fieldkit.core.format

import com.phishtopia.ja2fieldkit.core.io.LittleEndianReader

/** An on-disk one-byte BOOLEAN, retaining malformed values for diagnostics. */
data class EncodedBoolean(val rawValue: Int) {
    init {
        require(rawValue in 0..0xff) { "Encoded boolean must fit in one unsigned byte" }
    }

    val value: Boolean?
        get() =
            when (rawValue) {
                0 -> false
                1 -> true
                else -> null
            }
}

data class SaveSector(
    val x: Int,
    val y: Int,
    val z: Int,
)

data class InitialGameOptions(
    val gunNut: EncodedBoolean,
    val sciFi: EncodedBoolean,
    val difficultyLevel: Int,
    val turnTimeLimit: EncodedBoolean,
    val saveMode: Int,
)

/** Bytes whose meaning is not established by the admissible issue-4 evidence. */
data class OpaqueHeaderRange(
    val offset: Int,
    val bytes: List<Int>,
) {
    init {
        require(offset >= 0) { "Opaque range offset must be non-negative" }
        require(bytes.all { it in 0..0xff }) { "Opaque range values must be unsigned bytes" }
    }

    val endExclusive: Int
        get() = offset + bytes.size
}

/**
 * Parsed fields from the 432-byte normal header used by saved-game version 103,
 * game version string `Build 04.12.02`.
 *
 * This is a layout result, not a save-family identification. In particular,
 * parsing does not establish that the containing file is a JA2 Reborn save.
 */
data class SaveHeader(
    val saveVersion: Long,
    val gameVersion: String,
    val saveDescription: String,
    val day: Long,
    val hour: Int,
    val minute: Int,
    val sector: SaveSector,
    val playerMercCount: Int,
    val balance: Int,
    val currentScreenId: Long,
    val alternateSector: EncodedBoolean,
    val worldLoaded: EncodedBoolean,
    val loadScreenId: Int,
    val initialGameOptions: InitialGameOptions,
    val perSaveRandom: Long,
    val saveStateSize: Long,
    val opaqueRanges: List<OpaqueHeaderRange>,
)

sealed class SaveHeaderParseException(message: String) : IllegalArgumentException(message)

class TruncatedSaveHeaderException(
    val actualSize: Int,
    val requiredSize: Int = SaveLayoutFacts.NORMAL_HEADER_SIZE,
) : SaveHeaderParseException(
        "Build 04.12.02 normal header requires $requiredSize bytes; input has $actualSize",
    )

class UnsupportedSaveHeaderException(
    val saveVersion: Long,
    val gameVersion: String,
) : SaveHeaderParseException(
        "Unsupported normal header identity: save version $saveVersion, game version '$gameVersion'",
    )

/** Bounds-checked parser for the evidence-backed Build 04.12.02 normal header only. */
object SaveHeaderParser {
    const val SUPPORTED_SAVE_VERSION = 103L
    const val SUPPORTED_GAME_VERSION = "Build 04.12.02"

    fun parseBuild041202(bytes: ByteArray): SaveHeader {
        if (bytes.size < SaveLayoutFacts.NORMAL_HEADER_SIZE) {
            throw TruncatedSaveHeaderException(bytes.size)
        }

        // Work from exactly the header so later fields can never consume body bytes.
        val reader = LittleEndianReader(bytes.copyOfRange(0, SaveLayoutFacts.NORMAL_HEADER_SIZE))
        val saveVersion = reader.u32(0)
        val gameVersion = readNullTerminatedSingleByteString(reader, offset = 4, length = 16)

        if (saveVersion != SUPPORTED_SAVE_VERSION || gameVersion != SUPPORTED_GAME_VERSION) {
            throw UnsupportedSaveHeaderException(saveVersion, gameVersion)
        }

        return SaveHeader(
            saveVersion = saveVersion,
            gameVersion = gameVersion,
            saveDescription = readNullTerminatedUtf16Le(reader, offset = 20, codeUnits = 128),
            day = reader.u32(280),
            hour = reader.u8(284),
            minute = reader.u8(285),
            sector = SaveSector(
                x = reader.i16(286).toInt(),
                y = reader.i16(288).toInt(),
                z = reader.i8(290).toInt(),
            ),
            playerMercCount = reader.u8(291),
            balance = reader.i32(292),
            currentScreenId = reader.u32(296),
            alternateSector = EncodedBoolean(reader.u8(300)),
            worldLoaded = EncodedBoolean(reader.u8(301)),
            loadScreenId = reader.u8(302),
            initialGameOptions = InitialGameOptions(
                gunNut = EncodedBoolean(reader.u8(303)),
                sciFi = EncodedBoolean(reader.u8(304)),
                difficultyLevel = reader.u8(305),
                turnTimeLimit = EncodedBoolean(reader.u8(306)),
                saveMode = reader.u8(307),
            ),
            perSaveRandom = reader.u32(316),
            saveStateSize = reader.u32(320),
            opaqueRanges = listOf(
                reader.opaqueRange(offset = 276, length = 4),
                reader.opaqueRange(offset = 308, length = 7),
                reader.opaqueRange(offset = 315, length = 1),
                reader.opaqueRange(offset = 324, length = 108),
            ),
        )
    }

    private fun readNullTerminatedSingleByteString(
        reader: LittleEndianReader,
        offset: Int,
        length: Int,
    ): String =
        buildString {
            for (index in 0 until length) {
                val value = reader.u8(offset + index)
                if (value == 0) break
                append(value.toChar())
            }
        }

    private fun readNullTerminatedUtf16Le(
        reader: LittleEndianReader,
        offset: Int,
        codeUnits: Int,
    ): String {
        val result = StringBuilder(codeUnits)
        for (index in 0 until codeUnits) {
            val codeUnit = reader.u16(offset + index * 2)
            if (codeUnit == 0) break
            result.append(codeUnit.toChar())
        }
        return result.toString()
    }

    private fun LittleEndianReader.opaqueRange(offset: Int, length: Int): OpaqueHeaderRange =
        OpaqueHeaderRange(
            offset = offset,
            bytes = bytes(offset, length).map { it.toInt() and 0xff },
        )
}
