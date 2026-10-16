package de.dennisguse.opentracks.intervals;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WhoopHeartRateMonitorTest {

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    private static byte[] bytes(String h) {
        byte[] o = new byte[h.length() / 2];
        for (int i = 0; i < o.length; i++) o[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return o;
    }

    @Test
    public void frameMatchesPublishedBroadcastOn() {
        assertEquals("aa0800a823080e016c935474", hex(WhoopHeartRateMonitor.whoopFrame(bytes("23080e01"))));
        assertEquals("aa0800a823070e00c7e40f08", hex(WhoopHeartRateMonitor.whoopFrame(bytes("23070e00"))));
    }

    @Test
    public void framePadsBatteryQuery() {
        // inner 23 seq 1a -> padded to 4 bytes
        assertEquals("aa0800a823001a001725ee23", hex(WhoopHeartRateMonitor.whoopFrame(bytes("23001a"))));
        assertEquals("aa0800a823010301aed62bce", hex(WhoopHeartRateMonitor.whoopFrame(bytes("23010301"))));
    }

    @Test
    public void parsesRealtimeHr() {
        assertEquals(Integer.valueOf(0x42), WhoopHeartRateMonitor.parseWhoopRealtimeHr(WhoopHeartRateMonitor.whoopFrame(bytes("2802ad896566f065420000000000000000000000"))));
        assertEquals(Integer.valueOf(72), WhoopHeartRateMonitor.parseStandardHr(bytes("0048")));
    }

    @org.junit.Test
    public void backoffAndNames() {
        assertEquals(1000L, WhoopHeartRateMonitor.backoffMs(1));
        assertEquals(2000L, WhoopHeartRateMonitor.backoffMs(2));
        assertEquals(4000L, WhoopHeartRateMonitor.backoffMs(3));
        org.junit.Assert.assertTrue(WhoopHeartRateMonitor.isWhoopName("WHOOP 4A0788542"));
        org.junit.Assert.assertFalse(WhoopHeartRateMonitor.isWhoopName("Polar H10"));
        org.junit.Assert.assertFalse(WhoopHeartRateMonitor.isWhoopName(null));
    }
}
