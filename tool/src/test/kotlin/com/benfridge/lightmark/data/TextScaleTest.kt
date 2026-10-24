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
        assertEquals(0.85f, TextScale.nearest(0.86f))
        assertEquals(1.0f, TextScale.nearest(0.97f))
        assertEquals(1.2f, TextScale.nearest(5f))
        assertEquals(0.85f, TextScale.nearest(0f))
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
