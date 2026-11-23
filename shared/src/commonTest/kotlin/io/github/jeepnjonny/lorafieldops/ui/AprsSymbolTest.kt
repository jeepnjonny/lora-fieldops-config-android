package io.github.jeepnjonny.lorafieldops.ui

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class AprsSymbolTest {

    @Test
    fun cellsFollowCharacterCodes() {
        assertEquals(0, aprsSymbolCell('!'))      // police station, first cell
        assertEquals(15, aprsSymbolCell('0'))     // end of the first row
        assertEquals(29, aprsSymbolCell('>'))     // car
        assertEquals(93, aprsSymbolCell('~'))     // last cell
    }

    @Test
    fun unprintableHasNoCell() {
        assertNull(aprsSymbolCell(' '))
        assertNull(aprsSymbolCell('\u007f'))
        assertNull(aprsSymbolCell('é'))
    }
}
