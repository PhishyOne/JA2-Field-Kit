package com.phishtopia.ja2fieldkit.android.presentation

import com.phishtopia.ja2fieldkit.core.model.MercProfile
import com.phishtopia.ja2fieldkit.core.model.MercRosterEntry
import com.phishtopia.ja2fieldkit.core.model.ProfileRelationshipId
import com.phishtopia.ja2fieldkit.core.model.StandardProfileCategory
import java.util.Collections
import java.util.Locale

/** Retained dossier state contains display facts only, and no source snapshot or parser objects. */
data class PersonnelPresentation(
    val profileId: Int,
    val name: String,
    val currentSquad: Boolean,
    val standardCategory: String,
    val economics: List<StatPresentation>,
    val attributes: List<StatPresentation>,
    val traits: List<StatPresentation>,
    val personality: List<StatPresentation>,
    val record: List<StatPresentation>,
    val relationships: List<StatPresentation>,
) {
    val listLabel: String get() = "$name · Profile #$profileId" + if (currentSquad) " · Current squad" else ""
}

object PersonnelPresentationMapper {
    /** Null means identity coherence failed; no partial database or guessed squad membership. */
    fun map(profiles: List<MercProfile>, roster: List<MercRosterEntry>): List<PersonnelPresentation>? {
        val ids = profiles.map { it.profileId }
        val rosterIds = roster.map { it.profileIndex }
        if (ids.any { it !in 0..169 } || ids.size != ids.toSet().size ||
            rosterIds.size != rosterIds.toSet().size || rosterIds.any { it !in ids }
        ) return null
        val squad = rosterIds.toSet()
        val names = profiles.associate { it.profileId to displayName(it) }
        return immutable(profiles.mapNotNull { profile ->
            val name = names[profile.profileId] ?: return@mapNotNull null
            val career = profile.career
            val relationships = profile.relationships
            PersonnelPresentation(
                profileId = profile.profileId,
                name = name,
                currentSquad = profile.profileId in squad,
                standardCategory = categoryLabel(StandardProfileCategory.fromProfileId(profile.profileId)),
                economics = economics(profile),
                attributes = immutable(listOf(
                    StatPresentation("Health (profile current / max)", "${profile.life} / ${profile.lifeMax}"),
                    StatPresentation("Agility", profile.agility.toString()),
                    StatPresentation("Dexterity", profile.dexterity.toString()),
                    StatPresentation("Strength", profile.strength.toString()),
                    StatPresentation("Leadership", profile.leadership.toString()),
                    StatPresentation("Wisdom", profile.wisdom.toString()),
                    StatPresentation("Experience", profile.experienceLevel.toString()),
                    StatPresentation("Marksmanship", profile.marksmanship.toString()),
                    StatPresentation("Explosives", profile.explosives.toString()),
                    StatPresentation("Mechanical", profile.mechanical.toString()),
                    StatPresentation("Medical", profile.medical.toString()),
                )),
                traits = immutable(listOf(
                    StatPresentation("Skill trait 1", profile.skillTrait1.label),
                    StatPresentation("Skill trait 2", profile.skillTrait2.label),
                )),
                personality = immutable(listOf(
                    StatPresentation("Personality trait", profile.personalityTrait.label),
                    StatPresentation("Attitude", profile.attitude.label),
                )),
                record = immutable(buildList {
                    add(StatPresentation("Kills", career.kills.toString()))
                    add(StatPresentation("Assists", career.assists.toString()))
                    add(StatPresentation("Shots fired", career.shotsFired.toString()))
                    add(StatPresentation("Shots hit", career.shotsHit.toString()))
                    add(StatPresentation("Battles fought", career.battlesFought.toString()))
                    add(StatPresentation("Times wounded", career.timesWounded.toString()))
                    add(StatPresentation("Total days served", career.totalDaysServed.toString()))
                    add(StatPresentation("Total cost paid (salary)", career.totalCostPaid.toString()))
                    if (career.shotsFired > 0) add(StatPresentation("Shooting accuracy (derived)",
                        String.format(Locale.ROOT, "%.1f%%", career.shotsHit.toDouble() / career.shotsFired * 100)))
                }),
                relationships = immutable(listOf(
                    relation("Friend 1", relationships.friend1, names),
                    relation("Friend 2", relationships.friend2, names),
                    relation("Learned friend", relationships.learnedFriend, names),
                    relation("Enemy 1", relationships.enemy1, names),
                    relation("Enemy 2", relationships.enemy2, names),
                    relation("Learned enemy", relationships.learnedEnemy, names),
                )),
            )
        })
    }

