package de.dennisguse.opentracks.intervals;

import android.content.Context;
import android.os.Handler;

import de.dennisguse.opentracks.data.models.HeartRate;
import de.dennisguse.opentracks.sensors.driver.Driver;
import de.dennisguse.opentracks.sensors.driver.DriverObserver;
import de.dennisguse.opentracks.settings.PreferencesUtils;

/**
 * Recording HR driver for the WHOOP: takes HR from {@link WhoopHeartRateMonitor} (the single GATT
 * client) instead of opening a second GATT connection, which caused status 133.
 */
public class WhoopHeartRateDriver implements Driver, WhoopHeartRateMonitor.HeartRateListener {

    private final DriverObserver<HeartRate> observer;
    private WhoopHeartRateMonitor monitor;
    private Handler handler;
    private String address;
    private volatile boolean connected = false;
    private volatile boolean active = false;

    public WhoopHeartRateDriver(DriverObserver<HeartRate> observer) {
        this.observer = observer;
    }

    @Override
    public void connect(Context context, Handler handler, String address) {
        this.handler = handler;
        this.address = address;
        this.monitor = WhoopHeartRateMonitor.get(context);
        active = true;
        monitor.addListener(this);
        monitor.acquire(this);
    }

    @Override
    public void onHeartRate(int bpm, String deviceName, String addr) {
        if (!active || handler == null || !PreferencesUtils.isHeartRateEnabled()) return;
        handler.post(() -> {
            if (!active) return;
            if (!connected) {
                connected = true;
                observer.onConnected(address, deviceName);
            }
            observer.onDataReceived(HeartRate.of(bpm));
        });
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void disconnect() {
        active = false;
        if (monitor != null) {
            monitor.removeListener(this);
            monitor.release(this);
        }
        connected = false;
        observer.onDisconnected();
    }
}
