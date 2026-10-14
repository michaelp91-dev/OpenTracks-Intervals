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

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

import de.dennisguse.opentracks.data.models.Track;
import de.dennisguse.opentracks.workout.PocketDetector;
import de.dennisguse.opentracks.workout.PocketStore;

/** Counts pulls of the phone out of the pocket during a recording (gravity sensor, ~5 Hz). */
class PocketTracker implements SensorEventListener {

    private Context context;
    private SensorManager sensorManager;
    private Track.Id trackId;
    private PocketDetector detector;
    private List<PocketDetector.Event> previous;
    private int savedCount;

    void start(Context context, Track.Id trackId) {
        stop();
        this.context = context.getApplicationContext();
        this.trackId = trackId;
        this.detector = new PocketDetector();
        List<PocketDetector.Event> stored = PocketStore.load(this.context, trackId);
        this.previous = stored == null ? new ArrayList<>() : stored;
        this.savedCount = 0;
        sensorManager = (SensorManager) this.context.getSystemService(Context.SENSOR_SERVICE);
        Sensor gravity = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (gravity == null) {
            sensorManager = null;
            return;
        }
        PocketStore.save(this.context, trackId, previous);
        sensorManager.registerListener(this, gravity, 200_000);
    }

    void stop() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
            sensorManager = null;
        }
        if (detector != null && trackId != null) {
            detector.finish(System.currentTimeMillis());
            save();
        }
        detector = null;
        trackId = null;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        PocketDetector d = detector;
        if (d == null) {
            return;
        }
        long wallMs = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000L;
        d.onGravity(event.timestamp, wallMs, event.values[0], event.values[1], event.values[2]);
        if (d.events().size() != savedCount) {
            save();
        }
    }

    private void save() {
        List<PocketDetector.Event> events = detector.events();
        savedCount = events.size();
        List<PocketDetector.Event> all = new ArrayList<>(previous);
        all.addAll(events);
        PocketStore.save(context, trackId, all);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
