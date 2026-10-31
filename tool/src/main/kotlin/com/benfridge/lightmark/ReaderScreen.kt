package com.benfridge.lightmark

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.benfridge.lightmark.data.DocumentRef
import com.benfridge.lightmark.data.DocumentRepository
import com.benfridge.lightmark.data.DocumentStore
import com.benfridge.lightmark.data.DataStoreReaderSettings
import com.benfridge.lightmark.data.Databases
import com.benfridge.lightmark.data.HighlightDao
import com.benfridge.lightmark.data.Highlight
import com.benfridge.lightmark.data.LightmarkDatabase
import com.benfridge.lightmark.data.ReaderSettings
import com.benfridge.lightmark.data.TextScale
import com.benfridge.lightmark.data.planMerge
import com.benfridge.lightmark.markdown.Document
import com.benfridge.lightmark.markdown.Markdown
import com.benfridge.lightmark.reader.PageStep
import com.benfridge.lightmark.reader.layoutPage
import com.benfridge.lightmark.reader.pageContaining
import com.benfridge.lightmark.reader.paginate
import com.benfridge.lightmark.reader.readerGestures
import com.benfridge.lightmark.sync.HighlightSync
import com.benfridge.lightmark.reader.sliceIndexAt
import com.benfridge.lightmark.reader.stepBackward
import com.benfridge.lightmark.reader.stepForward
import com.benfridge.lightmark.reader.startOffsetOf
import com.benfridge.lightmark.ui.DocumentRange
import com.benfridge.lightmark.ui.PageContent
import com.benfridge.lightmark.ui.ReaderMetrics
import com.benfridge.lightmark.ui.blockInsetUnits
import com.benfridge.lightmark.ui.rememberFontScale
import com.benfridge.lightmark.ui.rememberMeasuredBlocks
import com.benfridge.lightmark.ui.selectionBetween
import com.benfridge.lightmark.ui.snapToWord
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightGrid
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** How the reader should place you when a chapter opens. */
enum class ChapterEntry { Restore, Start, End }

/**
 * Which way a volume key turns: 1 forward, -1 back, null for anything else.
 *
 * Down goes forward, which reads as moving down the page and matches what other
 * readers do with these keys. The keycodes are compile-time constants, so this is
 * reachable from a JVM test where a `KeyEvent` instance would not be.
 */
internal fun volumeKeyDelta(keyCode: Int): Int? = when (keyCode) {
    KeyEvent.KEYCODE_VOLUME_DOWN -> 1
    KeyEvent.KEYCODE_VOLUME_UP -> -1
    else -> null
}

/**
 * A page turn asked for by something other than a swipe.
 *
 * [id] rises on every nudge so two presses in the same direction are distinct values and
 * the second is not swallowed as a no-op state change.
 */
data class PageNudge(val delta: Int, val id: Long)

/** One chapter, loaded and ready to lay out. */
data class LoadedChapter(
    val ref: DocumentRef,
    val document: Document,
    val entry: ChapterEntry,
    val savedOffset: Int,
    /** Changes on every load, so composable state keyed on it resets cleanly. */
    val token: Long,
)

