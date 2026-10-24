package com.benfridge.lightmark

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.benfridge.lightmark.data.Book
import com.benfridge.lightmark.data.DocumentRef
import com.benfridge.lightmark.data.DataStoreReaderSettings
import com.benfridge.lightmark.data.DocumentRepository
import com.benfridge.lightmark.data.DocumentStore
import com.benfridge.lightmark.data.HighlightDao
import com.benfridge.lightmark.data.DocumentSource
import com.benfridge.lightmark.data.LightmarkDatabase
import com.benfridge.lightmark.data.Resume
import com.benfridge.lightmark.data.resumeTarget
import com.benfridge.lightmark.data.TextScale
import com.benfridge.lightmark.markdown.Block
import com.benfridge.lightmark.markdown.Span
import com.benfridge.lightmark.ui.blockStyle
import com.benfridge.lightmark.ui.rememberFontScale
import com.benfridge.lightmark.data.ReaderSettings
import com.benfridge.lightmark.export.ExportOutcome
import com.benfridge.lightmark.export.HighlightExporter
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
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

/** A loose document in the library. */
data class SingleRow(val ref: DocumentRef, val highlightCount: Int)

/** A book in the library, with where you left off. */
data class BookRow(
    val book: Book,
    val highlightCount: Int,
    val currentChapterTitle: String?,
    val currentChapterNumber: Int?,
)

/** What the export row is showing. */
sealed interface ExportState {
    data object Idle : ExportState
    data object Running : ExportState
    data class Done(val outcome: ExportOutcome) : ExportState
}

class LibraryViewModel(
    private val repository: DocumentStore,
    private val highlightDao: HighlightDao,
    private val exporter: HighlightExporter,
    private val settings: ReaderSettings,
) : LightViewModel<Unit>() {

    val books = MutableStateFlow<List<BookRow>>(emptyList())
    val singles = MutableStateFlow<List<SingleRow>>(emptyList())
    val loaded = MutableStateFlow(false)
    val totalHighlights = MutableStateFlow(0)
    val exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val volumeKeysTurnPages = MutableStateFlow(true)
    val textScale = MutableStateFlow(TextScale.DEFAULT)
    val resume = MutableStateFlow<Resume?>(null)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        exportState.value = ExportState.Idle
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.ensureSharedDirectory()
            val library = repository.library()
            resume.value = resumeTarget(library, repository.lastRead())
            // One read grouped in memory, rather than a query per document: a book split
            // into chapters makes that list long.
            val counts = highlightDao.listAll().groupingBy { it.documentId }.eachCount()

            singles.value = library.singles.map { SingleRow(it, counts[it.id] ?: 0) }
            books.value = library.books.map { book ->
                val current = repository.currentChapter(book.id)
                val index = book.chapters.indexOfFirst { it.id == current }
                BookRow(
                    book = book,
                    highlightCount = book.chapters.sumOf { counts[it.id] ?: 0 },
                    currentChapterTitle = book.chapters.getOrNull(index)?.title,
                    currentChapterNumber = if (index >= 0) index + 1 else null,
                )
            }
            totalHighlights.value = counts.values.sum()
            volumeKeysTurnPages.value = settings.volumeKeysTurnPages()
            textScale.value = settings.textScale()
            loaded.value = true
        }
    }

    fun cycleTextScale() {
        val next = TextScale.next(textScale.value)
        textScale.value = next
        viewModelScope.launch(Dispatchers.IO) { settings.setTextScale(next) }
    }

    fun toggleVolumeKeys() {
        val next = !volumeKeysTurnPages.value
        volumeKeysTurnPages.value = next
        viewModelScope.launch(Dispatchers.IO) { settings.setVolumeKeysTurnPages(next) }
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
            highlightDao = database.highlights(),
            exporter = HighlightExporter(lightContext.fileShare, database.highlights()),
            settings = DataStoreReaderSettings(lightContext.dataStore),
        )
    }

    @Composable
    override fun Content() {
        val books by viewModel.books.collectAsState()
        val singles by viewModel.singles.collectAsState()
        val loaded by viewModel.loaded.collectAsState()
        val totalHighlights by viewModel.totalHighlights.collectAsState()
        val exportState by viewModel.exportState.collectAsState()
        val volumeKeys by viewModel.volumeKeysTurnPages.collectAsState()
        val textScale by viewModel.textScale.collectAsState()
        val resume by viewModel.resume.collectAsState()
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

                    books.isEmpty() && singles.isEmpty() -> EmptyLibrary()

                    else -> LightScrollView(modifier = Modifier.fillMaxSize()) {
                        Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
                        resume?.let { target ->
                            ResumeRow(target) { openResume(target) }
                            SettingsDivider()
                        }
                        // Books first: one of them stands for thirty files.
                        books.forEach { row -> BookRowView(row) { openBook(row.book) } }
                        singles.forEach { row -> SingleRowView(row) { openSingle(row.ref) } }
                        SettingsDivider()
                        TextSizeRow(scale = textScale, onCycle = viewModel::cycleTextScale)
                        VolumeKeysRow(enabled = volumeKeys, onToggle = viewModel::toggleVolumeKeys)
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

    private fun openResume(target: Resume) {
        navigateTo<Unit>({ activity -> ReaderScreen(activity, target.chapters, target.index) })
    }

    private fun openBook(book: Book) {
        navigateTo<Unit>({ activity -> BookScreen(activity, book) })
    }

    private fun openSingle(ref: DocumentRef) {
        navigateTo<Unit>({ activity -> ReaderScreen(activity, ref) })
    }

    companion object {
        const val DB_NAME = "lightmark.db"
    }
}

@Composable
private fun EmptyLibrary() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 1.5f.gridUnitsAsDp()),
    ) {
        LightText(text = "No documents yet.", variant = LightTextVariant.Copy)
        Spacer(Modifier.height(1f.gridUnitsAsDp()))
        LightText(
            text = "Markdown and text files in the tool's documents folder show up " +
                "here. A folder of files is read as a book, one file per chapter.",
            variant = LightTextVariant.Detail,
            lighten = true,
        )
    }
}

