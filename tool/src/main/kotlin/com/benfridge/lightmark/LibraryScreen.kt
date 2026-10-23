package com.benfridge.lightmark

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.benfridge.lightmark.data.DocumentRef
import com.benfridge.lightmark.data.DocumentRepository
import com.benfridge.lightmark.data.DocumentSource
import com.benfridge.lightmark.data.LightmarkDatabase
import com.benfridge.lightmark.export.ExportOutcome
import com.benfridge.lightmark.export.HighlightExporter
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** A document plus how many highlights it holds, so the list can say. */
data class LibraryEntry(val ref: DocumentRef, val highlightCount: Int)

/** What the export row is showing. */
sealed interface ExportState {
    data object Idle : ExportState
    data object Running : ExportState
    data class Done(val outcome: ExportOutcome) : ExportState
}

class LibraryViewModel(
    private val repository: DocumentRepository,
    private val database: LightmarkDatabase,
    private val exporter: HighlightExporter,
) : LightViewModel<Unit>() {

    val entries = MutableStateFlow<List<LibraryEntry>>(emptyList())
    val loaded = MutableStateFlow(false)
    val totalHighlights = MutableStateFlow(0)
    val exportState = MutableStateFlow<ExportState>(ExportState.Idle)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        exportState.value = ExportState.Idle
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.ensureSharedDirectory()
            val refs = repository.list()
            // One read grouped in memory, rather than a query per document: with a book
            // split into chapters this list is long.
            val counts = database.highlights().listAll().groupingBy { it.documentId }.eachCount()
            entries.value = refs.map { LibraryEntry(it, counts[it.id] ?: 0) }
            totalHighlights.value = counts.values.sum()
            loaded.value = true
        }
    }

    fun export() {
        if (exportState.value is ExportState.Running) return
        exportState.value = ExportState.Running
        viewModelScope.launch(Dispatchers.IO) {
            exportState.value = ExportState.Done(exporter.export())
        }
    }
}

@InitialScreen
class LibraryScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, LibraryViewModel>(sealedActivity) {

    override val viewModelClass: Class<LibraryViewModel> get() = LibraryViewModel::class.java

    override fun createViewModel(): LibraryViewModel {
        val database = lightContext.buildDatabase(LightmarkDatabase::class.java, DB_NAME)
        return LibraryViewModel(
            repository = DocumentRepository(lightContext, lightContext.dataStore),
            database = database,
            exporter = HighlightExporter(lightContext.fileShare, database.highlights()),
        )
    }

    @Composable
    override fun Content() {
        val entries by viewModel.entries.collectAsState()
        val loaded by viewModel.loaded.collectAsState()
        val totalHighlights by viewModel.totalHighlights.collectAsState()
        val exportState by viewModel.exportState.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(center = LightTopBarCenter.Text("Lightmark"))

                when {
                    !loaded -> Unit

                    entries.isEmpty() -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 1.5f.gridUnitsAsDp()),
                    ) {
                        LightText(
                            text = "No documents yet.",
                            variant = LightTextVariant.Copy,
                        )
                        Spacer(Modifier.height(1f.gridUnitsAsDp()))
                        LightText(
                            text = "Markdown and text files in the tool's documents " +
                                "folder show up here.",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                        )
                    }

                    else -> LightScrollView(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
                        entries.forEach { entry ->
                            DocumentRow(entry) { open(entry.ref) }
                        }
                        if (totalHighlights > 0) {
                            ExportRow(
                                total = totalHighlights,
                                state = exportState,
                                onExport = viewModel::export,
                            )
                        }
                        Spacer(Modifier.height(1f.gridUnitsAsDp()))
                    }
                }
            }
        }
    }

    private fun open(ref: DocumentRef) {
        navigateTo<Unit>({ activity -> ReaderScreen(activity, ref) })
    }

    companion object {
        const val DB_NAME = "lightmark.db"
    }
}

/**
 * Writes every highlight to one Markdown file in the tool's shared directory.
 *
 * It lives here rather than in the reader because the reader has exactly two gestures and
 * adding a third would undo that. Exporting is a library-level action anyway: it covers
 * the whole shelf, not the chapter you happen to be in.
 */
@Composable
private fun ExportRow(total: Int, state: ExportState, onExport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 1f.gridUnitsAsDp()),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 1.5f.gridUnitsAsDp())
                .height(1.dp)
                .background(LightThemeTokens.colors.contentSecondary),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable { onExport() }
                .padding(
                    horizontal = 1.5f.gridUnitsAsDp(),
                    vertical = 0.75f.gridUnitsAsDp(),
                ),
        ) {
            LightText(
                text = when (state) {
                    ExportState.Running -> "Exporting…"
                    else -> "Export highlights"
                },
                variant = LightTextVariant.Copy,
            )
            LightText(
                text = exportDetail(total, state),
                variant = LightTextVariant.Detail,
                lighten = true,
            )
        }
    }
}

private fun exportDetail(total: Int, state: ExportState): String = when (state) {
    ExportState.Idle ->
        if (total == 1) "1 highlight" else "$total highlights"

    ExportState.Running -> "Writing the file"

    is ExportState.Done -> when (val outcome = state.outcome) {
        is ExportOutcome.Written ->
            "Saved to ${outcome.relativePath}"

        ExportOutcome.NothingToExport -> "Nothing highlighted yet"

        is ExportOutcome.Failed -> outcome.reason
    }
}

@Composable
private fun DocumentRow(entry: LibraryEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable { onClick() }
            .padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(text = entry.ref.title, variant = LightTextVariant.Copy)
        Row(verticalAlignment = Alignment.CenterVertically) {
            LightText(
                text = buildString {
                    append(if (entry.ref.source == DocumentSource.Bundled) "Included" else "Yours")
                    if (entry.highlightCount > 0) {
                        append("  ·  ")
                        append(entry.highlightCount)
                        append(if (entry.highlightCount == 1) " highlight" else " highlights")
                    }
                },
                variant = LightTextVariant.Detail,
                lighten = true,
            )
        }
    }
}
