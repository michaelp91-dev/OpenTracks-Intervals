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

public record WorkoutState(
        String workoutName,
        int segmentIndex,
        int segmentCount,
        SegmentType type,
        Duration remainingInSegment,
        Duration elapsed,
        Duration total,
        int runNumber,
        int runCount,
        int walkNumber,
        int walkCount,
        int round,
        int roundCount,
        boolean finished,
        /* Banner text for a distance workout, e.g. "5 km · 2.31 km · 12:04"; null for timed workouts. */
        String distanceStatus
) {
    public WorkoutState(String workoutName, int segmentIndex, int segmentCount, SegmentType type, Duration remainingInSegment,
                        Duration elapsed, Duration total, int runNumber, int runCount, int walkNumber, int walkCount,
                        int round, int roundCount, boolean finished) {
        this(workoutName, segmentIndex, segmentCount, type, remainingInSegment, elapsed, total, runNumber, runCount,
                walkNumber, walkCount, round, roundCount, finished, null);
    }
}
