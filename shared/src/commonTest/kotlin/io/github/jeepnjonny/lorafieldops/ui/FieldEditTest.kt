package io.github.jeepnjonny.lorafieldops.ui

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

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
