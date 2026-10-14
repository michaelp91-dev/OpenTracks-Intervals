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
package de.dennisguse.opentracks.sensors.raw;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SensorCsvTest {

    @Test
    public void filenameUsesTrackIdAndLocalStart() {
        long started = Instant.parse("2026-10-12T15:30:45Z").toEpochMilli();
        assertEquals("track-12-20261012-153045.csv", SensorCsv.filename(12, started, ZoneOffset.UTC));
        assertEquals("track-12-20261012-083045.csv", SensorCsv.filename(12, started, ZoneOffset.ofHours(-7)));
    }

    @Test
    public void sampleKeepsFiveValueColumns() {
        String line = SensorCsv.formatSample(1000L, 2000L, "TYPE_ACCELEROMETER", new float[]{1.5f, -2f, 3f});
        assertEquals("1000,2000,TYPE_ACCELEROMETER,1.5,-2.0,3.0,,\n", line);

        String rotation = SensorCsv.formatSample(1L, 2L, "TYPE_ROTATION_VECTOR", new float[]{0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 9f});
        assertEquals("1,2,TYPE_ROTATION_VECTOR,0.1,0.2,0.3,0.4,0.5\n", rotation);
    }

    @Test
    public void headerHoldsDeviceSensorsAndFixedRateFields() throws Exception {
        String header = SensorCsv.header(
                "Pixel 7",
                "16",
                36,
                1_700_000_000_000L,
                List.of(new SensorCsv.SensorDescription("TYPE_ACCELEROMETER", 1, "LSM6DSR, Accel", "STMicro")));
        assertTrue(header.contains("# device_model=Pixel 7\n"));
        assertTrue(header.contains("# android_version=16\n"));
        assertTrue(header.contains("# android_sdk=36\n"));
        assertTrue(header.contains("# target_sampling_hz=50\n"));
        assertTrue(header.contains("# sensor=TYPE_ACCELEROMETER,type=1,name=LSM6DSR  Accel,vendor=STMicro\n"));
        assertTrue(header.endsWith(SensorCsv.COLUMNS + "\n"));

        byte[] bytes = header.getBytes(StandardCharsets.UTF_8);
        int rate = SensorCsv.fieldOffset(bytes, SensorCsv.RATE_MARKER);
        int stats = SensorCsv.fieldOffset(bytes, SensorCsv.STATS_MARKER);
        assertTrue(rate > 0);
        assertTrue(stats > rate);
        assertEquals('\n', bytes[rate + SensorCsv.RATE_WIDTH]);
        assertEquals('\n', bytes[stats + SensorCsv.STATS_WIDTH]);

        File file = File.createTempFile("sensor-csv", ".csv");
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.write(bytes);
            assertTrue(SensorCsv.writeField(raf, rate, "TYPE_ACCELEROMETER=50.00", SensorCsv.RATE_WIDTH));
            String stored = SensorCsv.readField(raf, rate, SensorCsv.RATE_WIDTH);
            assertEquals("TYPE_ACCELEROMETER=50.00", stored);
            raf.seek(0);
            byte[] read = new byte[(int) raf.length()];
            raf.readFully(read);
            String text = new String(read, StandardCharsets.UTF_8);
            assertTrue(text.contains(SensorCsv.COLUMNS));
            assertTrue(text.indexOf(SensorCsv.COLUMNS) > text.indexOf("TYPE_ACCELEROMETER=50.00"));
            assertEquals(bytes.length, read.length);
        } finally {
            assertTrue(file.delete());
        }
    }

    @Test
    public void achievedRateIgnoresPauses() {
        SensorCsv.SensorStats stats = new SensorCsv.SensorStats();
        stats.add(0L);
        stats.add(20_000_000L);
        stats.add(40_000_000L);
        stats.add(5_000_000_000L);
        stats.add(5_020_000_000L);
        assertEquals(5, stats.count);
        assertEquals(3, stats.intervals);
        assertEquals(50.0, stats.hz(), 0.001);
    }

    @Test
    public void statsRoundTripAndFitTheHeader() {
        Map<String, SensorCsv.SensorStats> stats = new LinkedHashMap<>();
        int[] types = {
                SensorCsv.TYPE_ACCELEROMETER,
                SensorCsv.TYPE_LINEAR_ACCELERATION,
                SensorCsv.TYPE_GYROSCOPE,
                SensorCsv.TYPE_GRAVITY,
                SensorCsv.TYPE_ROTATION_VECTOR,
                SensorCsv.TYPE_STEP_DETECTOR,
                SensorCsv.TYPE_STEP_COUNTER
        };
        for (int type : types) {
            SensorCsv.SensorStats value = new SensorCsv.SensorStats();
            value.count = 9_999_999L;
            value.intervals = 9_999_998L;
            value.sumDtNs = 199_999_960_000_000L;
            stats.put(SensorCsv.nameFor(type), value);
        }
        String rates = SensorCsv.formatRates(stats);
        String raw = SensorCsv.formatStats(stats);
        assertTrue(rates.length() <= SensorCsv.RATE_WIDTH);
        assertTrue(raw.length() <= SensorCsv.STATS_WIDTH);
        assertTrue(rates.contains("TYPE_ACCELEROMETER=50.00"));
        Map<String, SensorCsv.SensorStats> parsed = SensorCsv.parseStats(raw);
        assertEquals(stats.keySet(), parsed.keySet());
        assertEquals(9_999_999L, parsed.get("TYPE_GYROSCOPE").count);
        assertFalse(SensorCsv.needsActivityRecognition(SensorCsv.TYPE_ACCELEROMETER));
        assertTrue(SensorCsv.needsActivityRecognition(SensorCsv.TYPE_STEP_COUNTER));
    }
}
