package io.github.jeepnjonny.lorafieldops.model

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class FirmwareVersionTest {

    @Test
    fun parsesGitDescribeForms() {
        assertEquals(FirmwareVersion(1, 2, 6), FirmwareVersion.parse("v1.2.6"))
        assertEquals(FirmwareVersion(1, 2, 6), FirmwareVersion.parse("v1.2.6-5-gabc1234"))
        assertEquals(FirmwareVersion(1, 2, 6), FirmwareVersion.parse(" 1.2.6-5-gabc1234-dirty "))
    }

    @Test
    fun unknownIsNull() {
        assertNull(FirmwareVersion.parse("unknown"))
        assertNull(FirmwareVersion.parse("abc1234"))
        assertNull(FirmwareVersion.parse(""))
    }

    @Test
    fun ordersNumerically() {
        assertTrue(FirmwareVersion(1, 10, 0) > FirmwareVersion(1, 9, 9))
        assertTrue(FirmwareVersion(1, 2, 0) >= FirmwareVersion.MIN_WIFI_MULTI_NETWORK)
        assertTrue(FirmwareVersion(1, 1, 9) < FirmwareVersion.MIN_WIFI_MULTI_NETWORK)
        assertEquals("1.4.7", FirmwareVersion(1, 4, 7).toString())
    }

    @Test
    fun enumsMapFirmwareCodes() {
        assertEquals(DeviceRole.DIGIPEATER, DeviceRole.fromCode(2))
        assertEquals("igate", DeviceRole.fromCode(1).cli)
        assertEquals(GpsSource.FIXED, GpsSource.fromCode(1))
        assertEquals("wide1+wide2", DigiMode.fromCode(2).cli)
        // Unknown codes from newer firmware show as the first option rather than crashing.
        assertEquals(DeviceRole.TRACKER, DeviceRole.fromCode(9))
        assertEquals(listOf(0, 1, 2), DeviceRole.entries.map { it.code })
    }
}
