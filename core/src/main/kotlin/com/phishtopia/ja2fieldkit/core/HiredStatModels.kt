package com.phishtopia.ja2fieldkit.core

import com.phishtopia.ja2fieldkit.core.model.*

// Exhaustive model projections; equality then checks every non-target fact.
internal fun MercProfile.withStat(stat: HiredMercStat, value: Int): MercProfile = when (stat) {
    HiredMercStat.AGILITY -> copy(agility = value)
    HiredMercStat.DEXTERITY -> copy(dexterity = value)
    HiredMercStat.STRENGTH -> copy(strength = value)
    HiredMercStat.LEADERSHIP -> copy(leadership = value)
    HiredMercStat.WISDOM -> copy(wisdom = value)
    HiredMercStat.EXPERIENCE_LEVEL -> copy(experienceLevel = value)
    HiredMercStat.MARKSMANSHIP -> copy(marksmanship = value)
    HiredMercStat.MECHANICAL -> copy(mechanical = value)
    HiredMercStat.EXPLOSIVES -> copy(explosives = value)
    HiredMercStat.MEDICAL -> copy(medical = value)
}

internal fun MercStats.withStat(stat: HiredMercStat, value: Int): MercStats = when (stat) {
    HiredMercStat.AGILITY -> copy(agility = value)
    HiredMercStat.DEXTERITY -> copy(dexterity = value)
    HiredMercStat.STRENGTH -> copy(strength = value)
    HiredMercStat.LEADERSHIP -> copy(leadership = value)
    HiredMercStat.WISDOM -> copy(wisdom = value)
    HiredMercStat.EXPERIENCE_LEVEL -> copy(experienceLevel = value)
    HiredMercStat.MARKSMANSHIP -> copy(marksmanship = value)
    HiredMercStat.MECHANICAL -> copy(mechanical = value)
    HiredMercStat.EXPLOSIVES -> copy(explosives = value)
    HiredMercStat.MEDICAL -> copy(medical = value)
}

internal fun LiveMercStats.withStat(stat: HiredMercStat, value: Int): LiveMercStats = when (stat) {
    HiredMercStat.AGILITY -> copy(agility = value)
    HiredMercStat.DEXTERITY -> copy(dexterity = value)
    HiredMercStat.STRENGTH -> copy(strength = value)
    HiredMercStat.LEADERSHIP -> copy(leadership = value)
    HiredMercStat.WISDOM -> copy(wisdom = value)
    HiredMercStat.EXPERIENCE_LEVEL -> copy(experienceLevel = value)
    HiredMercStat.MARKSMANSHIP -> copy(marksmanship = value)
    HiredMercStat.MECHANICAL -> copy(mechanical = value)
    HiredMercStat.EXPLOSIVES -> copy(explosives = value)
    HiredMercStat.MEDICAL -> copy(medical = value)
}
