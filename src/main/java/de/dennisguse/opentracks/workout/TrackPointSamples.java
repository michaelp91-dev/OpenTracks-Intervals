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

import de.dennisguse.opentracks.data.ContentProviderUtils;
import de.dennisguse.opentracks.data.TrackPointIterator;
import de.dennisguse.opentracks.data.models.Track;
import de.dennisguse.opentracks.data.models.TrackPoint;

/** Turns stored track points into per-point distance deltas. Segment breaks contribute no distance. */
public final class TrackPointSamples {

    private TrackPointSamples() {
    }

    public static List<Sample> from(ContentProviderUtils contentProviderUtils, Track.Id trackId) {
        List<Sample> samples = new ArrayList<>();
        try (TrackPointIterator iterator = contentProviderUtils.getTrackPointIterator(trackId, null)) {
            TrackPoint previous = null;
            while (iterator.hasNext()) {
                TrackPoint current = iterator.next();
                if (previous == null || isBoundary(current)) {
                    previous = current;
                    continue;
                }
                Double delta = deltaMeters(previous, current);
                if (delta != null && current.getTime() != null) {
                    samples.add(new Sample(current.getTime(), delta));
                }
                previous = current;
            }
        }
        return samples;
    }

    private static boolean isBoundary(TrackPoint point) {
        TrackPoint.Type type = point.type();
        return type == TrackPoint.Type.SEGMENT_START_MANUAL
                || type == TrackPoint.Type.SEGMENT_START_AUTOMATIC
                || type == TrackPoint.Type.SEGMENT_END_MANUAL;
    }

    private static Double deltaMeters(TrackPoint previous, TrackPoint current) {
        boolean sensor = current.sensorDistance() != null;
        boolean locations = current.position().hasLocation() && previous.position().hasLocation();
        if (!sensor && !locations) {
            return null;
        }
        return current.distanceToPrevious(previous).toM();
    }
}
