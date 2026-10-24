package com.benfridge.lightmark.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaginationTest {

    /** A block of uniform lines: easier to reason about than real text metrics. */
    private fun block(
        lines: Int,
        lineHeight: Float = 10f,
        spacing: Float = 5f,
        keepWhole: Boolean = false,
        charsPerLine: Int = 10,
    ) = MeasuredBlock(
        lineTops = List(lines) { it * lineHeight },
        lineBottoms = List(lines) { (it + 1) * lineHeight },
        charStarts = List(lines) { it * charsPerLine },
        charEnds = List(lines) { (it + 1) * charsPerLine },
        spacingBefore = spacing,
        keepWhole = keepWhole,
    )

    private fun Page.lineRanges() = slices.map { it.blockIndex to (it.firstLine..it.lastLine) }

    // ---- filling ----

    @Test
    fun `a block shorter than the page is one slice on one page`() {
        val pages = paginate(listOf(block(2)), pageHeight = 100f)
        assertEquals(1, pages.size)
        assertEquals(listOf(0 to 0..1), pages[0].lineRanges())
    }

    @Test
    fun `a long paragraph splits by line across pages`() {
        val pages = paginate(listOf(block(5)), pageHeight = 30f)
        assertEquals(2, pages.size)
        assertEquals(listOf(0 to 0..2), pages[0].lineRanges())
        assertEquals(listOf(0 to 3..4), pages[1].lineRanges())
    }

    @Test
    fun `slice character ranges tile the block with no gaps`() {
        val pages = paginate(listOf(block(5, charsPerLine = 7)), pageHeight = 30f)
        val slices = pages.flatMap { it.slices }
        assertEquals(0, slices.first().charStart)
        slices.zipWithNext().forEach { (a, b) ->
            assertEquals(a.charEnd, b.charStart, "slices must tile")
        }
        assertEquals(35, slices.last().charEnd)
    }

    @Test
    fun `spacing applies between blocks but not at the top of a page`() {
        // A fills 20; B needs 5 spacing + 20 => 45 exactly.
        val pages = paginate(listOf(block(2), block(2)), pageHeight = 45f)
        assertEquals(1, pages.size)
        assertEquals(0f, pages[0].slices[0].spacingBefore)
        assertEquals(5f, pages[0].slices[1].spacingBefore)
    }

    @Test
    fun `a block opening a page gets no leading spacing`() {
        val pages = paginate(listOf(block(2), block(2)), pageHeight = 25f)
        assertEquals(2, pages.size)
        assertEquals(0f, pages[1].slices.first().spacingBefore)
    }

    // ---- typographic rules ----

    @Test
    fun `a heading is moved rather than split`() {
        val blocks = listOf(block(3), block(2, keepWhole = true))
        val pages = paginate(blocks, pageHeight = 40f)
        assertEquals(2, pages.size)
        assertEquals(listOf(0 to 0..2), pages[0].lineRanges())
        assertEquals(listOf(1 to 0..1), pages[1].lineRanges())
    }

    @Test
    fun `a heading is not stranded at the foot of a page`() {
        // Heading fits at the bottom, but then no body line would.
        val blocks = listOf(block(2), block(1, keepWhole = true), block(2))
        val pages = paginate(blocks, pageHeight = 45f)
        assertEquals(2, pages.size)
        assertEquals(listOf(0 to 0..1), pages[0].lineRanges())
        assertTrue(pages[1].slices.first().blockIndex == 1, "heading should lead page 2")
        assertTrue(pages[1].slices.any { it.blockIndex == 2 }, "body should follow it")
    }

    @Test
    fun `a heading stays put when its body can follow`() {
        val blocks = listOf(block(2), block(1, keepWhole = true), block(2))
        val pages = paginate(blocks, pageHeight = 50f)
        assertEquals(setOf(0, 1, 2), pages[0].slices.map { it.blockIndex }.toSet())
    }

    @Test
    fun `a keepWhole block taller than a page is split rather than dropped`() {
        val pages = paginate(listOf(block(5, keepWhole = true)), pageHeight = 30f)
        assertEquals(2, pages.size)
        assertEquals(5, pages.sumOf { p -> p.slices.sumOf { it.lastLine - it.firstLine + 1 } })
    }

    // ---- degenerate input ----

    @Test
    fun `no blocks means no pages`() {
        assertEquals(emptyList(), paginate(emptyList(), 100f))
    }

    @Test
    fun `an unmeasured page height yields no pages`() {
        assertEquals(emptyList(), paginate(listOf(block(3)), 0f))
        assertEquals(emptyList(), paginate(listOf(block(3)), -5f))
    }

    @Test
    fun `a page too short for one line still makes progress`() {
        val pages = paginate(listOf(block(3)), pageHeight = 4f)
        assertEquals(3, pages.size)
        assertEquals(3, pages.sumOf { it.slices.size })
    }

    @Test
    fun `blocks with no lines are skipped`() {
        val blocks = listOf(block(1), block(0), block(1))
        val pages = paginate(blocks, pageHeight = 100f)
        assertEquals(listOf(0, 2), pages.single().slices.map { it.blockIndex })
    }

    @Test
    fun `every line appears exactly once across all pages`() {
        val blocks = listOf(block(7), block(1, keepWhole = true), block(11), block(3))
        val pages = paginate(blocks, pageHeight = 37f)
        val seen = mutableListOf<Pair<Int, Int>>()
        pages.forEach { page ->
            page.slices.forEach { s ->
                (s.firstLine..s.lastLine).forEach { seen += s.blockIndex to it }
            }
        }
        assertEquals(seen.size, seen.distinct().size, "no line repeated")
        assertEquals(7 + 1 + 11 + 3, seen.size, "no line lost")
    }

    // ---- geometry ----

    @Test
    fun `layoutPage stacks slices by height plus spacing`() {
        val blocks = listOf(block(2), block(2))
        val pages = paginate(blocks, pageHeight = 45f)
        val placed = layoutPage(pages[0], blocks)
        assertEquals(0f, placed[0].top)
        assertEquals(20f, placed[0].bottom)
        assertEquals(25f, placed[1].top)
        assertEquals(45f, placed[1].bottom)
    }

    @Test
    fun `sliceIndexAt finds the slice under a y position`() {
        val blocks = listOf(block(2), block(2))
        val placed = layoutPage(paginate(blocks, 45f)[0], blocks)
        assertEquals(0, sliceIndexAt(placed, 5f))
        assertEquals(1, sliceIndexAt(placed, 30f))
    }

    @Test
    fun `sliceIndexAt clamps outside the page and in the gap`() {
        val blocks = listOf(block(2), block(2))
        val placed = layoutPage(paginate(blocks, 45f)[0], blocks)
        assertEquals(0, sliceIndexAt(placed, -50f))
        assertEquals(1, sliceIndexAt(placed, 9999f))
        // Slice 0 ends at 20 and slice 1 starts at 25, so y=22 is nearer slice 0.
        assertEquals(0, sliceIndexAt(placed, 22f))
        assertEquals(1, sliceIndexAt(placed, 23f))
        assertEquals(-1, sliceIndexAt(emptyList(), 10f))
    }

    @Test
    fun `a gap between early slices resolves to a neighbour, not the last slice`() {
        // Six slices, each 20 tall with 5 of spacing. A press in the gap after the
        // first must not land in the sixth: that is a highlight on the wrong paragraph.
        val blocks = List(6) { block(2) }
        val placed = layoutPage(paginate(blocks, 1000f)[0], blocks)
        assertEquals(6, placed.size)
        val gapAfterFirst = placed[0].bottom + 2f
        val index = sliceIndexAt(placed, gapAfterFirst)
        assertTrue(index <= 1, "gap after slice 0 resolved to slice $index")
    }

    @Test
    fun `a gap resolves to whichever slice is nearer`() {
        val blocks = List(4) { block(2) }
        val placed = layoutPage(paginate(blocks, 1000f)[0], blocks)
        val gapTop = placed[1].bottom
        val gapBottom = placed[2].top
        assertEquals(1, sliceIndexAt(placed, gapTop + 0.1f), "just below slice 1")
        assertEquals(2, sliceIndexAt(placed, gapBottom - 0.1f), "just above slice 2")
    }

    @Test
    fun `every slice is reachable by its own midpoint`() {
        val blocks = List(5) { block(3) }
        val placed = layoutPage(paginate(blocks, 1000f)[0], blocks)
        placed.forEachIndexed { i, slice ->
            assertEquals(i, sliceIndexAt(placed, slice.top + slice.height / 2f))
        }
    }

    // ---- position round-tripping ----

    @Test
    fun `a page's start offset resolves back to that page`() {
        val blocks = listOf(block(5, charsPerLine = 10))
        val starts = listOf(0)
        val pages = paginate(blocks, pageHeight = 30f)
        pages.indices.forEach { index ->
            val offset = startOffsetOf(pages[index], starts)
            assertEquals(index, pageContaining(pages, starts, offset), "page $index round-trip")
        }
    }

    @Test
    fun `an offset past the end lands on the last page`() {
        val blocks = listOf(block(5))
        val pages = paginate(blocks, pageHeight = 30f)
        assertEquals(pages.lastIndex, pageContaining(pages, listOf(0), 99_999))
    }

    @Test
    fun `position queries on an empty document are safe`() {
        assertEquals(0, pageContaining(emptyList(), emptyList(), 10))
        assertEquals(0, startOffsetOf(Page(emptyList()), emptyList()))
    }
}
