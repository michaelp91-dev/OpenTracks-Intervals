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
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class WorkoutRowsTest {

    @Test
    public void legacyRows_withNullBlockColumns_loadAsTheSameFlatWorkout() {
        // Rows written before repeat blocks existed: the new columns are NULL after the migration.
        Workout preset = WorkoutPresets.c25kWeek1();
        List<WorkoutRows.Row> rows = new ArrayList<>();
        for (WorkoutSegment segment : preset.segments()) {
            rows.add(new WorkoutRows.Row(segment.type(), segment.duration().getSeconds(), null, null));
        }

        List<WorkoutItem> items = WorkoutRows.fromRows(rows);
        Workout loaded = new Workout(1, preset.name(), true, items);

        assertEquals(18, items.size());
        assertEquals(preset.segments(), loaded.segments());
        assertEquals(Duration.ofMinutes(30), loaded.totalDuration());
        assertEquals(0, loaded.blockCount());
    }

    @Test
    public void flatWorkout_writesNullBlockColumns() {
        for (WorkoutRows.Row row : WorkoutRows.toRows(WorkoutPresets.c25kWeek1().items())) {
            assertNull(row.blockId());
            assertNull(row.blockRounds());
        }
    }

    @Test
    public void blocks_roundTrip() {
        Workout workout = WorkoutBlockTest.example();

        List<WorkoutRows.Row> rows = WorkoutRows.toRows(workout.items());

        assertEquals(1 + 4 + 1, rows.size());
        assertNull(rows.get(0).blockId());
        for (int i = 1; i <= 4; i++) {
            assertEquals(Integer.valueOf(0), rows.get(i).blockId());
            assertEquals(Integer.valueOf(3), rows.get(i).blockRounds());
        }
        assertNull(rows.get(5).blockId());
        assertEquals(workout.items(), WorkoutRows.fromRows(rows));
    }

    @Test
    public void adjacentBlocks_stayApart() {
        List<WorkoutItem> items = List.of(
                new WorkoutBlock(List.of(WorkoutBlockTest.run(60), WorkoutBlockTest.walk(60)), 2),
                new WorkoutBlock(List.of(WorkoutBlockTest.run(60), WorkoutBlockTest.walk(60)), 5),
                WorkoutBlockTest.walk(30)
        );

        assertEquals(items, WorkoutRows.fromRows(WorkoutRows.toRows(items)));
    }

    @Test
    public void badRoundCount_playsOnceOrIsCapped() {
        List<WorkoutRows.Row> rows = List.of(
                new WorkoutRows.Row(SegmentType.RUN, 60, 0, null),
                new WorkoutRows.Row(SegmentType.RUN, 60, 1, 0),
                new WorkoutRows.Row(SegmentType.RUN, 60, 2, 500)
        );

        List<WorkoutItem> items = WorkoutRows.fromRows(rows);

        assertEquals(3, items.size());
        assertEquals(1, ((WorkoutBlock) items.get(0)).rounds());
        assertEquals(1, ((WorkoutBlock) items.get(1)).rounds());
        assertEquals(WorkoutBlock.MAX_ROUNDS, ((WorkoutBlock) items.get(2)).rounds());
    }
}
