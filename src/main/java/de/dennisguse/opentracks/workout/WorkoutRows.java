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
 * Maps workout items to rows of {@code workout_segments} and back.
 * <p>
 * A single segment is a row with {@code block_id} and {@code block_rounds} NULL (all rows written before
 * repeat blocks existed look like this). Each step of a block is its own row; consecutive rows sharing a
 * {@code block_id} form one block, and {@code block_rounds} holds its round count.
 */
public final class WorkoutRows {

    public record Row(SegmentType type, long durationSeconds, Integer blockId, Integer blockRounds) {
    }

    private WorkoutRows() {
    }

    public static List<Row> toRows(List<WorkoutItem> items) {
        List<Row> rows = new ArrayList<>();
        int nextBlockId = 0;
        for (WorkoutItem item : items) {
            if (item instanceof WorkoutBlock block) {
                int blockId = nextBlockId++;
                for (WorkoutSegment step : block.steps()) {
                    rows.add(new Row(step.type(), step.duration().getSeconds(), blockId, block.rounds()));
                }
            } else if (item instanceof WorkoutSegment segment) {
                rows.add(new Row(segment.type(), segment.duration().getSeconds(), null, null));
            } else {
                throw new IllegalArgumentException("Unknown workout item " + item);
            }
        }
        return rows;
    }

    /** Rows must be in position order. A block row with a missing or out-of-range round count plays once. */
    public static List<WorkoutItem> fromRows(List<Row> rows) {
        List<WorkoutItem> items = new ArrayList<>();
        int i = 0;
        while (i < rows.size()) {
            Row row = rows.get(i);
            if (row.blockId() == null) {
                items.add(segment(row));
                i++;
                continue;
            }
            Integer blockId = row.blockId();
            int rounds = row.blockRounds() == null ? 1 : row.blockRounds();
            rounds = Math.max(WorkoutBlock.MIN_ROUNDS, Math.min(WorkoutBlock.MAX_ROUNDS, rounds));
            List<WorkoutSegment> steps = new ArrayList<>();
            while (i < rows.size() && blockId.equals(rows.get(i).blockId())) {
                steps.add(segment(rows.get(i)));
                i++;
            }
            items.add(new WorkoutBlock(steps, rounds));
        }
        return items;
    }

    private static WorkoutSegment segment(Row row) {
        return new WorkoutSegment(row.type(), Duration.ofSeconds(Math.max(0, row.durationSeconds())));
    }
}
