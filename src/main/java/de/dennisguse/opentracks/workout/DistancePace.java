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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Distance workouts: parsing, pacing math and the cue schedule. Pure Java so it is unit tested.
 * <p>
 * Time is the workout clock: wall-clock time since the workout started (same clock as interval
 * workouts, so stopped time counts). Distance is the track's recorded total distance.
 */
public final class DistancePace {

    /** Within this many seconds of target counts as "On pace". */
    public static final long ON_PACE_SECONDS = 2;

    private DistancePace() {
    }

    /**
     * "5k", "5 km", "15K", "21.1km" are kilometers; "5000", "5000m", "2500 m" are meters.
     * A bare number below 100 is taken as kilometers. Returns null when not a positive distance.
     */
    public static Integer parseDistanceMeters(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim().toLowerCase(Locale.US).replace(" ", "").replace(',', '.');
        if (t.isEmpty()) {
            return null;
        }
        double factor;
        if (t.endsWith("km")) {
            factor = 1000;
            t = t.substring(0, t.length() - 2);
        } else if (t.endsWith("k")) {
            factor = 1000;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("m")) {
            factor = 1;
            t = t.substring(0, t.length() - 1);
        } else {
            factor = 0;
        }
        double value;
        try {
            value = Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
        if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0) {
            return null;
        }
        if (factor == 0) {
            factor = value < 100 ? 1000 : 1;
        }
        long meters = Math.round(value * factor);
        if (meters <= 0 || meters > 1_000_000) {
            return null;
        }
        return (int) meters;
    }

