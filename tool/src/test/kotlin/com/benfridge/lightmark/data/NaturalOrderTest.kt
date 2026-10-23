package com.benfridge.lightmark.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NaturalOrderTest {

    private fun sorted(vararg names: String) = names.toList().sortedWith(NATURAL)

    @Test
    fun `numbers order as numbers, not as text`() {
        assertEquals(
            listOf("2-x.md", "10-x.md"),
            sorted("10-x.md", "2-x.md"),
        )
    }

    @Test
    fun `an unpadded chapter set lands in reading order`() {
        assertEquals(
            listOf("1-intro.md", "2-swarm.md", "9-queens.md", "10-nuptial.md", "21-end.md"),
            sorted("21-end.md", "10-nuptial.md", "2-swarm.md", "9-queens.md", "1-intro.md"),
        )
    }

    @Test
    fun `zero padding sorts the same way`() {
        assertEquals(
            listOf("00-intro.md", "01-one.md", "09-nine.md", "10-ten.md"),
            sorted("10-ten.md", "01-one.md", "09-nine.md", "00-intro.md"),
        )
    }

    @Test
    fun `padded and unpadded mix correctly`() {
        assertEquals(
            listOf("1-a.md", "02-b.md", "3-c.md"),
            sorted("3-c.md", "1-a.md", "02-b.md"),
        )
    }

    @Test
    fun `ordering ignores case`() {
        assertEquals(listOf("alpha", "Beta", "gamma"), sorted("gamma", "Beta", "alpha"))
    }

    @Test
    fun `numbers inside the name are compared too`() {
        assertEquals(
            listOf("part-1-section-2.md", "part-1-section-10.md", "part-2-section-1.md"),
            sorted("part-2-section-1.md", "part-1-section-10.md", "part-1-section-2.md"),
        )
    }

    @Test
    fun `a prefix sorts before the longer name`() {
        assertEquals(listOf("intro", "introduction"), sorted("introduction", "intro"))
    }

    @Test
    fun `identical names compare equal`() {
        assertEquals(0, compareNatural("03-chapter.md", "03-chapter.md"))
    }

    @Test
    fun `empty strings are handled`() {
        assertEquals(0, compareNatural("", ""))
        assertTrue(compareNatural("", "a") < 0)
        assertTrue(compareNatural("a", "") > 0)
    }

    @Test
    fun `very long digit runs do not overflow`() {
        assertTrue(compareNatural("9999999999999999999999", "99999999999999999999991") < 0)
    }

    // ---- ids and titles ----

    @Test
    fun `a chapter title comes from its file name, not its folder`() {
        assertEquals(
            "02 the wild places",
            titleFromDocumentId("shared:ways-of-being/02-the-wild-places.md"),
        )
    }

    @Test
    fun `a loose document title still works`() {
        assertEquals("Notes", titleFromDocumentId("shared:notes.md"))
        assertEquals("Welcome", titleFromDocumentId("bundled:welcome.md"))
    }

    @Test
    fun `underscores and hyphens both become spaces`() {
        assertEquals("My trip notes", titleFromFileName("my-trip_notes.md"))
    }
}
