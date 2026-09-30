package com.phishtopia.ja2fieldkit.core.format

import com.phishtopia.ja2fieldkit.core.model.*
import kotlin.test.*

/** Independent literal serialized vectors; no production offset constants. */
class ProfileDossierTest {
    @Test fun everyFieldUsesItsExactOffsetWidthAndRecordStride() {
        val bytes = ByteArray(170 * 716)
        val start = 169 * 716
        fun put(offset: Int, value: Long, width: Int) {
            repeat(width) { bytes[start + offset + it] = (value ushr (8 * it)).toByte() }
        }
        put(336, 7, 1); put(337, 15, 1); put(340, 11, 1); put(549, 9, 1)
        listOf(310, 312, 314, 316, 318, 320, 322).forEachIndexed { i, offset ->
            put(offset, (0x8101 + i * 0x102).toLong(), 2)
        }
        put(708, 0xfedc_ba98L, 4)
        listOf(342, 343, 344, 347, 348, 349).forEachIndexed { i, offset -> put(offset, (10 + i).toLong(), 1) }
        // Unused relationship slots must never surface.
        listOf(345, 346, 350, 351).forEach { put(it, 99, 1) }
        val table = NormalMercProfileParser.parseBuild041202(bytes)
        val p = table[169]
        assertEquals(SkillTrait.CAMOUFLAGED, p.skillTrait1.known)
        assertEquals(SkillTrait.THIEF, p.skillTrait2.known)
        assertEquals(PersonalityTrait.PSYCHO, p.personalityTrait.known)
        assertEquals(Attitude.COWARD, p.attitude.known)
        assertEquals(ProfileCareerRecord(0x8101, 0x8203, 0x8305, 0x8407, 0x8509, 0x860b, 0x870d, 0xfedc_ba98L), p.career)
        assertEquals(listOf(10, 11, 12, 13, 14, 15), p.relationships.run {
            listOf(friend1, friend2, learnedFriend, enemy1, enemy2, learnedEnemy).map { it.profileId }
        })
        assertEquals(ProfileCareerRecord(), table[168].career)
        bytes.fill(0)
        assertEquals(0xfedc_ba98L, p.career.totalCostPaid)
        assertFailsWith<UnsupportedOperationException> { (table as MutableList).clear() }
    }

    @Test fun unsignedCountersPreserveBoundariesAndHighBits() {
        for (value in listOf(0, 1, 32767, 32768, 65535)) {
            val bytes = ByteArray(170 * 716)
            for (offset in listOf(310, 312, 314, 316, 318, 320, 322)) {
                bytes[offset] = value.toByte(); bytes[offset + 1] = (value ushr 8).toByte()
            }
            val cost = if (value == 65535) 0xffff_ffffL else value.toLong()
            repeat(4) { bytes[708 + it] = (cost ushr (8 * it)).toByte() }
            assertEquals(ProfileCareerRecord(value, value, value, value, value, value, value, cost),
                NormalMercProfileParser.parseBuild041202(bytes)[0].career)
        }
    }

    @Test fun everyKnownEnumIdAndEveryUnknownSignedByteIsSafe() {
        val skills = listOf("None", "Lockpicking", "Hand to hand", "Electronics", "Night operations", "Throwing", "Teaching", "Heavy weapons", "Automatic weapons", "Stealth", "Ambidextrous", "Thief", "Martial arts", "Knifing", "On roof", "Camouflaged")
        val personalities = listOf("None", "Heat intolerant", "Nervous", "Claustrophobic", "Nonswimmer", "Fear of insects", "Forgetful", "Psycho")
        val attitudes = listOf("Normal", "Friendly", "Loner", "Optimist", "Pessimist", "Aggressive", "Arrogant", "Big shot", "Asshole", "Coward")
        for (raw in -128..127) {
            assertEquals(skills.getOrNull(raw), SkillTraitId(raw).known?.label)
            assertEquals(personalities.getOrNull(raw), PersonalityTraitId(raw).known?.label)
            assertEquals(attitudes.getOrNull(raw), AttitudeId(raw).known?.label)
            assertEquals(skills.getOrNull(raw) ?: "Unknown ($raw)", SkillTraitId(raw).label)
            assertEquals(personalities.getOrNull(raw) ?: "Unknown ($raw)", PersonalityTraitId(raw).label)
            assertEquals(attitudes.getOrNull(raw) ?: "Unknown ($raw)", AttitudeId(raw).label)
        }
        for (raw in listOf(-129, 128, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { SkillTraitId(raw) }
            assertFailsWith<IllegalArgumentException> { PersonalityTraitId(raw) }
            assertFailsWith<IllegalArgumentException> { AttitudeId(raw) }
        }
        val bytes = ByteArray(170 * 716)
        bytes[336] = -128; bytes[337] = -1; bytes[340] = 127; bytes[549] = 10
        val p = NormalMercProfileParser.parseBuild041202(bytes).first()
        assertEquals("Unknown (-128)", p.personalityTrait.label)
        assertEquals("Unknown (-1)", p.skillTrait1.label)
        assertEquals("Unknown (127)", p.skillTrait2.label)
        assertEquals("Unknown (10)", p.attitude.label)
    }

    @Test fun relationshipSentinelsAndSignedIdsAreBounded() {
        for (raw in -128..127) {
            val bytes = ByteArray(170 * 716)
            listOf(342, 343, 344, 347, 348, 349).forEach { bytes[it] = raw.toByte() }
            val r = NormalMercProfileParser.parseBuild041202(bytes).first().relationships
            for (target in listOf(r.friend1, r.friend2, r.learnedFriend, r.enemy1, r.enemy2, r.learnedEnemy)) {
                assertEquals(raw == -1, target.isAbsent)
                assertEquals(raw.takeIf { it >= 0 }, target.profileId)
                assertEquals(raw, target.raw)
            }
        }
    }

    @Test fun newPublicFactsContainNoBytesParserInternalsOrSquadMembership() {
        val types = listOf(MercProfile::class.java, ProfileCareerRecord::class.java, SkillTraitId::class.java,
            PersonalityTraitId::class.java, AttitudeId::class.java, ProfileRelationships::class.java, ProfileRelationshipId::class.java)
        val surface = types.flatMap { it.declaredFields.toList() }.joinToString { "${it.name}:${it.type.name}" }
        for (forbidden in listOf("[B", "offset", "rotation", "Parser", "currentSquad")) assertFalse(forbidden in surface)
    }
}
