package de.dennisguse.opentracks.intervals;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.Locale;
import java.util.UUID;

import de.dennisguse.opentracks.R;
import de.dennisguse.opentracks.sensors.driver.SensorType;
import de.dennisguse.opentracks.settings.PreferencesUtils;
import de.dennisguse.opentracks.util.PermissionRequester;

/**
 * Lightweight BLE heart-rate client used by the start screen status light.
 * Connects to the heart-rate sensor configured in the sensor settings; if none is configured,
 * scans for the first device whose name starts with "WHOOP" and saves it as the heart-rate sensor
 * (so recordings pick it up too). Uses the standard Heart Rate Service (0x180D), which WHOOP
 * exposes when "Heart Rate Broadcast" is enabled in the WHOOP app.
 */
public class WhoopHeartRateMonitor {

    public enum State {RED, YELLOW, GREEN}

    private static final String TAG = WhoopHeartRateMonitor.class.getSimpleName();
    private static final UUID HR_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    private static final UUID HR_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final long SCAN_TIMEOUT_MS = 20_000;
    private static final long CONNECT_TIMEOUT_MS = 25_000;
    private static final long DATA_TIMEOUT_MS = 15_000;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MutableLiveData<State> state = new MutableLiveData<>(State.RED);
    private final MutableLiveData<Integer> heartRate = new MutableLiveData<>(null);
    private final MutableLiveData<String> detail = new MutableLiveData<>("Not connected");

    private BluetoothGatt gatt;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private String deviceName;

    private final Runnable failTimeout = () -> fail("Timed out connecting");
    private final Runnable dataTimeout = () -> {
        heartRate.postValue(null);
        fail("No heart rate data (is Heart Rate Broadcast on?)");
    };

    public WhoopHeartRateMonitor(Context context) {
        this.context = context.getApplicationContext();
    }

    public LiveData<State> getState() {
        return state;
    }

    public LiveData<Integer> getHeartRate() {
        return heartRate;
    }

    public LiveData<String> getDetail() {
        return detail;
    }

    @Nullable
    public String getDeviceName() {
        return deviceName;
    }

    @SuppressLint("MissingPermission")
    public void connect() {
        disconnect();
        if (!PermissionRequester.BLUETOOTH.hasPermission(context)) {
            fail("Bluetooth permission missing");
            return;
        }
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            fail("Bluetooth is off");
            return;
        }
        setState(State.YELLOW, "Connecting…");
        handler.postDelayed(failTimeout, CONNECT_TIMEOUT_MS);

        String address = PreferencesUtils.getString(R.string.settings_sensor_bluetooth_heart_rate_key, SensorType.NONE.getPreferenceValue());
        if (address != null && BluetoothAdapter.checkBluetoothAddress(address)) {
            connectGatt(adapter.getRemoteDevice(address));
            return;
        }

        // No sensor configured: scan for the first WHOOP.
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            fail("BLE scanner unavailable");
            return;
        }
        setState(State.YELLOW, "Searching for WHOOP…");
        handler.removeCallbacks(failTimeout);
        handler.postDelayed(failTimeout, SCAN_TIMEOUT_MS);
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                String name = result.getScanRecord() != null ? result.getScanRecord().getDeviceName() : null;
                if (name == null) name = device.getName();
                if (name != null && name.toUpperCase(Locale.US).startsWith("WHOOP")) {
                    Log.i(TAG, "Found " + name + " " + device.getAddress());
                    stopScan();
                    PreferencesUtils.setString(R.string.settings_sensor_bluetooth_heart_rate_key, device.getAddress());
                    handler.removeCallbacks(failTimeout);
                    handler.postDelayed(failTimeout, CONNECT_TIMEOUT_MS);
                    handler.post(() -> connectGatt(device));
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                fail("Scan failed (" + errorCode + ")");
            }
        };
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanner.startScan(null, settings, scanCallback);
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (scanner != null && scanCallback != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception e) {
                Log.w(TAG, "stopScan", e);
            }
        }
        scanCallback = null;
        scanner = null;
    }

    @SuppressLint("MissingPermission")
    private void connectGatt(BluetoothDevice device) {
        deviceName = device.getName() != null ? device.getName() : device.getAddress();
        setState(State.YELLOW, "Connecting to " + deviceName + "…");
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (g != gatt) return;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setState(State.YELLOW, "Discovering services…");
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                heartRate.postValue(null);
                fail("Disconnected (status " + status + ")");
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (g != gatt) return;
            BluetoothGattService service = g.getService(HR_SERVICE);
            BluetoothGattCharacteristic c = service != null ? service.getCharacteristic(HR_MEASUREMENT) : null;
            if (c == null) {
                fail("No heart rate service: turn on Heart Rate Broadcast in the WHOOP app");
                return;
            }
            g.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor d = c.getDescriptor(CCCD);
            if (d != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(d);
                }
            }
            setState(State.YELLOW, "Waiting for heart rate…");
            handler.removeCallbacks(dataTimeout);
            handler.postDelayed(dataTimeout, DATA_TIMEOUT_MS);
        }

        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt g, @NonNull BluetoothGattCharacteristic c, @NonNull byte[] value) {
            if (g != gatt || !HR_MEASUREMENT.equals(c.getUuid()) || value.length < 2) return;
            int bpm = (value[0] & 0x01) != 0 && value.length >= 3
                    ? (value[1] & 0xFF) | ((value[2] & 0xFF) << 8)
                    : value[1] & 0xFF;
            handler.removeCallbacks(failTimeout);
            handler.removeCallbacks(dataTimeout);
            handler.postDelayed(dataTimeout, DATA_TIMEOUT_MS);
            heartRate.postValue(bpm);
            setState(State.GREEN, "Connected to " + deviceName);
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                byte[] v = c.getValue();
                if (v != null) onCharacteristicChanged(g, c, v);
            }
        }
    };

    private void setState(State s, String msg) {
        state.postValue(s);
        detail.postValue(msg);
    }

    private void fail(String msg) {
        Log.w(TAG, msg);
        handler.post(this::closeInternal);
        setState(State.RED, msg);
    }

    @SuppressLint("MissingPermission")
    private void closeInternal() {
        handler.removeCallbacks(failTimeout);
        handler.removeCallbacks(dataTimeout);
        stopScan();
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception e) {
                Log.w(TAG, "close", e);
            }
            gatt = null;
        }
    }

    public void disconnect() {
        closeInternal();
        heartRate.postValue(null);
    }
}
