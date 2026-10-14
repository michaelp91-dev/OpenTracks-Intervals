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
import java.util.List;

/**
 * CSV of one finished workout: segment type, time, distance in kilometres, average km/h.
 */
public final class WorkoutSplitsText {

    private WorkoutSplitsText() {
    }

    public static String format(WorkoutSplitCalculator.Result result) {
        StringBuilder text = new StringBuilder();
        text.append("Type,Time,Distance km,Avg km/h\n");
        for (WorkoutSplitCalculator.Split split : result.segments()) {
            append(text, segmentLabel(split), split);
        }
        append(text, "All runs", result.runs());
        append(text, "All walks", allWalks(result.segments()));
        return text.toString();
    }

    /** Warm-up and cool-down count as walks in the file. */
    private static WorkoutSplitCalculator.Split allWalks(List<WorkoutSplitCalculator.Split> segments) {
        Duration time = Duration.ZERO;
        double meters = 0;
        for (WorkoutSplitCalculator.Split split : segments) {
            if (split.type() != null && split.type().isAnyWalk()) {
                time = time.plus(split.time());
                meters += split.meters();
            }
        }
        return new WorkoutSplitCalculator.Split(-1, null, time, meters, true, true);
    }

    private static String segmentLabel(WorkoutSplitCalculator.Split split) {
        String label = split.type() == null ? "" : split.type().spoken();
        if (!split.completed()) {
            label = label + " (partial)";
        }
        return label;
    }

    private static void append(StringBuilder text, String label, WorkoutSplitCalculator.Split split) {
        text.append(csv(label))
                .append(',')
                .append(csv(split.timeText()))
                .append(',')
                .append(csv(split.distanceText()))
                .append(',')
                .append(csv(split.speedText()))
                .append('\n');
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        if (safe.indexOf(',') >= 0 || safe.indexOf('"') >= 0 || safe.indexOf('\n') >= 0) {
            return '"' + safe.replace("\"", "\"\"") + '"';
        }
        return safe;
    }
}
