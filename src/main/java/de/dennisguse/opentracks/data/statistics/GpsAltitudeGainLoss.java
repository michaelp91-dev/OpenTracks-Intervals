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
package de.dennisguse.opentracks.data.statistics;

import androidx.annotation.Nullable;

import de.dennisguse.opentracks.data.models.AltitudeGainLoss;

/**
 * Altitude gain/loss from GPS altitude, for devices without a barometer.
 * Raw GPS altitude jitters by several meters, so it is smoothed (exponential moving average)
 * and a change only counts once the smoothed altitude moved {@link #THRESHOLD_M} away from the last counted level.
 */
public class GpsAltitudeGainLoss {

    static final double SMOOTHING_ALPHA = 0.2;
    static final double THRESHOLD_M = 4.0;
    /** The first altitudes only settle the average, so a noisy first fix is not counted as climb or descent. */
    static final int WARM_UP_SAMPLES = 10;

    private Double smoothed_m;
    private double reference_m;
    private int samples;

    public void reset() {
        smoothed_m = null;
    }

    /**
     * @return gain/loss counted since the previous altitude, or null on the first value.
     */
    @Nullable
    public AltitudeGainLoss add(double altitude_m) {
        if (smoothed_m == null) {
            smoothed_m = altitude_m;
            reference_m = altitude_m;
            samples = 1;
            return null;
        }
        smoothed_m = smoothed_m + SMOOTHING_ALPHA * (altitude_m - smoothed_m);
        if (samples < WARM_UP_SAMPLES) {
            samples++;
            reference_m = smoothed_m;
            return new AltitudeGainLoss(0f, 0f);
        }

        double change = smoothed_m - reference_m;
        if (change >= THRESHOLD_M) {
            reference_m = smoothed_m;
            return new AltitudeGainLoss((float) change, 0f);
        }
        if (-change >= THRESHOLD_M) {
            reference_m = smoothed_m;
            return new AltitudeGainLoss(0f, (float) -change);
        }
        return new AltitudeGainLoss(0f, 0f);
    }
}
