package de.dennisguse.opentracks.data.statistics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import de.dennisguse.opentracks.data.models.AltitudeGainLoss;

public class GpsAltitudeGainLossTest {

    private static float[] run(double[] altitudes) {
        GpsAltitudeGainLoss filter = new GpsAltitudeGainLoss();
        float gain = 0, loss = 0;
        for (double a : altitudes) {
            AltitudeGainLoss d = filter.add(a);
            if (d != null) {
                gain += d.gain_m();
                loss += d.loss_m();
            }
        }
        return new float[]{gain, loss};
    }

    @Test
    public void firstValue_noChange() {
        assertNull(new GpsAltitudeGainLoss().add(100));
    }

    @Test
    public void noise_isIgnored() {
        double[] alt = new double[600];
        for (int i = 0; i < alt.length; i++) {
            alt[i] = 200 + (i % 2 == 0 ? 3 : -3) + ((i / 7) % 2 == 0 ? 2 : -2);
        }
        float[] r = run(alt);
        assertEquals(0f, r[0], 0.01f);
        assertEquals(0f, r[1], 0.01f);
    }

    @Test
    public void realClimb_isCounted() {
        double[] alt = new double[300];
        for (int i = 0; i < alt.length; i++) {
            alt[i] = 100 + Math.min(i, 100) * 0.12 + (i % 2 == 0 ? 2 : -2); // 12 m climb with noise
        }
        float[] r = run(alt);
        assertEquals(12f, r[0], 4.5f);
        assertEquals(0f, r[1], 0.01f);
    }
}
