package it.frumorn.tratto.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageTest {
    @Test
    fun tagsDesLangues() {
        assertEquals(null, Language.SISTEMA.tag)
        assertEquals("fr", Language.FR.tag)
        assertEquals("it", Language.IT.tag)
        assertEquals("en", Language.EN.tag)
    }
}
