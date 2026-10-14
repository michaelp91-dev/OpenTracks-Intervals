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

import org.junit.Test;

import java.time.Duration;
import java.util.List;

public class WorkoutTimelineTest {

    @Test
    public void c25kWeek1_boundariesAndHalfwayAtRunFive() {
        List<WorkoutSegment> segments = WorkoutPresets.c25kWeek1().segments();
        WorkoutTimeline timeline = new WorkoutTimeline(segments);

        assertEquals(0, timeline.indexAt(Duration.ZERO));
        assertEquals(0, timeline.indexAt(Duration.ofSeconds(299)));
        assertEquals(1, timeline.indexAt(Duration.ofSeconds(300)));
        assertEquals(2, timeline.indexAt(Duration.ofSeconds(360)));
        assertEquals(Duration.ofSeconds(900), timeline.halfway());
        assertEquals(Duration.ofSeconds(1800), timeline.total());

        int halfwayIndex = timeline.indexAt(Duration.ofSeconds(900));
        assertEquals(9, halfwayIndex);
        assertEquals(SegmentType.RUN, segments.get(halfwayIndex).type());
        int runNumber = 0;
        for (int i = 0; i <= halfwayIndex; i++) {
            if (segments.get(i).type().isRun()) {
                runNumber++;
            }
        }
        assertEquals(5, runNumber);

        assertEquals(17, timeline.indexAt(Duration.ofSeconds(1500)));
        assertEquals(SegmentType.COOLDOWN, segments.get(17).type());
        assertEquals(18, timeline.indexAt(Duration.ofSeconds(1800)));
    }
}
