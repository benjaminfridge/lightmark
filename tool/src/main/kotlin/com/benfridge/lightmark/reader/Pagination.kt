package com.benfridge.lightmark.reader

/**
 * Splits a measured document into fixed-height pages, by line.
 *
 * Pure arithmetic over geometry someone else measured, so it is unit-testable and has
 * no Compose or Android types in it. The renderer measures text once, hands the line
 * boxes here, and gets back slices to draw.
 *
 * Line granularity matters: a paragraph may contribute lines 0..7 to one page and 8..12
 * to the next, which is how a book behaves. Breaking only on whole blocks would leave
 * ragged pages, and would fail outright on a paragraph taller than the screen.
 */

/** One block's measured geometry, in pixels relative to the block's own top. */
data class MeasuredBlock(
    val lineTops: List<Float>,
    val lineBottoms: List<Float>,
    /** Start of each line within the block's own text. */
    val charStarts: List<Int>,
    /** End of each line, exclusive, within the block's own text. */
    val charEnds: List<Int>,
    /** Space this block wants above it when it is not first on a page. */
    val spacingBefore: Float,
    /** Headings and rules: do not split across a page boundary unless forced. */
    val keepWhole: Boolean = false,
) {
    val lineCount: Int get() = lineTops.size

    /** Height of lines [first]..[lastInclusive]. */
    fun heightFrom(first: Int, lastInclusive: Int): Float =
        lineBottoms[lastInclusive] - lineTops[first]
}

/** A contiguous run of one block's lines, placed on a page. */
data class PageSlice(
    val blockIndex: Int,
    val firstLine: Int,
    /** Inclusive. */
    val lastLine: Int,
    /** Start within the block's own text. */
    val charStart: Int,
    /** End, exclusive, within the block's own text. */
    val charEnd: Int,
    /** Space to leave above this slice. Zero when it opens a page. */
    val spacingBefore: Float,
) {
    val isBlockStart: Boolean get() = firstLine == 0
}

data class Page(val slices: List<PageSlice>)

/**
 * Fills pages of [pageHeight] greedily, line by line.
 *
 * Two typographic rules are applied, both only when there is already content on the
 * page (so they can never stall progress):
 *  - a `keepWhole` block is pushed to the next page rather than split;
 *  - a `keepWhole` block is pushed if the following block could not then fit even one
 *    line, which is what stops a heading stranding itself at the foot of a page.
 *
 * Returns an empty list when there is nothing to lay out or the page has no height
 * yet — the caller has not measured.
 */
fun paginate(blocks: List<MeasuredBlock>, pageHeight: Float): List<Page> {
    if (blocks.isEmpty() || pageHeight <= 0f) return emptyList()

    val pages = mutableListOf<Page>()
    var current = mutableListOf<PageSlice>()
    var used = 0f

    fun flush() {
        if (current.isNotEmpty()) {
            pages += Page(current.toList())
            current = mutableListOf()
            used = 0f
        }
    }

    var blockIndex = 0
    while (blockIndex < blocks.size) {
        val block = blocks[blockIndex]
        if (block.lineCount == 0) {
            blockIndex++
            continue
        }

        var line = 0
        var openingBlock = true

        while (line < block.lineCount) {
            val spacing = if (openingBlock && current.isNotEmpty()) block.spacingBefore else 0f
            val top = used + spacing

            // The longest run of lines from `line` that still fits.
            var end = line
            while (end < block.lineCount && top + block.heightFrom(line, end) <= pageHeight) {
                end++
            }

            if (end == line) {
                if (current.isEmpty()) {
                    // The page cannot hold even one line. Emit it anyway; a clipped
                    // line beats an empty page and an infinite loop.
                    end = line + 1
                } else {
                    flush()
                    continue
                }
            } else if (block.keepWhole && end < block.lineCount && current.isNotEmpty()) {
                flush()
                continue
            } else if (block.keepWhole && end == block.lineCount && current.isNotEmpty()) {
                val next = blocks.drop(blockIndex + 1).firstOrNull { it.lineCount > 0 }
                if (next != null) {
                    val after = top + block.heightFrom(line, end - 1)
                    if (after + next.spacingBefore + next.heightFrom(0, 0) > pageHeight) {
                        flush()
                        continue
                    }
                }
            }

            current += PageSlice(
                blockIndex = blockIndex,
                firstLine = line,
                lastLine = end - 1,
                charStart = block.charStarts[line],
                charEnd = block.charEnds[end - 1],
                spacingBefore = spacing,
            )
            used = top + block.heightFrom(line, end - 1)
            line = end
            openingBlock = false
        }
        blockIndex++
    }

    flush()
    return pages
}

/**
 * The page holding [documentOffset], or 0 when nothing matches.
 *
 * [blockStarts] gives each block's offset into `Document.plainText`, so a slice's
 * absolute range is `blockStarts[blockIndex] + charStart` up to `+ charEnd`. Used to
 * turn a saved reading position back into a page after a reflow.
 */
fun pageContaining(pages: List<Page>, blockStarts: List<Int>, documentOffset: Int): Int {
    if (pages.isEmpty()) return 0
    val index = pages.indexOfFirst { page ->
        page.slices.any { slice ->
            val base = blockStarts.getOrElse(slice.blockIndex) { 0 }
            documentOffset < base + slice.charEnd
        }
    }
    return if (index >= 0) index else pages.lastIndex
}

/** The first character offset shown on [page], for persisting a reading position. */
fun startOffsetOf(page: Page, blockStarts: List<Int>): Int {
    val slice = page.slices.firstOrNull() ?: return 0
    return blockStarts.getOrElse(slice.blockIndex) { 0 } + slice.charStart
}

/** A slice with its resolved position inside the page's content box. */
data class PlacedSlice(
    val slice: PageSlice,
    val top: Float,
    val height: Float,
) {
    val bottom: Float get() = top + height
}

/**
 * Resolves a page's slices to vertical positions, by the same arithmetic [paginate]
 * used. Kept pure and shared so the renderer and the hit-tester cannot disagree about
 * where a line sits.
 */
fun layoutPage(page: Page, blocks: List<MeasuredBlock>): List<PlacedSlice> {
    var y = 0f
    return page.slices.map { slice ->
        val block = blocks[slice.blockIndex]
        val height = block.heightFrom(slice.firstLine, slice.lastLine)
        val top = y + slice.spacingBefore
        y = top + height
        PlacedSlice(slice, top, height)
    }
}

/**
 * Index of the placed slice containing [y], clamped to the nearest when [y] falls in
 * the gap between two slices or outside the page. Returns -1 only for an empty page.
 *
 * An index rather than the slice itself: two structurally equal slices would make
 * `indexOf` ambiguous, and the caller needs the index to find that slice's layout.
 */
fun sliceIndexAt(placed: List<PlacedSlice>, y: Float): Int {
    if (placed.isEmpty()) return -1
    val exact = placed.indexOfFirst { y >= it.top && y < it.bottom }
    if (exact >= 0) return exact
    return if (y < placed.first().top) 0 else placed.lastIndex
}
