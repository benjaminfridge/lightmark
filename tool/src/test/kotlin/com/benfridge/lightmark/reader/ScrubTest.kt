package com.benfridge.lightmark.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScrubTest {

    private val width = 1080f
    private val inset = 40f

    @Test
    fun `the page follows the finger across the track`() {
        assertEquals(0, scrubPageAt(inset, width, inset, 10))
        assertEquals(5, scrubPageAt(width / 2f, width, inset, 10))
        assertEquals(9, scrubPageAt(width - inset, width, inset, 10))
    }

    @Test
    fun `both ends are reachable without touching the bezel`() {
        assertEquals(0, scrubPageAt(20f, width, inset, 62))
        assertEquals(61, scrubPageAt(width - 20f, width, inset, 62))
    }

    @Test
    fun `a finger past either side of the screen stays in the chapter`() {
        assertEquals(0, scrubPageAt(-300f, width, inset, 62))
        assertEquals(61, scrubPageAt(width + 300f, width, inset, 62))
    }

    @Test
    fun `every page of a long chapter can be landed on`() {
        val count = 200
        val reached = (0..width.toInt()).map { scrubPageAt(it.toFloat(), width, inset, count) }.toSet()
        assertEquals((0 until count).toSet(), reached)
    }

    @Test
    fun `moving right never goes backwards`() {
        val pages = (0..width.toInt()).map { scrubPageAt(it.toFloat(), width, inset, 37) }
        assertEquals(pages.sorted(), pages)
    }

    @Test
    fun `a chapter with nothing laid out, or no room to scrub, gives the first page`() {
        assertEquals(0, scrubPageAt(500f, width, inset, 0))
        assertEquals(0, scrubPageAt(500f, width, inset, 1))
        assertEquals(0, scrubPageAt(500f, 60f, inset, 10))
    }

    @Test
    fun `a sideways drag from the zone that keeps going is a scrub`() {
        assertTrue(startsScrub(true, dx = 60f, dy = 10f, elapsedMs = SCRUB_DELAY_MS))
        assertTrue(startsScrub(true, dx = -60f, dy = 10f, elapsedMs = 900L))
    }

    @Test
    fun `a flick from the zone stays a page turn`() {
        assertFalse(startsScrub(true, dx = 300f, dy = 0f, elapsedMs = SCRUB_DELAY_MS - 1))
    }

    @Test
    fun `a drag from anywhere else on the page is never a scrub`() {
        assertFalse(startsScrub(false, dx = 300f, dy = 0f, elapsedMs = 900L))
    }

    @Test
    fun `a mostly vertical drag from the zone is not a scrub`() {
        assertFalse(startsScrub(true, dx = 40f, dy = -200f, elapsedMs = 900L))
    }
}