class ReaderViewModel(
    private val chapters: List<DocumentRef>,
    startIndex: Int,
    private val repository: DocumentStore,
    private val highlightDao: HighlightDao,
    private val settings: ReaderSettings,
    /** Injected so tests can run the loads on a scheduler they control. */
    private val io: CoroutineContext = Dispatchers.IO,
    private val onHighlightsChanged: () -> Unit = {},
) : LightViewModel<Unit>() {

    val loaded = MutableStateFlow<LoadedChapter?>(null)
    val highlights = MutableStateFlow<List<Highlight>>(emptyList())

    /** The range under the finger mid-drag. Null when nothing is being selected. */
    val selection = MutableStateFlow<DocumentRange?>(null)

    val failed = MutableStateFlow(false)

    /** Set by a volume-key press; the screen applies it and clears it. */
    val pageNudge = MutableStateFlow<PageNudge?>(null)

    /** The reader's chosen type size, as a multiplier on the LightOS scale. */
    val textScale = MutableStateFlow(TextScale.DEFAULT)

    /** Whether running text is justified. Ragged-right when false. */
    val justifyText = MutableStateFlow(false)

    private var volumeKeysTurnPages = true
    private var nudgeId = 0L
    private var index = startIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
    private var anchorOffset: Int? = null
    private var pageOffset = 0
    private val saves = Mutex()
    private var started = false

    /**
     * Rises on every [open]. A load that finishes after a newer one started is
     * discarded, and it doubles as the token the screen keys its state on.
     */
    private var generation = 0L

    val hasPrevious: Boolean get() = index > 0
    val hasNext: Boolean get() = index < chapters.lastIndex

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        start()
    }

    /** The body of [onScreenShow], callable without a screen to hand. */
    internal fun start() {
        if (started) return
        started = true
        viewModelScope.launch(io) {
            volumeKeysTurnPages = settings.volumeKeysTurnPages()
            textScale.value = settings.textScale()
            justifyText.value = settings.justifyText()
        }
        open(index, ChapterEntry.Restore)
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        persist()
    }

    /** Back abandons an in-progress selection before it leaves the document. */
    override fun onBackPressed(): Boolean {
        if (selection.value != null) {
            cancelSelection()
            return true
        }
        persist()
        return false
    }

    /** Saved on every turn, so a place survives the process dying as well as leaving. */
    fun notePageOffset(offset: Int) {
        if (offset == pageOffset) return
        pageOffset = offset
        persist()
    }

    /**
     * Volume down goes forward, volume up goes back, which reads as moving down the
     * page and matches what other readers do with these keys.
     *
     * Returning true stops LightOS acting on the key, so the volume does not change.
     * Returning false lets it through untouched when the setting is off.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = pressKey(keyCode)

    /**
     * The body of [onKeyDown] without the event, which a JVM test cannot construct:
     * `KeyEvent` is one of the android.jar stubs that throws when instantiated.
     */
    internal fun pressKey(keyCode: Int): Boolean {
        if (!volumeKeysTurnPages) return false
        val delta = volumeKeyDelta(keyCode) ?: return false
        pageNudge.value = PageNudge(delta, ++nudgeId)
        return true
    }

    /** Consume the matching up event so LightOS does not act on it either. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        volumeKeysTurnPages && keyCode.isVolumeKey

    /**
     * Held keys repeat. Consumed but ignored: on a panel this slow, auto-repeat would
     * flip through a chapter faster than it can draw, and faster than anyone reads.
     */
    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean =
        volumeKeysTurnPages && keyCode.isVolumeKey

    fun consumeNudge() {
        pageNudge.value = null
    }

    private val Int.isVolumeKey: Boolean
        get() = volumeKeyDelta(this) != null

    /**
     * Swiping past the last page continues into the next chapter, and swiping back from
     * the first page returns to the end of the previous one. The chapter is swapped in
     * place rather than pushed, so the back key always returns to the chapter list
     * however far you have read, instead of unwinding one chapter at a time.
     */
    fun nextChapter() {
        persist()
        open(index + 1, ChapterEntry.Start)
    }

    fun previousChapter() {
        persist()
        open(index - 1, ChapterEntry.End)
    }

    private fun open(target: Int, entry: ChapterEntry) {
        // Validate before moving: a rejected target must not leave `index` pointing
        // past the end, where persist() and hasNext would both be wrong.
        val ref = chapters.getOrNull(target) ?: return
        index = target

        val attempt = ++generation
        loaded.value = null
        // Reset, or a chapter that failed once keeps showing its error over the next
        // chapter's load.
        failed.value = false
        selection.value = null
        anchorOffset = null
        pageOffset = 0

        viewModelScope.launch(io) {
            val source = repository.read(ref)
            if (attempt != generation) return@launch
            if (source == null) {
                failed.value = true
                return@launch
            }

            val saved = if (entry == ChapterEntry.Restore) repository.offset(ref.id) else 0
            ref.bookId?.let { repository.saveCurrentChapter(it, ref.id) }
            repository.saveLastRead(ref.id)
            val chapterHighlights = highlightDao.list(ref.id)
            val document = Markdown.parse(source)

            // Two chapters opened in quick succession race here, and the slower read
            // would otherwise land last and win. Swiping twice would leave the reader
            // showing the first chapter while `index` pointed at the second.
            if (attempt != generation) return@launch
            highlights.value = chapterHighlights
            loaded.value = LoadedChapter(
                ref = ref,
                document = document,
                entry = entry,
                savedOffset = saved,
                token = attempt,
            )
        }
    }

    /**
     * NonCancellable because back clears this view model straight after asking it: the
     * SDK cancels viewModelScope in the same call, so an ordinary launch can lose the save
     * and reopen the chapter wherever it was last saved. Undispatched so the lock is taken
     * here, in the order the pages turned, rather than whenever a thread comes free.
     */
    private fun persist() {
        val ref = chapters.getOrNull(index) ?: return
        val offset = pageOffset
        viewModelScope.launch(NonCancellable, start = CoroutineStart.UNDISPATCHED) {
            saves.withLock { withContext(io) { repository.saveOffset(ref.id, offset) } }
        }
    }

    private suspend fun reload() {
        val ref = chapters.getOrNull(index) ?: return
        highlights.value = highlightDao.list(ref.id)
    }

    /**
     * Press and hold. Returns false when the press landed on an existing highlight,
     * which removes it instead of starting a new one — the only way to undo, and it
     * reuses the gesture already learned rather than adding a control.
     */
    fun beginSelection(documentOffset: Int): Boolean {
        val existing = highlights.value.firstOrNull { documentOffset in it.start until it.end }
        if (existing != null) {
            anchorOffset = null
            selection.value = null
            viewModelScope.launch(io) {
                highlightDao.delete(existing.id)
                reload()
                onHighlightsChanged()
            }
            return false
        }

        val plain = loaded.value?.document?.plainText ?: return false
        anchorOffset = documentOffset
        selection.value = snapToWord(plain, documentOffset)
        return true
    }

    fun extendSelection(documentOffset: Int) {
        val plain = loaded.value?.document?.plainText ?: return
        val anchor = anchorOffset ?: return
        selection.value = selectionBetween(plain, anchor, documentOffset)
    }

    fun commitSelection() {
        val range = selection.value
        anchorOffset = null
        selection.value = null
        if (range == null || range.isEmpty) return
        val state = loaded.value ?: return
        val plain = state.document.plainText

        viewModelScope.launch(io) {
            val dao = highlightDao
            val plan = planMerge(dao.list(state.ref.id), range.start, range.end)
            plan.absorbedIds.forEach { dao.delete(it) }
            dao.insert(
                Highlight(
                    documentId = state.ref.id,
                    start = plan.start,
                    end = plan.end,
                    createdAt = System.currentTimeMillis(),
                    // Copied in now, against the text it was taken from. Resolving it
                    // later would depend on the source file still being byte-identical.
                    text = plain.substring(
                        plan.start.coerceIn(0, plain.length),
                        plan.end.coerceIn(0, plain.length),
                    ),
                )
            )
            reload()
            onHighlightsChanged()
        }
    }

    fun cancelSelection() {
        anchorOffset = null
        selection.value = null
    }
}

