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

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.dennisguse.opentracks.data.ContentProviderUtils;
import de.dennisguse.opentracks.data.models.Track;
import de.dennisguse.opentracks.settings.PreferencesUtils;

/**
 * Writes raw phone sensors to one CSV for the current recording.
 * <p>
 * Samples are copied off the sensor event and written on this thread. The recording service
 * already holds a partial wake lock while a track is being recorded, so this class does not
 * take another one. The CSV is flushed about once a second. Gzip is not used: a kill would
 * leave an archive that cannot be read.
 */
public final class RawSensorLogger {

    private static final String TAG = "RawSensorLogger";
    private static final int FLUSH_BYTES = 32 * 1024;
    private static final long FLUSH_MS = 1000L;
    private static final int HEADER_SCAN_BYTES = 4096;
    private static final int MAX_BUFFER_CHARS = 1024 * 1024;

    private static final int[] TYPES = {
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_LINEAR_ACCELERATION,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_GRAVITY,
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_STEP_DETECTOR,
            Sensor.TYPE_STEP_COUNTER,
            Sensor.TYPE_PRESSURE, // v0 = hPa
            Sensor.TYPE_MAGNETIC_FIELD, // v0..v2 = uT
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED // v0..v2 = uT, v3..v5 = bias uT
    };

    private final HandlerThread thread;
    private final Handler handler;
    private final Map<String, SensorCsv.SensorStats> stats = new LinkedHashMap<>();
    private final StringBuilder buffer = new StringBuilder(FLUSH_BYTES);

    private volatile boolean closed;
    private boolean running;
    private boolean loggedStepSkip;
    private Track.Id trackId;
    private RandomAccessFile file;
    private int rateOffset = -1;
    private int statsOffset = -1;
    private long lastFlushElapsed;
    private SensorManager sensorManager;

