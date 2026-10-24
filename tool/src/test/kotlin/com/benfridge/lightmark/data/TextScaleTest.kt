package com.benfridge.lightmark.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextScaleTest {

    @Test
    fun `the steps are ordered and include the default`() {
        assertEquals(TextScale.STEPS.sorted(), TextScale.STEPS)
        assertTrue(TextScale.DEFAULT in TextScale.STEPS)
    }

    @Test
    fun `cycling visits every step and returns to the start`() {
        var scale = TextScale.STEPS.first()
        val visited = mutableListOf(scale)
        repeat(TextScale.STEPS.size) {
            scale = TextScale.next(scale)
            visited += scale
        }
        assertEquals(TextScale.STEPS + TextScale.STEPS.first(), visited)
    }

    @Test
    fun `a value between steps snaps to the nearest`() {
        assertEquals(TextScale.SMALL, TextScale.nearest(0.86f))
        assertEquals(TextScale.DEFAULT, TextScale.nearest(0.97f))
        assertEquals(TextScale.LARGE, TextScale.nearest(5f))
        assertEquals(TextScale.TINY, TextScale.nearest(0f))
    }

    @Test
    fun `there are four steps, smallest first`() {
        assertEquals(4, TextScale.STEPS.size)
        assertEquals(TextScale.TINY, TextScale.STEPS.first())
        assertEquals(TextScale.LARGE, TextScale.STEPS.last())
        assertTrue(TextScale.TINY < TextScale.SMALL)
    }

    @Test
    fun `tiny stays legible rather than merely small`() {
        // 25sp design * 0.787 screen scale * TINY, which is about 14sp: the size
        // Android itself uses for body text, and roughly 2.3mm on this panel.
        val renderedSp = 25f * 0.787f * TextScale.TINY
        assertTrue(renderedSp > 13f, "would be too small to read: ${renderedSp}sp")
        assertTrue(renderedSp < 16f, "not actually smaller than Small")
    }

    @Test
    fun `cycling from an off-step value still lands on a step`() {
        assertTrue(TextScale.next(0.91f) in TextScale.STEPS)
        assertTrue(TextScale.next(-3f) in TextScale.STEPS)
    }

    @Test
    fun `every step has its own label`() {
        val labels = TextScale.STEPS.map { TextScale.labelFor(it) }
        assertEquals(labels.size, labels.distinct().size)
        assertEquals("Medium", TextScale.labelFor(TextScale.DEFAULT))
    }
}
