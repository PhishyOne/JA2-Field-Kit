package com.phishtopia.ja2fieldkit.android.editing

import com.phishtopia.ja2fieldkit.core.HiredMercStat
import com.phishtopia.ja2fieldkit.core.Ja2SaveEditor
import com.phishtopia.ja2fieldkit.core.Ja2SaveInspector
import com.phishtopia.ja2fieldkit.core.SaveEditRequest
import com.phishtopia.ja2fieldkit.core.SaveEditResult
import com.phishtopia.ja2fieldkit.core.format.SaveLayout
import com.phishtopia.ja2fieldkit.core.model.*

internal sealed interface EditChoice {
    data class Stat(val stat: HiredMercStat, val value: Int) : EditChoice
    data class Inventory(val slot: Int, val item: SimpleItem, val condition: Int) : EditChoice
}
internal enum class SimpleItem(val itemId: Int, val label: String) {
    CLEAR(0, "Clear"), FIRST_AID(201, "First-aid kit"), MEDICAL(202, "Medical kit"), TOOLKIT(203, "Toolkit"),
}
internal data class EditMerc(val profileId: Int, val stats: Map<HiredMercStat, Int>,
    val slots: Map<Int, SaveEditRequest.ExpectedSlot>)

/** One retained raw snapshot; immutable to callers, zeroed when replaced/closed. No persistence. */
internal class EditSession(source: ByteArray, inspector: Ja2SaveInspector = Ja2SaveInspector(),
    private val editor: Ja2SaveEditor = Ja2SaveEditor.forAndroidCreateNewTesting()) {
    private var snapshot: ByteArray? = source.also { require(it.size <= Ja2SaveEditor.MAX_SAVE_BYTES) }.copyOf()
    private var used = false
    private var mercs: Map<Int, EditMerc> = try { parse(checkNotNull(snapshot), inspector) }
        catch (failure: RuntimeException) { snapshot?.fill(0); snapshot = null; throw failure }

    @Synchronized fun merc(profileId: Int): EditMerc? = if (snapshot == null || used) null else mercs[profileId]

    @Synchronized fun generate(profileId: Int, choice: EditChoice): SaveEditResult {
        val bytes = snapshot ?: return refused()
        val merc = merc(profileId) ?: return refused()
        val operation = operation(merc, choice) ?: return refused()
        // Identity comes only from our private snapshot; no UI hash/precondition parameter exists.
        val request = SaveEditRequest(SaveEditRequest.SourceIdentity(bytes.size,
            bytes.inputStream().use { hashExact(it, bytes.size) }), operation)
        return editor.edit(bytes, request)
    }

    @Synchronized fun consume() { used = true }
    @Synchronized fun clear() { snapshot?.fill(0); snapshot = null; used = true; mercs = emptyMap() }

    private fun parse(bytes: ByteArray, inspector: Ja2SaveInspector): Map<Int, EditMerc> {
        val parsed = inspector.inspectV01(bytes) as? SaveInspectionV01Result.Success ?: return emptyMap()
        if (parsed.format.layout != SaveLayout.NORMAL_V103_BUILD_041202_NON_LINUX ||
            parsed.format.saveVersion != 103 || parsed.format.buildLabel != "04.12.02") return emptyMap()
        val live = inspector.inspectLiveMercState(bytes) as? LiveMercStateInspectionResult.Success ?: return emptyMap()
        val profiles = inspector.parseBuild041202NormalNonLinuxProfiles(bytes)
        return java.util.Collections.unmodifiableMap(parsed.roster.mapNotNull { roster ->
            if (parsed.roster.count { it.profileIndex == roster.profileIndex } != 1) return@mapNotNull null
            val current = live.mercs.singleOrNull { it.profileIndex == roster.profileIndex } ?: return@mapNotNull null
            val profile = profiles.singleOrNull { it.profileId == roster.profileIndex } ?: return@mapNotNull null
            val stats = HiredMercStat.entries.mapNotNull { stat ->
                val (base, value) = values(stat, roster.stats, current.stats)
                if (base == value && stat.contains(value)) stat to value else null
            }.toMap()
            // Copy the complete profile assertion. Core must still match BOTH serialized sides
            // and admit the complete canonical object; this projection grants no write authority.
            val expected = (7..10).mapNotNull { index ->
                profile.inventory.getOrNull(index)?.let { slot ->
                    index to SaveEditRequest.ExpectedSlot(slot.itemId, slot.count, slot.status)
                }
            }.toMap()
            roster.profileIndex to EditMerc(roster.profileIndex, java.util.Collections.unmodifiableMap(stats), java.util.Collections.unmodifiableMap(expected))
        }.toMap())
    }

    companion object {
        fun available(debug: Boolean, api: Int): Boolean = debug && api >= 29
        fun operation(merc: EditMerc, choice: EditChoice): SaveEditRequest.Operation? = when (choice) {
            is EditChoice.Stat -> merc.stats[choice.stat]?.takeIf { choice.stat.contains(choice.value) }?.let {
                SaveEditRequest.SetHiredStat(merc.profileId, choice.stat, it, choice.value)
            }
            is EditChoice.Inventory -> merc.slots[choice.slot]?.takeIf {
                choice.slot in 7..10 && (choice.item == SimpleItem.CLEAR || choice.condition in 1..100)
            }?.let {
                if (choice.item == SimpleItem.CLEAR) SaveEditRequest.ClearSlot(merc.profileId, choice.slot, it)
                else SaveEditRequest.SetSimpleItem(merc.profileId, choice.slot, it, choice.item.itemId, 1, choice.condition)
            }
        }
        private fun refused() = SaveEditResult.Failure(SaveEditResult.Stage.PRECONDITION,
            SaveEditResult.Reason.INVALID_REQUEST)
        private fun values(stat: HiredMercStat, base: MercStats, live: LiveMercStats): Pair<Int?, Int> = when (stat) {
            HiredMercStat.AGILITY -> base.agility to live.agility
            HiredMercStat.DEXTERITY -> base.dexterity to live.dexterity
            HiredMercStat.STRENGTH -> base.strength to live.strength
            HiredMercStat.LEADERSHIP -> base.leadership to live.leadership
            HiredMercStat.WISDOM -> base.wisdom to live.wisdom
            HiredMercStat.EXPERIENCE_LEVEL -> base.experienceLevel to live.experienceLevel
            HiredMercStat.MARKSMANSHIP -> base.marksmanship to live.marksmanship
            HiredMercStat.MECHANICAL -> base.mechanical to live.mechanical
            HiredMercStat.EXPLOSIVES -> base.explosives to live.explosives
            HiredMercStat.MEDICAL -> base.medical to live.medical
        }
    }
}
