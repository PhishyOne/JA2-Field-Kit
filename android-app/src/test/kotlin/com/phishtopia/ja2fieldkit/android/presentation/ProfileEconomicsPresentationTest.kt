package com.phishtopia.ja2fieldkit.android.presentation

import com.phishtopia.ja2fieldkit.core.model.*
import kotlin.test.*

class ProfileEconomicsPresentationTest {
    private fun map(id: Int, facts: ProfileEconomicsFacts = ProfileEconomicsFacts(), squad: Boolean = false): PersonnelPresentation {
        val roster = if (squad) listOf(MercRosterEntry(id, "Name", null, MercStats(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1))) else emptyList()
        return assertNotNull(PersonnelPresentationMapper.map(listOf(personnelProfile(id).copy(economics = facts)), roster)).single()
    }

    @Test fun allCategoriesAndBoundariesHaveExactRowSetsAndRosterAloneControlsSquadMarker() {
        val common = listOf("Recorded profile status", "Availability delay counter (days, profile)", "Daily salary (profile)")
        val aim = common + listOf("Weekly salary (7-day package)", "Two-week salary (14-day package)",
            "Medical deposit required", "Medical deposit amount (profile)", "Optional gear cost (profile)")
        val merc = common + "M.E.R.C. billing days since payment (profile)"
        for (id in 0..169) {
            val label = when (id) {
                in 0..39 -> "A.I.M. profile"
                in 40..50 -> "M.E.R.C. profile"
                in 51..56 -> "I.M.P. profile"
                in 57..74 -> "RPC profile"
                in 75..159 -> "NPC profile"
                in 160..163 -> "Vehicle profile"
                else -> "Reserved profile"
            }
            for (squad in listOf(false, true)) {
                val p = map(id, ProfileEconomicsFacts(mercStatus = -5, dailySalary = -1, mercBillingDays = -1), squad)
                assertEquals(label, p.standardCategory)
                assertEquals(when (id) { in 0..39 -> aim; in 40..50 -> merc; else -> emptyList() }, p.economics.map { it.label })
                assertEquals(squad, p.currentSquad)
                assertEquals(squad, p.listLabel.contains("Current squad"))
                assertEquals(1, p.record.count { it.label.startsWith("Total cost paid") })
                assertFalse(p.economics.any { it.label.contains("Total cost") })
            }
        }
        assertFailsWith<UnsupportedOperationException> { (map(0).economics as MutableList).clear() }
    }

    @Test fun everySignedStatusHasExactContextAwareDisplayWithoutContractInference() {
        val known = mapOf(0 to "Ready status recorded", -1 to "Missing dialogue text",
            -2 to "Annoyed; contact still permitted", -3 to "Annoyed; contact refused",
            -4 to "Hired, awaiting arrival status", -5 to "Dead status", -6 to "Returning home status",
            -7 to "Working elsewhere status", -8 to "Fired while POW")
        for (id in listOf(0, 40)) for (raw in -128..127) {
            val expected = when {
                raw > 0 -> "Positive profile status ($raw)"
                id == 40 && raw in -3..-2 -> "Unknown (raw: $raw)"
                else -> known[raw] ?: "Unknown (raw: $raw)"
            }
            for (squad in listOf(false, true)) {
                val p = map(id, ProfileEconomicsFacts(mercStatus = raw), squad)
                assertEquals(expected, p.economics.first().value)
                assertEquals(squad, p.currentSquad)
            }
        }
    }

    @Test fun rawNegativeValuesDepositUnknownsUnsignedMaximaAndZeroCounterStayLiteral() {
        val facts = ProfileEconomicsFacts(0xffff_ffffL, -32768, 0xffff_ffffL, 0x8000_0000L,
            1, 65535, 32768, 0, Int.MIN_VALUE)
        assertEquals(listOf("Ready status recorded", "4294967295", "Uninterpreted (raw: -32768)",
            "4294967295", "2147483648", "Yes", "65535", "32768"), map(0, facts).economics.map { it.value })
        assertEquals("Uninterpreted (raw: -2147483648)", map(40, facts).economics.last().value)
        for (raw in -128..127) {
            assertEquals(when (raw) { 0 -> "No"; 1 -> "Yes"; else -> "Unknown (raw: $raw)" },
                map(0, facts.copy(medicalDeposit = raw)).economics[5].value)
        }
        for (value in listOf(-1, 0, 32767)) {
            val expected = if (value < 0) "Uninterpreted (raw: $value)" else value.toString()
            assertEquals(expected, map(0, facts.copy(dailySalary = value)).economics[2].value)
            assertEquals(expected, map(40, facts.copy(mercBillingDays = value)).economics.last().value)
        }
        assertEquals("0", map(0).economics[1].value)
        assertFalse(map(0).economics.toString().contains("available", ignoreCase = true))
    }
}
