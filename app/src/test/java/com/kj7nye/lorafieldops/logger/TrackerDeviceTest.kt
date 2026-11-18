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
}
