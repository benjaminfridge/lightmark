package com.benfridge.lightmark.reader

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Page turning, especially at the two places it is easy to get wrong: the join between
 * chapters, and the ends of the book.
 */
class PageStepTest {

    // ---- forward, inside a chapter ----

    @Test
    fun `forward from the first of several pages`() {
        assertEquals(PageStep.ToPage(1), stepForward(0, 10, hasNextChapter = true))
    }

    @Test
    fun `forward from the middle`() {
        assertEquals(PageStep.ToPage(6), stepForward(5, 10, hasNextChapter = false))
    }

    // ---- forward, at the chapter join ----

    @Test
    fun `forward from the last page enters the next chapter`() {
        assertEquals(PageStep.NextChapter, stepForward(9, 10, hasNextChapter = true))
    }

    @Test
    fun `forward from the last page of the last chapter goes nowhere`() {
        assertEquals(PageStep.Nowhere, stepForward(9, 10, hasNextChapter = false))
    }

    @Test
    fun `a single-page chapter still hands on to the next`() {
        assertEquals(PageStep.NextChapter, stepForward(0, 1, hasNextChapter = true))
    }

    @Test
    fun `a single-page document with nowhere to go stays put`() {
        assertEquals(PageStep.Nowhere, stepForward(0, 1, hasNextChapter = false))
    }

    // ---- back, inside a chapter ----

    @Test
    fun `back from the middle`() {
        assertEquals(PageStep.ToPage(4), stepBackward(5, 10, hasPreviousChapter = true))
    }

    @Test
    fun `back from the last page`() {
        assertEquals(PageStep.ToPage(8), stepBackward(9, 10, hasPreviousChapter = false))
    }

    // ---- back, at the chapter join ----

    @Test
    fun `back from the first page enters the previous chapter`() {
        assertEquals(PageStep.PreviousChapter, stepBackward(0, 10, hasPreviousChapter = true))
    }

    @Test
    fun `back from the very start of the book goes nowhere`() {
        assertEquals(PageStep.Nowhere, stepBackward(0, 10, hasPreviousChapter = false))
    }

    // ---- nothing laid out yet ----

    @Test
    fun `neither direction moves before the chapter has paginated`() {
        assertEquals(PageStep.Nowhere, stepForward(0, 0, hasNextChapter = true))
        assertEquals(PageStep.Nowhere, stepBackward(0, 0, hasPreviousChapter = true))
    }

    @Test
    fun `a negative page count is treated as nothing laid out`() {
        assertEquals(PageStep.Nowhere, stepForward(0, -1, hasNextChapter = true))
        assertEquals(PageStep.Nowhere, stepBackward(3, -1, hasPreviousChapter = true))
    }

    // ---- out-of-range input is clamped, never thrown ----

    @Test
    fun `a page index past the end still advances the chapter rather than overrunning`() {
        assertEquals(PageStep.NextChapter, stepForward(99, 10, hasNextChapter = true))
        assertEquals(PageStep.Nowhere, stepForward(99, 10, hasNextChapter = false))
    }

    @Test
    fun `a negative page index lands at the start, not off the front`() {
        assertEquals(PageStep.ToPage(0), stepForward(-5, 10, hasNextChapter = false))
        assertEquals(PageStep.Nowhere, stepBackward(-5, 10, hasPreviousChapter = false))
    }

    @Test
    fun `forward in a one-page chapter never targets page one`() {
        // From before the start, forward reaches the only page there is.
        assertEquals(PageStep.ToPage(0), stepForward(-2, 1, hasNextChapter = false))
        // From the page itself there is nowhere left to go inside the chapter.
        assertEquals(PageStep.Nowhere, stepForward(0, 1, hasNextChapter = false))
    }

    // ---- the invariant that matters ----

    @Test
    fun `a step never targets a page outside the chapter`() {
        for (count in 1..6) {
            for (index in -2..count + 2) {
                listOf(
                    stepForward(index, count, hasNextChapter = true),
                    stepForward(index, count, hasNextChapter = false),
                    stepBackward(index, count, hasPreviousChapter = true),
                    stepBackward(index, count, hasPreviousChapter = false),
                ).forEach { step ->
                    if (step is PageStep.ToPage) {
                        assertEquals(
                            step.index.coerceIn(0, count - 1),
                            step.index,
                            "count=$count index=$index gave ${step.index}",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `forward then back returns to the same page away from the edges`() {
        val count = 8
        for (index in 1 until count - 1) {
            val forward = stepForward(index, count, hasNextChapter = false)
            val there = (forward as PageStep.ToPage).index
            val back = stepBackward(there, count, hasPreviousChapter = false)
            assertEquals(PageStep.ToPage(index), back, "round trip from $index")
        }
    }
}