    private fun categoryLabel(category: StandardProfileCategory): String = when (category) {
        StandardProfileCategory.AIM -> "A.I.M. profile"
        StandardProfileCategory.MERC -> "M.E.R.C. profile"
        StandardProfileCategory.IMP -> "I.M.P. profile"
        StandardProfileCategory.RPC -> "RPC profile"
        StandardProfileCategory.NPC -> "NPC profile"
        StandardProfileCategory.VEHICLE -> "Vehicle profile"
        StandardProfileCategory.NOT_USED -> "Reserved profile"
    }

    private fun economics(profile: MercProfile): List<StatPresentation> {
        val category = StandardProfileCategory.fromProfileId(profile.profileId)
        if (category != StandardProfileCategory.AIM && category != StandardProfileCategory.MERC) return emptyList()
        val facts = profile.economics
        return immutable(buildList {
            add(StatPresentation("Recorded profile status", recordedStatus(facts.mercStatus, category)))
            add(StatPresentation("Availability delay counter (days, profile)", facts.availabilityDelayCounter.toString()))
            add(StatPresentation("Daily salary (profile)", nonnegativeOrRaw(facts.dailySalary)))
            if (category == StandardProfileCategory.AIM) {
                add(StatPresentation("Weekly salary (7-day package)", facts.weeklySalary.toString()))
                add(StatPresentation("Two-week salary (14-day package)", facts.biWeeklySalary.toString()))
                add(StatPresentation("Medical deposit required", when (facts.medicalDeposit) {
                    0 -> "No"
                    1 -> "Yes"
                    else -> "Unknown (raw: ${facts.medicalDeposit})"
                }))
                add(StatPresentation("Medical deposit amount (profile)", facts.medicalDepositAmount.toString()))
                add(StatPresentation("Optional gear cost (profile)", facts.optionalGearCost.toString()))
            } else {
                add(StatPresentation("M.E.R.C. billing days since payment (profile)", nonnegativeOrRaw(facts.mercBillingDays)))
            }
        })
    }

    private fun nonnegativeOrRaw(value: Int): String =
        if (value < 0) "Uninterpreted (raw: $value)" else value.toString()

    private fun recordedStatus(raw: Int, category: StandardProfileCategory): String = when (raw) {
        0 -> "Ready status recorded"
        -1 -> "Missing dialogue text"
        -2 -> if (category == StandardProfileCategory.AIM) "Annoyed; contact still permitted" else "Unknown (raw: $raw)"
        -3 -> if (category == StandardProfileCategory.AIM) "Annoyed; contact refused" else "Unknown (raw: $raw)"
        -4 -> "Hired, awaiting arrival status"
        -5 -> "Dead status"
        -6 -> "Returning home status"
        -7 -> "Working elsewhere status"
        -8 -> "Fired while POW"
        in 1..127 -> "Positive profile status ($raw)"
        else -> "Unknown (raw: $raw)"
    }

    private fun displayName(profile: MercProfile): String? {
        val name = PresentationTextSanitizer.sanitize(profile.name).trim()
        val nickname = PresentationTextSanitizer.sanitize(profile.nickname).trim()
        return when {
            name.isBlank() -> nickname.takeIf { it.isNotBlank() }
            nickname.isBlank() || name == nickname -> name
            else -> "$name ($nickname)"
        }
    }

    private fun relation(label: String, target: ProfileRelationshipId, names: Map<Int, String?>): StatPresentation {
        val id = target.profileId
        return StatPresentation(label, when {
            target.isAbsent -> "None"
            id == null || id !in names -> "Unknown (${target.raw})"
            else -> names[id]?.let { "$it · Profile #$id" } ?: "Profile #$id"
        })
    }

    private fun <T> immutable(items: List<T>): List<T> = Collections.unmodifiableList(ArrayList(items))
}

fun InspectionScreenState.openPersonnel(profileId: Int? = null): InspectionScreenState {
    if (this !is InspectionScreenState.Success) return this
    if (profileId != null && personnel.none { it.profileId == profileId }) return this
    return copy(personnelVisible = true, dossierProfileId = profileId)
}

fun InspectionScreenState.closePersonnel(): InspectionScreenState {
    if (this !is InspectionScreenState.Success || !personnelVisible) return this
    return if (dossierProfileId != null) copy(dossierProfileId = null)
        else copy(personnelVisible = false)
}
