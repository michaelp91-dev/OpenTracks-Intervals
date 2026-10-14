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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Drops distance on segment resets so a gap between GPS segments is not counted. */
public final class SampleFold {

    public record Observation(Instant time, boolean boundary, Double deltaMeters) {
    }

    private SampleFold() {
    }

    public static List<Sample> fold(List<Observation> observations) {
        List<Sample> samples = new ArrayList<>();
        for (Observation observation : observations) {
            if (observation.boundary() || observation.time() == null || observation.deltaMeters() == null) {
                continue;
            }
            samples.add(new Sample(observation.time(), observation.deltaMeters()));
        }
        return samples;
    }
}
