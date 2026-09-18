package com.phishtopia.ja2fieldkit.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.phishtopia.ja2fieldkit.android.importing.SourceMetadata
import com.phishtopia.ja2fieldkit.android.importing.SourceProvenance
import com.phishtopia.ja2fieldkit.android.presentation.CampaignPresentation
import com.phishtopia.ja2fieldkit.android.presentation.FormatPresentation
import com.phishtopia.ja2fieldkit.android.presentation.InspectionScreenState
import com.phishtopia.ja2fieldkit.android.presentation.MercPresentation
import com.phishtopia.ja2fieldkit.android.presentation.SourceFailureKind

class MainActivity : ComponentActivity() {
    private val model: InspectionViewModel by viewModels()
    private val stateObserver: (InspectionScreenState) -> Unit = ::render

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.inspect(applicationContext.contentResolver, uri, SourceProvenance.DOCUMENT_PICKER)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model.attach(stateObserver)
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        model.detach(stateObserver)
        super.onDestroy()
    }

    private fun handleIntent(incoming: Intent?) {
        when (incoming?.action) {
            Intent.ACTION_VIEW -> {
                val uri = incoming.data
                if (uri == null) model.showSourceFailure(null, SourceFailureKind.UNAVAILABLE)
                else model.inspect(applicationContext.contentResolver, uri, SourceProvenance.OPEN_WITH)
            }

            Intent.ACTION_SEND -> {
                val clipData = incoming.clipData
                if (clipData != null && clipData.itemCount > 1) {
                    model.showSourceFailure(null, SourceFailureKind.MULTIPLE_ITEMS)
                    return
                }
                val uri = clipData?.getItemAt(0)?.uri ?: incoming.sharedStreamUri()
                if (uri == null) model.showSourceFailure(null, SourceFailureKind.UNAVAILABLE)
                else model.inspect(applicationContext.contentResolver, uri, SourceProvenance.SHARE_TO)
            }
        }
    }

    private fun render(screenState: InspectionScreenState) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
            addTitle(getString(R.string.app_name))
            addBody(getString(R.string.read_only_notice))
        }

        when (screenState) {
            InspectionScreenState.Initial -> {
                content.addBody(getString(R.string.no_save_selected))
                content.addOpenButton(R.string.open_save)
            }

            is InspectionScreenState.Loading -> {
                content.addHeading(getString(R.string.loading_save))
                screenState.sourceName?.let { content.addLabelValue("Source", it) }
            }

            is InspectionScreenState.Success -> {
                content.addHeading("Inspection complete")
                content.addSource(screenState.source)
                content.addFormat(screenState.format)
                content.addCampaign(screenState.campaign)
                content.addHeading("Roster")
                if (screenState.roster.isEmpty()) content.addBody("No roster members found.")
                else screenState.roster.forEach(content::addMerc)
                content.addOpenButton(R.string.open_another_save)
            }

            is InspectionScreenState.Failure -> {
                content.addHeading(screenState.title)
                screenState.source?.let(content::addSource)
                content.addLabelValue("Failure kind", screenState.failureKind)
                content.addLabelValue("Diagnostic", screenState.diagnostic)
                screenState.format?.let(content::addFormat)
                content.addBody("No save data was changed.")
                content.addOpenButton(R.string.open_another_save)
            }
        }

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun LinearLayout.addSource(source: SourceMetadata) {
        addHeading("Source")
        addLabelValue("Filename", source.displayName)
        source.declaredSizeBytes?.let { addLabelValue("Provider size", "$it bytes") }
        addLabelValue(
            "Opened via",
            when (source.provenance) {
                SourceProvenance.DOCUMENT_PICKER -> "Document picker"
                SourceProvenance.OPEN_WITH -> "Open with"
                SourceProvenance.SHARE_TO -> "Share to"
            },
        )
    }

    private fun LinearLayout.addFormat(format: FormatPresentation) {
        addHeading("Format")
        addLabelValue("Save version", format.version)
        addLabelValue("Build", format.build)
        addLabelValue("Layout", format.layout)
        addLabelValue("Compatibility", format.compatibility)
        addLabelValue("Producer", format.producer)
    }

    private fun LinearLayout.addCampaign(campaign: CampaignPresentation) {
        addHeading("Campaign")
        addLabelValue("Time", campaign.dayAndTime)
        addLabelValue("Sector", campaign.sector)
        addLabelValue("Roster", campaign.rosterCount)
        addLabelValue("Balance", campaign.balance)
    }

    private fun LinearLayout.addMerc(merc: MercPresentation) {
        val displayName = merc.nickname
            ?.takeIf { it.isNotBlank() }
            ?.let { "${merc.name} ($it)" }
            ?: merc.name
        addHeading(displayName, 19f)
        addBody(merc.stats.joinToString("  ·  ") { "${it.label}: ${it.value}" })
    }

    private fun LinearLayout.addTitle(text: String) {
        addView(textView(text, 28f).apply { setPadding(0, 0, 0, dp(8)) })
    }

    private fun LinearLayout.addHeading(text: String, size: Float = 22f) {
        addView(textView(text, size).apply {
            setPadding(0, dp(20), 0, dp(6))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isAccessibilityHeading = true
        })
    }

    private fun LinearLayout.addBody(text: String) {
        addView(textView(text, 16f).apply { setPadding(0, dp(4), 0, dp(8)) })
    }

    private fun LinearLayout.addLabelValue(label: String, value: String) {
        addBody("$label: $value")
    }

    private fun LinearLayout.addOpenButton(label: Int) {
        addView(Button(this@MainActivity).apply {
            setText(label)
            setOnClickListener { openDocument.launch(arrayOf("*/*")) }
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun textView(text: String, size: Float): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextIsSelectable(true)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

@Suppress("DEPRECATION")
private fun Intent.sharedStreamUri(): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    }
