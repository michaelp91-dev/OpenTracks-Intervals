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
package de.dennisguse.opentracks.services;

/**
 * Spoken and on-screen start countdown. {@link #begin()} is the first second.
 * Each {@link #tick()} is one second later. The last tick is {@link #GO}, which
 * is the moment recording may start.
 */
public final class StartCountdown {

    public static final String GO = "Go";

    private final int seconds;
    private int remaining;
    private boolean finished;

    public StartCountdown(int seconds) {
        if (seconds < 1) {
            throw new IllegalArgumentException("seconds");
        }
        this.seconds = seconds;
        this.remaining = seconds;
    }

    public int lengthSeconds() {
        return seconds;
    }

    public String begin() {
        return Integer.toString(remaining);
    }

    /** One second after the previous cue. Null once {@link #GO} has been returned. */
    public String tick() {
        if (finished) {
            return null;
        }
        remaining--;
        if (remaining <= 0) {
            finished = true;
            return GO;
        }
        return Integer.toString(remaining);
    }

    public boolean isFinished() {
        return finished;
    }
}
