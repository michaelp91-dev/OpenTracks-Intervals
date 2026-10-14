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

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

/** Survives a recording-service restart so cues can continue without repeating. */
public final class WorkoutSessionStore {

    private static final String PREF = "workout_session";
    private static final String TRACK_ID = "track_id";
    private static final String WORKOUT_ID = "workout_id";
    private static final String START_EPOCH_MS = "start_epoch_ms";
    private static final String START_ELAPSED_MS = "start_elapsed_ms";

    private final SharedPreferences preferences;

    public WorkoutSessionStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    @SuppressLint("ApplySharedPref") // commit so a kill during recording cannot drop the session
    public void save(long trackId, long workoutId, long startEpochMs, long startElapsedMs) {
        preferences.edit()
                .putLong(TRACK_ID, trackId)
                .putLong(WORKOUT_ID, workoutId)
                .putLong(START_EPOCH_MS, startEpochMs)
                .putLong(START_ELAPSED_MS, startElapsedMs)
                .commit();
    }

    @SuppressLint("ApplySharedPref") // commit so a kill right after stop cannot restore a finished workout
    public void clear() {
        preferences.edit().clear().commit();
    }

    public Saved load() {
        if (!preferences.contains(TRACK_ID) || !preferences.contains(WORKOUT_ID)) {
            return null;
        }
        return new Saved(
                preferences.getLong(TRACK_ID, -1),
                preferences.getLong(WORKOUT_ID, -1),
                preferences.getLong(START_EPOCH_MS, 0),
                preferences.getLong(START_ELAPSED_MS, 0)
        );
    }

    public record Saved(long trackId, long workoutId, long startEpochMs, long startElapsedMs) {
    }
}
