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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.benfridge.lightmark.data.Book
import com.benfridge.lightmark.data.DocumentRepository
import com.benfridge.lightmark.data.LightmarkDatabase
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.ui.LightBarButton
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

/** A chapter with what the list needs to say about it. */
data class ChapterRow(
    val index: Int,
    val title: String,
    val highlightCount: Int,
    val isCurrent: Boolean,
)

class BookViewModel(
    private val book: Book,
    private val repository: DocumentRepository,
    private val database: LightmarkDatabase,
) : LightViewModel<Unit>() {

    val rows = MutableStateFlow<List<ChapterRow>>(emptyList())
    val currentIndex = MutableStateFlow(0)
    val loaded = MutableStateFlow(false)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            val current = repository.currentChapter(book.id)
            val counts = database.highlights().listAll().groupingBy { it.documentId }.eachCount()
            val index = book.chapters.indexOfFirst { it.id == current }.coerceAtLeast(0)
            currentIndex.value = index
            rows.value = book.chapters.mapIndexed { i, ref ->
                ChapterRow(
                    index = i,
                    title = ref.title,
                    highlightCount = counts[ref.id] ?: 0,
                    isCurrent = i == index && current != null,
                )
            }
            loaded.value = true
        }
    }
}

/**
 * The chapters of one book, in order.
 *
 * A split book needs somewhere to answer "where am I", which a flat library of thirty
 * equal rows cannot. The chapter you were last in is marked, and opening any chapter
 * hands the whole list to the reader so swiping carries straight on into the next one.
 */
class BookScreen(
    sealedActivity: SealedLightActivity,
    private val book: Book,
) : LightScreen<Unit, BookViewModel>(sealedActivity) {

    override val viewModelClass: Class<BookViewModel> get() = BookViewModel::class.java

    override fun createViewModel(): BookViewModel = BookViewModel(
        book = book,
        repository = DocumentRepository(lightContext, lightContext.dataStore),
        database = lightContext.buildDatabase(LightmarkDatabase::class.java, LibraryScreen.DB_NAME),
    )

    @Composable
    override fun Content() {
        val rows by viewModel.rows.collectAsState()
        val currentIndex by viewModel.currentIndex.collectAsState()
        val loaded by viewModel.loaded.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(book.title),
                )

                if (!loaded) return@Column

                LightScrollView(modifier = Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
                    if (rows.any { it.isCurrent }) {
                        ResumeRow(rows[currentIndex]) { open(currentIndex) }
                    }
                    rows.forEach { row ->
                        ChapterRowView(row) { open(row.index) }
                    }
                    Spacer(Modifier.height(1f.gridUnitsAsDp()))
                }
            }
        }
    }

    private fun open(index: Int) {
        navigateTo<Unit>({ activity -> ReaderScreen(activity, book.chapters, index) })
    }
}

/** The one row that gets you back to where you stopped, without hunting the list. */
@Composable
private fun ResumeRow(row: ChapterRow, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable { onClick() }
            .padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(text = "Continue reading", variant = LightTextVariant.Copy)
        LightText(text = row.title, variant = LightTextVariant.Detail, lighten = true)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 0.4f.gridUnitsAsDp())
            .height(1.dp)
            .background(LightThemeTokens.colors.contentSecondary),
    )
}

@Composable
private fun ChapterRowView(row: ChapterRow, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable { onClick() }
            .padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 0.6f.gridUnitsAsDp()),
    ) {
        // A dot in the margin marks where you are, rather than a word that would have to
        // be read on every row.
        Box(modifier = Modifier.width(1f.gridUnitsAsDp())) {
            if (row.isCurrent) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .height(8.dp)
                        .width(8.dp)
                        .background(LightThemeTokens.colors.content),
                )
            }
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            LightText(text = row.title, variant = LightTextVariant.Copy)
            if (row.highlightCount > 0) {
                LightText(
                    text = if (row.highlightCount == 1) "1 highlight"
                    else "${row.highlightCount} highlights",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                )
            }
        }
    }
}
