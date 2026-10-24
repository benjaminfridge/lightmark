package com.benfridge.lightmark.export

import com.benfridge.lightmark.data.Highlight
import com.benfridge.lightmark.data.titleFromDocumentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HighlightExportTest {

    private fun highlight(
        id: Long,
        documentId: String,
        start: Int,
        text: String,
    ) = Highlight(
        id = id,
        documentId = documentId,
        start = start,
        end = start + text.length,
        createdAt = 0L,
        text = text,
    )

    // ---- titles ----

    @Test
    fun `a title is derived from the document id, not looked up`() {
        assertEquals("02 the wild places", titleFromDocumentId("shared:02-the-wild-places.md"))
        assertEquals("Welcome", titleFromDocumentId("bundled:welcome.md"))
    }

    @Test
    fun `an id without a source prefix still yields a title`() {
        assertEquals("Notes", titleFromDocumentId("notes.md"))
    }

    // ---- grouping ----

    @Test
    fun `highlights group by document and sort within it`() {
        val sections = sectionsFrom(
            listOf(
                highlight(1, "shared:b.md", 50, "second in b"),
                highlight(2, "shared:a.md", 10, "first in a"),
                highlight(3, "shared:b.md", 5, "first in b"),
            )
        )
        assertEquals(listOf("A", "B"), sections.map { it.title })
        assertEquals(listOf("first in b", "second in b"), sections[1].quotes)
    }

    @Test
    fun `chapters export in reading order, not alphabetical order`() {
        val sections = sectionsFrom(
            listOf(
                highlight(1, "shared:book/10-tenth.md", 0, "from ten"),
                highlight(2, "shared:book/2-second.md", 0, "from two"),
                highlight(3, "shared:book/1-first.md", 0, "from one"),
            )
        )
        assertEquals(
            listOf("1 first", "2 second", "10 tenth"),
            sections.map { it.title },
        )
    }

    @Test
    fun `no highlights means no sections`() {
        assertTrue(sectionsFrom(emptyList()).isEmpty())
    }

    // ---- formatting ----

    @Test
    fun `the document opens with a heading and a summary`() {
        val markdown = formatHighlightsMarkdown(
            sectionsFrom(listOf(highlight(1, "shared:a.md", 0, "one"))),
            exportedAt = "23 October 2026",
        )
        assertTrue(markdown.startsWith("# Highlights\n"))
        assertTrue(
            markdown.contains("1 highlight across 1 document. Exported 23 October 2026."),
            markdown,
        )
    }

    @Test
    fun `counts are pluralised`() {
        val markdown = formatHighlightsMarkdown(
            sectionsFrom(
                listOf(
                    highlight(1, "shared:a.md", 0, "one"),
                    highlight(2, "shared:b.md", 0, "two"),
                )
            ),
            exportedAt = "x",
        )
        assertTrue(markdown.contains("2 highlights across 2 documents."), markdown)
    }

    @Test
    fun `each document becomes a heading and each highlight a blockquote`() {
        val markdown = formatHighlightsMarkdown(
            sectionsFrom(
                listOf(
                    highlight(1, "shared:chapter-one.md", 0, "the first quote"),
                    highlight(2, "shared:chapter-one.md", 40, "the second quote"),
                )
            ),
            exportedAt = "x",
        )
        assertTrue(markdown.contains("## Chapter one"), markdown)
        assertTrue(markdown.contains("> the first quote"), markdown)
        assertTrue(markdown.contains("> the second quote"), markdown)
    }

    @Test
    fun `a quote spanning a paragraph break marks every line`() {
        val markdown = formatHighlightsMarkdown(
            sectionsFrom(listOf(highlight(1, "shared:a.md", 0, "end of one\nstart of two"))),
            exportedAt = "x",
        )
        assertTrue(markdown.contains("> end of one\n> start of two"), markdown)
    }

    @Test
    fun `a highlight with no recorded text is marked rather than dropped`() {
        val markdown = formatHighlightsMarkdown(
            sectionsFrom(listOf(highlight(1, "shared:a.md", 0, "   "))),
            exportedAt = "x",
        )
        assertTrue(markdown.contains("> _(text not recorded)_"), markdown)
    }

    @Test
    fun `an empty export says so instead of producing an empty file`() {
        val markdown = formatHighlightsMarkdown(emptyList(), exportedAt = "23 October 2026")
        assertTrue(markdown.contains("Nothing highlighted yet."), markdown)
    }

    @Test
    fun `the output ends with exactly one newline`() {
        val markdown = formatHighlightsMarkdown(
            sectionsFrom(listOf(highlight(1, "shared:a.md", 0, "one"))),
            exportedAt = "x",
        )
        assertTrue(markdown.endsWith("\n"))
        assertTrue(!markdown.endsWith("\n\n"), "no trailing blank lines")
    }

    @Test
    fun `the same input always renders the same file`() {
        val rows = listOf(
            highlight(1, "shared:b.md", 0, "b one"),
            highlight(2, "shared:a.md", 0, "a one"),
        )
        assertEquals(
            formatHighlightsMarkdown(sectionsFrom(rows), "x"),
            formatHighlightsMarkdown(sectionsFrom(rows.reversed()), "x"),
        )
    }

    @Test
    fun `the export path is the tool manager job key plus the file name`() {
        assertEquals("highlights", HighlightExporter.JOB_KEY)
        assertEquals("highlights/lightmark-highlights.md", HighlightExporter.RELATIVE_PATH)
        assertTrue(HighlightExporter.RELATIVE_PATH.endsWith(HighlightExporter.FILE_NAME))
    }
}
