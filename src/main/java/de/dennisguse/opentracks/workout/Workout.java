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
import java.util.ArrayList;
import java.util.List;

/**
 * A workout is an ordered list of items: single segments and repeat blocks.
 * {@link #segments()} and {@link #plannedSteps()} give the expanded, playable order.
 */
public record Workout(long id, String name, boolean builtin, List<WorkoutItem> items, int distanceMeters) {

    /**
     * @param distanceMeters distance goal in meters; 0 for a timed (interval) workout. A distance
     *                       workout has no timed items.
     */
    public Workout {
        if (name == null) {
            name = "";
        }
        if (distanceMeters < 0) {
            throw new IllegalArgumentException("distanceMeters");
        }
        items = distanceMeters > 0 ? List.of() : List.copyOf(items);
    }

    /** A timed workout. */
    public Workout(long id, String name, boolean builtin, List<WorkoutItem> items) {
        this(id, name, builtin, items, 0);
    }

    public boolean isDistance() {
        return distanceMeters > 0;
    }

    /** A workout made only of single segments, as all workouts were before repeat blocks. */
    public static Workout ofSegments(long id, String name, boolean builtin, List<WorkoutSegment> segments) {
        return new Workout(id, name, builtin, new ArrayList<>(segments));
    }

    /** Every timed segment in play order, with blocks expanded into their rounds. */
    public List<WorkoutSegment> segments() {
        List<WorkoutSegment> segments = new ArrayList<>();
        for (PlannedStep step : plannedSteps()) {
            segments.add(step.segment());
        }
        return List.copyOf(segments);
    }

    public List<PlannedStep> plannedSteps() {
        return PlannedStep.expand(items);
    }

    /** Number of timed segments including every round. */
    public int segmentCount() {
        int count = 0;
        for (WorkoutItem item : items) {
            count += item.segmentCount();
        }
        return count;
    }

    public int blockCount() {
        int count = 0;
        for (WorkoutItem item : items) {
            if (item instanceof WorkoutBlock) {
                count++;
            }
        }
        return count;
    }

    public Duration totalDuration() {
        Duration total = Duration.ZERO;
        for (WorkoutItem item : items) {
            total = total.plus(item.totalDuration());
        }
        return total;
    }
}
