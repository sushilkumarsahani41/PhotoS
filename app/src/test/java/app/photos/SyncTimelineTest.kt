package app.photos

import org.junit.Assert.*
import org.junit.Test

class SyncTimelineTest {
    @Test fun modifiedSourceMustNeverDisplayBackedUp() {
        val stored = SyncRecord("hash", "target", "present", 100, 100, 50, 200)
        assertEquals("Backed up ✓", stored.label(50, 200))
        assertEquals("Changed", stored.label(51, 200))
        assertEquals("Changed", stored.label(50, 201))
    }
    @Test fun unverifiedSourceModificationNeedsANewCheck() {
        val stored = SyncRecord("hash", "", "missing", 100, 0, 50, 200)
        assertEquals("Not checked", stored.label(51, 200))
        assertEquals("Not backed up", stored.label(50, 200))
    }
    @Test fun missingCopyAndChangedContentAreDifferent() {
        assertEquals("missing", SyncRules.absentState(null, "new"))
        assertEquals("missing", SyncRules.absentState("same", "same"))
        assertEquals("changed", SyncRules.absentState("old", "new"))
    }
    @Test fun timelineIndexesPhotosWithoutHeaders() {
        assertEquals(listOf(0, 3, 4), TimelineIndex.groupStarts(listOf(3, 1, 12)))
        assertEquals(emptyList<Int>(), TimelineIndex.groupStarts(emptyList()))
    }
    @Test fun scrubberReachesBothEndsAndClampsOutsideTrack() {
        assertEquals(0, TimelineIndex.atFraction(-1f, 100))
        assertEquals(99, TimelineIndex.atFraction(1.5f, 100))
        assertEquals(50, TimelineIndex.atFraction(.5f, 101))
        assertEquals(0, TimelineIndex.atFraction(.8f, 1))
    }
}
