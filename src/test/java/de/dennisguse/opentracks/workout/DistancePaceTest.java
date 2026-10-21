package de.dennisguse.opentracks.workout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Duration;
import java.util.List;

public class DistancePaceTest {

    private static Duration t(int m, int s) {
        return Duration.ofSeconds(m * 60L + s);
    }

    @Test
    public void parseDistance_kmAndMeters() {
        assertEquals(Integer.valueOf(5000), DistancePace.parseDistanceMeters("5K"));
        assertEquals(Integer.valueOf(15000), DistancePace.parseDistanceMeters("15 km"));
        assertEquals(Integer.valueOf(21100), DistancePace.parseDistanceMeters("21.1km"));
        assertEquals(Integer.valueOf(5000), DistancePace.parseDistanceMeters("5000"));
        assertEquals(Integer.valueOf(2500), DistancePace.parseDistanceMeters("2500 m"));
        assertEquals(Integer.valueOf(10000), DistancePace.parseDistanceMeters("10"));
        assertNull(DistancePace.parseDistanceMeters(""));
        assertNull(DistancePace.parseDistanceMeters("0"));
        assertNull(DistancePace.parseDistanceMeters("-5k"));
        assertNull(DistancePace.parseDistanceMeters("five"));
    }

    @Test
    public void parseFinishTime_mmssAndHmmss() {
        assertEquals(t(30, 0), DistancePace.parseFinishTime("30:00"));
        assertEquals(Duration.ofSeconds(3600 + 5 * 60 + 30), DistancePace.parseFinishTime("1:05:30"));
        assertEquals(t(95, 0), DistancePace.parseFinishTime("95:00"));
        assertNull(DistancePace.parseFinishTime("30"));
        assertNull(DistancePace.parseFinishTime("30:60"));
        assertNull(DistancePace.parseFinishTime("1:60:00"));
        assertNull(DistancePace.parseFinishTime("0:00"));
        assertNull(DistancePace.parseFinishTime("ab:cd"));
    }

    @Test
    public void parseEvery_mustBePositiveAndAtMostTotal() {
        assertEquals(Integer.valueOf(1000), DistancePace.parseEvery("1000", 5000));
        assertEquals(Integer.valueOf(5000), DistancePace.parseEvery("5000", 5000));
        assertNull(DistancePace.parseEvery("0", 5000));
        assertNull(DistancePace.parseEvery("-1", 5000));
        assertNull(DistancePace.parseEvery("5001", 5000));
        assertNull(DistancePace.parseEvery("1k", 5000));
    }

    @Test
    public void targetAt_isProportional() {
        assertEquals(t(6, 0), DistancePace.targetAt(t(30, 0), 1000, 5000));
        assertEquals(t(15, 0), DistancePace.targetAt(t(30, 0), 2500, 5000));
        assertEquals(t(30, 0), DistancePace.targetAt(t(30, 0), 5000, 5000));
    }

    @Test
    public void deltaPhrase_examples() {
        assertEquals("Twenty seconds ahead", DistancePace.deltaPhrase(t(5, 40), t(6, 0)));
        assertEquals("Thirty seconds behind", DistancePace.deltaPhrase(t(6, 30), t(6, 0)));
        assertEquals("On pace", DistancePace.deltaPhrase(t(6, 2), t(6, 0)));
        assertEquals("On pace", DistancePace.deltaPhrase(t(5, 58), t(6, 0)));
        assertEquals("Three seconds behind", DistancePace.deltaPhrase(t(6, 3), t(6, 0)));
        assertEquals("One minute behind", DistancePace.deltaPhrase(t(7, 0), t(6, 0)));
        assertEquals("One minute five seconds ahead", DistancePace.deltaPhrase(t(4, 55), t(6, 0)));
        assertEquals("Two minutes thirty-one seconds behind", DistancePace.deltaPhrase(t(8, 31), t(6, 0)));
    }

    @Test
    public void checkpoints_everyAndFinish() {
        assertEquals(List.of(1000, 2000, 3000, 4000, 5000), DistancePace.checkpoints(5000, 1000));
        assertEquals(List.of(2500, 5000, 7500, 10000, 12500, 15000), DistancePace.checkpoints(15000, 2500));
        assertEquals(List.of(4000, 5000), DistancePace.checkpoints(5000, 4000));
        assertEquals(List.of(5000), DistancePace.checkpoints(5000, 5000));
        assertEquals(List.of(5000), DistancePace.checkpoints(5000, null));
    }

