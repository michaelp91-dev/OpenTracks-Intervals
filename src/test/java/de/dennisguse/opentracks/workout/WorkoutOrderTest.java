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

import java.util.List;

public class WorkoutOrderTest {

    @Test
    public void moved_dragDownAndUp() {
        List<Long> ids = List.of(1L, 2L, 3L, 4L);
        assertEquals(List.of(2L, 3L, 1L, 4L), WorkoutOrder.moved(ids, 0, 2));
        assertEquals(List.of(4L, 1L, 2L, 3L), WorkoutOrder.moved(ids, 3, 0));
        assertEquals(ids, WorkoutOrder.moved(ids, 1, 1));
        assertEquals(ids, WorkoutOrder.moved(ids, 0, 9));
    }

    @Test
    public void next_putsNewWorkoutsAtTheBottom() {
        assertEquals(0, WorkoutOrder.next(null));
        assertEquals(6, WorkoutOrder.next(5L));
    }

    @Test
    public void migration_keepsTheOldOldestFirstOrder() {
        // Before sorting existed the list was "_id ASC"; migration copies _id into sort_order.
        assertEquals("UPDATE workouts SET sort_order = _id", WorkoutOrder.MIGRATE_SQL);
        assertEquals("sort_order ASC, _id ASC", WorkoutOrder.ORDER_BY);
    }
}
