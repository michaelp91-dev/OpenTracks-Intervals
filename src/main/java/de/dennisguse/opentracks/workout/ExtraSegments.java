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
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Optional warm-up jog, prepare and cool-down jog. They go in a fixed place:
 * warm-up walk, warm-up jog, prepare, main workout, cool-down jog, cool-down walk.
 * A zero or missing duration leaves that segment out.
 */
public final class ExtraSegments {

    private ExtraSegments() {
    }

    /**
     * Removes any top-level warm-up jog, prepare and cool-down jog in {@code items}, then adds the
     * given ones back in their fixed places. {@code typeOf} returns null for a repeat block.
     */
    public static <T> List<T> apply(List<T> items, Function<T, SegmentType> typeOf,
                                    Duration warmupJog, Duration prepare, Duration cooldownJog,
                                    BiFunction<SegmentType, Duration, T> make) {
        List<T> out = new ArrayList<>();
        for (T item : items) {
            SegmentType type = typeOf.apply(item);
            if (type != SegmentType.WARMUP_JOG && type != SegmentType.PREPARE && type != SegmentType.COOLDOWN_JOG) {
                out.add(item);
            }
        }
        int start = 0;
        while (start < out.size() && typeOf.apply(out.get(start)) == SegmentType.WARMUP) {
            start++;
        }
        int end = out.size();
        while (end > start && typeOf.apply(out.get(end - 1)) == SegmentType.COOLDOWN) {
            end--;
        }
        if (positive(cooldownJog)) {
            out.add(end, make.apply(SegmentType.COOLDOWN_JOG, cooldownJog));
        }
        if (positive(prepare)) {
            out.add(start, make.apply(SegmentType.PREPARE, prepare));
        }
        if (positive(warmupJog)) {
            out.add(start, make.apply(SegmentType.WARMUP_JOG, warmupJog));
        }
        return out;
    }

    private static boolean positive(Duration duration) {
        return duration != null && duration.getSeconds() > 0;
    }
}
