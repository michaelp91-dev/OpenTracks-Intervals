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
import java.util.ArrayList;
import java.util.List;

public class WorkoutBlockTest {

    static WorkoutSegment run(long seconds) {
        return new WorkoutSegment(SegmentType.RUN, Duration.ofSeconds(seconds));
    }

    static WorkoutSegment walk(long seconds) {
        return new WorkoutSegment(SegmentType.WALK, Duration.ofSeconds(seconds));
    }

    /** Michael's example: run 1, walk 1, run 2, walk 2, three rounds, between a warm-up and a cool-down. */
    static Workout example() {
        List<WorkoutItem> items = new ArrayList<>();
        items.add(new WorkoutSegment(SegmentType.WARMUP, Duration.ofMinutes(5)));
        items.add(new WorkoutBlock(List.of(run(60), walk(60), run(120), walk(120)), 3));
        items.add(new WorkoutSegment(SegmentType.COOLDOWN, Duration.ofMinutes(5)));
        return new Workout(7, "Ladder", false, items);
    }

    @Test
    public void block_staysOneItemButCountsEveryRound() {
        Workout workout = example();

        assertEquals(3, workout.items().size());
        assertEquals(1, workout.blockCount());
        assertEquals(1 + 4 * 3 + 1, workout.segmentCount());
        assertEquals(14, workout.segments().size());
        // 5:00 + 3 x 6:00 + 5:00
        assertEquals(Duration.ofMinutes(28), workout.totalDuration());
        WorkoutBlock block = (WorkoutBlock) workout.items().get(1);
        assertEquals(Duration.ofMinutes(6), block.roundDuration());
        assertEquals(Duration.ofMinutes(18), block.totalDuration());
    }

    @Test
    public void expand_repeatsStepsInOrder() {
        List<WorkoutSegment> segments = example().segments();

        assertEquals(SegmentType.WARMUP, segments.get(0).type());
        for (int round = 0; round < 3; round++) {
            int base = 1 + round * 4;
            assertEquals(run(60), segments.get(base));
            assertEquals(walk(60), segments.get(base + 1));
            assertEquals(run(120), segments.get(base + 2));
            assertEquals(walk(120), segments.get(base + 3));
        }
        assertEquals(SegmentType.COOLDOWN, segments.get(13).type());
    }

    @Test
    public void plannedSteps_markOnlyFirstRunOfEachRound() {
        List<PlannedStep> plan = example().plannedSteps();

        PlannedStep warmup = plan.get(0);
        assertFalse(warmup.inBlock());
        assertFalse(warmup.announceRound());
        assertEquals(0, warmup.round());

        for (int round = 1; round <= 3; round++) {
            int base = 1 + (round - 1) * 4;
            for (int i = 0; i < 4; i++) {
                PlannedStep step = plan.get(base + i);
                assertTrue(step.inBlock());
                assertEquals(round, step.round());
                assertEquals(3, step.roundCount());
                assertEquals(round == 3, step.isFinalRound());
                assertEquals("only the first run of the round", i == 0, step.announceRound());
            }
        }
        assertFalse(plan.get(13).announceRound());
        assertFalse(plan.get(13).inBlock());
    }

    @Test
    public void plannedSteps_walkFirstRoundAnnouncesOnTheFirstRun() {
        Workout workout = new Workout(0, "w", false, List.of(new WorkoutBlock(List.of(walk(30), run(60), run(30)), 2)));
        List<PlannedStep> plan = workout.plannedSteps();

        assertFalse(plan.get(0).announceRound());
        assertTrue(plan.get(1).announceRound());
        assertFalse(plan.get(2).announceRound());
        assertFalse(plan.get(3).announceRound());
        assertTrue(plan.get(4).announceRound());
        assertFalse(plan.get(5).announceRound());
    }

    @Test
    public void plannedSteps_blockWithoutRunsNeverAnnounces() {
        Workout workout = new Workout(0, "w", false, List.of(new WorkoutBlock(List.of(walk(30), walk(60)), 2)));
        for (PlannedStep step : workout.plannedSteps()) {
            assertFalse(step.announceRound());
        }
    }

    @Test
    public void flatWorkout_hasNoRounds() {
        for (PlannedStep step : WorkoutPresets.c25kWeek1().plannedSteps()) {
            assertFalse(step.inBlock());
            assertFalse(step.announceRound());
        }
    }

    @Test
    public void twoBlocks_numberRoundsSeparately() {
        Workout workout = new Workout(0, "w", false, List.of(
                new WorkoutBlock(List.of(run(60), walk(60)), 2),
                new WorkoutBlock(List.of(run(30), walk(30)), 3)
        ));
        List<PlannedStep> plan = workout.plannedSteps();

        assertEquals(10, plan.size());
        assertEquals(2, plan.get(2).round());
        assertEquals(2, plan.get(2).roundCount());
        assertEquals(1, plan.get(4).round());
        assertEquals(3, plan.get(4).roundCount());
        assertTrue(plan.get(4).announceRound());
    }

    @Test
    public void withRounds_changesOnlyTheCount() {
        WorkoutBlock block = new WorkoutBlock(List.of(run(60), walk(60)), 3);
        WorkoutBlock four = block.withRounds(4);

        assertEquals(block.steps(), four.steps());
        assertEquals(4, four.rounds());
        assertEquals(Duration.ofMinutes(8), four.totalDuration());
        assertEquals(Duration.ofMinutes(4), block.withRounds(2).totalDuration());
    }

    @Test(expected = IllegalArgumentException.class)
    public void block_needsAStep() {
        new WorkoutBlock(List.of(), 3);
    }

    @Test(expected = IllegalArgumentException.class)
    public void block_needsAtLeastOneRound() {
        new WorkoutBlock(List.of(run(60)), 0);
    }
}
