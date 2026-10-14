package de.dennisguse.opentracks.workout;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public class PocketDetectorTest {

    private static final long MS = 1_000_000L;

    private static void feed(PocketDetector d, long fromMs, long toMs, float y, float z) {
        for (long t = fromMs; t < toMs; t += 20) {
            d.onGravity(t * MS, 1_000_000L + t, 0f, y, z);
        }
    }

    @Test
    public void startInHand_isIgnored_pullOutCounted() {
        PocketDetector d = new PocketDetector();
        feed(d, 0, 4_000, 0.5f, 9.3f);       // holding the phone at start
        feed(d, 4_000, 60_000, -9.3f, 1f);   // pocket
        feed(d, 60_000, 61_000, -3f, 8f);    // too short: glance
        feed(d, 61_000, 70_000, -9.3f, 1f);
        feed(d, 70_000, 88_000, 0.5f, 9.3f); // out 18 s
        feed(d, 88_000, 120_000, -9.3f, 1f);
        d.finish(1_000_000L + 120_000);
        List<PocketDetector.Event> events = d.events();
        assertEquals(1, events.size());
        assertEquals(1_070_000L, events.get(0).startWallMs());
        assertEquals(1_088_000L, events.get(0).endWallMs());
        assertEquals("Out of pocket: 1 times, 0:18", PocketDetector.summary(events));
        assertEquals(events, PocketDetector.fromCsv(PocketDetector.toCsv(events)));
    }

    @Test
    public void openAtEnd_isClosed() {
        PocketDetector d = new PocketDetector();
        feed(d, 0, 5_000, -9.3f, 1f);
        feed(d, 5_000, 10_000, 0.5f, 9.3f);
        d.finish(1_010_000L);
        assertEquals(1, d.events().size());
        assertEquals(5_000L, d.events().get(0).durationMs());
    }
}