    /** "30:00" (mm:ss) or "1:45:30" (h:mm:ss). Null when invalid or zero. */
    public static Duration parseFinishTime(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.trim().split(":");
        try {
            long h = 0;
            long m;
            long s;
            if (parts.length == 2) {
                m = Long.parseLong(parts[0].trim());
                s = Long.parseLong(parts[1].trim());
            } else if (parts.length == 3) {
                h = Long.parseLong(parts[0].trim());
                m = Long.parseLong(parts[1].trim());
                s = Long.parseLong(parts[2].trim());
                if (m >= 60) {
                    return null;
                }
            } else {
                return null;
            }
            if (h < 0 || m < 0 || s < 0 || s >= 60) {
                return null;
            }
            long total = h * 3600 + m * 60 + s;
            return total > 0 ? Duration.ofSeconds(total) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Update distance in whole meters, valid when 0 < every <= total. Null otherwise. */
    public static Integer parseEvery(String text, int totalMeters) {
        if (text == null) {
            return null;
        }
        try {
            int every = Integer.parseInt(text.trim());
            return every > 0 && every <= totalMeters ? every : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Target time at distance d: goal * d / total. */
    public static Duration targetAt(Duration goal, double meters, double totalMeters) {
        return Duration.ofMillis(Math.round(goal.toMillis() * meters / totalMeters));
    }

    /** Update points: every, 2*every, ... below total, then total itself. */
    public static List<Integer> checkpoints(int totalMeters, Integer everyMeters) {
        List<Integer> points = new ArrayList<>();
        if (everyMeters != null && everyMeters > 0) {
            for (long d = everyMeters; d < totalMeters; d += everyMeters) {
                points.add((int) d);
            }
        }
        points.add(totalMeters);
        return points;
    }

    /** actual - target: positive is behind. "On pace", "Twenty seconds ahead", "One minute five seconds behind". */
    public static String deltaPhrase(Duration actual, Duration target) {
        long deltaMs = actual.toMillis() - target.toMillis();
        long seconds = Math.round(Math.abs(deltaMs) / 1000.0);
        if (seconds <= ON_PACE_SECONDS) {
            return "On pace";
        }
        String amount = capitalize(spokenAmount(seconds));
        return amount + (deltaMs < 0 ? " ahead" : " behind");
    }

    /** "twenty seconds", "one minute", "two minutes five seconds"; hours as minutes. */
    static String spokenAmount(long seconds) {
        long minutes = seconds / 60;
        long rest = seconds % 60;
        StringBuilder b = new StringBuilder();
        if (minutes > 0) {
            b.append(words(minutes)).append(minutes == 1 ? " minute" : " minutes");
        }
        if (rest > 0 || minutes == 0) {
            if (b.length() > 0) {
                b.append(' ');
            }
            b.append(words(rest)).append(rest == 1 ? " second" : " seconds");
        }
        return b.toString();
    }

    /** "29 minutes 40 seconds", "1 hour 5 minutes" for a finish time. */
    static String spokenClock(Duration duration) {
        long total = Math.round(duration.toMillis() / 1000.0);
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        StringBuilder b = new StringBuilder();
        if (h > 0) {
            b.append(h).append(h == 1 ? " hour" : " hours");
        }
        if (m > 0) {
            if (b.length() > 0) b.append(' ');
            b.append(m).append(m == 1 ? " minute" : " minutes");
        }
        if (s > 0 || b.length() == 0) {
            if (b.length() > 0) b.append(' ');
            b.append(s).append(s == 1 ? " second" : " seconds");
        }
        return b.toString();
    }

    /** "1 kilometer", "2.5 kilometers", "500 meters". */
    public static String spokenDistance(int meters) {
        if (meters < 1000) {
            return meters + (meters == 1 ? " meter" : " meters");
        }
        if (meters == 1000) {
            return "1 kilometer";
        }
        return formatKm(meters) + " kilometers";
    }

    /** "5", "2.5", "21.1" (km, trailing zeros dropped). */
    public static String formatKm(int meters) {
        String s = String.format(Locale.US, "%.3f", meters / 1000.0);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    /** "5 km", "2.5 km", "800 m" for lists and banners. */
    public static String shortDistance(int meters) {
        return meters < 1000 ? meters + " m" : formatKm(meters) + " km";
    }

    private static final String[] ONES = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
    private static final String[] TENS = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};

    static String words(long n) {
        if (n < 20) {
            return ONES[(int) n];
        }
        if (n < 100) {
            return TENS[(int) (n / 10)] + (n % 10 == 0 ? "" : "-" + ONES[(int) (n % 10)]);
        }
        return Long.toString(n);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Follows distance and time and says when an update point is passed. The crossing time is
     * interpolated between the last two samples. If one tick jumps over several points only the
     * last one is spoken.
     */
    public static final class Tracker {
        private final int totalMeters;
        private final Duration goal;
        private final List<Integer> points;
        private int next;
        private double lastMeters;
        private Duration lastElapsed = Duration.ZERO;
        private Duration finishTime;

        /** @param goal null for no pace; everyMeters ignored then (only the finish is spoken) */
        public Tracker(int totalMeters, Duration goal, Integer everyMeters) {
            if (totalMeters <= 0) {
                throw new IllegalArgumentException("totalMeters");
            }
            this.totalMeters = totalMeters;
            this.goal = goal;
            this.points = checkpoints(totalMeters, goal == null ? null : everyMeters);
        }

        public List<Integer> points() {
            return points;
        }

        public boolean isFinished() {
            return next >= points.size();
        }

        public Duration finishTime() {
            return finishTime;
        }

        public int totalMeters() {
            return totalMeters;
        }

        public boolean hasPace() {
            return goal != null;
        }

        /** After a restart: points already passed are not repeated. */
        public void skipTo(double meters, Duration elapsed) {
            while (next < points.size() && points.get(next) <= meters) {
                next++;
            }
            lastMeters = meters;
            lastElapsed = elapsed;
        }

        /** @return the cue to speak, or null */
        public String onProgress(double meters, Duration elapsed) {
            if (isFinished()) {
                return null;
            }
            if (meters < lastMeters) {
                meters = lastMeters;
            }
            int crossed = -1;
            while (next < points.size() && points.get(next) <= meters) {
                crossed = next;
                next++;
            }
            String cue = null;
            if (crossed >= 0) {
                int point = points.get(crossed);
                Duration at = crossingTime(point, meters, elapsed);
                cue = cueFor(point, at);
                if (isFinished()) {
                    finishTime = at;
                }
            }
            lastMeters = meters;
            lastElapsed = elapsed;
            return cue;
        }

        Duration crossingTime(int point, double meters, Duration elapsed) {
            double span = meters - lastMeters;
            if (span <= 0 || point <= lastMeters) {
                return elapsed;
            }
            long dt = elapsed.toMillis() - lastElapsed.toMillis();
            return lastElapsed.plusMillis(Math.round(dt * (point - lastMeters) / span));
        }

        String cueFor(int point, Duration at) {
            boolean finish = point == totalMeters;
            if (goal == null) {
                return "Distance goal reached. " + spokenDistance(totalMeters) + " in " + spokenClock(at) + ".";
            }
            String delta = deltaPhrase(at, targetAt(goal, point, totalMeters));
            if (finish) {
                return "Finish. " + spokenDistance(totalMeters) + " in " + spokenClock(at) + ". " + delta + ".";
            }
            return spokenDistance(point) + ". " + delta + ".";
        }
    }
}
