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
import java.util.List;

/**
 * A sequence of steps repeated {@code rounds} times. Stays one entry in the editor and the database;
 * it is expanded into rounds only when the workout plays.
 */
public record WorkoutBlock(List<WorkoutSegment> steps, int rounds) implements WorkoutItem {

    public static final int MIN_ROUNDS = 1;
    public static final int MAX_ROUNDS = 99;

    public WorkoutBlock {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("steps");
        }
        if (rounds < MIN_ROUNDS || rounds > MAX_ROUNDS) {
            throw new IllegalArgumentException("rounds");
        }
        steps = List.copyOf(steps);
    }

    public WorkoutBlock withRounds(int newRounds) {
        return new WorkoutBlock(steps, newRounds);
    }

    /** Length of one round. */
    public Duration roundDuration() {
        Duration total = Duration.ZERO;
        for (WorkoutSegment step : steps) {
            total = total.plus(step.duration());
        }
        return total;
    }

    @Override
    public Duration totalDuration() {
        return roundDuration().multipliedBy(rounds);
    }

    @Override
    public int segmentCount() {
        return steps.size() * rounds;
    }
}
