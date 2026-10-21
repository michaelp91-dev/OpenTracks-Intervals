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

import android.content.Context;

import de.dennisguse.opentracks.R;

public final class WorkoutBanner {

    private WorkoutBanner() {
    }

    public static String format(Context context, WorkoutState state) {
        if (state == null) {
            return "";
        }
        if (state.distanceStatus() != null) {
            return state.distanceStatus();
        }
        if (state.finished() || state.type() == null) {
            return context.getString(R.string.workout_cue_complete);
        }
        String head;
        if (state.type().isRun()) {
            head = context.getString(R.string.workout_banner_run, state.runNumber(), state.runCount());
        } else if (state.type().isWalkInterval()) {
            head = context.getString(R.string.workout_banner_walk, state.walkNumber(), state.walkCount());
        } else {
            head = context.getString(state.type().labelRes());
        }
        if (state.roundCount() > 0) {
            head = context.getString(R.string.workout_banner_round, head, state.round(), state.roundCount());
        }
        return context.getString(
                R.string.workout_banner_status,
                head,
                WorkoutFormat.minutesSeconds(state.remainingInSegment()),
                WorkoutFormat.minutesSeconds(state.elapsed()),
                WorkoutFormat.minutesSeconds(state.total())
        );
    }
}
