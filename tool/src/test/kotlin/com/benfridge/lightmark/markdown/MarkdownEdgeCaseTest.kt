package com.benfridge.lightmark.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Adversarial input. A reader is fed whatever a PDF converter produced, so the parser
 * has to survive malformed markup without losing text or throwing.
 */
class MarkdownEdgeCaseTest {

    private fun roundTrips(doc: Document) {
        doc.blocks.forEach { placed ->
            assertEquals(
                placed.text,
                doc.plainText.substring(placed.start, placed.end),
                "block at ${placed.start} does not index into plainText",
            )
        }
    }

    // ---- line endings and whitespace ----

    @Test
    fun `windows line endings parse the same as unix`() {
        val unix = Markdown.parse("# A\n\none\n\ntwo")
        val windows = Markdown.parse("# A\r\n\r\none\r\n\r\ntwo")
        assertEquals(unix.plainText, windows.plainText)
        assertEquals(unix.blocks.size, windows.blocks.size)
    }

    @Test
    fun `old mac line endings parse too`() {
        assertEquals(Markdown.parse("one\n\ntwo").plainText, Markdown.parse("one\r\rtwo").plainText)
    }

    @Test
    fun `trailing whitespace does not create empty blocks`() {
        val doc = Markdown.parse("one   \n\n   \n\ntwo   ")
        assertEquals(2, doc.blocks.size)
    }

    @Test
    fun `a document of only whitespace is empty`() {
        assertTrue(Markdown.parse("   \n\t\n  \n").isEmpty)
    }

    // ---- headings ----

    @Test
    fun `an empty heading is dropped rather than rendered as a hash`() {
        assertTrue(Markdown.parse("# ").isEmpty)
        assertTrue(Markdown.parse("###").isEmpty)
        assertTrue(Markdown.parse("one\n\n##\n\ntwo").blocks.none {
            it.text.trim().all { c -> c == '#' } && it.text.isNotEmpty()
        })
    }

    @Test
    fun `seven hashes alone is still a paragraph`() {
        assertTrue(Markdown.parse("#######").blocks.single().block is Block.Paragraph)
    }

    @Test
    fun `seven hashes is a paragraph, not a heading`() {
        val doc = Markdown.parse("####### too deep")
        assertTrue(doc.blocks.single().block is Block.Paragraph)
    }

    @Test
    fun `closing hashes are trimmed`() {
        assertEquals("Title", Markdown.parse("## Title ##").plainText)
    }

    @Test
    fun `a heading directly after a paragraph still breaks the paragraph`() {
        val doc = Markdown.parse("some text\n# Heading")
        assertEquals(2, doc.blocks.size)
        assertTrue(doc.blocks[1].block is Block.Heading)
    }

    // ---- code ----

    @Test
    fun `an unclosed fence takes the rest of the document rather than dropping it`() {
        val doc = Markdown.parse("```\nval a = 1\nval b = 2")
        val code = doc.blocks.single().block as Block.Code
        assertTrue(code.text.contains("val a = 1"))
        assertTrue(code.text.contains("val b = 2"))
    }

    @Test
    fun `tilde fences work like backtick fences`() {
        val doc = Markdown.parse("~~~\nplain\n~~~")
        assertTrue(doc.blocks.single().block is Block.Code)
    }

    @Test
    fun `a fence with a language tag drops the tag`() {
        val doc = Markdown.parse("```kotlin\nx\n```")
        assertEquals("x", (doc.blocks.single().block as Block.Code).text)
    }

    @Test
    fun `tab-indented code is code`() {
        assertTrue(Markdown.parse("\tindented").blocks.single().block is Block.Code)
    }

    // ---- lists ----

    @Test
    fun `parenthesised ordinals are list items`() {
        val doc = Markdown.parse("1) first\n2) second")
        assertEquals(2, doc.blocks.count { it.block is Block.ListItem })
    }

    @Test
    fun `a four-digit number is not a list marker`() {
        val doc = Markdown.parse("1066. the battle of hastings")
        assertTrue(doc.blocks.single().block is Block.Paragraph)
    }

    @Test
    fun `an indented continuation folds into its list item`() {
        val doc = Markdown.parse("- first line\n  second line\n- next")
        val items = doc.blocks.map { it.block }.filterIsInstance<Block.ListItem>()
        assertEquals(2, items.size)
        assertTrue(doc.plainText.startsWith("first line second line"))
    }

    // ---- inline ----

    @Test
    fun `an unclosed code span keeps its backtick as text`() {
        val spans = Markdown.parseInline("a `dangling span")
        assertEquals("a `dangling span", spans.joinToString("") { it.text })
    }

    @Test
    fun `a link with an empty target keeps its text`() {
        assertEquals("text", Markdown.parse("[text]()").plainText)
    }

    @Test
    fun `a bracket that is not a link stays literal`() {
        assertEquals("[not a link]", Markdown.parse("[not a link]").plainText)
    }

    @Test
    fun `emphasis inside a link survives`() {
        val spans = Markdown.parseInline("[see **this**](http://x)")
        assertTrue(spans.any { Emphasis.Link in it.emphasis && Emphasis.Bold in it.emphasis })
    }

    @Test
    fun `nested bold and italic both apply`() {
        val spans = Markdown.parseInline("**bold with *italic* inside**")
        assertTrue(spans.any { Emphasis.Bold in it.emphasis && Emphasis.Italic in it.emphasis })
    }

    @Test
    fun `a trailing backslash does not run off the end`() {
        assertEquals("text\\", Markdown.parse("text\\").plainText)
    }

    @Test
    fun `asterisks used as footnote marks do not swallow the paragraph`() {
        val text = Markdown.parse("see note * and another * here").plainText
        assertTrue(text.contains("see note"))
        assertTrue(text.contains("here"))
    }

    // ---- the invariant, under pressure ----

    @Test
    fun `offsets survive a document that uses everything at once`() {
        val doc = Markdown.parse(
            """
            # Title ##

            A paragraph with **bold**, *italic*, `code`, a [link](http://example.com)
            and some_snake_case that must not become italic.

            > A quote
            > over two lines

            - one
            - two with a
              continuation
            1. ordered
            2) also ordered

            ```kotlin
            fenced(code)
            ```

                indented code

            ---

            Final paragraph.
            """.trimIndent()
        )
        roundTrips(doc)
        assertTrue(doc.blocks.size > 8, "expected a rich document, got ${doc.blocks.size}")
        assertTrue(doc.plainText.contains("some_snake_case"))
        assertTrue(!doc.plainText.contains("example.com"), "link targets stay out of plain text")
    }

    @Test
    fun `block offsets are strictly increasing and never overlap`() {
        val doc = Markdown.parse("# A\n\none\n\n- two\n\n> three\n\n---\n\nfour")
        doc.blocks.zipWithNext().forEach { (a, b) ->
            assertTrue(b.start >= a.end, "block at ${b.start} overlaps the one ending ${a.end}")
        }
    }

    @Test
    fun `a very long paragraph is one block`() {
        val doc = Markdown.parse((1..500).joinToString(" ") { "word$it" })
        assertEquals(1, doc.blocks.size)
        roundTrips(doc)
    }
}
