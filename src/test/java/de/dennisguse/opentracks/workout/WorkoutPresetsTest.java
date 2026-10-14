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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class WorkoutPresetsTest {

    @Test
    public void c25kWeek1_hasEighteenAlternatingSegmentsAndThirtyMinutes() {
        Workout workout = WorkoutPresets.c25kWeek1();
        List<WorkoutSegment> segments = workout.segments();

        assertEquals(WorkoutPresets.C25K_WEEK1_NAME, workout.name());
        assertTrue(workout.builtin());
        assertEquals(18, segments.size());
        assertEquals(SegmentType.WARMUP, segments.get(0).type());
        assertEquals(300, segments.get(0).duration().getSeconds());
        assertEquals(SegmentType.COOLDOWN, segments.get(17).type());
        assertEquals(300, segments.get(17).duration().getSeconds());

        for (int round = 0; round < 8; round++) {
            WorkoutSegment run = segments.get(1 + round * 2);
            WorkoutSegment walk = segments.get(2 + round * 2);
            assertEquals(SegmentType.RUN, run.type());
            assertEquals(60, run.duration().getSeconds());
            assertEquals(SegmentType.WALK, walk.type());
            assertEquals(90, walk.duration().getSeconds());
        }

        assertEquals(1800, workout.totalDuration().getSeconds());
    }
}
