package com.benfridge.lightmark

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

class ReaderViewModel(
    private val ref: DocumentRef,
    private val repository: DocumentRepository,
    private val database: LightmarkDatabase,
) : LightViewModel<Unit>() {

    val document = MutableStateFlow<Document?>(null)
    val highlights = MutableStateFlow<List<Highlight>>(emptyList())

    /** The range under the finger mid-drag. Null when nothing is being selected. */
    val selection = MutableStateFlow<DocumentRange?>(null)

    val failed = MutableStateFlow(false)
    val restoredOffset = MutableStateFlow<Int?>(null)

    private var anchorOffset: Int? = null
    private var pageOffset = 0

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            val source = repository.read(ref)
            if (source == null) {
                failed.value = true
                return@launch
            }
            document.value = Markdown.parse(source)
            restoredOffset.value = repository.offset(ref.id)
            reload()
        }
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

    private fun persist() {
        val offset = pageOffset
        viewModelScope.launch(Dispatchers.IO) { repository.saveOffset(ref.id, offset) }
    }

    private suspend fun reload() {
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

        val plain = document.value?.plainText ?: return false
        anchorOffset = documentOffset
        selection.value = snapToWord(plain, documentOffset)
        return true
    }

    fun extendSelection(documentOffset: Int) {
        val plain = document.value?.plainText ?: return
        val anchor = anchorOffset ?: return
        selection.value = selectionBetween(plain, anchor, documentOffset)
    }

    fun commitSelection() {
        val range = selection.value
        anchorOffset = null
        selection.value = null
        if (range == null || range.isEmpty) return
        val plain = document.value?.plainText ?: return

        viewModelScope.launch(Dispatchers.IO) {
            val dao = database.highlights()
            val plan = planMerge(dao.list(ref.id), range.start, range.end)
            plan.absorbedIds.forEach { dao.delete(it) }
            dao.insert(
                Highlight(
                    documentId = ref.id,
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
 * The only mark that is not text is a hairline along the bottom edge showing how far
 * through the document you are. A reader with no scrollbar and no page number is
 * otherwise read blind, and "how much is left" is something you genuinely need.
 */
class ReaderScreen(
    private val sealedActivity: SealedLightActivity,
    private val ref: DocumentRef,
) : LightScreen<Unit, ReaderViewModel>(sealedActivity) {

    override val viewModelClass: Class<ReaderViewModel> get() = ReaderViewModel::class.java

    override fun createViewModel(): ReaderViewModel = ReaderViewModel(
        ref = ref,
        repository = DocumentRepository(lightContext, lightContext.dataStore),
        database = lightContext.buildDatabase(LightmarkDatabase::class.java, LibraryScreen.DB_NAME),
    )

    @Composable
    override fun Content() {
        val document by viewModel.document.collectAsState()
        val highlights by viewModel.highlights.collectAsState()
        val selection by viewModel.selection.collectAsState()
        val failed by viewModel.failed.collectAsState()
        val restoredOffset by viewModel.restoredOffset.collectAsState()
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

                val doc = document
                when {
                    failed -> LightText(
                        text = "That document could not be read.",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.padding(
                            horizontal = with(density) { sideMargin.toDp() },
                            vertical = with(density) { topMargin.toDp() },
                        ),
                    )

                    doc == null -> Unit

                    else -> {
                        val measurer = rememberTextMeasurer()
                        val blocks = rememberMeasuredBlocks(
                            document = doc,
                            contentWidthPx = contentWidth,
                            unitPx = unitPx,
                            typography = typography,
                            measurer = measurer,
                        )
                        val pages = remember(blocks, pageHeight) { paginate(blocks, pageHeight) }
                        val blockStarts = remember(doc) { doc.blocks.map { it.start } }

                        // Position is held as a character offset and the page derived
                        // from it, not the other way round. A theme switch or an
                        // activity recreation re-measures and re-paginates; deriving
                        // the page means that keeps your place instead of resetting.
                        var readingOffset by remember(doc) { mutableIntStateOf(-1) }
                        LaunchedEffect(restoredOffset) {
                            if (readingOffset < 0) readingOffset = restoredOffset ?: 0
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

                        val placed = remember(pages, pageIndex, blocks) {
                            pages.getOrNull(pageIndex)
                                ?.let { layoutPage(it, blocks) }
                                ?: emptyList()
                        }
                        val layouts = remember(pageIndex, pages) {
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
                                    key = pageIndex to pages.size,
                                    swipeThreshold = widthPx * SWIPE_FRACTION,
                                    onPageForward = { goToPage(pageIndex + 1) },
                                    onPageBack = { goToPage(pageIndex - 1) },
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
 * How far through the document you are, as one hairline along the bottom edge.
 *
 * Not a page count and not a percentage: a number invites checking, a filling line is
 * read without being looked at.
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
