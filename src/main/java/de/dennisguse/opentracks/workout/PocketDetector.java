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
import java.util.Locale;

/**
 * Detects the phone being taken out of a (thigh) pocket from the gravity vector.
 * In the pocket the phone hangs upright, so gravity lies along its long axis (|y| ~ 9.3).
 * In the hand the screen faces up, so gravity moves to the z axis.
 * Tuned on the 2026-10-14 15 min walk (Pixel 7): exactly one 18 s event, no false positives.
 */
public final class PocketDetector {

    static final double OUT_MAX_ABS_Y = 5.0;
    static final double OUT_MIN_ABS_Z = 7.0;
    static final long OUT_HOLD_NS = 2_000_000_000L;
    static final double IN_MIN_ABS_Y = 8.0;
    static final long IN_HOLD_NS = 1_500_000_000L;

    public record Event(long startWallMs, long endWallMs) {
        public long durationMs() {
            return Math.max(0, endWallMs - startWallMs);
        }
    }

    private enum State {UNKNOWN, IN, OUT}

    private State state = State.UNKNOWN;
    private long candidateNs = -1;
    private long candidateWallMs;
    private long outStartWallMs;
    private final List<Event> events = new ArrayList<>();

    /** Values in m/s^2 from TYPE_GRAVITY. Counting starts only after the phone was first seen in the pocket. */
    public synchronized void onGravity(long timestampNs, long wallMs, float x, float y, float z) {
        boolean inPose = Math.abs(y) > IN_MIN_ABS_Y;
        boolean outPose = Math.abs(y) < OUT_MAX_ABS_Y && Math.abs(z) > OUT_MIN_ABS_Z;
        switch (state) {
            case UNKNOWN, OUT -> {
                if (!hold(inPose, timestampNs, wallMs, IN_HOLD_NS)) {
                    return;
                }
                if (state == State.OUT) {
                    events.add(new Event(outStartWallMs, candidateWallMs));
                }
                state = State.IN;
                candidateNs = -1;
            }
            case IN -> {
                if (hold(outPose, timestampNs, wallMs, OUT_HOLD_NS)) {
                    state = State.OUT;
                    outStartWallMs = candidateWallMs;
                    candidateNs = -1;
                }
            }
        }
    }

    private boolean hold(boolean condition, long timestampNs, long wallMs, long holdNs) {
        if (!condition) {
            candidateNs = -1;
            return false;
        }
        if (candidateNs < 0) {
            candidateNs = timestampNs;
            candidateWallMs = wallMs;
        }
        return timestampNs - candidateNs >= holdNs;
    }

    /** Closes a pull-out still open when the recording ends. */
    public synchronized void finish(long wallMs) {
        if (state == State.OUT) {
            events.add(new Event(outStartWallMs, wallMs));
        }
        state = State.UNKNOWN;
        candidateNs = -1;
    }

    public synchronized List<Event> events() {
        return new ArrayList<>(events);
    }

    public static long totalMs(List<Event> events) {
        long total = 0;
        for (Event e : events) {
            total += e.durationMs();
        }
        return total;
    }

    /** e.g. "Out of pocket: 1 times, 0:18". */
    public static String summary(List<Event> events) {
        long s = Math.round(totalMs(events) / 1000.0);
        return String.format(Locale.US, "Out of pocket: %d times, %d:%02d", events.size(), s / 60, s % 60);
    }

    public static String toCsv(List<Event> events) {
        StringBuilder text = new StringBuilder("event,start_wall_time_ms,end_wall_time_ms,start_iso,end_iso,duration_s\n");
        int i = 1;
        for (Event e : events) {
            text.append(i++).append(',').append(e.startWallMs()).append(',').append(e.endWallMs()).append(',')
                    .append(java.time.Instant.ofEpochMilli(e.startWallMs())).append(',')
                    .append(java.time.Instant.ofEpochMilli(e.endWallMs())).append(',')
                    .append(String.format(Locale.US, "%.1f", e.durationMs() / 1000.0)).append('\n');
        }
        return text.toString();
    }

    public static List<Event> fromCsv(String text) {
        List<Event> events = new ArrayList<>();
        for (String line : text.split("\n")) {
            String[] parts = line.split(",");
            if (parts.length < 3 || parts[0].equals("event")) {
                continue;
            }
            try {
                events.add(new Event(Long.parseLong(parts[1]), Long.parseLong(parts[2])));
            } catch (NumberFormatException ignored) {
            }
        }
        return events;
    }
}
