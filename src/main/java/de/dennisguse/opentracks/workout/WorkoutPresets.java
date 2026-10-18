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

public final class WorkoutPresets {

    public static final String C25K_WEEK1_NAME = "Couch to 5K – Week 1";

    private WorkoutPresets() {
    }

    /** 5:00 warm-up, 8 × (1:00 run + 1:30 walk), 5:00 cool-down. 18 segments, 30:00. */
    public static Workout c25kWeek1() {
        List<WorkoutSegment> segments = new ArrayList<>();
        segments.add(new WorkoutSegment(SegmentType.WARMUP, Duration.ofMinutes(5)));
        for (int round = 0; round < 8; round++) {
            segments.add(new WorkoutSegment(SegmentType.RUN, Duration.ofMinutes(1)));
            segments.add(new WorkoutSegment(SegmentType.WALK, Duration.ofSeconds(90)));
        }
        segments.add(new WorkoutSegment(SegmentType.COOLDOWN, Duration.ofMinutes(5)));
        return Workout.ofSegments(0, C25K_WEEK1_NAME, true, segments);
    }
}