    private final Runnable periodicFlush = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            flushBuffer();
            handler.postDelayed(this, FLUSH_MS);
        }
    };

    private final SensorEventListener listener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (!running || file == null || event == null || event.sensor == null) {
                return;
            }
            int count = Math.min(event.values.length, SensorCsv.MAX_VALUES);
            float[] copy = new float[count];
            System.arraycopy(event.values, 0, copy, 0, count);
            long timestampNs = event.timestamp;
            long wallTimeMs = System.currentTimeMillis();
            String name = SensorCsv.nameFor(event.sensor.getType());
            SensorCsv.SensorStats sensorStats = stats.get(name);
            if (sensorStats == null) {
                sensorStats = new SensorCsv.SensorStats();
                stats.put(name, sensorStats);
            }
            sensorStats.add(timestampNs);
            buffer.append(SensorCsv.formatSample(timestampNs, wallTimeMs, name, copy));
            long now = SystemClock.elapsedRealtime();
            if (buffer.length() >= FLUSH_BYTES || now - lastFlushElapsed >= FLUSH_MS) {
                flushBuffer();
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Called on the logger thread; 0..3 = SENSOR_STATUS_UNRELIABLE..ACCURACY_HIGH.
            if (!running || file == null || sensor == null) {
                return;
            }
            buffer.append(SensorCsv.accuracyMarker(SensorCsv.nameFor(sensor.getType()), accuracy, System.currentTimeMillis()));
        }
    };

    public RawSensorLogger() {
        thread = new HandlerThread("RawSensorLogger");
        thread.setDaemon(true);
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    public void start(Context context, Track.Id trackId) {
        if (closed || context == null || trackId == null) {
            return;
        }
        Context app = context.getApplicationContext();
        runSync(() -> startOnThread(app, trackId));
    }

    /** Recording ended. Flushes, writes a stopped marker, and keeps the file. */
    public void stop() {
        if (closed) {
            return;
        }
        runSync(() -> finish("stopped"));
    }

    /** Recording is still open (setting turned off, or the process is going away). */
    public void pause() {
        runSync(() -> finish("paused"));
    }

    public void flush() {
        if (closed) {
            return;
        }
        runSync(() -> {
            if (running) {
                flushBuffer();
            }
        });
    }

    public void shutdown() {
        if (closed) {
            return;
        }
        closed = true;
        runSync(() -> finish("paused"));
        thread.quitSafely();
    }

    private void startOnThread(Context context, Track.Id id) {
        if (!PreferencesUtils.shouldRecordSensorData()) {
            if (running) {
                finish("paused");
            }
            return;
        }
        if (running && this.trackId != null && this.trackId.id() == id.id()) {
            return;
        }
        if (running) {
            finish("stopped");
        }
        try {
            List<Sensor> sensors = availableSensors(context);
            if (sensors.isEmpty()) {
                Log.i(TAG, "No raw sensors to record");
                return;
            }
            File existing = SensorLogStore.fileFor(context, id);
            if (existing != null && existing.length() == 0) {
                if (!existing.delete()) {
                    Log.w(TAG, "Could not replace an empty sensor file");
                }
                existing = null;
            }
            if (existing == null) {
                createFile(context, id, sensors);
            } else {
                openExisting(existing);
                noteNewSensors(sensors);
                buffer.append(SensorCsv.marker("resumed", System.currentTimeMillis()));
                flushBuffer();
            }
            register(context, sensors);
            this.trackId = id;
            running = true;
            lastFlushElapsed = SystemClock.elapsedRealtime();
            handler.removeCallbacks(periodicFlush);
            handler.postDelayed(periodicFlush, FLUSH_MS);
            Log.i(TAG, "Recording raw sensors for track " + id.id() + " (" + sensors.size() + ")");
        } catch (Exception e) {
            Log.e(TAG, "Could not start raw sensor log", e);
            finish(null);
        }
    }

    private void finish(String markerKind) {
        running = false;
        handler.removeCallbacks(periodicFlush);
        unregister();
        if (file != null && markerKind != null) {
            buffer.append(SensorCsv.marker(markerKind, System.currentTimeMillis()));
        }
        flushBuffer();
        closeFile();
        stats.clear();
        trackId = null;
    }

    private List<Sensor> availableSensors(Context context) {
        SensorManager manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        List<Sensor> found = new ArrayList<>();
        if (manager == null) {
            return found;
        }
        boolean stepsAllowed = canUseStepSensors(context);
        for (int type : TYPES) {
            if (SensorCsv.needsActivityRecognition(type) && !stepsAllowed) {
                if (!loggedStepSkip) {
                    Log.i(TAG, "Step sensors skipped; activity recognition permission is not granted");
                    loggedStepSkip = true;
                }
                continue;
            }
            Sensor sensor = manager.getDefaultSensor(type);
            if (sensor != null) {
                found.add(sensor);
            }
        }
        return found;
    }

    private void createFile(Context context, Track.Id id, List<Sensor> sensors) throws IOException {
        long startedAt = System.currentTimeMillis();
        ZoneOffset offset = ZoneOffset.systemDefault().getRules().getOffset(Instant.ofEpochMilli(startedAt));
        try {
            Track track = new ContentProviderUtils(context).getTrack(id);
            if (track != null) {
                startedAt = track.statistics().startTime().toEpochMilli();
                offset = track.zoneOffset();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Using the current time for the sensor file name", e);
        }
        String filename = SensorCsv.filename(id.id(), startedAt, offset);
        File path = new File(SensorLogStore.directory(context), filename);
        file = new RandomAccessFile(path, "rw");
        file.setLength(0);
        stats.clear();
        List<SensorCsv.SensorDescription> descriptions = new ArrayList<>();
        for (Sensor sensor : sensors) {
            String key = SensorCsv.nameFor(sensor.getType());
            descriptions.add(new SensorCsv.SensorDescription(key, sensor.getType(), sensor.getName(), sensor.getVendor()));
            stats.put(key, new SensorCsv.SensorStats());
        }
        String header = SensorCsv.header(Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, startedAt, descriptions);
        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        file.write(headerBytes);
        rateOffset = SensorCsv.fieldOffset(headerBytes, SensorCsv.RATE_MARKER);
        statsOffset = SensorCsv.fieldOffset(headerBytes, SensorCsv.STATS_MARKER);
        writeMutableFields();
        file.getFD().sync();
        SensorLogStore.upsert(context, id, filename, startedAt);
    }

    private void openExisting(File path) throws IOException {
        file = new RandomAccessFile(path, "rw");
        byte[] prefix = new byte[HEADER_SCAN_BYTES];
        int read = file.read(prefix);
        if (read < 0) {
            read = 0;
        }
        if (read < prefix.length) {
            prefix = Arrays.copyOf(prefix, read);
        }
        rateOffset = SensorCsv.fieldOffset(prefix, SensorCsv.RATE_MARKER);
        statsOffset = SensorCsv.fieldOffset(prefix, SensorCsv.STATS_MARKER);
        stats.clear();
        if (statsOffset >= 0) {
            stats.putAll(SensorCsv.parseStats(SensorCsv.readField(file, statsOffset, SensorCsv.STATS_WIDTH)));
        }
        file.seek(file.length());
    }

    private void noteNewSensors(List<Sensor> sensors) {
        for (Sensor sensor : sensors) {
            String key = SensorCsv.nameFor(sensor.getType());
            if (stats.containsKey(key)) {
                continue;
            }
            stats.put(key, new SensorCsv.SensorStats());
            buffer.append(SensorCsv.sensorLine(new SensorCsv.SensorDescription(
                    key, sensor.getType(), sensor.getName(), sensor.getVendor())));
        }
    }

    private void register(Context context, List<Sensor> sensors) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager == null) {
            return;
        }
        for (Sensor sensor : sensors) {
            boolean ok = sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME, handler);
            if (!ok) {
                Log.w(TAG, "Could not register " + sensor.getName());
            }
        }
    }

    private void unregister() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(listener);
        }
        sensorManager = null;
    }

    private void flushBuffer() {
        if (file == null) {
            buffer.setLength(0);
            return;
        }
        try {
            if (buffer.length() > MAX_BUFFER_CHARS) {
                Log.e(TAG, "Dropping sensor buffer; the file is not keeping up");
                buffer.setLength(0);
            }
            if (buffer.length() > 0) {
                file.seek(file.length());
                file.write(buffer.toString().getBytes(StandardCharsets.UTF_8));
                buffer.setLength(0);
            }
            writeMutableFields();
            file.getFD().sync();
            lastFlushElapsed = SystemClock.elapsedRealtime();
        } catch (IOException e) {
            Log.e(TAG, "Could not flush sensor log", e);
            if (buffer.length() > MAX_BUFFER_CHARS) {
                buffer.setLength(0);
            }
        }
    }

    private void writeMutableFields() throws IOException {
        if (file == null || rateOffset < 0 || statsOffset < 0) {
            return;
        }
        String rates = SensorCsv.formatRates(stats);
        String raw = SensorCsv.formatStats(stats);
        if (!SensorCsv.writeField(file, rateOffset, rates, SensorCsv.RATE_WIDTH)) {
            Log.w(TAG, "Sampling rate metadata does not fit");
        }
        if (!SensorCsv.writeField(file, statsOffset, raw, SensorCsv.STATS_WIDTH)) {
            Log.w(TAG, "Sample stats metadata does not fit");
        }
        file.seek(file.length());
    }

    private void closeFile() {
        if (file != null) {
            try {
                file.close();
            } catch (IOException e) {
                Log.w(TAG, "Could not close sensor log", e);
            }
            file = null;
        }
        rateOffset = -1;
        statsOffset = -1;
        buffer.setLength(0);
    }

    private static boolean canUseStepSensors(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return true;
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
    }

    private void runSync(Runnable action) {
        if (Looper.myLooper() == handler.getLooper()) {
            action.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        boolean posted = handler.post(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        if (!posted) {
            return;
        }
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                Log.w(TAG, "Timed out waiting for the sensor log");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
