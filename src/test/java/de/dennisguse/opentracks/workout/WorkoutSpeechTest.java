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
}
