package com.phishtopia.ja2fieldkit.core.model

/** Raw serialized facts, not live hiring, employment, or contract authority. */
data class ProfileEconomicsFacts(
    val availabilityDelayCounter: Long = 0,
    val dailySalary: Int = 0,
    val weeklySalary: Long = 0,
    val biWeeklySalary: Long = 0,
    val medicalDeposit: Int = 0,
    val medicalDepositAmount: Int = 0,
    val optionalGearCost: Int = 0,
    val mercStatus: Int = 0,
    val mercBillingDays: Int = 0,
)

/**
 * External default metadata at JA2 Reborn 743f38a, never a serialized save field.
 * Mods/external metadata may override these categories outside the pinned default content.
 */
enum class StandardProfileCategory {
    AIM, MERC, IMP, RPC, NPC, VEHICLE, NOT_USED;

    companion object {
        fun fromProfileId(profileId: Int): StandardProfileCategory = when (profileId) {
            in 0..39 -> AIM
            in 40..50 -> MERC
            in 51..56 -> IMP
            in 57..74 -> RPC
            in 75..159 -> NPC
            in 160..163 -> VEHICLE
            in 164..169 -> NOT_USED
            else -> throw IllegalArgumentException("Profile ID outside standard metadata range")
        }
    }
}
