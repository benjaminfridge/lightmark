package com.benfridge.lightmark.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HighlightMergeTest {

    private fun highlight(id: Long, start: Int, end: Int) =
        Highlight(id = id, documentId = "doc", start = start, end = end, createdAt = 0L)

    @Test
    fun `a new range on empty storage is kept as is`() {
        val plan = planMerge(emptyList(), 10, 20)
        assertEquals(10, plan.start)
        assertEquals(20, plan.end)
        assertTrue(plan.absorbedIds.isEmpty())
    }

    @Test
    fun `an overlapping range is absorbed and widened`() {
        val plan = planMerge(listOf(highlight(1, 5, 15)), 10, 25)
        assertEquals(5, plan.start)
        assertEquals(25, plan.end)
        assertEquals(listOf(1L), plan.absorbedIds)
    }

    @Test
    fun `a range fully inside an existing one collapses into it`() {
        val plan = planMerge(listOf(highlight(1, 0, 100)), 40, 50)
        assertEquals(0, plan.start)
        assertEquals(100, plan.end)
        assertEquals(listOf(1L), plan.absorbedIds)
    }

    @Test
    fun `touching ranges merge, which joins a highlight continued overleaf`() {
        val plan = planMerge(listOf(highlight(7, 0, 10)), 10, 20)
        assertEquals(0, plan.start)
        assertEquals(20, plan.end)
        assertEquals(listOf(7L), plan.absorbedIds)
    }

    @Test
    fun `a disjoint range absorbs nothing`() {
        val plan = planMerge(listOf(highlight(1, 0, 10)), 40, 50)
        assertEquals(40, plan.start)
        assertEquals(50, plan.end)
        assertTrue(plan.absorbedIds.isEmpty())
    }

    @Test
    fun `several overlapping ranges all collapse into one`() {
        val existing = listOf(highlight(1, 0, 10), highlight(2, 12, 18), highlight(3, 40, 45))
        val plan = planMerge(existing, 5, 20)
        assertEquals(0, plan.start)
        assertEquals(20, plan.end)
        assertEquals(listOf(1L, 2L), plan.absorbedIds)
    }


}