/**
 * The reader. Two gestures and one button: swipe to turn the page, press and hold then
 * drag to highlight, and the top bar's back arrow to leave. LightOS has no system back
 * key on the phone, so without the arrow there is no way out of a document.
 *
 * A book is a list of chapter files, and swiping carries across the boundary between
 * them, so a split book reads as one thing. A loose document is simply a list of one.
 *
 * The only mark that is not text is a hairline along the bottom edge showing how far
 * through the current chapter you are.
 */
class ReaderScreen(
    private val sealedActivity: SealedLightActivity,
    private val chapters: List<DocumentRef>,
    private val startIndex: Int = 0,
) : LightScreen<Unit, ReaderViewModel>(sealedActivity) {

    constructor(sealedActivity: SealedLightActivity, ref: DocumentRef) :
        this(sealedActivity, listOf(ref), 0)

    override val viewModelClass: Class<ReaderViewModel> get() = ReaderViewModel::class.java

    override fun createViewModel(): ReaderViewModel = ReaderViewModel(
        chapters = chapters,
        startIndex = startIndex,
        repository = DocumentRepository(lightContext, lightContext.dataStore),
        highlightDao = Databases.highlights(lightContext),
        settings = DataStoreReaderSettings(lightContext.dataStore),
        onHighlightsChanged = { HighlightSync.changed(lightContext) },
    )

    @Composable
    override fun Content() {
        val loaded by viewModel.loaded.collectAsState()
        val highlights by viewModel.highlights.collectAsState()
        val selection by viewModel.selection.collectAsState()
        val failed by viewModel.failed.collectAsState()
        val pageNudge by viewModel.pageNudge.collectAsState()
        val textScale by viewModel.textScale.collectAsState()
        val justifyText by viewModel.justifyText.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            val colors = LightThemeTokens.colors
            val typography = LightThemeTokens.typography

            Column(
                modifier = Modifier.fillMaxSize().background(colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                )

                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    val density = LocalDensity.current
                    val widthPx = with(density) { maxWidth.toPx() }
                    val heightPx = with(density) { maxHeight.toPx() }
                    val unitPx = widthPx / LightGrid.WIDTH
                    val sideMargin = ReaderMetrics.SIDE_MARGIN_UNITS * unitPx
                    val topMargin = ReaderMetrics.TOP_MARGIN_UNITS * unitPx
                    val bottomMargin = ReaderMetrics.BOTTOM_MARGIN_UNITS * unitPx
                    val hairline = with(density) { 1.dp.toPx() }
                    val contentWidth = (widthPx - sideMargin * 2f).toInt()
                    val pageHeight = heightPx - topMargin - bottomMargin - hairline

                    val state = loaded
                    when {
                        failed -> LightText(
                            text = "That document could not be read.",
                            variant = LightTextVariant.Copy,
                            modifier = Modifier.padding(
                                horizontal = with(density) { sideMargin.toDp() },
                                vertical = with(density) { topMargin.toDp() },
                            ),
                        )

                        state == null -> Unit

                        else -> {
                            val doc = state.document
                            val measurer = rememberTextMeasurer()
                            // The LightOS scale times what the reader chose. Changing it
                            // re-measures and re-paginates; the reading position is a
                            // character offset and is not keyed on it, so the page is
                            // re-derived around the same word.
                            val fontScale = rememberFontScale() * textScale
                            // Null until the measuring pass finishes. An empty list
                            // paginates to no pages, which is already the "nothing to draw
                            // yet" state every branch below handles.
                            val blocks = rememberMeasuredBlocks(
                                document = doc,
                                contentWidthPx = contentWidth,
                                unitPx = unitPx,
                                typography = typography,
                                fontScale = fontScale,
                                justify = justifyText,
                                measurer = measurer,
                            ) ?: emptyList()
                            val pages = remember(blocks, pageHeight) { paginate(blocks, pageHeight) }
                            val blockStarts = remember(state.token) { doc.blocks.map { it.start } }

                            // Position is held as a character offset and the page derived
                            // from it, not the other way round. A theme switch or an
                            // activity recreation re-measures and re-paginates; deriving
                            // the page means that keeps your place instead of resetting.
                            var readingOffset by remember(state.token) { mutableIntStateOf(-1) }

                            LaunchedEffect(state.token, pages) {
                                if (readingOffset < 0 && pages.isNotEmpty()) {
                                    readingOffset = when (state.entry) {
                                        ChapterEntry.Restore -> state.savedOffset
                                        ChapterEntry.Start -> 0
                                        // Arrived by swiping backwards: open at the end.
                                        ChapterEntry.End -> startOffsetOf(pages.last(), blockStarts)
                                    }
                                }
                            }

                            val pageIndex = remember(pages, readingOffset, blockStarts) {
                                if (pages.isEmpty() || readingOffset < 0) 0
                                else pageContaining(pages, blockStarts, readingOffset)
                            }

                            // Not before the saved place is restored: page 0 is only a
                            // placeholder until then, and noting it would overwrite that place.
                            LaunchedEffect(pageIndex, pages, readingOffset >= 0) {
                                if (readingOffset < 0) return@LaunchedEffect
                                pages.getOrNull(pageIndex)?.let {
                                    viewModel.notePageOffset(startOffsetOf(it, blockStarts))
                                }
                            }

                            fun goToPage(index: Int) {
                                val target = pages.getOrNull(index) ?: return
                                readingOffset = startOffsetOf(target, blockStarts)
                            }

                            // The swipe and the volume keys both come through here, so the
                            // two cannot drift apart at a chapter boundary. The decision
                            // itself is in stepForward/stepBackward, where it is testable.
                            fun apply(step: PageStep) {
                                when (step) {
                                    is PageStep.ToPage -> goToPage(step.index)
                                    PageStep.NextChapter -> viewModel.nextChapter()
                                    PageStep.PreviousChapter -> viewModel.previousChapter()
                                    PageStep.Nowhere -> Unit
                                }
                            }

                            fun advance() =
                                apply(stepForward(pageIndex, pages.size, viewModel.hasNext))

                            fun retreat() =
                                apply(stepBackward(pageIndex, pages.size, viewModel.hasPrevious))

                            LaunchedEffect(pageNudge, pages) {
                                val nudge = pageNudge ?: return@LaunchedEffect
                                if (pages.isEmpty()) return@LaunchedEffect
                                if (nudge.delta > 0) advance() else retreat()
                                viewModel.consumeNudge()
                            }

                            val placed = remember(pages, pageIndex, blocks) {
                                pages.getOrNull(pageIndex)
                                    ?.let { layoutPage(it, blocks) }
                                    ?: emptyList()
                            }
                            val layouts = remember(pageIndex, pages, state.token) {
                                mutableMapOf<Int, TextLayoutResult>()
                            }

                            // Screen coordinates -> document character offset.
                            fun offsetAt(position: Offset): Int? {
                                val index = sliceIndexAt(placed, position.y - topMargin)
                                if (index < 0) return null
                                val hit = placed[index]
                                val layout = layouts[index] ?: return null
                                val block = doc.blocks[hit.slice.blockIndex]
                                val inset = blockInsetUnits(block.block) * unitPx
                                val local = Offset(
                                    position.x - sideMargin - inset,
                                    position.y - topMargin - hit.top,
                                )
                                return block.start + hit.slice.charStart +
                                    layout.getOffsetForPosition(local)
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .readerGestures(
                                        key = Triple(state.token, pageIndex, pages.size),
                                        swipeThreshold = widthPx * SWIPE_FRACTION,
                                        onPageForward = { advance() },
                                        onPageBack = { retreat() },
                                        onSelectStart = { position ->
                                            val offset = offsetAt(position)
                                            val started = offset != null &&
                                                viewModel.beginSelection(offset)
                                            // Confirms the mode changed. Without it a hold
                                            // that began a selection feels identical to one
                                            // that found no text.
                                            sealedActivity.vibrator.click()
                                            started
                                        },
                                        onSelectExtend = { position ->
                                            offsetAt(position)?.let(viewModel::extendSelection)
                                        },
                                        onSelectCommit = viewModel::commitSelection,
                                        onSelectCancel = viewModel::cancelSelection,
                                    ),
                            ) {
                                if (placed.isNotEmpty()) {
                                    PageContent(
                                        document = doc,
                                        placed = placed,
                                        highlights = highlights.map {
                                            DocumentRange(it.start, it.end)
                                        },
                                        selection = selection,
                                        colors = colors,
                                        typography = typography,
                                        fontScale = fontScale,
                                        justify = justifyText,
                                        unitPx = unitPx,
                                        density = density,
                                        onSliceLayout = { index, layout ->
                                            layouts[index] = layout
                                        },
                                        modifier = Modifier.padding(
                                            start = with(density) { sideMargin.toDp() },
                                            end = with(density) { sideMargin.toDp() },
                                            top = with(density) { topMargin.toDp() },
                                        ),
                                    )
                                }
                            }

                            if (pages.size > 1) {
                                ProgressHairline(
                                    fraction = (pageIndex + 1f) / pages.size,
                                    modifier = Modifier.align(Alignment.BottomStart),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        /** A swipe must cross this share of the screen's width to turn the page. */
        const val SWIPE_FRACTION = 0.12f
    }
}

/**
 * How far through the chapter you are, as one hairline along the bottom edge.
 *
 * Not a page count and not a percentage: a number invites checking, a filling line is
 * read without being looked at. It measures the chapter rather than the book because
 * chapters differ in length, so a book-wide fraction would be quietly wrong.
 */
@Composable
private fun ProgressHairline(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(1.dp)
                .background(LightThemeTokens.colors.contentSecondary),
        )
    }
}
