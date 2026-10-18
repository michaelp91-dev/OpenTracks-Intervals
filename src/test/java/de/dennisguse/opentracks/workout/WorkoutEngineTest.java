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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class WorkoutEngineTest {

    @Test
    public void threeSegments_cueSequenceMergesHalfway() {
        WorkoutEngine engine = new WorkoutEngine(toy());

        List<String> cues = new ArrayList<>();
        cues.add(engine.onElapsed(Duration.ZERO).cue());
        cues.add(engine.onElapsed(Duration.ofSeconds(10)).cue());
        cues.add(engine.onElapsed(Duration.ofSeconds(20)).cue());
        cues.add(engine.onElapsed(Duration.ofSeconds(40)).cue());

        assertEquals(List.of(
                "10 second warm-up walk. 10 seconds.",
                "10 second run. 10 seconds.",
                "20 second walk. Halfway point.",
                "Workout complete"
        ), cues);
    }

    @Test
    public void countdown_saysTenSecondsThenFiveThroughOne() {
        WorkoutEngine engine = new WorkoutEngine(List.of(
                new WorkoutSegment(SegmentType.RUN, Duration.ofSeconds(30))
        ));

        assertEquals("30 second run", engine.onElapsed(Duration.ZERO).cue());
        assertEquals("Halfway point", engine.onElapsed(Duration.ofSeconds(15)).cue());
        assertNull(engine.onElapsed(Duration.ofSeconds(19)).cue());
        assertEquals("10 seconds", engine.onElapsed(Duration.ofSeconds(20)).cue());
        assertNull(engine.onElapsed(Duration.ofSeconds(21)).cue());
        assertNull(engine.onElapsed(Duration.ofSeconds(24)).cue());
        assertEquals("5", engine.onElapsed(Duration.ofSeconds(25)).cue());
        assertEquals("4", engine.onElapsed(Duration.ofSeconds(26)).cue());
        assertEquals("3", engine.onElapsed(Duration.ofSeconds(27)).cue());
        assertEquals("2", engine.onElapsed(Duration.ofSeconds(28)).cue());
        assertEquals("1", engine.onElapsed(Duration.ofSeconds(29)).cue());
        assertEquals("Workout complete", engine.onElapsed(Duration.ofSeconds(30)).cue());
    }

    @Test
    public void countdown_lateTickSpeaksOnlyTheLatestNumber() {
        WorkoutEngine engine = new WorkoutEngine(List.of(
                new WorkoutSegment(SegmentType.RUN, Duration.ofSeconds(30))
        ));
        engine.onElapsed(Duration.ZERO);

        assertEquals("Halfway point. 3.", engine.onElapsed(Duration.ofSeconds(27)).cue());
        assertEquals("2", engine.onElapsed(Duration.ofSeconds(28)).cue());
    }

    @Test
    public void countdown_shortSegmentDoesNotSayTenSeconds() {
        WorkoutEngine engine = new WorkoutEngine(List.of(
                new WorkoutSegment(SegmentType.WALK, Duration.ofSeconds(8))
        ));

        assertEquals("8 second walk", engine.onElapsed(Duration.ZERO).cue());
        assertEquals("5", engine.onElapsed(Duration.ofSeconds(3)).cue());
    }

    @Test
    public void countdown_thirtySecondsOnlyWhenTheIntervalIsLonger() {
        WorkoutEngine shortInterval = new WorkoutEngine(List.of(
                new WorkoutSegment(SegmentType.RUN, Duration.ofSeconds(30))
        ));
        assertEquals("30 second run", shortInterval.onElapsed(Duration.ZERO).cue());

        WorkoutEngine longInterval = new WorkoutEngine(List.of(
                new WorkoutSegment(SegmentType.RUN, Duration.ofSeconds(90))
        ));
        assertEquals("1 minute 30 second run", longInterval.onElapsed(Duration.ZERO).cue());
        assertEquals("Halfway point", longInterval.onElapsed(Duration.ofSeconds(45)).cue());
        assertEquals("30 seconds", longInterval.onElapsed(Duration.ofSeconds(60)).cue());
        assertEquals("10 seconds", longInterval.onElapsed(Duration.ofSeconds(80)).cue());
    }

    @Test
    public void countdown_resumeDoesNotRepeatAMarkAlreadyPassed() {
        WorkoutEngine engine = new WorkoutEngine(List.of(
                new WorkoutSegment(SegmentType.RUN, Duration.ofSeconds(30))
        ));
        engine.resumeAt(Duration.ofSeconds(22));

        assertNull(engine.onElapsed(Duration.ofSeconds(23)).cue());
        assertEquals("5", engine.onElapsed(Duration.ofSeconds(25)).cue());
    }

    @Test
    public void lateTick_crossingTwoBoundaries_writesTwoRowsAndSpeaksOnce() {
        WorkoutEngine engine = new WorkoutEngine(toy());
        engine.onElapsed(Duration.ZERO);

        WorkoutEngine.Tick jump = engine.onElapsed(Duration.ofSeconds(20));

        assertEquals(2, jump.closed().size());
        assertTrue(jump.closed().get(0).completed());
        assertTrue(jump.closed().get(1).completed());
        assertEquals("20 second walk. Halfway point.", jump.cue());
    }

    @Test
    public void stopEarly_writesPartialSegmentWithoutCompleteCue() {
        WorkoutEngine engine = new WorkoutEngine(toy());
        engine.onElapsed(Duration.ZERO);

        WorkoutEngine.Tick stopped = engine.stop(Duration.ofSeconds(5));

        assertNull(stopped.cue());
        assertEquals(1, stopped.closed().size());
        assertFalse(stopped.closed().get(0).completed());
        assertEquals(Duration.ofSeconds(5), stopped.closed().get(0).endOffset());
    }

    @Test
    public void resume_doesNotRepeatCuesAlreadyPassed() {
        WorkoutEngine engine = new WorkoutEngine(toy());
        engine.resumeAt(Duration.ofSeconds(25));

        WorkoutEngine.Tick tick = engine.onElapsed(Duration.ofSeconds(26));

        assertNull(tick.cue());
        assertTrue(tick.closed().isEmpty());
        assertFalse(tick.snapshot().finished());
        assertEquals(SegmentType.WALK, tick.snapshot().type());
    }

    private static List<WorkoutSegment> toy() {
        return List.of(
                new WorkoutSegment(SegmentType.WARMUP, Duration.ofSeconds(10)),
                new WorkoutSegment(SegmentType.RUN, Duration.ofSeconds(10)),
                new WorkoutSegment(SegmentType.WALK, Duration.ofSeconds(20))
        );
    }

    private static List<String> cuesAtSegmentStarts(WorkoutEngine engine, List<WorkoutSegment> segments) {
        WorkoutTimeline timeline = new WorkoutTimeline(segments);
        List<String> cues = new ArrayList<>();
        for (int i = 0; i < timeline.size(); i++) {
            cues.add(engine.onElapsed(timeline.startOffset(i)).cue());
        }
        cues.add(engine.onElapsed(timeline.total()).cue());
        return cues;
    }

    @Test
    public void block_announcesRoundOnFirstRunAndFinalRound() {
        Workout workout = WorkoutBlockTest.example();
        WorkoutEngine engine = new WorkoutEngine(workout);

        List<String> cues = cuesAtSegmentStarts(engine, workout.segments());

        // Total 28:00, halfway 14:00 falls inside round 2's 2 minute run (13:00-15:00) and is said at the next start.
        assertEquals(List.of(
                "5 minute warm-up walk",
                "1 minute run, round 1",
                "1 minute walk",
                "2 minute run",
                "2 minute walk",
                "1 minute run, round 2",
                "1 minute walk",
                "2 minute run",
                "2 minute walk. Halfway point.",
                "1 minute run, round 3, final round",
                "1 minute walk",
                "2 minute run",
                "2 minute walk",
                "5 minute cool-down walk",
                "Workout complete"
        ), cues);
    }

    @Test
    public void block_roundCueMergesWithHalfway() {
        Workout workout = new Workout(0, "w", false, List.of(
                new WorkoutBlock(List.of(WorkoutBlockTest.run(60), WorkoutBlockTest.walk(60)), 2)
        ));
        WorkoutEngine engine = new WorkoutEngine(workout);

        assertEquals("1 minute run, round 1", engine.onElapsed(Duration.ZERO).cue());
        assertEquals("1 minute walk", engine.onElapsed(Duration.ofSeconds(60)).cue());
        assertEquals("1 minute run, round 2, final round. Halfway point.", engine.onElapsed(Duration.ofSeconds(120)).cue());
    }

    @Test
    public void flatWorkout_cuesHaveNoRoundText() {
        Workout preset = WorkoutPresets.c25kWeek1();
        for (String cue : cuesAtSegmentStarts(new WorkoutEngine(preset), preset.segments())) {
            assertFalse(cue, cue.contains("round"));
        }
    }

    @Test
    public void snapshot_reportsRoundOfBlockSteps() {
        Workout workout = WorkoutBlockTest.example();
        WorkoutEngine engine = new WorkoutEngine(workout);

        WorkoutEngine.Snapshot warmup = engine.snapshot(Duration.ofMinutes(1));
        assertEquals(0, warmup.round());
        assertEquals(0, warmup.roundCount());

        // 5:00 warm-up + one 6:00 round + 1:30 into round 2 (its 1 minute walk)
        WorkoutEngine.Snapshot inRoundTwo = engine.snapshot(Duration.ofSeconds(300 + 360 + 90));
        assertEquals(SegmentType.WALK, inRoundTwo.type());
        assertEquals(2, inRoundTwo.round());
        assertEquals(3, inRoundTwo.roundCount());
        assertEquals(14, inRoundTwo.segmentCount());
        assertEquals(Duration.ofMinutes(28), inRoundTwo.total());

        WorkoutEngine.Snapshot cooldown = engine.snapshot(Duration.ofMinutes(25));
        assertEquals(SegmentType.COOLDOWN, cooldown.type());
        assertEquals(0, cooldown.roundCount());
    }

    @Test
    public void resume_doesNotRepeatRoundCue() {
        Workout workout = WorkoutBlockTest.example();
        WorkoutEngine engine = new WorkoutEngine(workout);

        engine.resumeAt(Duration.ofSeconds(300 + 360 + 5));
        assertNull(engine.onElapsed(Duration.ofSeconds(300 + 360 + 6)).cue());
        assertEquals("1 minute walk", engine.onElapsed(Duration.ofSeconds(300 + 360 + 60)).cue());
    }
}
