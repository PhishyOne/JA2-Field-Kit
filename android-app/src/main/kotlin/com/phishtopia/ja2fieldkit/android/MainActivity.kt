package com.phishtopia.ja2fieldkit.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.phishtopia.ja2fieldkit.android.importing.ImportedSaveProvenance
import com.phishtopia.ja2fieldkit.android.importing.SourceProvenance
import com.phishtopia.ja2fieldkit.android.presentation.CampaignPresentation
import com.phishtopia.ja2fieldkit.android.presentation.FormatPresentation
import com.phishtopia.ja2fieldkit.android.presentation.InspectionScreenState
import com.phishtopia.ja2fieldkit.android.presentation.groupInventory
import com.phishtopia.ja2fieldkit.android.presentation.visibleText
import com.phishtopia.ja2fieldkit.android.presentation.MercPresentation
import com.phishtopia.ja2fieldkit.android.report.CompatibilityReportPreview

class MainActivity : ComponentActivity() {
    private val model: InspectionViewModel by viewModels()
    private val stateObserver: (InspectionScreenState) -> Unit = ::render
    private var renderedSuccess: InspectionScreenState.Success? = null
    private var mercSelector: Spinner? = null
    private var selectedMercDetail: LinearLayout? = null

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.inspect(applicationContext.contentResolver, uri, SourceProvenance.DOCUMENT_PICKER)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val consumed = consumeIntent(if (savedInstanceState == null) intent else null)
        retainIntent(consumed.storedIntent)
        enableEdgeToEdge()
        model.attach(stateObserver)
        handleRequest(consumed.request)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val consumed = consumeIntent(intent)
        retainIntent(consumed.storedIntent)
        handleRequest(consumed.request)
    }

    override fun onDestroy() {
        model.detach(stateObserver)
        super.onDestroy()
    }

    private fun consumeIntent(incoming: Intent?): ConsumedImportIntent<Uri> {
        if (incoming == null) {
            return consumeImportIntent(IncomingImportIntent<Uri>(action = null))
        }
        val extracted = when (incoming.action) {
            Intent.ACTION_VIEW -> IncomingImportIntent(
                action = incoming.action,
                viewSource = incoming.data,
            )

            Intent.ACTION_SEND -> {
                val clipData = incoming.clipData
                val sharedSourceCount = clipData?.itemCount ?: 0
                val sharedSource = when {
                    sharedSourceCount > 1 -> null
                    sharedSourceCount == 1 -> clipData?.getItemAt(0)?.uri ?: incoming.sharedStreamUri()
                    else -> incoming.sharedStreamUri()
                }
                IncomingImportIntent(
                    action = incoming.action,
                    sharedSource = sharedSource,
                    sharedSourceCount = sharedSourceCount,
                )
            }

            else -> IncomingImportIntent(action = incoming.action)
        }
        return consumeImportIntent(extracted)
    }

    private fun retainIntent(storedIntent: StoredActivityIntent) {
        when (storedIntent) {
            StoredActivityIntent.SOURCE_FREE -> setIntent(Intent(this, MainActivity::class.java))
        }
    }

    private fun handleRequest(request: ImportIntentRequest<Uri>) {
        when (request) {
            ImportIntentRequest.None -> Unit
            is ImportIntentRequest.Reject -> model.showSourceFailure(request.kind)
            is ImportIntentRequest.Inspect -> model.inspect(
                applicationContext.contentResolver,
                request.source,
                request.provenance,
            )
        }
    }

    private fun render(screenState: InspectionScreenState) {
        if (updateMercSelection(screenState)) return

        // A full render replaces the view tree, including after Activity recreation.
        renderedSuccess = null
        mercSelector = null
        selectedMercDetail = null
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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
                content.addCampaign(screenState.campaign)
                content.addHeading("Roster")
                if (screenState.roster.isEmpty()) content.addBody("No roster members found.")
                else content.addMercSelector(screenState)
                content.addHeading("Technical details")
                content.addSource(screenState.source)
                content.addFormat(screenState.format)
                content.addOpenButton(R.string.open_another_save)
            }

            is InspectionScreenState.Failure -> {
                content.addHeading(screenState.title)
                screenState.source?.let { content.addSource(it) }
                content.addLabelValue("Failure kind", screenState.failureKind)
                content.addLabelValue("Diagnostic", screenState.diagnostic)
                screenState.format?.let { content.addFormat(it) }
                content.addBody("No save data was changed.")
                screenState.compatibilityReportInputs?.let {
                    val preview = screenState.compatibilityReportPreview
                    if (preview != null) {
                        content.addCompatibilityReportPreview(preview)
                    } else {
                        content.addView(Button(this).apply {
                            setText(R.string.preview_compatibility_report)
                            setOnClickListener { model.showCompatibilityReportPreview() }
                        }, matchWidth())
                    }
                }
                content.addOpenButton(R.string.open_another_save)
            }
        }

        val root = ScrollView(this).apply {
            addView(content)
            applySafeContentPadding(
                ContentPadding(
                    left = dp(20),
                    top = dp(20),
                    right = dp(20),
                    bottom = dp(32),
                ),
            )
        }
        setContentView(root)
        renderedSuccess = screenState as? InspectionScreenState.Success
        ViewCompat.requestApplyInsets(root)
    }

    private fun updateMercSelection(screenState: InspectionScreenState): Boolean {
        val previous = renderedSuccess ?: return false
        if (screenState !is InspectionScreenState.Success) return false
        if (screenState.selectedProfileIndex == previous.selectedProfileIndex ||
            screenState.roster !== previous.roster ||
            screenState != previous.copy(selectedProfileIndex = screenState.selectedProfileIndex)
        ) return false
        val selector = mercSelector ?: return false
        val detail = selectedMercDetail ?: return false
        val selected = screenState.selectedMerc ?: return false

        // Keep the scroll viewport and selector (including accessibility focus) attached.
        renderedSuccess = screenState
        detail.removeAllViews()
        detail.addMerc(selected)
        val position = screenState.roster.indexOfFirst { it.profileIndex == selected.profileIndex }
        if (selector.selectedItemPosition != position) selector.setSelection(position)
        return true
    }

    private fun View.applySafeContentPadding(base: ContentPadding) {
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
            val safeInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            val padding = contentPadding(
                base = base,
                insets = ContentInsets(
                    left = safeInsets.left,
                    top = safeInsets.top,
                    right = safeInsets.right,
                    bottom = safeInsets.bottom,
                ),
            )
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            windowInsets
        }
    }

    private fun LinearLayout.addSource(source: ImportedSaveProvenance) {
        addHeading("Source")
        addLabelValue("Filename", source.displayName)
        addLabelValue("Actual size", "${source.actualSizeBytes} bytes")
        source.declaredSizeBytes?.let { addLabelValue("Provider size", "$it bytes") }
        source.lastModifiedEpochMillis?.let {
            addLabelValue("Provider last modified", "$it ms since Unix epoch")
        }
        addLabelValue("SHA-256", source.sha256Hex)
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

    private fun LinearLayout.addMercSelector(state: InspectionScreenState.Success) {
        val selected = state.selectedMerc ?: return
        val selector = Spinner(this@MainActivity).apply {
            id = View.generateViewId()
            contentDescription = "Selected merc"
            // Selection is owned by the ViewModel, not Android's view-state restoration.
            isSaveEnabled = false
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                state.roster.map { "${it.displayName()} · Profile #${it.profileIndex}" },
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(state.roster.indexOfFirst { it.profileIndex == selected.profileIndex })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (mercSelector !== parent) return
                    val merc = state.roster.getOrNull(position) ?: return
                    // ViewModel idempotency also covers programmatic selection callbacks.
                    model.selectMerc(merc.profileIndex)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        addView(textView("Selected merc", 16f).apply { labelFor = selector.id })
        addView(selector, matchWidth())
        mercSelector = selector
        selectedMercDetail = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addMerc(selected)
        }.also { addView(it, matchWidth()) }
    }

    private fun MercPresentation.displayName(): String = nickname
        ?.takeIf { it.isNotBlank() }
        ?.let { "$name ($it)" }
        ?: name

    private fun LinearLayout.addMerc(merc: MercPresentation) {
        addHeading(merc.displayName(), 19f)
        addBody("Live/current tactical stats")
        addBody(merc.liveStats.joinToString("  ·  ") { "${it.label}: ${it.value}" })
        addBody("Profile/base stats")
        addBody(merc.profileStats.joinToString("  ·  ") { "${it.label}: ${it.value}" })
        addHeading("Inventory", 17f)
        val groups = groupInventory(merc.inventory)
        if (groups == null) {
            addBody("Inventory unavailable: inconsistent slots.")
            return
        }
        groups.forEach { group ->
            addHeading(group.title, 16f)
            group.slots.chunked(2).forEach { slots ->
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                slots.forEach { slot ->
                    row.addView(textView(slot.visibleText(), 16f).apply {
                        setPadding(dp(4), dp(8), dp(4), dp(8))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                // Keep an odd final cell the same width as the other cells.
                if (slots.size == 1) {
                    row.addView(View(this@MainActivity),
                        LinearLayout.LayoutParams(0, 0, 1f))
                }
                addView(row, matchWidth())
            }
        }
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
        }, matchWidth())
    }

    private fun LinearLayout.addCompatibilityReportPreview(preview: CompatibilityReportPreview) {
        addHeading(getString(R.string.compatibility_report_preview))
        addBody(getString(R.string.compatibility_report_privacy_notice))
        addBody(preview.text)
        addView(Button(this@MainActivity).apply {
            setText(R.string.copy_compatibility_report)
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText(
                        getString(R.string.compatibility_report_clip_label),
                        preview.clipboardText,
                    ),
                )
            }
        }, matchWidth())
        addView(Button(this@MainActivity).apply {
            setText(R.string.share_compatibility_report)
            setOnClickListener {
                val payload = preview.share
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = payload.mimeType
                    putExtra(Intent.EXTRA_TEXT, payload.text)
                }
                startActivity(
                    Intent.createChooser(send, getString(R.string.share_compatibility_report)),
                )
            }
        }, matchWidth())
    }

    private fun matchWidth(): ViewGroup.LayoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

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
