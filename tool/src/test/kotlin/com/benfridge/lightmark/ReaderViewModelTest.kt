package com.benfridge.lightmark

import com.benfridge.lightmark.data.DocumentRef
import com.benfridge.lightmark.data.DocumentSource
import com.benfridge.lightmark.data.DocumentStore
import com.benfridge.lightmark.data.Highlight
import com.benfridge.lightmark.data.HighlightDao
import com.benfridge.lightmark.data.Library
import com.benfridge.lightmark.data.ReaderSettings
import com.benfridge.lightmark.data.TextScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reader's own behaviour, with storage faked.
 *
 * Mostly here for the chapter-load race: two chapters opened in quick succession both
 * start a read, and the slower one used to land last and win.
 */
class ReaderViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // ---- fakes ----

    private class FakeStore(
        val texts: Map<String, String>,
        /** Virtual milliseconds each read takes, so ordering can be forced. */
        val readDelays: Map<String, Long> = emptyMap(),
        val savedOffsets: MutableMap<String, Int> = mutableMapOf(),
    ) : DocumentStore {
        val currentChapters = mutableMapOf<String, String>()
        var reads = 0

        override fun library() = Library()
        override suspend fun read(ref: DocumentRef): String? {
            reads++
            delay(readDelays[ref.id] ?: 0L)
            return texts[ref.id]
        }
        override suspend fun saveOffset(documentId: String, characterOffset: Int) {
            savedOffsets[documentId] = characterOffset
        }
        override suspend fun offset(documentId: String) = savedOffsets[documentId] ?: 0
        override suspend fun saveCurrentChapter(bookId: String, documentId: String) {
            currentChapters[bookId] = documentId
        }
        override suspend fun currentChapter(bookId: String) = currentChapters[bookId]
        var lastRead: String? = null
        override suspend fun saveLastRead(documentId: String) { lastRead = documentId }
        override suspend fun lastRead() = lastRead
        override fun ensureSharedDirectory() = Unit
    }

    private class FakeDao(rows: List<Highlight> = emptyList()) : HighlightDao {
        val rows = rows.toMutableList()
        private var nextId = 1L
        override suspend fun list(documentId: String) =
            rows.filter { it.documentId == documentId }.sortedBy { it.start }
        override suspend fun listAll() = rows.sortedWith(compareBy({ it.documentId }, { it.start }))
        override suspend fun totalCount() = rows.size
        override suspend fun insert(highlight: Highlight): Long {
            rows += highlight.copy(id = nextId)
            return nextId++
        }
        override suspend fun delete(id: Long) { rows.removeAll { it.id == id } }
    }

    private class FakeSettings(
        var volumeKeys: Boolean = true,
        var scale: Float = TextScale.DEFAULT,
    ) : ReaderSettings {
        override suspend fun volumeKeysTurnPages() = volumeKeys
        override suspend fun setVolumeKeysTurnPages(enabled: Boolean) { volumeKeys = enabled }
        override suspend fun textScale() = scale
        override suspend fun setTextScale(scale: Float) { this.scale = scale }
        var justify: Boolean = false
        override suspend fun justifyText() = justify
        override suspend fun setJustifyText(enabled: Boolean) { justify = enabled }
    }

    private fun chapter(name: String, book: String? = "book:b") = DocumentRef(
        id = "shared:$name",
        title = name,
        source = DocumentSource.Shared,
        path = "documents/$name",
        bookId = book,
    )

    private fun viewModel(
        store: DocumentStore,
        chapters: List<DocumentRef>,
        startIndex: Int = 0,
        dao: HighlightDao = FakeDao(),
        settings: ReaderSettings = FakeSettings(),
    ) = ReaderViewModel(chapters, startIndex, store, dao, settings, dispatcher)

    // ---- the race ----

    @Test
    fun `the chapter opened last wins even when its read finishes first`() =
        runTest(dispatcher) {
            val slow = chapter("slow.md")
            val fast = chapter("fast.md")
            // The first chapter takes 1000 virtual ms and the second 10, so without a
            // generation guard the first lands last and overwrites the second.
            val store = FakeStore(
                texts = mapOf(slow.id to "slow text", fast.id to "fast text"),
                readDelays = mapOf(slow.id to 1000L, fast.id to 10L),
            )
            val vm = viewModel(store, listOf(slow, fast))
            vm.start()
            vm.nextChapter()
            advanceUntilIdle()

            assertEquals(fast.id, vm.loaded.value?.ref?.id, "the chapter opened last must win")
            assertEquals(2, store.reads, "both reads did run, so this really raced")
        }

    @Test
    fun `a discarded load leaves no trace in the visible state`() = runTest(dispatcher) {
        val slow = chapter("slow.md")
        val fast = chapter("fast.md")
        val store = FakeStore(
            texts = mapOf(slow.id to "# Slow", fast.id to "# Fast"),
            readDelays = mapOf(slow.id to 500L, fast.id to 5L),
        )
        val vm = viewModel(store, listOf(slow, fast))
        vm.start()
        vm.nextChapter()
        advanceUntilIdle()
        assertTrue(
            vm.loaded.value?.document?.plainText?.contains("Fast") == true,
            "the visible text belongs to the winning chapter",
        )
    }

    // ---- failure handling ----

    @Test
    fun `a chapter that cannot be read reports failure`() = runTest(dispatcher) {
        val vm = viewModel(FakeStore(emptyMap()), listOf(chapter("missing.md")))
        vm.start()
        advanceUntilIdle()
        assertTrue(vm.failed.value)
        assertNull(vm.loaded.value)
    }

    @Test
    fun `the next chapter clears a previous failure`() = runTest(dispatcher) {
        val bad = chapter("missing.md")
        val good = chapter("present.md")
        val vm = viewModel(FakeStore(mapOf(good.id to "text")), listOf(bad, good))
        vm.start()
        advanceUntilIdle()
        assertTrue(vm.failed.value, "first chapter should have failed")

        vm.nextChapter()
        advanceUntilIdle()
        assertFalse(vm.failed.value, "a readable chapter must clear the error")
        assertEquals(good.id, vm.loaded.value?.ref?.id)
    }

    // ---- chapter boundaries ----

    @Test
    fun `there is no chapter after the last one`() = runTest(dispatcher) {
        val only = chapter("only.md")
        val vm = viewModel(FakeStore(mapOf(only.id to "text")), listOf(only))
        vm.start()
        advanceUntilIdle()
        assertFalse(vm.hasNext)
        assertFalse(vm.hasPrevious)
        vm.nextChapter()
        advanceUntilIdle()
        assertEquals(only.id, vm.loaded.value?.ref?.id, "still on the only chapter")
    }

    @Test
    fun `moving back from the first chapter changes nothing`() = runTest(dispatcher) {
        val a = chapter("a.md")
        val b = chapter("b.md")
        val vm = viewModel(FakeStore(mapOf(a.id to "A", b.id to "B")), listOf(a, b))
        vm.start()
        advanceUntilIdle()
        vm.previousChapter()
        advanceUntilIdle()
        assertEquals(a.id, vm.loaded.value?.ref?.id)
    }

    @Test
    fun `entering the previous chapter asks to open at its end`() = runTest(dispatcher) {
        val a = chapter("a.md")
        val b = chapter("b.md")
        val vm = viewModel(FakeStore(mapOf(a.id to "A", b.id to "B")), listOf(a, b), startIndex = 1)
        vm.start()
        advanceUntilIdle()
        vm.previousChapter()
        advanceUntilIdle()
        assertEquals(ChapterEntry.End, vm.loaded.value?.entry)
    }

    @Test
    fun `entering the next chapter asks to open at its start`() = runTest(dispatcher) {
        val a = chapter("a.md")
        val b = chapter("b.md")
        val vm = viewModel(FakeStore(mapOf(a.id to "A", b.id to "B")), listOf(a, b))
        vm.start()
        advanceUntilIdle()
        vm.nextChapter()
        advanceUntilIdle()
        assertEquals(ChapterEntry.Start, vm.loaded.value?.entry)
    }

    @Test
    fun `the first chapter opens at the saved offset`() = runTest(dispatcher) {
        val a = chapter("a.md")
        val store = FakeStore(mapOf(a.id to "some text"), savedOffsets = mutableMapOf(a.id to 42))
        val vm = viewModel(store, listOf(a))
        vm.start()
        advanceUntilIdle()
        assertEquals(ChapterEntry.Restore, vm.loaded.value?.entry)
        assertEquals(42, vm.loaded.value?.savedOffset)
    }

    // ---- book position ----

    @Test
    fun `opening a chapter records it as the book's place`() = runTest(dispatcher) {
        val a = chapter("a.md")
        val b = chapter("b.md")
        val store = FakeStore(mapOf(a.id to "A", b.id to "B"))
        val vm = viewModel(store, listOf(a, b))
        vm.start()
        advanceUntilIdle()
        assertEquals(a.id, store.currentChapters["book:b"])
        vm.nextChapter()
        advanceUntilIdle()
        assertEquals(b.id, store.currentChapters["book:b"])
    }

    @Test
    fun `the last document read is remembered, book or not`() = runTest(dispatcher) {
        val loose = chapter("notes.md", book = null)
        val store = FakeStore(mapOf(loose.id to "text"))
        val vm = viewModel(store, listOf(loose))
        vm.start()
        advanceUntilIdle()
        assertEquals(loose.id, store.lastRead, "a loose document is still where you were")
    }

    @Test
    fun `a loose document records no book position`() = runTest(dispatcher) {
        val loose = chapter("notes.md", book = null)
        val store = FakeStore(mapOf(loose.id to "text"))
        val vm = viewModel(store, listOf(loose))
        vm.start()
        advanceUntilIdle()
        assertTrue(store.currentChapters.isEmpty())
    }

    @Test
    fun `leaving a chapter saves where you were`() = runTest(dispatcher) {
        val a = chapter("a.md")
        val b = chapter("b.md")
        val store = FakeStore(mapOf(a.id to "A", b.id to "B"))
        val vm = viewModel(store, listOf(a, b))
        vm.start()
        advanceUntilIdle()
        vm.notePageOffset(123)
        vm.nextChapter()
        advanceUntilIdle()
        assertEquals(123, store.savedOffsets[a.id], "the chapter being left keeps its place")
    }

    // ---- volume keys ----

    @Test
    fun `volume down nudges forward and volume up nudges back`() = runTest(dispatcher) {
        val a = chapter("a.md")
        val vm = viewModel(FakeStore(mapOf(a.id to "text")), listOf(a))
        vm.start()
        advanceUntilIdle()

        assertTrue(vm.pressKey(VOLUME_DOWN))
        assertEquals(1, vm.pageNudge.value?.delta)
        vm.consumeNudge()

        assertTrue(vm.pressKey(VOLUME_UP))
        assertEquals(-1, vm.pageNudge.value?.delta)
    }

    @Test
    fun `two presses the same way are two distinct nudges`() = runTest(dispatcher) {
        val a = chapter("a.md")
        val vm = viewModel(FakeStore(mapOf(a.id to "text")), listOf(a))
        vm.start()
        advanceUntilIdle()
        vm.pressKey(VOLUME_DOWN)
        val first = vm.pageNudge.value
        vm.pressKey(VOLUME_DOWN)
        val second = vm.pageNudge.value
        assertTrue(first != second, "a repeated press must not be swallowed as a no-op")
    }

    @Test
    fun `the keys are left alone when the setting is off`() = runTest(dispatcher) {
        val a = chapter("a.md")
        val vm = viewModel(
            FakeStore(mapOf(a.id to "text")),
            listOf(a),
            settings = FakeSettings(volumeKeys = false),
        )
        vm.start()
        advanceUntilIdle()
        assertFalse(vm.pressKey(VOLUME_DOWN), "not consumed, so LightOS changes the volume")
        assertNull(vm.pageNudge.value)
    }

    @Test
    fun `other keys are never claimed`() = runTest(dispatcher) {
        val a = chapter("a.md")
        val vm = viewModel(FakeStore(mapOf(a.id to "text")), listOf(a))
        vm.start()
        advanceUntilIdle()
        assertFalse(vm.pressKey(KEYCODE_A))
        assertNull(volumeKeyDelta(KEYCODE_A))
    }

    // ---- selection ----

    @Test
    fun `back cancels a selection before it leaves the document`() = runTest(dispatcher) {
        val a = chapter("a.md")
        val vm = viewModel(FakeStore(mapOf(a.id to "the quick brown fox")), listOf(a))
        vm.start()
        advanceUntilIdle()

        assertTrue(vm.beginSelection(4))
        assertTrue(vm.onBackPressed(), "back is consumed while selecting")
        assertNull(vm.selection.value)
        assertFalse(vm.onBackPressed(), "with nothing selected, back leaves")
    }

    @Test
    fun `holding on an existing highlight removes it instead of selecting`() =
        runTest(dispatcher) {
            val a = chapter("a.md")
            val existing = Highlight(
                id = 7, documentId = a.id, start = 4, end = 9, createdAt = 0, text = "quick",
            )
            val dao = FakeDao(listOf(existing))
            val vm = viewModel(
                FakeStore(mapOf(a.id to "the quick brown fox")), listOf(a), dao = dao,
            )
            vm.start()
            advanceUntilIdle()

            assertFalse(vm.beginSelection(6), "a press inside a highlight does not select")
            advanceUntilIdle()
            assertTrue(dao.rows.none { it.id == 7L }, "it was removed")
            assertNull(vm.selection.value)
        }

    @Test
    fun `committing a selection stores the words, not just the offsets`() =
        runTest(dispatcher) {
            val a = chapter("a.md")
            val dao = FakeDao()
            val vm = viewModel(
                FakeStore(mapOf(a.id to "the quick brown fox")), listOf(a), dao = dao,
            )
            vm.start()
            advanceUntilIdle()
            vm.beginSelection(4)
            vm.extendSelection(6)
            vm.commitSelection()
            advanceUntilIdle()
            assertEquals(1, dao.rows.size)
            assertEquals("quick", dao.rows.single().text)
        }

    private companion object {
        // android.view.KeyEvent constants, inlined at compile time.
        const val VOLUME_UP = 24
        const val VOLUME_DOWN = 25
        const val KEYCODE_A = 29
    }
}
