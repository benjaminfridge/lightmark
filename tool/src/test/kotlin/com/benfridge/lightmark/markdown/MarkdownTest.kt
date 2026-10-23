package com.benfridge.lightmark.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownTest {

    // ---- the invariant highlights depend on ----

    @Test
    fun `block offsets index into plain text`() {
        val doc = Markdown.parse(
            """
            # Title

            First paragraph.

            - one
            - two
            """.trimIndent()
        )
        doc.blocks.forEach { placed ->
            assertEquals(
                placed.text,
                doc.plainText.substring(placed.start, placed.end),
                "block at ${placed.start} does not match plainText",
            )
        }
    }

    @Test
    fun `offsets survive a reparse`() {
        val source = "# A\n\nsome **bold** text\n\n> quoted\n"
        val first = Markdown.parse(source)
        val second = Markdown.parse(source)
        assertEquals(first.plainText, second.plainText)
        assertEquals(first.blocks.map { it.start }, second.blocks.map { it.start })
    }

    @Test
    fun `link urls stay out of plain text`() {
        val doc = Markdown.parse("See [the docs](https://example.com/very/long/path) here.")
        assertEquals("See the docs here.", doc.plainText)
        assertTrue(doc.plainText.none { it == '/' })
    }

    // ---- blocks ----

    @Test
    fun `atx headings parse with level`() {
        val doc = Markdown.parse("### Third")
        val heading = doc.blocks.single().block as Block.Heading
        assertEquals(3, heading.level)
        assertEquals("Third", doc.plainText)
    }

    @Test
    fun `hash without space is not a heading`() {
        val doc = Markdown.parse("#hashtag")
        assertTrue(doc.blocks.single().block is Block.Paragraph)
    }

    @Test
    fun `consecutive lines fold into one paragraph`() {
        val doc = Markdown.parse("one\ntwo\nthree")
        assertEquals(1, doc.blocks.size)
        assertEquals("one two three", doc.plainText)
    }

    @Test
    fun `blank line separates paragraphs`() {
        val doc = Markdown.parse("one\n\ntwo")
        assertEquals(2, doc.blocks.size)
    }

    @Test
    fun `bullet and ordered lists become list items`() {
        val doc = Markdown.parse("- alpha\n- beta\n\n1. first\n2. second")
        val items = doc.blocks.map { it.block }.filterIsInstance<Block.ListItem>()
        assertEquals(4, items.size)
        assertEquals("1.", items[2].marker.trim())
    }

    @Test
    fun `markers are not part of plain text`() {
        val doc = Markdown.parse("- alpha")
        assertEquals("alpha", doc.plainText)
    }

    @Test
    fun `thematic break is not a list`() {
        listOf("---", "***", "___", "- - -").forEach { rule ->
            val doc = Markdown.parse(rule)
            assertTrue(doc.blocks.single().block is Block.Rule, "'$rule' should be a rule")
        }
    }

    @Test
    fun `fenced code keeps its line breaks and drops the fence`() {
        val doc = Markdown.parse("```kotlin\nval a = 1\nval b = 2\n```")
        val code = doc.blocks.single().block as Block.Code
        assertEquals("val a = 1\nval b = 2", code.text)
    }

    @Test
    fun `indented code is code`() {
        val doc = Markdown.parse("    pandoc in.pdf -o out.md")
        assertTrue(doc.blocks.single().block is Block.Code)
    }

    @Test
    fun `blockquote folds its lines`() {
        val doc = Markdown.parse("> one\n> two")
        assertTrue(doc.blocks.single().block is Block.Quote)
        assertEquals("one two", doc.plainText)
    }

    // ---- inline ----

    @Test
    fun `bold and italic produce emphasis`() {
        val spans = Markdown.parseInline("plain **bold** and *italic*")
        assertTrue(spans.any { it.text == "bold" && Emphasis.Bold in it.emphasis })
        assertTrue(spans.any { it.text == "italic" && Emphasis.Italic in it.emphasis })
    }

    @Test
    fun `inline code is not reparsed`() {
        val spans = Markdown.parseInline("use `a **b** c` here")
        val code = spans.single { Emphasis.Code in it.emphasis }
        assertEquals("a **b** c", code.text)
    }

    @Test
    fun `underscores inside a word do not italicise`() {
        val spans = Markdown.parseInline("call some_long_name now")
        assertEquals("call some_long_name now", spans.joinToString("") { it.text })
        assertTrue(spans.none { Emphasis.Italic in it.emphasis })
    }

    @Test
    fun `escapes are literal`() {
        val spans = Markdown.parseInline("literal \\*stars\\* here")
        assertEquals("literal *stars* here", spans.joinToString("") { it.text })
        assertTrue(spans.none { Emphasis.Italic in it.emphasis })
    }

    @Test
    fun `unterminated emphasis does not lose text`() {
        val spans = Markdown.parseInline("a **dangling bold")
        assertEquals("a dangling bold", spans.joinToString("") { it.text })
    }

    @Test
    fun `empty input parses to an empty document`() {
        assertTrue(Markdown.parse("").isEmpty)
        assertTrue(Markdown.parse("\n\n   \n").isEmpty)
    }
}
