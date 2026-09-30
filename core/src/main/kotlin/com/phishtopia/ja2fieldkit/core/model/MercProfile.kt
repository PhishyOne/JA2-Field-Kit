package com.phishtopia.ja2fieldkit.core.model

/**
 * The independently evidenced read-only v0.1 slice of one MERCPROFILESTRUCT record.
 *
 * [profileId] is the record index in the serialized 170-entry table. The structure does not
 * serialize a separate profile identifier. These records are profiles, not a hired-player roster.
 */
data class MercProfile(
    val profileId: Int,
    val name: String,
    val nickname: String,
    val life: Int,
    val lifeMax: Int,
    val agility: Int,
    val dexterity: Int,
    val strength: Int,
    val leadership: Int,
    val wisdom: Int,
    val marksmanship: Int,
    val explosives: Int,
    val mechanical: Int,
    val medical: Int,
    val experienceLevel: Int,
    val inventory: List<ProfileInventorySlot> = emptyList(),
    val inventoryUndroppable: Int = 0,
    val skillTrait1: SkillTraitId = SkillTraitId(0),
    val skillTrait2: SkillTraitId = SkillTraitId(0),
    val personalityTrait: PersonalityTraitId = PersonalityTraitId(0),
    val attitude: AttitudeId = AttitudeId(0),
    val career: ProfileCareerRecord = ProfileCareerRecord(),
    val relationships: ProfileRelationships = ProfileRelationships(),
)

/** Unsigned serialized profile facts; interpretation never grants write authority. */
data class ProfileInventorySlot(val itemId: Int, val count: Int, val status: Int)
