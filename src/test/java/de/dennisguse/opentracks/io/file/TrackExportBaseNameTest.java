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
package de.dennisguse.opentracks.io.file;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import de.dennisguse.opentracks.data.models.Statistics;
import de.dennisguse.opentracks.data.models.Track;

public class TrackExportBaseNameTest {

    private static final String TEMPLATE = "{date}_{time}_{name}";
    private static final String BASE = "2026-10-08_19_17_28.721_Couch to 5K _ Week 1 Day 3";

    @Test
    public void kmzSplitsAndReadingsShareOneBaseName() {
        Track track = track("Couch to 5K \u2013 Week 1 Day 3");
        TrackFilenameGenerator generator = new TrackFilenameGenerator(TEMPLATE);

        assertEquals(BASE, generator.baseName(track));
        assertEquals(BASE + ".kmz", generator.format(track, TrackFileFormat.KMZ_WITH_TRACKDETAIL_AND_SENSORDATA_AND_PICTURES));
        assertEquals(BASE + "-splits.csv", generator.splitsFilename(track));
        assertEquals(BASE + "-readings.csv", generator.readingsFilename(track));
    }

    @Test
    public void splitsKeepTheDayFromTheTrackName() {
        TrackFilenameGenerator generator = new TrackFilenameGenerator(TEMPLATE);
        Track day = track("Week 1 Day 3");

        String splits = generator.splitsFilename(day);

        assertEquals("2026-10-08_19_17_28.721_Week 1 Day 3-splits.csv", splits);
        assertEquals(generator.baseName(day) + "-splits.csv", splits);
    }

    private static Track track(String name) {
        return new Track(
                null,
                UUID.fromString("0000fee0-0000-1000-8000-00805f9b34fb"),
                name,
                "",
                "",
                null,
                ZoneOffset.UTC,
                new Statistics(
                        Instant.parse("2026-10-08T19:17:28.721Z"),
                        Instant.parse("2026-10-08T19:17:28.721Z"),
                        null, null, null, null, null, null, null, null)
        );
    }
}
