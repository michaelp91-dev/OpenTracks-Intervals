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

import androidx.annotation.StringRes;

import de.dennisguse.opentracks.R;

public enum SegmentType {
    // Order is the editor's type list order.
    WARMUP(R.string.workout_segment_warmup, "Warm-up walk"),
    WARMUP_JOG(R.string.workout_segment_warmup_jog, "Warm-up jog"),
    PREPARE(R.string.workout_segment_prepare, "Prepare"),
    RUN(R.string.workout_segment_run, "Run"),
    WALK(R.string.workout_segment_walk, "Walk"),
    COOLDOWN_JOG(R.string.workout_segment_cooldown_jog, "Cool-down jog"),
    COOLDOWN(R.string.workout_segment_cooldown, "Cool-down walk");

    @StringRes
    private final int labelRes;
    private final String spoken;

    SegmentType(@StringRes int labelRes, String spoken) {
        this.labelRes = labelRes;
        this.spoken = spoken;
    }

    @StringRes
    public int labelRes() {
        return labelRes;
    }

    public String spoken() {
        return spoken;
    }

    public boolean isRun() {
        return this == RUN;
    }

    /** Walk intervals only. Warm-up and cool-down are not included. */
    public boolean isWalkInterval() {
        return this == WALK;
    }

    /** Warm-up, walk intervals, and cool-down. */
    public boolean isAnyWalk() {
        return this == WARMUP || this == WALK || this == COOLDOWN;
    }

    /** Warm-up jog and cool-down jog. Not run intervals: they are not counted or merged. */
    public boolean isJog() {
        return this == WARMUP_JOG || this == COOLDOWN_JOG;
    }

    /** Segments that may follow the last walk without blocking "run the last walk". */
    public boolean isCooldownPart() {
        return this == COOLDOWN || this == COOLDOWN_JOG;
    }

    public static SegmentType fromDatabase(String value) {
        return valueOf(value);
    }
}
