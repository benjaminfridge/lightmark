package com.benfridge.lightmark.reader

import com.benfridge.lightmark.markdown.Block
import com.benfridge.lightmark.markdown.Document
import com.benfridge.lightmark.markdown.Markdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End to end over the whole pipeline: parse, measure, paginate, place.
 *
 * The property that matters is that the pages *tile* the document — every character
 * appears on exactly one page, in order, with nothing lost or repeated. A highlight is
 * a character range into the same string, so a gap or an overlap here is a highlight
 * rendered on the wrong words, or a paragraph the reader can never reach.
 */
class DocumentTilingTest {

    /**
     * Stands in for the text measurer: fixed-width lines over each block's own text,
     * which is enough to exercise pagination without a Compose layout.
     */
    private fun measure(
        document: Document,
        charsPerLine: Int = 20,
        lineHeight: Float = 10f,
        spacing: Float = 4f,
    ): List<MeasuredBlock> = document.blocks.map { placed ->
        val length = placed.text.length
        val keepWhole = placed.block is Block.Heading || placed.block is Block.Rule
        if (length == 0) {
            // A rule has no text but still occupies a line's worth of space.
            return@map MeasuredBlock(
                lineTops = listOf(0f),
                lineBottoms = listOf(lineHeight),
                charStarts = listOf(0),
                charEnds = listOf(0),
                spacingBefore = spacing,
                keepWhole = keepWhole,
            )
        }
        val lineCount = (length + charsPerLine - 1) / charsPerLine
        MeasuredBlock(
            lineTops = List(lineCount) { it * lineHeight },
            lineBottoms = List(lineCount) { (it + 1) * lineHeight },
            charStarts = List(lineCount) { it * charsPerLine },
            charEnds = List(lineCount) { minOf((it + 1) * charsPerLine, length) },
            spacingBefore = spacing,
            keepWhole = keepWhole,
        )
    }

    private val source = """
        # Ways of reading

        A first paragraph long enough to run over several lines once it has been
        measured, so that pagination has something to split.

        ## A section

        - a list item
        - another list item that is also quite long and will wrap more than once

        > A quotation that spans a couple of lines and should be kept whole where
        > the page allows it.

        ```
        fenced(code)
        ```

        ---

        A closing paragraph, also reasonably long, to make sure the final page is
        not a special case that happens to work.
    """.trimIndent()

    private fun paginated(pageHeight: Float): Triple<Document, List<MeasuredBlock>, List<Page>> {
        val doc = Markdown.parse(source)
        val blocks = measure(doc)
        return Triple(doc, blocks, paginate(blocks, pageHeight))
    }

    @Test
    fun `every character of every block is covered exactly once`() {
        for (pageHeight in listOf(25f, 40f, 73f, 120f, 500f)) {
            val (doc, _, pages) = paginated(pageHeight)
            val covered = mutableMapOf<Int, MutableList<IntRange>>()
            pages.forEach { page ->
                page.slices.forEach { s ->
                    covered.getOrPut(s.blockIndex) { mutableListOf() }
                        .add(s.charStart until s.charEnd)
                }
            }
            doc.blocks.forEachIndexed { index, placed ->
                if (placed.text.isEmpty()) return@forEachIndexed
                val ranges = covered[index].orEmpty().sortedBy { it.first }
                assertTrue(ranges.isNotEmpty(), "h=$pageHeight block $index never placed")
                assertEquals(0, ranges.first().first, "h=$pageHeight block $index starts late")
                assertEquals(
                    placed.text.length,
                    ranges.last().last + 1,
                    "h=$pageHeight block $index ends early",
                )
                ranges.zipWithNext().forEach { (a, b) ->
                    assertEquals(
                        a.last + 1,
                        b.first,
                        "h=$pageHeight block $index has a gap or overlap",
                    )
                }
            }
        }
    }

    @Test
    fun `reassembling the pages reproduces the document text`() {
        for (pageHeight in listOf(30f, 55f, 200f)) {
            val (doc, _, pages) = paginated(pageHeight)
            val rebuilt = buildString {
                pages.forEach { page ->
                    page.slices.forEach { s ->
                        append(doc.blocks[s.blockIndex].text.substring(s.charStart, s.charEnd))
                    }
                }
            }
            val expected = doc.blocks.joinToString("") { it.text }
            assertEquals(expected, rebuilt, "page height $pageHeight")
        }
    }

    @Test
    fun `slices run in document order across the whole book`() {
        val (_, _, pages) = paginated(45f)
        val seen = pages.flatMap { it.slices }
        seen.zipWithNext().forEach { (a, b) ->
            val ordered = b.blockIndex > a.blockIndex ||
                (b.blockIndex == a.blockIndex && b.firstLine > a.lastLine)
            assertTrue(ordered, "slice order broke between block ${a.blockIndex} and ${b.blockIndex}")
        }
    }

    @Test
    fun `no page is taller than the page it was laid out for`() {
        for (pageHeight in listOf(40f, 60f, 100f)) {
            val (_, blocks, pages) = paginated(pageHeight)
            pages.forEachIndexed { index, page ->
                val placed = layoutPage(page, blocks)
                val used = placed.lastOrNull()?.bottom ?: 0f
                // The single exception is a page that could not fit even one line.
                val onlyOneLine = page.slices.size == 1 &&
                    page.slices[0].firstLine == page.slices[0].lastLine
                assertTrue(
                    used <= pageHeight || onlyOneLine,
                    "h=$pageHeight page $index used $used",
                )
            }
        }
    }

    @Test
    fun `placed slices never overlap vertically`() {
        val (_, blocks, pages) = paginated(50f)
        pages.forEach { page ->
            layoutPage(page, blocks).zipWithNext().forEach { (a, b) ->
                assertTrue(b.top >= a.bottom, "slice at ${b.top} overlaps one ending ${a.bottom}")
            }
        }
    }

    @Test
    fun `every page start offset resolves back to its own page`() {
        for (pageHeight in listOf(35f, 80f)) {
            val (doc, _, pages) = paginated(pageHeight)
            val starts = doc.blocks.map { it.start }
            pages.indices.forEach { index ->
                val offset = startOffsetOf(pages[index], starts)
                assertEquals(
                    index,
                    pageContaining(pages, starts, offset),
                    "h=$pageHeight page $index did not round-trip",
                )
            }
        }
    }

    @Test
    fun `reading forward one page at a time reaches the last page`() {
        val (doc, _, pages) = paginated(45f)
        val starts = doc.blocks.map { it.start }
        var index = 0
        var guard = 0
        while (index < pages.lastIndex && guard++ < 1000) {
            val step = stepForward(index, pages.size, hasNextChapter = false)
            index = (step as PageStep.ToPage).index
        }
        assertEquals(pages.lastIndex, index, "did not reach the end in $guard steps")
        // And the last page's offset still resolves to the last page.
        assertEquals(
            pages.lastIndex,
            pageContaining(pages, starts, startOffsetOf(pages.last(), starts)),
        )
    }

    @Test
    fun `a document of a single very long word still paginates`() {
        val doc = Markdown.parse("x".repeat(500))
        val blocks = measure(doc, charsPerLine = 20)
        val pages = paginate(blocks, 25f)
        assertTrue(pages.isNotEmpty())
        val lines = pages.sumOf { p -> p.slices.sumOf { it.lastLine - it.firstLine + 1 } }
        assertEquals(25, lines, "500 chars at 20 per line")
    }

    @Test
    fun `an empty document produces no pages and no crash`() {
        val doc = Markdown.parse("")
        assertEquals(emptyList(), paginate(measure(doc), 100f))
    }
}
