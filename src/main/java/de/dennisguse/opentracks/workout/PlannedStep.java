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

import java.util.ArrayList;
import java.util.List;

/**
 * One timed segment as it plays. Steps from a {@link WorkoutBlock} carry their round ({@code 1..roundCount});
 * single steps have round 0 and roundCount 0.
 *
 * @param announceRound true only for the first run step of a block round; that cue gets "round N" appended
 */
public record PlannedStep(WorkoutSegment segment, int round, int roundCount, boolean announceRound) {

    public boolean inBlock() {
        return roundCount > 0;
    }

    public boolean isFinalRound() {
        return inBlock() && round == roundCount;
    }

    /** Expands blocks into rounds, in order. */
    public static List<PlannedStep> expand(List<WorkoutItem> items) {
        List<PlannedStep> plan = new ArrayList<>();
        for (WorkoutItem item : items) {
            if (item instanceof WorkoutBlock block) {
                for (int round = 1; round <= block.rounds(); round++) {
                    boolean runAnnounced = false;
                    for (WorkoutSegment step : block.steps()) {
                        boolean announce = !runAnnounced && step.type().isRun();
                        if (announce) {
                            runAnnounced = true;
                        }
                        plan.add(new PlannedStep(step, round, block.rounds(), announce));
                    }
                }
            } else if (item instanceof WorkoutSegment segment) {
                plan.add(new PlannedStep(segment, 0, 0, false));
            } else {
                throw new IllegalArgumentException("Unknown workout item " + item);
            }
        }
        return List.copyOf(plan);
    }

    /** Plain segments with no round information. */
    public static List<PlannedStep> ofSegments(List<WorkoutSegment> segments) {
        List<PlannedStep> plan = new ArrayList<>();
        for (WorkoutSegment segment : segments) {
            plan.add(new PlannedStep(segment, 0, 0, false));
        }
        return List.copyOf(plan);
    }
}
