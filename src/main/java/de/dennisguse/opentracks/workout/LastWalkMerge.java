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
 * "Run the last walk": the final walk interval joins the run right before it,
 * so 1:00 run + 1:30 walk becomes one 2:30 run. Only a cool-down may follow that walk.
 */
public final class LastWalkMerge {

    private LastWalkMerge() {
    }

    /** Index of the walk that would be merged, or -1 when the workout does not end in run then walk. */
    public static int mergeableWalkIndex(List<PlannedStep> plan) {
        int walk = -1;
        for (int i = plan.size() - 1; i >= 0; i--) {
            SegmentType type = plan.get(i).segment().type();
            if (type == SegmentType.COOLDOWN) {
                continue;
            }
            if (type == SegmentType.WALK) {
                walk = i;
            }
            break;
        }
        if (walk <= 0 || !plan.get(walk - 1).segment().type().isRun()) {
            return -1;
        }
        return walk;
    }

    public static boolean applies(Workout workout) {
        return workout != null && !workout.isDistance() && mergeableWalkIndex(workout.plannedSteps()) >= 0;
    }

    /** The plan with its last walk merged into the run before it; unchanged when it does not apply. */
    public static List<PlannedStep> merge(List<PlannedStep> plan) {
        int walk = mergeableWalkIndex(plan);
        if (walk < 0) {
            return plan;
        }
        PlannedStep run = plan.get(walk - 1);
        WorkoutSegment merged = new WorkoutSegment(SegmentType.RUN,
                run.segment().duration().plus(plan.get(walk).segment().duration()));
        List<PlannedStep> out = new ArrayList<>(plan.subList(0, walk - 1));
        out.add(new PlannedStep(merged, run.round(), run.roundCount(), run.announceRound()));
        out.addAll(plan.subList(walk + 1, plan.size()));
        return List.copyOf(out);
    }
}
