/*
 * Copyright 2026 Michael Puckett
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package de.dennisguse.opentracks.workout;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java run/walk summary computed from stored trackpoints (no Android deps, unit-testable).
 *
 * <p>Method:
 * <ul>
 *   <li>Steps are built between consecutive points of the same recorded segment. A segment
 *       boundary (start/resume/end) breaks the chain, so paused time is never counted.</li>
 *   <li>A step longer than {@link #MAX_STEP_SECONDS} is treated as a stop/gap and ignored.</li>
 *   <li>GPS spike filter, two stages: (1) any single step faster than {@link #MAX_PLAUSIBLE_MPS}
 *       (~43 km/h, impossible on foot) is dropped as a jump; (2) speeds are smoothed with a
 *       trailing rolling window of at least {@link #WINDOW_SECONDS} s (window distance / window
 *       time). Max/min speed and best/slowest pace use only these window speeds, and only once a
 *       window spans the full {@link #WINDOW_SECONDS}.</li>
 *   <li>A step is "moving" when its window speed is at least {@link #MOVING_MPS} (1.8 km/h).
 *       Stops are excluded from moving time, average speed, min speed and all paces.</li>
 *   <li>Heart rate: samples outside 30..230 bpm (incl. 0 = no reading) are ignored.</li>
 * </ul>
 */
public final class RunSummaryCalculator {

    public static final double MAX_STEP_SECONDS = 30.0;
    public static final double MAX_PLAUSIBLE_MPS = 12.0;
    public static final double WINDOW_SECONDS = 10.0;
    public static final double MOVING_MPS = 0.5;
    public static final float MIN_HR = 30f;
    public static final float MAX_HR = 230f;

    /** One stored trackpoint. lat/lon/altitude/hr may be null. sensorDeltaM overrides GPS distance. */
    public record Point(long timeMs, boolean segmentStart, Double lat, Double lon, Double sensorDeltaM, Float hr) {
    }

    public record Summary(double movingSeconds, double movingMeters,
                          double avgKmh, double maxKmh, double minKmh,
                          Float avgHr, Float maxHr, Float minHr) {

        /** min/km for a km/h value; NaN when not available. */
        public static double pace(double kmh) {
            return Double.isNaN(kmh) || kmh <= 0 ? Double.NaN : 60.0 / kmh;
        }

        public double avgPace() { return pace(avgKmh); }

        public double bestPace() { return pace(maxKmh); }

        public double slowestPace() { return pace(minKmh); }
    }

    public record IntervalRow(int position, SegmentType type, boolean completed, double seconds, double meters, Float avgHr) {
        public double kmh() {
            return seconds > 0 ? meters / seconds * 3.6 : Double.NaN;
        }
    }

    private record Step(long endMs, double seconds, double meters) {
    }

    private RunSummaryCalculator() {
    }

    public static Summary compute(List<Point> points) {
        List<Step> steps = steps(points);
        double movingS = 0, movingM = 0, max = Double.NaN, min = Double.NaN;
        // rolling window per contiguous run of steps
        int start = 0;
        double winS = 0, winM = 0;
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            if (s == null) { // chain break
                start = i + 1; winS = 0; winM = 0;
                continue;
            }
            winS += s.seconds; winM += s.meters;
            while (start < i && winS - steps.get(start).seconds >= WINDOW_SECONDS) {
                winS -= steps.get(start).seconds; winM -= steps.get(start).meters;
                start++;
            }
            double v = winS > 0 ? winM / winS : 0;
            if (v < MOVING_MPS) continue;
            movingS += s.seconds; movingM += s.meters;
            if (winS >= WINDOW_SECONDS) {
                if (Double.isNaN(max) || v > max) max = v;
                if (Double.isNaN(min) || v < min) min = v;
            }
        }
        double avg = movingS > 0 ? movingM / movingS * 3.6 : Double.NaN;
        float[] hr = hrStats(points, Long.MIN_VALUE, Long.MAX_VALUE);
        return new Summary(movingS, movingM, avg, max * 3.6, min * 3.6,
                hr == null ? null : hr[0], hr == null ? null : hr[1], hr == null ? null : hr[2]);
    }

    public static List<IntervalRow> intervals(List<RecordedSegment> segments, List<Point> points) {
        List<Step> steps = steps(points);
        List<IntervalRow> rows = new ArrayList<>();
        for (RecordedSegment seg : segments) {
            long a = seg.start().toEpochMilli(), b = seg.end().toEpochMilli();
            double m = 0;
            for (Step s : steps) {
                if (s != null && s.endMs >= a && s.endMs < b) m += s.meters;
            }
            float[] hr = hrStats(points, a, b);
            rows.add(new IntervalRow(seg.position(), seg.type(), seg.completed(), Math.max(0, b - a) / 1000.0, m, hr == null ? null : hr[0]));
        }
        return rows;
    }

    /** Returns {avg, max, min} or null when no valid HR sample in [fromMs, toMs). */
    static float[] hrStats(List<Point> points, long fromMs, long toMs) {
        double sum = 0; int n = 0; float max = 0, min = Float.MAX_VALUE;
        for (Point p : points) {
            if (p.hr == null || p.hr < MIN_HR || p.hr > MAX_HR || p.timeMs < fromMs || p.timeMs >= toMs) continue;
            sum += p.hr; n++;
            max = Math.max(max, p.hr); min = Math.min(min, p.hr);
        }
        return n == 0 ? null : new float[]{(float) (sum / n), max, min};
    }

    /** Valid steps; null entries mark a chain break (segment start, gap, or missing distance). */
    private static List<Step> steps(List<Point> points) {
        List<Step> steps = new ArrayList<>();
        Point prev = null;
        for (Point p : points) {
            if (prev == null || p.segmentStart) {
                steps.add(null);
                prev = p;
                continue;
            }
            double dt = (p.timeMs - prev.timeMs) / 1000.0;
            Double d = p.sensorDeltaM;
            if (d == null && p.lat != null && p.lon != null && prev.lat != null && prev.lon != null) {
                d = haversine(prev.lat, prev.lon, p.lat, p.lon);
            }
            if (d == null) {
                // HR-only point: keep the previous located point as anchor unless the gap is too long
                if (dt > MAX_STEP_SECONDS) { steps.add(null); prev = p; }
                continue;
            }
            if (dt <= 0) {
                continue;
            }
            if (dt > MAX_STEP_SECONDS) {
                steps.add(null);
            } else if (d / dt <= MAX_PLAUSIBLE_MPS) {
                steps.add(new Step(p.timeMs, dt, d));
            } // else: implausible jump, drop the step but keep chain (next step measured from p)
            prev = p;
        }
        return steps;
    }

    static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371008.8;
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(h));
    }
}
