package de.dennisguse.opentracks.workout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class RunSummaryCalculatorTest {

    // ~1 m of latitude in degrees
    private static final double M = 1.0 / 111195.0;

    private static RunSummaryCalculator.Point p(long s, double northM, Float hr) {
        return new RunSummaryCalculator.Point(s * 1000, false, northM * M, 0.0, null, hr);
    }

    /** Points every second moving at mps for seconds, starting at t0/offset. */
    private static void walk(List<RunSummaryCalculator.Point> pts, long t0, double startM, double mps, int seconds, Float hr) {
        for (int i = 0; i <= seconds; i++) pts.add(p(t0 + i, startM + i * mps, hr));
    }

    @Test
    public void constantSpeed() {
        List<RunSummaryCalculator.Point> pts = new ArrayList<>();
        walk(pts, 0, 0, 1.5, 120, 120f);
        RunSummaryCalculator.Summary s = RunSummaryCalculator.compute(pts);
        assertEquals(5.4, s.avgKmh(), 0.05);
        assertEquals(5.4, s.maxKmh(), 0.05);
        assertEquals(5.4, s.minKmh(), 0.05);
        assertEquals(120.0, s.movingSeconds(), 0.01);
        assertEquals(60.0 / 5.4, s.avgPace(), 0.02);
    }

    @Test
    public void gpsSpikeDoesNotInflateMaxSpeed() {
        List<RunSummaryCalculator.Point> pts = new ArrayList<>();
        walk(pts, 0, 0, 1.5, 60, null);
        pts.add(p(61, 90 + 500, null)); // 500 m jump in 1 s
        walk(pts, 62, 93, 1.5, 60, null);
        RunSummaryCalculator.Summary s = RunSummaryCalculator.compute(pts);
        assertTrue("max " + s.maxKmh(), s.maxKmh() < 7.0);
    }

    @Test
    public void moderateSpikeIsSmoothedByWindow() {
        List<RunSummaryCalculator.Point> pts = new ArrayList<>();
        walk(pts, 0, 0, 1.5, 60, null);
        pts.add(p(61, 90 + 10, null)); // 10 m/s for one second (under cap)
        walk(pts, 62, 100 + 1.5, 1.5, 60, null);
        RunSummaryCalculator.Summary s = RunSummaryCalculator.compute(pts);
        // raw step would be 36 km/h; 10 s window ~ (9*1.5+10)/10 m/s = 8.5 km/h
        assertTrue("max " + s.maxKmh(), s.maxKmh() < 10.0);
    }

    @Test
    public void stopsAndPausesExcluded() {
        List<RunSummaryCalculator.Point> pts = new ArrayList<>();
        walk(pts, 0, 0, 2.0, 60, null);
        for (int i = 1; i <= 60; i++) pts.add(p(60 + i, 120, null)); // standing still 60 s
        walk(pts, 121, 120, 1.0, 60, null);
        // pause: new segment 10 minutes later, far away (would be a huge speed if counted)
        pts.add(new RunSummaryCalculator.Point(800_000, true, 2000 * M, 0.0, null, null));
        walk(pts, 801, 2000, 2.0, 60, null);
        RunSummaryCalculator.Summary s = RunSummaryCalculator.compute(pts);
        assertTrue("min " + s.minKmh(), s.minKmh() >= 1.8);
        assertTrue("max " + s.maxKmh(), s.maxKmh() <= 7.3);
        // moving time well under the 3 x 60 s of motion + some window spill-over, no stop/pause time
        assertTrue("moving " + s.movingSeconds(), s.movingSeconds() >= 170 && s.movingSeconds() <= 195);
        assertEquals(60.0 / s.minKmh(), s.slowestPace(), 1e-9);
    }

    @Test
    public void heartRateStats() {
        List<RunSummaryCalculator.Point> pts = new ArrayList<>();
        pts.add(p(0, 0, 100f));
        pts.add(p(1, 1, 0f));     // no reading
        pts.add(p(2, 2, 140f));
        pts.add(p(3, 3, null));
        pts.add(p(4, 4, 300f));   // implausible
        pts.add(p(5, 5, 120f));
        RunSummaryCalculator.Summary s = RunSummaryCalculator.compute(pts);
        assertEquals(120f, s.avgHr(), 0.01);
        assertEquals(140f, s.maxHr(), 0.01);
        assertEquals(100f, s.minHr(), 0.01);
    }

    @Test
    public void missingDataGivesNaNAndNull() {
        RunSummaryCalculator.Summary s = RunSummaryCalculator.compute(List.of());
        assertNull(s.avgHr());
        assertTrue(Double.isNaN(s.avgKmh()));
        assertTrue(Double.isNaN(s.maxKmh()));
        assertTrue(Double.isNaN(s.bestPace()));
    }

    @Test
    public void intervalRows() {
        List<RunSummaryCalculator.Point> pts = new ArrayList<>();
        walk(pts, 0, 0, 2.5, 60, 150f);
        walk(pts, 61, 152.5, 1.5, 59, 110f);
        List<RecordedSegment> segs = List.of(
                new RecordedSegment(0, SegmentType.values()[0], Instant.ofEpochSecond(0), Instant.ofEpochSecond(60), true),
                new RecordedSegment(1, SegmentType.values()[0], Instant.ofEpochSecond(60), Instant.ofEpochSecond(120), true));
        List<RunSummaryCalculator.IntervalRow> rows = RunSummaryCalculator.intervals(segs, pts);
        assertEquals(2, rows.size());
        assertEquals(60.0, rows.get(0).seconds(), 1e-9);
        assertEquals(147.5, rows.get(0).meters(), 0.5); // steps ending at 1..59 s
        assertEquals(150f, rows.get(0).avgHr(), 0.01);
        assertEquals(110.67f, rows.get(1).avgHr(), 0.01); // includes the t=60 s sample (150) at the boundary
    }
}
