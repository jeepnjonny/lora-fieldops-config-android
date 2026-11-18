package com.kj7nye.lorafieldops.logger

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerDeviceTest {
    @Test fun usesTrackerCallsignUpperCased() = assertEquals("K7ABC-9", trackerDevice(" k7abc-9 "))
    @Test fun noCallsignYet() = assertEquals("", trackerDevice(null))
    @Test fun blankCallsign() = assertEquals("", trackerDevice("  "))
    @Test fun noCallIsIgnored() = assertEquals("", trackerDevice("NOCALL-7"))
}
