package com.phishtopia.ja2fieldkit.core.model

import com.phishtopia.ja2fieldkit.core.format.SaveFormatDetection

data class CampaignSummary(
    val day: Int?,
    val clockMinutes: Int?,
    val sector: String?,
    val playerMercCount: Int?,
    val balance: Long?,
)

data class MercStats(
    val health: Int?,
    val agility: Int?,
    val dexterity: Int?,
    val strength: Int?,
    val leadership: Int?,
    val wisdom: Int?,
    val experienceLevel: Int?,
    val marksmanship: Int?,
    val mechanical: Int?,
    val explosives: Int?,
    val medical: Int?,
)

data class MercRosterEntry(
    val profileIndex: Int,
    val name: String,
    val nickname: String?,
    val stats: MercStats,
)

data class SaveInspection(
    val format: SaveFormatDetection,
    val campaign: CampaignSummary?,
    val roster: List<MercRosterEntry>,
)
