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

/** Spoken interval names such as "1 minute 30 second run". */
public final class WorkoutSpeech {

    private WorkoutSpeech() {
    }

    public static String intervalCue(SegmentType type, Duration duration) {
        String name = type.spoken().toLowerCase(Locale.US);
        String length = spokenLength(duration);
        if (length.isEmpty()) {
            return name;
        }
        return length + " " + name;
    }

    /**
     * Interval cue for a planned step. On the first run of a block round it adds the round:
     * "1 minute run, round 2", and on the last round "1 minute run, round 6, final round".
     */
    public static String stepCue(PlannedStep step, Duration duration) {
        String cue = intervalCue(step.segment().type(), duration);
        if (!step.announceRound()) {
            return cue;
        }
        return cue + ", " + roundPhrase(step.round(), step.roundCount());
    }

    /** "round 2", or "round 6, final round" when it is the last round. */
    public static String roundPhrase(int round, int roundCount) {
        String phrase = "round " + round;
        if (round == roundCount) {
            phrase += ", final round";
        }
        return phrase;
    }

    /** Minutes and seconds, with a zero part left off. "minute" and "second" stay singular. */
    public static String spokenLength(Duration duration) {
        long total = duration == null ? 0 : Math.max(0, duration.getSeconds());
        long minutes = total / 60;
        long seconds = total % 60;
        StringBuilder spoken = new StringBuilder();
        if (minutes > 0) {
            spoken.append(minutes).append(" minute");
        }
        if (seconds > 0) {
            if (spoken.length() > 0) {
                spoken.append(' ');
            }
            spoken.append(seconds).append(" second");
        }
        return spoken.toString();
    }
}
