package com.phishtopia.ja2fieldkit.android

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.*
import com.phishtopia.ja2fieldkit.android.editing.*

/** Desired values only cross this boundary; current-value assertions are owned by EditSession. */
internal fun showEditDialog(context: Context, merc: EditMerc, submit: (EditChoice) -> Unit) {
    val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24, 12, 24, 12)
        isSaveEnabled = false
    }
    fun label(text: String) = TextView(context).also { it.text = text; panel.addView(it) }
    fun selector(description: String, options: List<String>) = Spinner(context).also {
        it.isSaveEnabled = false
        it.contentDescription = description
        it.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, options)
        panel.addView(it)
    }
    label("Debug test editor · One edit per new save. Original untouched.\nReopen the generated save for another edit.")
    val mode = selector("Edit kind", listOf("Stat", "Inventory"))
    val stats = merc.stats.keys.toList()
    val stat = selector("Stat and current value", stats.map {
        "${it.name.lowercase().replace('_', ' ')}: ${merc.stats[it]} (${it.minimum()}..${it.maximum()})"
    })
    val value = EditText(context).apply {
        isSaveEnabled = false
        inputType = InputType.TYPE_CLASS_NUMBER
        hint = "Desired stat value"
    }.also { panel.addView(it) }
    val slot = selector("Profile inventory slot and current state", (7..10).map { index ->
        val current = merc.slots[index]
        if (current == null) "Profile slot $index: unavailable"
        else "Profile slot $index: item ${current.itemId()}, count ${current.count()}, condition ${current.status()}"
    })
    val item = selector("Inventory operation", SimpleItem.entries.map { it.label })
    val condition = EditText(context).apply {
        isSaveEnabled = false
        inputType = InputType.TYPE_CLASS_NUMBER
        hint = "Condition 1..100 (ignored for Clear)"
    }.also { panel.addView(it) }
    val error = label("Profile inventory shown. Live inventory must agree for editing. Only plain admitted slots can change; core verification may refuse this edit.")
    mode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            listOf(stat, value).forEach { it.visibility = if (position == 0) View.VISIBLE else View.GONE }
            listOf(slot, item, condition).forEach { it.visibility = if (position == 1) View.VISIBLE else View.GONE }
        }
    }
    val dialog = AlertDialog.Builder(context).setTitle("Create NEW .sav")
        .setView(ScrollView(context).apply { addView(panel) })
        .setNegativeButton("Cancel", null).setPositiveButton("Verify & export new save", null).create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val choice = if (mode.selectedItemPosition == 0) {
                val selected = stats.getOrNull(stat.selectedItemPosition)
                val desired = value.text.toString().toIntOrNull()
                if (selected != null && desired != null && selected.contains(desired)) EditChoice.Stat(selected, desired) else null
            } else {
                val selected = SimpleItem.entries[item.selectedItemPosition]
                val desired = if (selected == SimpleItem.CLEAR) 1 else condition.text.toString().toIntOrNull()
                if (desired != null && desired in 1..100) EditChoice.Inventory(slot.selectedItemPosition + 7, selected, desired) else null
            }
            if (choice == null) error.text = "Enter a value within the displayed range."
            else { dialog.dismiss(); submit(choice) }
        }
    }
    dialog.show()
}
