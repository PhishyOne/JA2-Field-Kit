package com.phishtopia.ja2fieldkit.android.presentation

import java.util.Collections

/** Matches retained standard-category labels, never infers categories from IDs or save data. */
enum class PersonnelCategoryFilter(val label: String, val presentationLabel: String?) {
    ALL("All", null), AIM("A.I.M.", "A.I.M. profile"), MERC("M.E.R.C.", "M.E.R.C. profile"),
    IMP("I.M.P.", "I.M.P. profile"), RPC("RPC", "RPC profile"), NPC("NPC", "NPC profile"),
    VEHICLE("Vehicle", "Vehicle profile"), RESERVED("Reserved", "Reserved profile"),
}

data class PersonnelFilter(
    val query: String = "",
    val category: PersonnelCategoryFilter = PersonnelCategoryFilter.ALL,
    val currentSquadOnly: Boolean = false,
) {
    init { require(query == safeQuery(query)) }

    fun results(source: List<PersonnelPresentation>): List<PersonnelPresentation> {
        val text = query.trim()
        return Collections.unmodifiableList(source.filter { person ->
            (text.isBlank() || person.name.contains(text, ignoreCase = true) || person.profileId.toString() == text) &&
                (category.presentationLabel == null || person.standardCategory == category.presentationLabel) &&
                (!currentSquadOnly || person.currentSquad)
        })
    }

    companion object {
        const val MAX_QUERY_CODE_POINTS = 120
        fun safeQuery(value: String): String {
            val end = value.offsetByCodePoints(0, minOf(MAX_QUERY_CODE_POINTS, value.codePointCount(0, value.length)))
            return PresentationTextSanitizer.sanitize(value.substring(0, end))
        }
    }
}

fun InspectionScreenState.withPersonnelQuery(query: String): InspectionScreenState =
    updatePersonnelFilter { copy(query = PersonnelFilter.safeQuery(query)) }

fun InspectionScreenState.withPersonnelCategory(category: PersonnelCategoryFilter): InspectionScreenState =
    updatePersonnelFilter { copy(category = category) }

fun InspectionScreenState.withPersonnelCurrentSquadOnly(enabled: Boolean): InspectionScreenState =
    updatePersonnelFilter { copy(currentSquadOnly = enabled) }

fun InspectionScreenState.clearPersonnelFilters(): InspectionScreenState =
    updatePersonnelFilter { PersonnelFilter() }

private inline fun InspectionScreenState.updatePersonnelFilter(
    update: PersonnelFilter.() -> PersonnelFilter,
): InspectionScreenState {
    if (this !is InspectionScreenState.Success) return this
    val next = personnelFilter.update()
    return if (next == personnelFilter) this else copy(personnelFilter = next)
}
