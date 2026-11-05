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

public class CalloutsAndLastWalkTest {

    private static WorkoutSegment seg(SegmentType t, int seconds) {
        return new WorkoutSegment(t, Duration.ofSeconds(seconds));
    }

    /** Every non-null cue from 0 to the end, one tick per second. */
    private static List<String> run(WorkoutEngine engine, int totalSeconds) {
        List<String> cues = new ArrayList<>();
        for (int s = 0; s <= totalSeconds; s++) {
            String cue = engine.onElapsed(Duration.ofSeconds(s)).cue();
            if (cue != null) {
                cues.add(s + ":" + cue);
            }
        }
        return cues;
    }

    @Test
    public void twoMinuteInterval_halfwaySaysOneMinuteLeft_noOtherMinuteMarks() {
        WorkoutEngine engine = new WorkoutEngine(List.of(seg(SegmentType.WARMUP, 600), seg(SegmentType.RUN, 120), seg(SegmentType.COOLDOWN, 900)));
        List<String> cues = run(engine, 1620);
        assertTrue(cues.toString(), cues.contains("660:Halfway, 1 minute left"));
        assertTrue(cues.contains("690:30 seconds"));
        assertTrue(cues.contains("710:10 seconds"));
        assertTrue(cues.contains("715:5"));
        assertTrue(cues.contains("719:1"));
    }

    @Test
    public void threeMinuteInterval_twoAndOneMinuteLeftPlusHalfway() {
        WorkoutEngine engine = new WorkoutEngine(List.of(seg(SegmentType.WARMUP, 600), seg(SegmentType.RUN, 180), seg(SegmentType.COOLDOWN, 900)));
        List<String> cues = run(engine, 1680);
        assertTrue(cues.toString(), cues.contains("660:2 minutes left"));
        assertTrue(cues.contains("690:Halfway"));
        assertTrue(cues.contains("720:1 minute left"));
        assertTrue(cues.contains("750:30 seconds"));
    }

    @Test
    public void tenMinuteInterval_everyMinute_halfwayNotDuplicated() {
        WorkoutEngine engine = new WorkoutEngine(List.of(seg(SegmentType.WARMUP, 900), seg(SegmentType.RUN, 600), seg(SegmentType.COOLDOWN, 1500)));
        List<String> cues = run(engine, 3000);
        assertTrue(cues.toString(), cues.contains("960:9 minutes left"));
        assertTrue(cues.contains("1200:Halfway, 5 minutes left"));
        assertFalse(cues.toString(), cues.toString().contains("5 minutes left. 5 minutes left"));
        assertTrue(cues.contains("1440:1 minute left"));
        assertEquals(List.of(9, 8, 7, 6, 5, 4, 3, 2, 1), WorkoutEngine.minuteMarks(Duration.ofMinutes(10)));
        assertTrue(WorkoutEngine.minuteMarks(Duration.ofSeconds(150)).isEmpty());
    }

    @Test
    public void c25kWeek1_lastWalkMergesIntoTwoAndAHalfMinuteRun() {
        Workout w = WorkoutPresets.c25kWeek1();
        assertTrue(LastWalkMerge.applies(w));
        List<PlannedStep> merged = LastWalkMerge.merge(w.plannedSteps());
        assertEquals(w.plannedSteps().size() - 1, merged.size());
        PlannedStep last = merged.get(merged.size() - 2);
        assertEquals(SegmentType.RUN, last.segment().type());
        assertEquals(Duration.ofSeconds(150), last.segment().duration());
        assertEquals(SegmentType.COOLDOWN, merged.get(merged.size() - 1).segment().type());
        assertEquals(w.totalDuration(), new WorkoutTimeline(segmentsOf(merged)).total());
    }

    private static List<WorkoutSegment> segmentsOf(List<PlannedStep> plan) {
        List<WorkoutSegment> out = new ArrayList<>();
        for (PlannedStep p : plan) {
            out.add(p.segment());
        }
        return out;
    }

    @Test
    public void blockWorkout_onlyFinalRunAndWalkMerge_announcedAsFourMinuteRun() {
        Workout w = new Workout(1, "x", false, List.of(
                new WorkoutBlock(List.of(seg(SegmentType.RUN, 60), seg(SegmentType.WALK, 60), seg(SegmentType.RUN, 120), seg(SegmentType.WALK, 120)), 3)));
        List<PlannedStep> merged = LastWalkMerge.merge(w.plannedSteps());
        assertEquals(11, merged.size());
        assertEquals(Duration.ofMinutes(4), merged.get(10).segment().duration());
        assertEquals(SegmentType.WALK, merged.get(9).segment().type());

        WorkoutEngine engine = new WorkoutEngine(w, true);
        List<String> cues = run(engine, 18 * 60);
        int start = 14 * 60; // 2 full rounds (12 min) + run 1 + walk 1
        assertTrue(cues.toString(), cues.contains(start + ":4 minute run"));
        assertTrue(cues.contains((start + 60) + ":3 minutes left"));
        assertTrue(cues.contains((start + 120) + ":Halfway, 2 minutes left"));
        assertTrue(cues.contains((start + 180) + ":1 minute left"));
    }

    @Test
    public void noPromptWhenWorkoutDoesNotEndRunThenWalk() {
        assertFalse(LastWalkMerge.applies(Workout.ofSegments(1, "a", false, List.of(seg(SegmentType.WALK, 60), seg(SegmentType.RUN, 60)))));
        assertFalse(LastWalkMerge.applies(Workout.ofSegments(1, "b", false, List.of(seg(SegmentType.WARMUP, 60), seg(SegmentType.WALK, 60)))));
        assertFalse(LastWalkMerge.applies(Workout.ofSegments(1, "c", false, List.of(seg(SegmentType.RUN, 60), seg(SegmentType.WALK, 60), seg(SegmentType.RUN, 60)))));
        assertTrue(LastWalkMerge.applies(Workout.ofSegments(1, "d", false, List.of(seg(SegmentType.RUN, 60), seg(SegmentType.WALK, 60)))));
    }
}
