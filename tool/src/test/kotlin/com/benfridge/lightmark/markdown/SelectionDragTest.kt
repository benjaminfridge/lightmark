package com.benfridge.lightmark.markdown

import com.benfridge.lightmark.ui.selectionBetween
import kotlin.test.Test
import kotlin.test.assertEquals

class SelectionDragTest {

    private val text = "the quick brown fox jumps"

    private fun span(range: com.benfridge.lightmark.ui.DocumentRange) =
        text.substring(range.start, range.end)

    @Test
    fun `dragging forward covers both end words whole`() {
        // from inside "quick" to inside "fox"
        assertEquals("quick brown fox", span(selectionBetween(text, 5, 17)))
    }

    @Test
    fun `dragging backward gives the same span`() {
        assertEquals("quick brown fox", span(selectionBetween(text, 17, 5)))
    }

    @Test
    fun `a drag that stays inside one word selects that word`() {
        assertEquals("quick", span(selectionBetween(text, 4, 8)))
    }

    @Test
    fun `a drag to the very end includes the last word`() {
        assertEquals("jumps", span(selectionBetween(text, 20, 9_999)))
    }

    @Test
    fun `a drag on empty text is empty`() {
        val range = selectionBetween("", 0, 5)
        assertEquals(0, range.start)
        assertEquals(0, range.end)
    }
}