    @Test
    public void tracker_speaksAtEachUpdateAndFinish() {
        DistancePace.Tracker tracker = new DistancePace.Tracker(5000, t(30, 0), 1000);
        assertNull(tracker.onProgress(500, t(2, 50)));
        assertNull(tracker.onProgress(999, t(5, 39)));
        // 1000 m crossed between 999 m at 5:39 and 1001 m at 5:41 -> 5:40, target 6:00
        assertEquals("1 kilometer. Twenty seconds ahead.", tracker.onProgress(1001, t(5, 41)));
        assertNull(tracker.onProgress(1500, t(9, 0)));
        assertEquals("2 kilometers. Thirty seconds behind.", tracker.onProgress(2000, t(12, 30)));
        assertEquals("3 kilometers. On pace.", tracker.onProgress(3000, t(18, 1)));
        assertFalse(tracker.isFinished());
        // GPS jump over 4 km and 5 km: only the finish is spoken
        assertEquals("Finish. 5 kilometers in 29 minutes 40 seconds. Twenty seconds ahead.", tracker.onProgress(5000, t(29, 40)));
        assertTrue(tracker.isFinished());
        assertEquals(t(29, 40), tracker.finishTime());
        assertNull(tracker.onProgress(5100, t(30, 10)));
    }

    @Test
    public void tracker_halfKmUpdates() {
        DistancePace.Tracker tracker = new DistancePace.Tracker(15000, Duration.ofSeconds(90 * 60), 2500);
        assertEquals("2.5 kilometers. On pace.", tracker.onProgress(2500, t(15, 0)));
        assertEquals("5 kilometers. One minute ten seconds behind.", tracker.onProgress(5000, t(31, 10)));
    }

    @Test
    public void tracker_withoutPace_onlyCompletionCue() {
        DistancePace.Tracker tracker = new DistancePace.Tracker(5000, null, 1000);
        assertEquals(List.of(5000), tracker.points());
        assertNull(tracker.onProgress(1000, t(6, 0)));
        assertNull(tracker.onProgress(4999, t(29, 59)));
        assertEquals("Distance goal reached. 5 kilometers in 30 minutes.", tracker.onProgress(5000, t(30, 0)));
        assertTrue(tracker.isFinished());
    }

    @Test
    public void tracker_skipToAfterRestart_doesNotRepeatPassedCues() {
        DistancePace.Tracker tracker = new DistancePace.Tracker(5000, t(30, 0), 1000);
        tracker.skipTo(2300, t(14, 0));
        assertNull(tracker.onProgress(2900, t(17, 30)));
        assertEquals("3 kilometers. On pace.", tracker.onProgress(3000, t(18, 0)));
    }

    @Test
    public void tracker_distanceGoingBackwardIsIgnored() {
        DistancePace.Tracker tracker = new DistancePace.Tracker(2000, t(10, 0), 1000);
        assertNull(tracker.onProgress(900, t(4, 30)));
        assertNull(tracker.onProgress(850, t(4, 40)));
        assertEquals("1 kilometer. On pace.", tracker.onProgress(1000, t(5, 0)));
    }

    @Test
    public void spokenDistanceAndClock() {
        assertEquals("500 meters", DistancePace.spokenDistance(500));
        assertEquals("1 kilometer", DistancePace.spokenDistance(1000));
        assertEquals("21.1 kilometers", DistancePace.spokenDistance(21100));
        assertEquals("1 hour 5 minutes", DistancePace.spokenClock(Duration.ofSeconds(3900)));
        assertEquals("5 km", DistancePace.shortDistance(5000));
        assertEquals("800 m", DistancePace.shortDistance(800));
    }

    @Test
    public void workout_distanceHasNoItemsAndOldConstructorIsTimed() {
        Workout distance = new Workout(1, "5K", false, List.of(new WorkoutSegment(SegmentType.RUN, t(1, 0))), 5000);
        assertTrue(distance.isDistance());
        assertTrue(distance.items().isEmpty());
        Workout timed = new Workout(2, "Intervals", false, List.of(new WorkoutSegment(SegmentType.RUN, t(1, 0))));
        assertFalse(timed.isDistance());
        assertEquals(1, timed.segmentCount());
    }
}
