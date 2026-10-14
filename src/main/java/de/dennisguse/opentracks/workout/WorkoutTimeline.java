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
 * Planned segment offsets. Index {@code -1} is before the start and {@code size()} is after the end.
 * A segment covers {@code [start, end)}.
 */
public final class WorkoutTimeline {

    private final List<Duration> startOffsets;
    private final List<Duration> endOffsets;
    private final Duration total;

    public WorkoutTimeline(List<WorkoutSegment> segments) {
        List<Duration> starts = new ArrayList<>();
        List<Duration> ends = new ArrayList<>();
        Duration cursor = Duration.ZERO;
        for (WorkoutSegment segment : segments) {
            starts.add(cursor);
            cursor = cursor.plus(segment.duration());
            ends.add(cursor);
        }
        startOffsets = List.copyOf(starts);
        endOffsets = List.copyOf(ends);
        total = cursor;
    }

    public int size() {
        return startOffsets.size();
    }

    public Duration total() {
        return total;
    }

    public Duration halfway() {
        return total.dividedBy(2);
    }

    public Duration startOffset(int index) {
        return startOffsets.get(index);
    }

    public Duration endOffset(int index) {
        return endOffsets.get(index);
    }

    public int indexAt(Duration elapsed) {
        if (elapsed == null || elapsed.isNegative()) {
            return -1;
        }
        if (elapsed.compareTo(total) >= 0) {
            return size();
        }
        for (int i = 0; i < size(); i++) {
            if (elapsed.compareTo(endOffsets.get(i)) < 0) {
                return i;
            }
        }
        return size();
    }
}