/**
 * Straight back to the last thing read, from anywhere in the library.
 *
 * Without it, picking the phone up to carry on means the shelf, then the book, then the
 * chapter list, then Continue — four taps to return to a page you never meant to leave.
 */
@Composable
private fun ResumeRow(target: Resume, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable { onClick() }
            .padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(text = "Continue reading", variant = LightTextVariant.Copy)
        LightText(
            text = target.bookTitle?.let { "${target.title}  ·  $it" } ?: target.title,
            variant = LightTextVariant.Detail,
            lighten = true,
        )
    }
}

@Composable
private fun BookRowView(row: BookRow, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable { onClick() }
            .padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(text = row.book.title, variant = LightTextVariant.Copy)
        LightText(text = bookDetail(row), variant = LightTextVariant.Detail, lighten = true)
    }
}

private fun bookDetail(row: BookRow): String = buildString {
    val total = row.book.chapters.size
    if (row.currentChapterNumber != null) {
        append(row.currentChapterNumber).append(" of ").append(total)
    } else {
        append(total).append(if (total == 1) " chapter" else " chapters")
    }
    if (row.highlightCount > 0) {
        append("  ·  ").append(row.highlightCount)
        append(if (row.highlightCount == 1) " highlight" else " highlights")
    }
}

@Composable
private fun SingleRowView(row: SingleRow, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable { onClick() }
            .padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(text = row.ref.title, variant = LightTextVariant.Copy)
        LightText(
            text = buildString {
                append(if (row.ref.source == DocumentSource.Bundled) "Included" else "Yours")
                if (row.highlightCount > 0) {
                    append("  ·  ").append(row.highlightCount)
                    append(if (row.highlightCount == 1) " highlight" else " highlights")
                }
            },
            variant = LightTextVariant.Detail,
            lighten = true,
        )
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
    Column(modifier = Modifier.fillMaxWidth()) {
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

/** Separates the shelf from the two things you can do to it. */
@Composable
private fun SettingsDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp())
            .height(1.dp)
            .background(LightThemeTokens.colors.contentSecondary),
    )
}

/**
 * Cycles the reader's type size, and shows the result in the reader's own style.
 *
 * The control has to live here because the reader has two gestures and adding a third
 * would undo that. Showing a sample at the chosen size is what makes a control on a
 * different screen usable: you can see what you are choosing without opening a book.
 */
@Composable
private fun TextSizeRow(scale: Float, onCycle: () -> Unit) {
    val sampleStyle = blockStyle(
        block = Block.Paragraph(listOf(Span(SAMPLE))),
        typography = LightThemeTokens.typography,
        fontScale = rememberFontScale() * scale,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable { onCycle() }
            .padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LightText(
                text = "Text size",
                variant = LightTextVariant.Copy,
                modifier = Modifier.weight(1f),
            )
            LightText(
                text = TextScale.labelFor(scale),
                variant = LightTextVariant.Copy,
                lighten = true,
            )
        }
        Text(
            text = SAMPLE,
            style = sampleStyle,
            color = LightThemeTokens.colors.contentSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 0.3f.gridUnitsAsDp()),
        )
    }
}

private const val SAMPLE =
    "There is only nature, in all its eternal flowering, creating microprocessors."

/**
 * Turning this off gives the volume keys back to whatever you are listening to, at the
 * cost of swiping for every page.
 */
@Composable
private fun VolumeKeysRow(enabled: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable { onToggle() }
            .padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            LightText(text = "Volume keys turn pages", variant = LightTextVariant.Copy)
            LightText(
                text = if (enabled) "Down for next, up for back"
                else "Volume keys change the volume",
                variant = LightTextVariant.Detail,
                lighten = true,
            )
        }
        LightIcon(
            icon = if (enabled) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF,
        )
    }
}

private fun exportDetail(total: Int, state: ExportState): String = when (state) {
    ExportState.Idle ->
        if (total == 1) "1 highlight" else "$total highlights"

    ExportState.Running -> "Writing the file"

    is ExportState.Done -> when (val outcome = state.outcome) {
        is ExportOutcome.Written -> "Saved to ${outcome.relativePath}"
        ExportOutcome.NothingToExport -> "Nothing highlighted yet"
        is ExportOutcome.Failed -> outcome.reason
    }
}
