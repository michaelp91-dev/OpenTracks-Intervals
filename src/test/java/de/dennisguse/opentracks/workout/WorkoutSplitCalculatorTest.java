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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class WorkoutSplitCalculatorTest {

    private final WorkoutSplitCalculator calculator = new WorkoutSplitCalculator();

    @Test
    public void steadyPaces_runsAreFasterThanWalkIntervals() {
        List<WorkoutSegment> plan = WorkoutPresets.c25kWeek1().segments();
        WorkoutTimeline timeline = new WorkoutTimeline(plan);
        Instant start = Instant.parse("2026-10-04T15:00:00Z");
        List<RecordedSegment> recorded = new ArrayList<>();
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) {
            recorded.add(new RecordedSegment(
                    i,
                    plan.get(i).type(),
                    start.plus(timeline.startOffset(i)),
                    start.plus(timeline.endOffset(i)),
                    true));
            double metersPerSecond = plan.get(i).type().isRun() ? 2.5 : 1.4;
            long seconds = plan.get(i).duration().getSeconds();
            Instant segmentStart = start.plus(timeline.startOffset(i));
            for (long second = 0; second < seconds; second++) {
                samples.add(new Sample(segmentStart.plusSeconds(second), metersPerSecond));
            }
        }

        WorkoutSplitCalculator.Result result = calculator.compute(recorded, samples);

        assertEquals(18, result.segments().size());
        for (int round = 0; round < 8; round++) {
            WorkoutSplitCalculator.Split run = result.segments().get(1 + round * 2);
            WorkoutSplitCalculator.Split walk = result.segments().get(2 + round * 2);
            assertEquals(150.0, run.meters(), 0.001);
            assertEquals("9.0", run.speedText());
            assertEquals(126.0, walk.meters(), 0.02);
            assertEquals("5.0", walk.speedText());
        }
        assertEquals(1200.0, result.runs().meters(), 0.01);
        assertEquals("9.0", result.runs().speedText());
        assertEquals(1008.0, result.walks().meters(), 0.2);
        assertEquals("5.0", result.walks().speedText());
        assertTrue(result.walks().meters() < result.segments().get(0).meters() + result.walks().meters());
    }

    @Test
    public void segmentReset_doesNotAddTheGapDistance() {
        Instant start = Instant.parse("2026-10-04T15:00:00Z");
        List<RecordedSegment> recorded = List.of(
                new RecordedSegment(0, SegmentType.RUN, start, start.plusSeconds(10), true),
                new RecordedSegment(1, SegmentType.WALK, start.plusSeconds(10), start.plusSeconds(20), true)
        );
        List<Sample> samples = SampleFold.fold(List.of(
                new SampleFold.Observation(start.plusSeconds(5), false, 10.0),
                new SampleFold.Observation(start.plusSeconds(10), true, 1000.0),
                new SampleFold.Observation(start.plusSeconds(15), false, 4.0)
        ));

        WorkoutSplitCalculator.Result result = calculator.compute(recorded, samples);

        assertEquals(10.0, result.segments().get(0).meters(), 0.001);
        assertEquals(4.0, result.segments().get(1).meters(), 0.001);
    }

    @Test
    public void partialLastSegment_usesItsRecordedWindow() {
        Instant start = Instant.parse("2026-10-04T15:00:00Z");
        List<RecordedSegment> recorded = List.of(
                new RecordedSegment(0, SegmentType.RUN, start, start.plusSeconds(60), true),
                new RecordedSegment(1, SegmentType.WALK, start.plusSeconds(60), start.plusSeconds(75), false)
        );
        List<Sample> samples = new ArrayList<>();
        for (int second = 0; second < 60; second++) {
            samples.add(new Sample(start.plusSeconds(second), 2.5));
        }
        for (int second = 60; second < 75; second++) {
            samples.add(new Sample(start.plusSeconds(second), 1.4));
        }

        WorkoutSplitCalculator.Result result = calculator.compute(recorded, samples);
        WorkoutSplitCalculator.Split partial = result.segments().get(1);

        assertFalse(partial.completed());
        assertEquals(Duration.ofSeconds(15), partial.time());
        assertEquals(21.0, partial.meters(), 0.02);
        assertEquals("5.0", partial.speedText());
    }

    @Test
    public void zeroLengthSegment_speedIsDashNotNaN() {
        Instant start = Instant.parse("2026-10-04T15:00:00Z");
        List<RecordedSegment> recorded = List.of(
                new RecordedSegment(0, SegmentType.RUN, start, start, true)
        );
        List<Sample> samples = List.of(new Sample(start, 10));

        WorkoutSplitCalculator.Split split = calculator.compute(recorded, samples).segments().get(0);

        assertEquals(0.0, split.meters(), 0.001);
        assertEquals(WorkoutFormat.UNAVAILABLE, split.speedText());
        assertFalse(split.speedText().equals("NaN"));
        assertTrue(Double.isNaN(split.kmh()));
    }
}
