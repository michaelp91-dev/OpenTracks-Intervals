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
import java.time.Instant;
import java.util.List;

public class WorkoutSplitsTextTest {

    @Test
    public void format_includesSegmentsAndSummaries() {
        Instant start = Instant.parse("2026-10-04T15:00:00Z");
        List<RecordedSegment> recorded = List.of(
                new RecordedSegment(0, SegmentType.WARMUP, start, start.plusSeconds(60), true),
                new RecordedSegment(1, SegmentType.RUN, start.plusSeconds(60), start.plusSeconds(120), true),
                new RecordedSegment(2, SegmentType.WALK, start.plusSeconds(120), start.plusSeconds(135), false),
                new RecordedSegment(3, SegmentType.COOLDOWN, start.plusSeconds(135), start.plusSeconds(165), true)
        );
        List<Sample> samples = List.of(
                new Sample(start.plusSeconds(1), 84),
                new Sample(start.plusSeconds(61), 150),
                new Sample(start.plusSeconds(121), 21),
                new Sample(start.plusSeconds(136), 42)
        );

        String text = WorkoutSplitsText.format(new WorkoutSplitCalculator().compute(recorded, samples));

        assertEquals("""
                Type,Time,Distance km,Avg km/h
                Warm-up walk,1:00,0.08,5.0
                Run,1:00,0.15,9.0
                Walk (partial),0:15,0.02,5.0
                Cool-down walk,0:30,0.04,5.0
                All runs,1:00,0.15,9.0
                All walks,1:45,0.15,5.0
                """, text);
    }
}
