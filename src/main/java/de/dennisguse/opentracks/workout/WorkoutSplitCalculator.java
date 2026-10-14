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

public final class WorkoutSplitCalculator {

    public record Split(int position, SegmentType type, Duration time, double meters, boolean completed, boolean summary) {

        public double kmh() {
            double seconds = time.toMillis() / 1000.0;
            if (seconds <= 0.0) {
                return Double.NaN;
            }
            return meters / seconds * 3.6;
        }

        public String speedText() {
            double value = kmh();
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return WorkoutFormat.UNAVAILABLE;
            }
            return String.format(Locale.US, "%.1f", value);
        }

        public String distanceText() {
            return String.format(Locale.US, "%.2f", meters / 1000.0);
        }

        public String timeText() {
            return WorkoutFormat.minutesSeconds(time);
        }
    }

    public record Result(List<Split> segments, Split runs, Split walks) {
    }

    public Result compute(List<RecordedSegment> recorded, List<Sample> samples) {
        double[] meters = new double[recorded.size()];
        if (samples != null) {
            for (Sample sample : samples) {
                int index = indexAt(recorded, sample.time());
                if (index >= 0) {
                    meters[index] += sample.deltaMeters();
                }
            }
        }
        List<Split> splits = new ArrayList<>();
        for (int i = 0; i < recorded.size(); i++) {
            RecordedSegment segment = recorded.get(i);
            Duration time = Duration.between(segment.start(), segment.end());
            if (time.isNegative()) {
                time = Duration.ZERO;
            }
            splits.add(new Split(segment.position(), segment.type(), time, meters[i], segment.completed(), false));
        }
        return new Result(List.copyOf(splits), aggregate(splits, true), aggregate(splits, false));
    }

    private static int indexAt(List<RecordedSegment> recorded, java.time.Instant time) {
        if (time == null) {
            return -1;
        }
        for (int i = 0; i < recorded.size(); i++) {
            RecordedSegment segment = recorded.get(i);
            if (!time.isBefore(segment.start()) && time.isBefore(segment.end())) {
                return i;
            }
        }
        return -1;
    }

    private static Split aggregate(List<Split> splits, boolean runs) {
        Duration time = Duration.ZERO;
        double meters = 0;
        for (Split split : splits) {
            boolean include = split.type() != null && (runs ? split.type().isRun() : split.type().isWalkInterval());
            if (!include) {
                continue;
            }
            time = time.plus(split.time());
            meters += split.meters();
        }
        return new Split(-1, null, time, meters, true, true);
    }
}
