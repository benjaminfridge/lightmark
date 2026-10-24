package com.benfridge.lightmark.reader

/**
 * Where a page turn goes.
 *
 * Pulled out of the screen so the boundary cases can be tested: the swipe and the
 * volume keys both route through here, and the interesting behaviour is all at the
 * edges — the last page of a chapter, the first page of the first chapter, a chapter
 * that has not paginated yet.
 */
sealed interface PageStep {
    /** Stay in this chapter, move to this page. */
    data class ToPage(val index: Int) : PageStep

    data object NextChapter : PageStep
    data object PreviousChapter : PageStep

    /** The end of the book, the start of it, or nothing laid out yet. */
    data object Nowhere : PageStep
}

/**
 * Forward: the next page, else the next chapter, else nothing.
 *
 * The target is clamped to the chapter rather than the input, because clamping the
 * input and then adding can still land past the end — `stepForward(-2, 1)` reads as
 * "before the start of a one-page chapter" and must give page 0, not page 1.
 */
fun stepForward(pageIndex: Int, pageCount: Int, hasNextChapter: Boolean): PageStep = when {
    pageCount <= 0 -> PageStep.Nowhere
    pageIndex < pageCount - 1 -> PageStep.ToPage((pageIndex + 1).coerceIn(0, pageCount - 1))
    hasNextChapter -> PageStep.NextChapter
    else -> PageStep.Nowhere
}

/** Back: the previous page, else the previous chapter at its end, else nothing. */
fun stepBackward(pageIndex: Int, pageCount: Int, hasPreviousChapter: Boolean): PageStep = when {
    pageCount <= 0 -> PageStep.Nowhere
    pageIndex > 0 -> PageStep.ToPage((pageIndex - 1).coerceIn(0, pageCount - 1))
    hasPreviousChapter -> PageStep.PreviousChapter
    else -> PageStep.Nowhere
}
