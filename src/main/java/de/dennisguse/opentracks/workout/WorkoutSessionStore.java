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

    private static final String PACE_WORKOUT_ID = "pace_workout_id";
    private static final String PACE_GOAL_S = "pace_goal_s";
    private static final String PACE_EVERY_M = "pace_every_m";

    private static final String LAST_WALK_WORKOUT_ID = "last_walk_workout_id";
    private static final String LAST_WALK_RUN = "last_walk_run";

    private final SharedPreferences preferences;

    /** "Run the last walk?" answer for the next start of this workout. Cleared with the session. */
    @SuppressLint("ApplySharedPref")
    public void saveRunLastWalk(long workoutId, boolean run) {
        preferences.edit()
                .putLong(LAST_WALK_WORKOUT_ID, workoutId)
                .putBoolean(LAST_WALK_RUN, run)
                .commit();
    }

    public boolean loadRunLastWalk(long workoutId) {
        return preferences.getLong(LAST_WALK_WORKOUT_ID, -1) == workoutId && preferences.getBoolean(LAST_WALK_RUN, false);
    }

    /**
     * Pace choice for the next start of a distance workout. goalSeconds 0 means no pace.
     * Kept with the session so a restart keeps the same plan; cleared with it.
     */
    @SuppressLint("ApplySharedPref")
    public void savePace(long workoutId, long goalSeconds, int everyMeters) {
        preferences.edit()
                .putLong(PACE_WORKOUT_ID, workoutId)
                .putLong(PACE_GOAL_S, goalSeconds)
                .putInt(PACE_EVERY_M, everyMeters)
                .commit();
    }

    /** @return {goalSeconds, everyMeters} for this workout, or null when none was chosen */
    public long[] loadPace(long workoutId) {
        if (preferences.getLong(PACE_WORKOUT_ID, -1) != workoutId || preferences.getLong(PACE_GOAL_S, 0) <= 0) {
            return null;
        }
        return new long[]{preferences.getLong(PACE_GOAL_S, 0), preferences.getInt(PACE_EVERY_M, 0)};
    }

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
