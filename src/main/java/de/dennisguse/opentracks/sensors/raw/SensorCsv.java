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

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Plain-text CSV layout for one recording's raw sensors.
 * Comment lines start with {@code #}. The achieved-rate fields are fixed width so they can be
 * rewritten in place while samples are appended. A kill can then keep every flushed sample.
 */
public final class SensorCsv {

    public static final int TARGET_HZ = 50;
    public static final int RATE_WIDTH = 320;
    public static final int STATS_WIDTH = 512;
    /** Intervals longer than this are a pause or a stopped recording, not the sensor's rate. */
    public static final long GAP_NS = 1_000_000_000L;

    public static final String COLUMNS = "timestamp_ns,wall_time_ms,sensor,v0,v1,v2,v3,v4";
    public static final String RATE_MARKER = "# sampling_rate_hz=";
    public static final String STATS_MARKER = "# sample_stats=";

    /** Same integers as {@code android.hardware.Sensor}. */
    public static final int TYPE_ACCELEROMETER = 1;
    public static final int TYPE_GYROSCOPE = 4;
    public static final int TYPE_PRESSURE = 6;
    public static final int TYPE_GRAVITY = 9;
    public static final int TYPE_LINEAR_ACCELERATION = 10;
    public static final int TYPE_ROTATION_VECTOR = 11;
    public static final int TYPE_STEP_DETECTOR = 18;
    public static final int TYPE_STEP_COUNTER = 19;

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private SensorCsv() {
    }

    public static String nameFor(int type) {
        switch (type) {
            case TYPE_ACCELEROMETER:
                return "TYPE_ACCELEROMETER";
            case TYPE_GYROSCOPE:
                return "TYPE_GYROSCOPE";
            case TYPE_PRESSURE:
                return "TYPE_PRESSURE";
            case TYPE_GRAVITY:
                return "TYPE_GRAVITY";
            case TYPE_LINEAR_ACCELERATION:
                return "TYPE_LINEAR_ACCELERATION";
            case TYPE_ROTATION_VECTOR:
                return "TYPE_ROTATION_VECTOR";
            case TYPE_STEP_DETECTOR:
                return "TYPE_STEP_DETECTOR";
            case TYPE_STEP_COUNTER:
                return "TYPE_STEP_COUNTER";
            default:
                return "TYPE_" + type;
        }
    }

    public static boolean needsActivityRecognition(int type) {
        return type == TYPE_STEP_DETECTOR || type == TYPE_STEP_COUNTER;
    }

    public static String filename(long trackId, long startedAtEpochMs, ZoneOffset offset) {
        ZoneOffset zone = offset == null ? ZoneOffset.UTC : offset;
        String stamp = FILE_TIME.format(Instant.ofEpochMilli(startedAtEpochMs).atOffset(zone));
        return "track-" + trackId + "-" + stamp + ".csv";
    }

    public static String header(String model, String androidVersion, int sdk, long startedAtEpochMs, List<SensorDescription> sensors) {
        StringBuilder text = new StringBuilder();
        text.append("# opentracks_sensor_log=1\n");
        text.append("# device_model=").append(sanitize(model)).append('\n');
        text.append("# android_version=").append(sanitize(androidVersion)).append('\n');
        text.append("# android_sdk=").append(sdk).append('\n');
        text.append("# started_wall_time_ms=").append(startedAtEpochMs).append('\n');
        text.append("# target_sampling=SENSOR_DELAY_GAME\n");
        text.append("# target_sampling_hz=").append(TARGET_HZ).append('\n');
        text.append("# rate_gap_s=1\n");
        if (sensors != null) {
            for (SensorDescription sensor : sensors) {
                text.append(sensorLine(sensor));
            }
        }
        text.append(RATE_MARKER).append(spaces(RATE_WIDTH)).append('\n');
        text.append(STATS_MARKER).append(spaces(STATS_WIDTH)).append('\n');
        text.append(COLUMNS).append('\n');
        return text.toString();
    }

    public static String sensorLine(SensorDescription sensor) {
        return "# sensor=" + sanitize(sensor.key)
                + ",type=" + sensor.type
                + ",name=" + sanitize(sensor.name)
                + ",vendor=" + sanitize(sensor.vendor)
                + "\n";
    }

    public static String marker(String kind, long wallTimeMs) {
        return "# " + kind + " wall_time_ms=" + wallTimeMs + "\n";
    }

    public static String formatSample(long timestampNs, long wallTimeMs, String sensor, float[] values) {
        StringBuilder line = new StringBuilder(96);
        line.append(timestampNs).append(',').append(wallTimeMs).append(',').append(sensor);
        int count = values == null ? 0 : Math.min(values.length, 5);
        for (int i = 0; i < 5; i++) {
            line.append(',');
            if (i < count && !Float.isNaN(values[i]) && !Float.isInfinite(values[i])) {
                line.append(Float.toString(values[i]));
            }
        }
        line.append('\n');
        return line.toString();
    }

    public static String formatRates(Map<String, SensorStats> stats) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, SensorStats> entry : stats.entrySet()) {
            if (text.length() > 0) {
                text.append(';');
            }
            text.append(entry.getKey()).append('=');
            text.append(String.format(Locale.US, "%.2f", entry.getValue().hz()));
        }
        return text.toString();
    }

    public static String formatStats(Map<String, SensorStats> stats) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, SensorStats> entry : stats.entrySet()) {
            if (text.length() > 0) {
                text.append(';');
            }
            SensorStats value = entry.getValue();
            text.append(entry.getKey())
                    .append(',')
                    .append(value.count)
                    .append(',')
                    .append(value.intervals)
                    .append(',')
                    .append(value.sumDtNs);
        }
        return text.toString();
    }

    public static Map<String, SensorStats> parseStats(String field) {
        Map<String, SensorStats> stats = new LinkedHashMap<>();
        if (field == null || field.isEmpty()) {
            return stats;
        }
        String[] parts = field.split(";");
        for (String part : parts) {
            String[] bits = part.split(",");
            if (bits.length != 4) {
                continue;
            }
            try {
                SensorStats value = new SensorStats();
                value.count = Long.parseLong(bits[1]);
                value.intervals = Long.parseLong(bits[2]);
                value.sumDtNs = Long.parseLong(bits[3]);
                stats.put(bits[0], value);
            } catch (NumberFormatException ignored) {
                // A torn rewrite is skipped. The next flush writes the field again.
            }
        }
        return stats;
    }

    /** @return index of the value after {@code marker}, or -1 */
    public static int fieldOffset(byte[] prefix, String marker) {
        int at = indexOf(prefix, marker);
        if (at < 0) {
            return -1;
        }
        return at + marker.length();
    }

    public static int indexOf(byte[] data, String needle) {
        if (data == null || needle == null || needle.isEmpty() || data.length < needle.length()) {
            return -1;
        }
        byte[] wanted = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= data.length - wanted.length; i++) {
            for (int j = 0; j < wanted.length; j++) {
                if (data[i + j] != wanted[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    public static boolean writeField(RandomAccessFile file, int offset, String value, int width) throws IOException {
        if (file == null || offset < 0 || value == null || value.length() > width) {
            return false;
        }
        file.seek(offset);
        file.write(pad(value, width).getBytes(StandardCharsets.US_ASCII));
        return true;
    }

    public static String readField(RandomAccessFile file, int offset, int width) throws IOException {
        if (file == null || offset < 0 || width <= 0) {
            return "";
        }
        byte[] buf = new byte[width];
        file.seek(offset);
        int read = file.read(buf);
        if (read <= 0) {
            return "";
        }
        return new String(buf, 0, read, StandardCharsets.US_ASCII).trim();
    }

    static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c > 126 || c == ',') {
                text.append(' ');
            } else {
                text.append(c);
            }
        }
        return text.toString().trim();
    }

    private static String pad(String value, int width) {
        StringBuilder text = new StringBuilder(width);
        text.append(value);
        while (text.length() < width) {
            text.append(' ');
        }
        return text.toString();
    }

    private static String spaces(int width) {
        return pad("", width);
    }

    public static final class SensorDescription {
        public final String key;
        public final int type;
        public final String name;
        public final String vendor;

        public SensorDescription(String key, int type, String name, String vendor) {
            this.key = key;
            this.type = type;
            this.name = name;
            this.vendor = vendor;
        }
    }

    public static final class SensorStats {
        public long count;
        public long intervals;
        public long sumDtNs;
        public boolean hasPrevious;
        public long previousTimestampNs;

        public void add(long timestampNs) {
            if (hasPrevious) {
                long dt = timestampNs - previousTimestampNs;
                if (dt > 0 && dt < GAP_NS) {
                    intervals++;
                    sumDtNs += dt;
                }
            }
            hasPrevious = true;
            previousTimestampNs = timestampNs;
            count++;
        }

        public double hz() {
            if (intervals <= 0 || sumDtNs <= 0) {
                return 0.0;
            }
            return intervals * 1_000_000_000.0 / (double) sumDtNs;
        }
    }
}
