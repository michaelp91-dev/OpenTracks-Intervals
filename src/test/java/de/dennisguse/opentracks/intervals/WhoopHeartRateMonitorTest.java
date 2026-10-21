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

    @org.junit.Test
    public void doubleTapEventAndAnnouncement() {
        byte[] tap = WhoopHeartRateMonitor.whoopFrame(bytes("30050e0001020304"));
        assertEquals(Integer.valueOf(14), WhoopHeartRateMonitor.parseWhoopEventId(tap));
        assertEquals(Integer.valueOf(9), WhoopHeartRateMonitor.parseWhoopEventId(WhoopHeartRateMonitor.whoopFrame(bytes("30060900"))));
        org.junit.Assert.assertNull(WhoopHeartRateMonitor.parseWhoopEventId(WhoopHeartRateMonitor.whoopFrame(bytes("23010301"))));
        assertEquals("142 beats per minute", WhoopHeartRateMonitor.heartRateAnnouncement(142, 2000));
        assertEquals("heart rate unavailable", WhoopHeartRateMonitor.heartRateAnnouncement(142, 30000));
        assertEquals("heart rate unavailable", WhoopHeartRateMonitor.heartRateAnnouncement(null, 0));
    }

    @org.junit.Test
    public void batteryResponseAndLowWarning() {
        // 0x24 seq=0 cmd=0x1a resp_seq=3 result=1 deci-percent 823 (0x0337) -> 82%
        assertEquals(Integer.valueOf(82), WhoopHeartRateMonitor.parseWhoopBatteryResponse(WhoopHeartRateMonitor.whoopFrame(bytes("24001a03013703"))));
        org.junit.Assert.assertNull(WhoopHeartRateMonitor.parseWhoopBatteryResponse(WhoopHeartRateMonitor.whoopFrame(bytes("24001a03003703"))));
        org.junit.Assert.assertNull(WhoopHeartRateMonitor.parseWhoopBatteryResponse(WhoopHeartRateMonitor.whoopFrame(bytes("24000b03013703"))));
        assertEquals("142 beats per minute. WHOOP battery low, 15 percent", WhoopHeartRateMonitor.heartRateAnnouncement(142, 1000, 15));
        assertEquals("142 beats per minute", WhoopHeartRateMonitor.heartRateAnnouncement(142, 1000, 50));
    }

    @Test
    public void scan_withSavedStrap_ignoresOtherWhoops() {
        String mine = "AA:BB:CC:DD:EE:01";
        // A coworker's strap: WHOOP name and WHOOP service, different address.
        org.junit.Assert.assertFalse(WhoopHeartRateMonitor.acceptsScanResult(mine, "AA:BB:CC:DD:EE:99", "WHOOP 4C0123", true));
        org.junit.Assert.assertFalse(WhoopHeartRateMonitor.acceptsScanResult(mine, "AA:BB:CC:DD:EE:99", null, true));
        org.junit.Assert.assertTrue(WhoopHeartRateMonitor.acceptsScanResult(mine, "aa:bb:cc:dd:ee:01", null, false));
        org.junit.Assert.assertFalse(WhoopHeartRateMonitor.acceptsScanResult(mine, null, "WHOOP", true));
    }

    @Test
    public void scan_withoutSavedStrap_acceptsFirstWhoop() {
        org.junit.Assert.assertTrue(WhoopHeartRateMonitor.acceptsScanResult(null, "AA:BB:CC:DD:EE:01", "WHOOP 4C0123", false));
        org.junit.Assert.assertTrue(WhoopHeartRateMonitor.acceptsScanResult(null, "AA:BB:CC:DD:EE:01", null, true));
        org.junit.Assert.assertFalse(WhoopHeartRateMonitor.acceptsScanResult(null, "AA:BB:CC:DD:EE:01", "Polar H10", false));
    }

    @Test
    public void bonded_withSavedStrap_onlyThatAddress() {
        String mine = "AA:BB:CC:DD:EE:01";
        org.junit.Assert.assertFalse(WhoopHeartRateMonitor.acceptsBonded(mine, "AA:BB:CC:DD:EE:02", "WHOOP old"));
        org.junit.Assert.assertTrue(WhoopHeartRateMonitor.acceptsBonded(mine, mine, "WHOOP"));
        org.junit.Assert.assertTrue(WhoopHeartRateMonitor.acceptsBonded(null, "AA:BB:CC:DD:EE:02", "WHOOP old"));
        org.junit.Assert.assertFalse(WhoopHeartRateMonitor.acceptsBonded(null, "AA:BB:CC:DD:EE:02", "Pixel Buds"));
    }

    @Test
    public void hrLabel_showsHeartRateAndBatteryTogetherOnlyWhenConnected() {
        WhoopHeartRateMonitor.State g = WhoopHeartRateMonitor.State.GREEN;
        assertEquals("HR \u2665 72  85%", WhoopHeartRateMonitor.hrLabel(true, g, 72, 85, false));
        assertEquals("HR \u2665 72  --%", WhoopHeartRateMonitor.hrLabel(true, g, 72, null, false));
        assertEquals("HR \u2665 72  battery n/a", WhoopHeartRateMonitor.hrLabel(true, g, 72, null, true));
        assertEquals("HR off", WhoopHeartRateMonitor.hrLabel(false, g, 72, 85, false));
        assertEquals("HR connecting\u2026", WhoopHeartRateMonitor.hrLabel(true, WhoopHeartRateMonitor.State.YELLOW, 72, 85, false));
        assertEquals("HR not connected", WhoopHeartRateMonitor.hrLabel(true, WhoopHeartRateMonitor.State.RED, 72, 85, false));
    }
}
