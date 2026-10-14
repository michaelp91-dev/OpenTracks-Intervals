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
import java.util.Locale;

public final class WorkoutFormat {

    public static final String UNAVAILABLE = "\u2013";

    private WorkoutFormat() {
    }

    public static String minutesSeconds(Duration duration) {
        long seconds = duration == null ? 0 : Math.max(0, duration.getSeconds());
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** Accepts {@code m:ss} or a number of seconds. Returns null when the text is not a duration. */
    public static Duration parseMinutesSeconds(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] parts = trimmed.split(":");
        try {
            if (parts.length == 1) {
                long seconds = Long.parseLong(parts[0].trim());
                if (seconds < 0) {
                    return null;
                }
                return Duration.ofSeconds(seconds);
            }
            if (parts.length == 2) {
                long minutes = Long.parseLong(parts[0].trim());
                long seconds = Long.parseLong(parts[1].trim());
                if (minutes < 0 || seconds < 0 || seconds >= 60) {
                    return null;
                }
                return Duration.ofSeconds(minutes * 60 + seconds);
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }
}
