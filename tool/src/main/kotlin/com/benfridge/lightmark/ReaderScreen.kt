package com.benfridge.lightmark

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import com.benfridge.lightmark.data.Highlight
import com.benfridge.lightmark.data.LightmarkDatabase
import com.benfridge.lightmark.data.ReaderSettings
import com.benfridge.lightmark.data.planMerge
import com.benfridge.lightmark.markdown.Document
import com.benfridge.lightmark.markdown.Markdown
import com.benfridge.lightmark.reader.layoutPage
import com.benfridge.lightmark.reader.pageContaining
import com.benfridge.lightmark.reader.paginate
import com.benfridge.lightmark.reader.readerGestures
import com.benfridge.lightmark.reader.sliceIndexAt
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
import com.thelightphone.sdk.ui.LightGrid
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** How the reader should place you when a chapter opens. */
enum class ChapterEntry { Restore, Start, End }

/**
 * A page turn asked for by something other than a swipe.
 *
 * [id] rises on every nudge so two presses in the same direction are distinct values and
 * the second is not swallowed as a no-op state change.
 */
data class PageNudge(val delta: Int, val id: Long)

/** One chapter, loaded and ready to lay out. */
data class LoadedChapter(
    val index: Int,
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
    private val repository: DocumentRepository,
    private val database: LightmarkDatabase,
    private val settings: ReaderSettings,
) : LightViewModel<Unit>() {

    val loaded = MutableStateFlow<LoadedChapter?>(null)
    val highlights = MutableStateFlow<List<Highlight>>(emptyList())

    /** The range under the finger mid-drag. Null when nothing is being selected. */
    val selection = MutableStateFlow<DocumentRange?>(null)

    val failed = MutableStateFlow(false)

    /** Set by a volume-key press; the screen applies it and clears it. */
    val pageNudge = MutableStateFlow<PageNudge?>(null)

    private var volumeKeysTurnPages = true
    private var nudgeId = 0L
    private var index = startIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
    private var anchorOffset: Int? = null
    private var pageOffset = 0
    private var token = 0L
    private var started = false

    val hasPrevious: Boolean get() = index > 0
    val hasNext: Boolean get() = index < chapters.lastIndex
    val chapterCount: Int get() = chapters.size

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (started) return
        started = true
        viewModelScope.launch(Dispatchers.IO) {
            volumeKeysTurnPages = settings.volumeKeysTurnPages()
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

    fun notePageOffset(offset: Int) {
        pageOffset = offset
    }

    /**
     * Volume down goes forward, volume up goes back, which reads as moving down the
     * page and matches what other readers do with these keys.
     *
     * Returning true stops LightOS acting on the key, so the volume does not change.
     * Returning false lets it through untouched when the setting is off.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!volumeKeysTurnPages) return false
        val delta = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> 1
            KeyEvent.KEYCODE_VOLUME_UP -> -1
            else -> return false
        }
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
        get() = this == KeyEvent.KEYCODE_VOLUME_DOWN || this == KeyEvent.KEYCODE_VOLUME_UP

    /**
     * Swiping past the last page continues into the next chapter, and swiping back from
     * the first page returns to the end of the previous one. The chapter is swapped in
     * place rather than pushed, so the back key always returns to the chapter list
     * however far you have read, instead of unwinding one chapter at a time.
     */
    fun nextChapter(): Boolean {
        if (!hasNext) return false
        persist()
        open(index + 1, ChapterEntry.Start)
        return true
    }

    fun previousChapter(): Boolean {
        if (!hasPrevious) return false
        persist()
        open(index - 1, ChapterEntry.End)
        return true
    }

    private fun open(target: Int, entry: ChapterEntry) {
        index = target
        val ref = chapters.getOrNull(target) ?: return
        loaded.value = null
        selection.value = null
        anchorOffset = null
        pageOffset = 0

        viewModelScope.launch(Dispatchers.IO) {
            val source = repository.read(ref)
            if (source == null) {
                failed.value = true
                return@launch
            }
            failed.value = false
            val saved = if (entry == ChapterEntry.Restore) repository.offset(ref.id) else 0
            ref.bookId?.let { repository.saveCurrentChapter(it, ref.id) }
            highlights.value = database.highlights().list(ref.id)
            loaded.value = LoadedChapter(
                index = target,
                ref = ref,
                document = Markdown.parse(source),
                entry = entry,
                savedOffset = saved,
                token = ++token,
            )
        }
    }

    private fun persist() {
        val ref = chapters.getOrNull(index) ?: return
        val offset = pageOffset
        viewModelScope.launch(Dispatchers.IO) { repository.saveOffset(ref.id, offset) }
    }

    private suspend fun reload() {
        val ref = chapters.getOrNull(index) ?: return
        highlights.value = database.highlights().list(ref.id)
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
            viewModelScope.launch(Dispatchers.IO) {
                database.highlights().delete(existing.id)
                reload()
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

        viewModelScope.launch(Dispatchers.IO) {
            val dao = database.highlights()
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
        }
    }

    fun cancelSelection() {
        anchorOffset = null
        selection.value = null
    }
}

/**
 * The reader. Two gestures and no chrome: swipe to turn the page, press and hold then
 * drag to highlight. Leaving is the hardware back key, which the SDK already routes
 * through [goBack], so there is no top bar taking two of the screen's thirty-one
 * vertical grid units on every page.
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
        database = lightContext.buildDatabase(LightmarkDatabase::class.java, LibraryScreen.DB_NAME),
        settings = ReaderSettings(lightContext.dataStore),
    )

    @Composable
    override fun Content() {
        val loaded by viewModel.loaded.collectAsState()
        val highlights by viewModel.highlights.collectAsState()
        val selection by viewModel.selection.collectAsState()
        val failed by viewModel.failed.collectAsState()
        val pageNudge by viewModel.pageNudge.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            val colors = LightThemeTokens.colors
            val typography = LightThemeTokens.typography

            BoxWithConstraints(
                modifier = Modifier.fillMaxSize().background(colors.background),
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
                        val fontScale = rememberFontScale()
                        val blocks = rememberMeasuredBlocks(
                            document = doc,
                            contentWidthPx = contentWidth,
                            unitPx = unitPx,
                            typography = typography,
                            fontScale = fontScale,
                            measurer = measurer,
                        )
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

                        LaunchedEffect(pageIndex, pages) {
                            pages.getOrNull(pageIndex)?.let {
                                viewModel.notePageOffset(startOffsetOf(it, blockStarts))
                            }
                        }

                        fun goToPage(index: Int) {
                            val target = pages.getOrNull(index) ?: return
                            readingOffset = startOffsetOf(target, blockStarts)
                        }

                        // One path forward and one back, shared by the swipe and the
                        // volume keys, so the two can never drift apart at a chapter
                        // boundary.
                        fun advance() {
                            if (pageIndex < pages.lastIndex) goToPage(pageIndex + 1)
                            else viewModel.nextChapter()
                        }

                        fun retreat() {
                            if (pageIndex > 0) goToPage(pageIndex - 1)
                            else viewModel.previousChapter()
                        }

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
