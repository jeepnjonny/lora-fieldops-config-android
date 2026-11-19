package com.kj7nye.lorafieldops.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseUrlTest {

    @Test
    fun acceptsHttpsSite() {
        assertEquals(null, baseUrlError("https://apps.k7swi.org/aprs-coverage"))
        assertEquals(null, baseUrlError("  HTTPS://example.org/  "))
    }

    @Test
    fun rejectsInsecureOrOddAddresses() {
        assertNotNull(baseUrlError(""))
        assertNotNull(baseUrlError("http://apps.k7swi.org/aprs-coverage"))
        assertNotNull(baseUrlError("apps.k7swi.org/aprs-coverage"))
        assertNotNull(baseUrlError("https://user:pw@example.org"))
        assertNotNull(baseUrlError("https://example.org/?x=1"))
        assertNotNull(baseUrlError("https://exa mple.org"))
    }

    @Test
    fun hostChangeDetection() {
        assertFalse(hostChanged("https://apps.k7swi.org/aprs-coverage", "https://APPS.k7swi.org/other"))
        assertTrue(hostChanged("https://apps.k7swi.org/aprs-coverage", "https://evil.example/aprs-coverage"))
    }
}
