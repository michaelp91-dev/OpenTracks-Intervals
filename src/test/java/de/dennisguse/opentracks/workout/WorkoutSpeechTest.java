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

public class WorkoutSpeechTest {

    @Test
    public void examplesFromThePlan() {
        assertEquals("5 minute warm-up walk", WorkoutSpeech.intervalCue(SegmentType.WARMUP, Duration.ofMinutes(5)));
        assertEquals("1 minute run", WorkoutSpeech.intervalCue(SegmentType.RUN, Duration.ofMinutes(1)));
        assertEquals("1 minute 30 second walk", WorkoutSpeech.intervalCue(SegmentType.WALK, Duration.ofSeconds(90)));
        assertEquals("1 minute 30 second run", WorkoutSpeech.intervalCue(SegmentType.RUN, Duration.ofSeconds(90)));
    }

    @Test
    public void leavesOffAZeroPart() {
        assertEquals("45 second cool-down walk", WorkoutSpeech.intervalCue(SegmentType.COOLDOWN, Duration.ofSeconds(45)));
        assertEquals("2 minute run", WorkoutSpeech.intervalCue(SegmentType.RUN, Duration.ofMinutes(2)));
    }

    @Test
    public void stepCue_addsRoundOnlyWhenMarked() {
        WorkoutSegment run = new WorkoutSegment(SegmentType.RUN, Duration.ofMinutes(1));
        assertEquals("1 minute run", WorkoutSpeech.stepCue(new PlannedStep(run, 0, 0, false), run.duration()));
        assertEquals("1 minute run", WorkoutSpeech.stepCue(new PlannedStep(run, 2, 6, false), run.duration()));
        assertEquals("1 minute run, round 1", WorkoutSpeech.stepCue(new PlannedStep(run, 1, 6, true), run.duration()));
        assertEquals("1 minute run, round 5", WorkoutSpeech.stepCue(new PlannedStep(run, 5, 6, true), run.duration()));
        assertEquals("1 minute run, round 6, final round", WorkoutSpeech.stepCue(new PlannedStep(run, 6, 6, true), run.duration()));
    }

    @Test
    public void roundPhrase() {
        assertEquals("round 2", WorkoutSpeech.roundPhrase(2, 3));
        assertEquals("round 3, final round", WorkoutSpeech.roundPhrase(3, 3));
    }
}
