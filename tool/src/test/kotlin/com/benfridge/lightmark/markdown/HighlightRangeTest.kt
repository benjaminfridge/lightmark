package com.benfridge.lightmark.markdown

import com.benfridge.lightmark.ui.DocumentRange
import com.benfridge.lightmark.ui.merged
import com.benfridge.lightmark.ui.snapToWord
import kotlin.test.Test
import kotlin.test.assertEquals

class HighlightRangeTest {

    private val text = "the quick brown fox"

    @Test
    fun `snapping grows to word bounds`() {
        val range = snapToWord(text, 6) // inside "quick"
        assertEquals("quick", text.substring(range.start, range.end))
    }

    @Test
    fun `snapping at a word's first and last character picks the same word`() {
        assertEquals(snapToWord(text, 4), snapToWord(text, 8))
    }

    @Test
    fun `snapping keeps hyphens and apostrophes together`() {
        val s = "a well-known don't case"
        assertEquals("well-known", s.substring(snapToWord(s, 4).let { it.start } , snapToWord(s, 4).end))
        assertEquals("don't", s.substring(snapToWord(s, 16).start, snapToWord(s, 16).end))
    }

    @Test
    fun `snapping on whitespace takes one character`() {
        val range = snapToWord(text, 3)
        assertEquals(1, range.end - range.start)
    }

    @Test
    fun `snapping past the end clamps`() {
        val range = snapToWord(text, 9999)
        assertEquals("fox", text.substring(range.start, range.end))
    }

    @Test
    fun `empty text snaps to empty`() {
        assertEquals(DocumentRange(0, 0), snapToWord("", 3))
    }

    @Test
    fun `overlapping ranges merge`() {
        val merged = listOf(DocumentRange(0, 10), DocumentRange(5, 20)).merged()
        assertEquals(listOf(DocumentRange(0, 20)), merged)
    }

    @Test
    fun `touching ranges merge`() {
        val merged = listOf(DocumentRange(0, 10), DocumentRange(10, 15)).merged()
        assertEquals(listOf(DocumentRange(0, 15)), merged)
    }

    @Test
    fun `disjoint ranges are left alone`() {
        val input = listOf(DocumentRange(0, 5), DocumentRange(10, 15))
        assertEquals(input, input.merged())
    }

    @Test
    fun `contains is half open`() {
        val range = DocumentRange(2, 5)
        assertEquals(true, 2 in range)
        assertEquals(true, 4 in range)
        assertEquals(false, 5 in range)
    }
}
