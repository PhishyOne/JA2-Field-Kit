package com.phishtopia.ja2fieldkit.core.model

/** Project-authored labels for the pinned normal profile enums; IDs are explicit, never ordinals. */
enum class SkillTrait(val id: Int, val label: String) {
    NONE(0, "None"), LOCKPICKING(1, "Lockpicking"), HAND_TO_HAND(2, "Hand to hand"),
    ELECTRONICS(3, "Electronics"), NIGHT_OPERATIONS(4, "Night operations"), THROWING(5, "Throwing"),
    TEACHING(6, "Teaching"), HEAVY_WEAPONS(7, "Heavy weapons"), AUTOMATIC_WEAPONS(8, "Automatic weapons"),
    STEALTH(9, "Stealth"), AMBIDEXTROUS(10, "Ambidextrous"), THIEF(11, "Thief"),
    MARTIAL_ARTS(12, "Martial arts"), KNIFING(13, "Knifing"), ON_ROOF(14, "On roof"),
    CAMOUFLAGED(15, "Camouflaged"),
}

enum class PersonalityTrait(val id: Int, val label: String) {
    NONE(0, "None"), HEAT_INTOLERANT(1, "Heat intolerant"), NERVOUS(2, "Nervous"),
    CLAUSTROPHOBIC(3, "Claustrophobic"), NONSWIMMER(4, "Nonswimmer"),
    FEAR_OF_INSECTS(5, "Fear of insects"), FORGETFUL(6, "Forgetful"), PSYCHO(7, "Psycho"),
}

enum class Attitude(val id: Int, val label: String) {
    NORMAL(0, "Normal"), FRIENDLY(1, "Friendly"), LONER(2, "Loner"), OPTIMIST(3, "Optimist"),
    PESSIMIST(4, "Pessimist"), AGGRESSIVE(5, "Aggressive"), ARROGANT(6, "Arrogant"),
    BIG_SHOT(7, "Big shot"), ASSHOLE(8, "Asshole"), COWARD(9, "Coward"),
}

/** Signed byte domains remain bounded; unfamiliar IDs do not invalidate a read-only inspection. */
data class SkillTraitId(val raw: Int) {
    init { require(raw in -128..127) }
    val known: SkillTrait? get() = SkillTrait.entries.find { it.id == raw }
    val label: String get() = known?.label ?: "Unknown ($raw)"
}
data class PersonalityTraitId(val raw: Int) {
    init { require(raw in -128..127) }
    val known: PersonalityTrait? get() = PersonalityTrait.entries.find { it.id == raw }
    val label: String get() = known?.label ?: "Unknown ($raw)"
}
data class AttitudeId(val raw: Int) {
    init { require(raw in -128..127) }
    val known: Attitude? get() = Attitude.entries.find { it.id == raw }
    val label: String get() = known?.label ?: "Unknown ($raw)"
}

data class ProfileCareerRecord(
    val kills: Int = 0,
    val assists: Int = 0,
    val shotsFired: Int = 0,
    val shotsHit: Int = 0,
    val battlesFought: Int = 0,
    val timesWounded: Int = 0,
    val totalDaysServed: Int = 0,
    val totalCostPaid: Long = 0,
) {
    init {
        require(listOf(kills, assists, shotsFired, shotsHit, battlesFought, timesWounded, totalDaysServed)
            .all { it in 0..65535 })
        require(totalCostPaid in 0..0xffff_ffffL)
    }
}

/** -1 is absent. Other negative bytes are unknown, never reinterpreted as unsigned profile IDs. */
data class ProfileRelationshipId(val raw: Int) {
    init { require(raw in -128..127) }
    val profileId: Int? get() = raw.takeIf { it >= 0 }
    val isAbsent: Boolean get() = raw == -1
}

/** Only upstream's three meaningful slots; slot 2 is an established learned relationship. */
data class ProfileRelationships(
    val friend1: ProfileRelationshipId = ProfileRelationshipId(-1),
    val friend2: ProfileRelationshipId = ProfileRelationshipId(-1),
    val learnedFriend: ProfileRelationshipId = ProfileRelationshipId(-1),
    val enemy1: ProfileRelationshipId = ProfileRelationshipId(-1),
    val enemy2: ProfileRelationshipId = ProfileRelationshipId(-1),
    val learnedEnemy: ProfileRelationshipId = ProfileRelationshipId(-1),
)
