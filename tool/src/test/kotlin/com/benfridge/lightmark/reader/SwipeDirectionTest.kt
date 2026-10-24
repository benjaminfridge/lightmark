package com.benfridge.lightmark.reader

import kotlin.test.Test
import kotlin.test.assertEquals

class SwipeDirectionTest {

    private val threshold = 100f

    @Test
    fun `dragging left advances, because the content follows the finger`() {
        assertEquals(1, swipeDirection(dx = -150f, dy = 0f, threshold = threshold))
    }

    @Test
    fun `dragging right goes back`() {
        assertEquals(-1, swipeDirection(dx = 150f, dy = 0f, threshold = threshold))
    }

    @Test
    fun `a drag shorter than the threshold turns nothing`() {
        assertEquals(0, swipeDirection(-99f, 0f, threshold))
        assertEquals(0, swipeDirection(99f, 0f, threshold))
    }

    @Test
    fun `exactly the threshold counts`() {
        assertEquals(1, swipeDirection(-100f, 0f, threshold))
        assertEquals(-1, swipeDirection(100f, 0f, threshold))
    }

    @Test
    fun `a mostly vertical drag turns nothing, so scrolling intent is not a page turn`() {
        assertEquals(0, swipeDirection(-150f, 400f, threshold))
        assertEquals(0, swipeDirection(150f, -400f, threshold))
    }

    @Test
    fun `a diagonal drag at forty-five degrees is rejected rather than guessed`() {
        assertEquals(0, swipeDirection(-200f, 200f, threshold))
        assertEquals(0, swipeDirection(-200f, -200f, threshold))
    }

    @Test
    fun `a diagonal drag that is clearly horizontal still counts`() {
        assertEquals(1, swipeDirection(-300f, 120f, threshold))
    }

    @Test
    fun `no movement turns nothing`() {
        assertEquals(0, swipeDirection(0f, 0f, threshold))
    }

    @Test
    fun `direction is symmetric about zero`() {
        for (d in listOf(101f, 250f, 1000f)) {
            assertEquals(-swipeDirection(d, 0f, threshold), swipeDirection(-d, 0f, threshold))
        }
    }
}
