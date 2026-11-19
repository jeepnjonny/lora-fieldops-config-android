package com.kj7nye.lorafieldops.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FieldEditTest {

    @Test
    fun singleCharFieldReplacesOnType() {
        assertEquals(">", singleCharEdit("", ">"))
        assertEquals("", singleCharEdit(">", ""))
        // Cursor after the old char: keep the new one.
        assertEquals("[", singleCharEdit(">", ">["))
        // Cursor before the old char: keep the new one.
        assertEquals("[", singleCharEdit(">", "[>"))
        assertNull(singleCharEdit(">", "abc"))
    }
}
