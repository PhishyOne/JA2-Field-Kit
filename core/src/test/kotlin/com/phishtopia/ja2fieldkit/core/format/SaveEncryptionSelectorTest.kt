package com.phishtopia.ja2fieldkit.core.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SaveEncryptionSelectorTest {
    @Test
    fun extractsOnlySelectorInputsFromTheEvidencedHeader() {
        val header = SaveHeaderParser.parseBuild041202(syntheticBuild041202Header())

        val inputs = NormalEncryptionHeaderInputs.fromBuild041202(header)

        assertEquals(
            NormalEncryptionHeaderInputs(
                balance = -123_456_789,
                playerMercCount = 0x5a,
                sectorZ = -2,
                loadScreenId = 0xbc,
                alternateSector = true,
                perSaveRandom = 0xfedc_ba98L,
                worldDay = 0x1020_3040L,
                gunNut = true,
                sciFi = false,
                difficulty = NormalSaveDifficulty.MEDIUM,
            ),
            inputs,
        )
        assertEquals(NormalRotationTableIndex(139), NormalSaveEncryptionSelector.select(inputs))
    }

    @Test
    fun appliesOnlyTheDocumentedNestedRandomDivisibilityBonuses() {
        val cases =
            listOf(
                1L to 0,
                2L to 1,
                14L to 2,
                46L to 1,
                158L to 1,
                322L to 3,
                1_106L to 4,
                25_438L to 5,
                7L to 0,
                23L to 0,
                79L to 0,
            )

        cases.forEach { (random, expectedIndex) ->
            assertEquals(
                NormalRotationTableIndex(expectedIndex),
                NormalSaveEncryptionSelector.select(inputs(perSaveRandom = random)),
                "random value $random",
            )
        }
    }

    @Test
    fun treatsSignedContributionsAsWrappedUnsigned32Arithmetic() {
        // UINT32(-1) is 4294967295, whose remainder is 5. A signed floorMod
        // implementation would produce 9 instead.
        assertEquals(
            NormalRotationTableIndex(5),
            NormalSaveEncryptionSelector.select(inputs(balance = -1)),
        )

        // Adding signed sectorZ * 3 wraps to 4294967293, whose remainder is
        // 3. A signed floorMod implementation would produce 7 instead.
        assertEquals(
            NormalRotationTableIndex(3),
            NormalSaveEncryptionSelector.select(inputs(sectorZ = -1)),
        )

        // The multiplication wraps to 2147483645 before reduction. Leaving
        // it as the unbounded value 6442450941 would produce remainder 1.
        assertEquals(
            NormalRotationTableIndex(5),
            NormalSaveEncryptionSelector.select(
                inputs(balance = Int.MAX_VALUE, playerMercCount = 2),
            ),
        )

        // Addition wraps independently too: UINT32(-1) + 1 becomes zero.
        assertEquals(
            NormalRotationTableIndex(0),
            NormalSaveEncryptionSelector.select(inputs(balance = -1, loadScreenId = 1)),
        )
    }

    @Test
    fun mapsEveryOptionCombinationToItsNineteenTableBank() {
        NormalSaveDifficulty.entries.forEach { difficulty ->
            listOf(false, true).forEach { gunNut ->
                listOf(false, true).forEach { sciFi ->
                    val expected =
                        difficulty.tableBankOffset +
                            (if (gunNut) 19 * 6 else 0) +
                            (if (sciFi) 19 * 3 else 0)
                    assertEquals(
                        NormalRotationTableIndex(expected),
                        NormalSaveEncryptionSelector.select(
                            inputs(gunNut = gunNut, sciFi = sciFi, difficulty = difficulty),
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun reducesWorldDayByWholeTensBeforeTheNineteenTableModulo() {
        assertEquals(
            NormalRotationTableIndex(18),
            NormalSaveEncryptionSelector.select(inputs(worldDay = 189)),
        )
        assertEquals(
            NormalRotationTableIndex(0),
            NormalSaveEncryptionSelector.select(inputs(worldDay = 190)),
        )
    }

    @Test
    fun rejectsOutOfRangeRawInputsAndTableIndexes() {
        val mercError = assertFailsWith<InvalidEncryptionHeaderInputException> {
            inputs(playerMercCount = 256)
        }
        assertEquals("playerMercCount", mercError.fieldName)

        val sectorError = assertFailsWith<InvalidEncryptionHeaderInputException> {
            inputs(sectorZ = -129)
        }
        assertEquals("sectorZ", sectorError.fieldName)

        val loadScreenError = assertFailsWith<InvalidEncryptionHeaderInputException> {
            inputs(loadScreenId = -1)
        }
        assertEquals("loadScreenId", loadScreenError.fieldName)

        val randomError = assertFailsWith<InvalidEncryptionHeaderInputException> {
            inputs(perSaveRandom = 0x1_0000_0000L)
        }
        assertEquals("perSaveRandom", randomError.fieldName)

        val dayError = assertFailsWith<InvalidEncryptionHeaderInputException> {
            inputs(worldDay = -1)
        }
        assertEquals("worldDay", dayError.fieldName)

        assertFailsWith<InvalidRotationTableIndexException> { NormalRotationTableIndex(-1) }
        assertFailsWith<InvalidRotationTableIndexException> { NormalRotationTableIndex(228) }
    }

    @Test
    fun rejectsUnsupportedHeaderEncodingsInsteadOfGuessing() {
        val header = SaveHeaderParser.parseBuild041202(syntheticBuild041202Header())

        val identityError = assertFailsWith<InvalidEncryptionHeaderInputException> {
            NormalEncryptionHeaderInputs.fromBuild041202(header.copy(saveVersion = 102))
        }
        assertEquals("headerIdentity", identityError.fieldName)

        val unsupportedBooleans =
            listOf(
                "alternateSector" to header.copy(alternateSector = EncodedBoolean(2)),
                "gunNut" to
                    header.copy(
                        initialGameOptions =
                            header.initialGameOptions.copy(gunNut = EncodedBoolean(2)),
                    ),
                "sciFi" to
                    header.copy(
                        initialGameOptions =
                            header.initialGameOptions.copy(sciFi = EncodedBoolean(0xff)),
                    ),
            )
        unsupportedBooleans.forEach { (fieldName, invalidHeader) ->
            val error = assertFailsWith<InvalidEncryptionHeaderInputException> {
                NormalEncryptionHeaderInputs.fromBuild041202(invalidHeader)
            }
            assertEquals(fieldName, error.fieldName)
        }

        val difficultyError = assertFailsWith<InvalidEncryptionHeaderInputException> {
            NormalEncryptionHeaderInputs.fromBuild041202(
                header.copy(
                    initialGameOptions = header.initialGameOptions.copy(difficultyLevel = 0),
                ),
            )
        }
        assertEquals("difficultyLevel", difficultyError.fieldName)
    }

    @Test
    fun mapsEvidencedDifficultyNumericIdentities() {
        val header = SaveHeaderParser.parseBuild041202(syntheticBuild041202Header())

        listOf(
            1 to NormalSaveDifficulty.EASY,
            2 to NormalSaveDifficulty.MEDIUM,
            3 to NormalSaveDifficulty.HARD,
        ).forEach { (rawDifficulty, expectedDifficulty) ->
            val inputs =
                NormalEncryptionHeaderInputs.fromBuild041202(
                    header.copy(
                        initialGameOptions =
                            header.initialGameOptions.copy(difficultyLevel = rawDifficulty),
                    ),
                )

            assertEquals(expectedDifficulty, inputs.difficulty)
        }
    }

    private fun inputs(
        balance: Int = 0,
        playerMercCount: Int = 0,
        sectorZ: Int = 0,
        loadScreenId: Int = 0,
        alternateSector: Boolean = false,
        perSaveRandom: Long = 1,
        worldDay: Long = 0,
        gunNut: Boolean = false,
        sciFi: Boolean = false,
        difficulty: NormalSaveDifficulty = NormalSaveDifficulty.EASY,
    ): NormalEncryptionHeaderInputs =
        NormalEncryptionHeaderInputs(
            balance = balance,
            playerMercCount = playerMercCount,
            sectorZ = sectorZ,
            loadScreenId = loadScreenId,
            alternateSector = alternateSector,
            perSaveRandom = perSaveRandom,
            worldDay = worldDay,
            gunNut = gunNut,
            sciFi = sciFi,
            difficulty = difficulty,
        )

    private fun syntheticBuild041202Header(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/synthetic-build-04.12.02-header-v1.bin")) {
            "Manifested synthetic header resource is missing"
        }.use { it.readBytes() }
}
