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

/** User-chosen order of the Workouts list, stored as {@code workouts.sort_order}. */
public final class WorkoutOrder {

    /** Gives existing workouts their order from before sorting existed (oldest first, i.e. {@code _id}). */
    static final String MIGRATE_SQL = "UPDATE " + WorkoutDatabase.TABLE_WORKOUTS + " SET sort_order = _id";

    /** Sort clause for listing: user order, then id for ties. */
    static final String ORDER_BY = "sort_order ASC, _id ASC";

    private WorkoutOrder() {
    }

    /** A copy of {@code ids} with the entry at {@code from} moved to {@code to}. */
    public static <T> List<T> moved(List<T> ids, int from, int to) {
        List<T> copy = new ArrayList<>(ids);
        if (from < 0 || from >= copy.size() || to < 0 || to >= copy.size() || from == to) {
            return copy;
        }
        copy.add(to, copy.remove(from));
        return copy;
    }

    /** The sort value for a new workout so it lands at the bottom. */
    public static long next(Long currentMax) {
        return currentMax == null ? 0 : currentMax + 1;
    }
}
