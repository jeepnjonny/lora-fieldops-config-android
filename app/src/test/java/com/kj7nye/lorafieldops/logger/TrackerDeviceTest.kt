package com.kj7nye.lorafieldops.logger

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerDeviceTest {
    @Test fun usesTrackerCallsignUpperCased() = assertEquals("K7ABC-9", trackerDevice(TrackerIdentity(" k7abc-9 ")))
    @Test fun noConfigReadYet() = assertEquals("", trackerDevice(null))
    @Test fun blankCallsign() = assertEquals("", trackerDevice(TrackerIdentity("  ", "Rover1")))
    @Test fun noCallIsIgnored() = assertEquals("", trackerDevice(TrackerIdentity("NOCALL-7", "Rover1")))
    @Test fun objectModeAddsObjectName() = assertEquals("K7ABC-9:Rover1", trackerDevice(TrackerIdentity("k7abc-9", " Rover1 ")))
    @Test fun blankObjectNameIsPositionMode() = assertEquals("K7ABC-9", trackerDevice(TrackerIdentity("K7ABC-9", "   ")))
    @Test fun objectNameCappedAtNine() = assertEquals("K7ABC-9:ABCDEFGHI", trackerDevice(TrackerIdentity("K7ABC-9", "ABCDEFGHIJK")))
    @Test fun recorderSourceIsSender() = assertEquals("K7ABC-9", deviceSourceCall(trackerDevice(TrackerIdentity("K7ABC-9", "Rover1"))))
    @Test fun objectFromCallsignWithoutSsid() = assertEquals("K7SWI:CAR01", trackerDevice(TrackerIdentity("K7SWI", "CAR01")))
    @Test fun ssidZeroIsDropped() = assertEquals("K7SWI:CAR01", trackerDevice(TrackerIdentity("k7swi-0", "CAR01")))
    @Test fun ssidZeroIsDroppedWithoutObject() = assertEquals("K7SWI", trackerDevice(TrackerIdentity("K7SWI-0")))
    @Test fun ssid10IsKept() = assertEquals("K7SWI-10", trackerDevice(TrackerIdentity("K7SWI-10")))
}
