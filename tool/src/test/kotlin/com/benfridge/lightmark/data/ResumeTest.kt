package com.benfridge.lightmark.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResumeTest {

    private fun ref(id: String, title: String, book: String? = null) = DocumentRef(
        id = id, title = title, source = DocumentSource.Shared, path = id, bookId = book,
    )

    private val chapters = listOf(
        ref("shared:wob/00-intro.md", "00 intro", "book:wob"),
        ref("shared:wob/01-thinking.md", "01 thinking", "book:wob"),
    )
    private val library = Library(
        singles = listOf(ref("shared:notes.md", "Notes")),
        books = listOf(Book("book:wob", "Ways of being", chapters)),
    )

    @Test
    fun `a chapter resumes with the rest of its book alongside it`() {
        val resume = resumeTarget(library, "shared:wob/01-thinking.md")!!
        assertEquals(1, resume.index)
        assertEquals(chapters, resume.chapters, "the whole book, so swiping carries on")
        assertEquals("01 thinking", resume.title)
        assertEquals("Ways of being", resume.bookTitle)
    }

    @Test
    fun `a loose document resumes on its own`() {
        val resume = resumeTarget(library, "shared:notes.md")!!
        assertEquals(0, resume.index)
        assertEquals(1, resume.chapters.size)
        assertNull(resume.bookTitle)
    }

    @Test
    fun `a document that has gone offers nothing rather than the wrong thing`() {
        assertNull(resumeTarget(library, "shared:wob/99-renamed.md"))
    }

    @Test
    fun `no pointer yet means nothing to resume`() {
        assertNull(resumeTarget(library, null))
        assertNull(resumeTarget(library, ""))
    }

    @Test
    fun `an empty library resumes nothing`() {
        assertNull(resumeTarget(Library(), "shared:anything.md"))
    }

    @Test
    fun `books are searched before loose documents`() {
        val clash = Library(
            singles = listOf(ref("shared:x.md", "Loose x")),
            books = listOf(Book("book:b", "B", listOf(ref("shared:x.md", "Chapter x", "book:b")))),
        )
        assertEquals("B", resumeTarget(clash, "shared:x.md")?.bookTitle)
    }
}
