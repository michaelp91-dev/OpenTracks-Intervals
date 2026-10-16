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
 * (so recordings pick it up too). Reads standard HR (0x2A37). For a WHOOP 4.0 it also sends the
 * WHOOP "broadcast HR on" command (cmd 0x0e, data 01) on the custom service, which turns on the
 * standard HR service without the WHOOP app/subscription, and parses realtime HR frames from
 * DATA_FROM_STRAP as a fallback.
 */
public class WhoopHeartRateMonitor {

    public enum State {RED, YELLOW, GREEN}

    private static final String TAG = WhoopHeartRateMonitor.class.getSimpleName();
    private static final UUID HR_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    private static final UUID HR_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    // WHOOP 4.0 custom service (see GrokDrop/Projects/whoop-ble-protocol.md)
    private static final UUID WHOOP_SERVICE = UUID.fromString("61080001-8d6d-82b8-614a-1c8cb0f8dcc6");
    private static final UUID WHOOP_CMD_TO_STRAP = UUID.fromString("61080002-8d6d-82b8-614a-1c8cb0f8dcc6");
    private static final UUID WHOOP_CMD_FROM_STRAP = UUID.fromString("61080003-8d6d-82b8-614a-1c8cb0f8dcc6");
    private static final UUID WHOOP_DATA_FROM_STRAP = UUID.fromString("61080005-8d6d-82b8-614a-1c8cb0f8dcc6");
    private static final int WHOOP_PACKET_COMMAND = 0x23;
    private static final int WHOOP_PACKET_REALTIME_DATA = 0x28;
    private static final int WHOOP_CMD_TOGGLE_HR_BROADCAST = 0x0e; // TOGGLE_GENERIC_HR_PROFILE -> standard 0x180D
    private static final int WHOOP_CMD_TOGGLE_REALTIME_HR = 0x03;  // 1 Hz HR in 0x28 frames on DATA_FROM_STRAP
    private static final int WHOOP_CMD_GET_BATTERY = 0x1a;
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
    private final java.util.ArrayDeque<Runnable> gattQueue = new java.util.ArrayDeque<>();
    private boolean gattBusy = false;
    private int whoopSeq = 1;
    private boolean handshakeReconnectDone = false;
    private BluetoothDevice currentDevice;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private String deviceName;

    private final Runnable failTimeout = () -> fail("Timed out connecting");
    private final Runnable dataTimeout = () -> {
        heartRate.postValue(null);
        fail("No heart rate data from strap");
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
        handshakeReconnectDone = false;
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
                boolean whoopService = result.getScanRecord() != null && result.getScanRecord().getServiceUuids() != null
                        && result.getScanRecord().getServiceUuids().contains(new android.os.ParcelUuid(WHOOP_SERVICE));
                if ((name != null && name.toUpperCase(Locale.US).startsWith("WHOOP")) || whoopService) {
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
        currentDevice = device;
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
            BluetoothGattService hrService = g.getService(HR_SERVICE);
            BluetoothGattCharacteristic hr = hrService != null ? hrService.getCharacteristic(HR_MEASUREMENT) : null;
            BluetoothGattService whoop = g.getService(WHOOP_SERVICE);
            if (hr == null && whoop == null) {
                fail("Device has no heart rate or WHOOP service");
                return;
            }
            if (hr != null) enqueueNotify(g, hr);
            if (whoop != null) {
                BluetoothGattCharacteristic cmdIn = whoop.getCharacteristic(WHOOP_CMD_FROM_STRAP);
                BluetoothGattCharacteristic data = whoop.getCharacteristic(WHOOP_DATA_FROM_STRAP);
                BluetoothGattCharacteristic cmdOut = whoop.getCharacteristic(WHOOP_CMD_TO_STRAP);
                if (cmdIn != null) enqueueNotify(g, cmdIn);
                if (data != null) enqueueNotify(g, data);
                if (cmdOut != null && hr == null) {
                    // Handshake (same as Michael's working 10/14 Mac script hr_broadcast_on.py):
                    // battery query, then TOGGLE_GENERIC_HR_PROFILE [01] -> strap exposes standard 0x180D.
                    // Also TOGGLE_REALTIME_HR [01] so HR flows on DATA_FROM_STRAP meanwhile.
                    enqueueWrite(g, cmdOut, whoopCommand(WHOOP_CMD_GET_BATTERY, new byte[0]));
                    enqueueWrite(g, cmdOut, whoopCommand(WHOOP_CMD_TOGGLE_HR_BROADCAST, new byte[]{0x01}));
                    enqueueWrite(g, cmdOut, whoopCommand(WHOOP_CMD_TOGGLE_REALTIME_HR, new byte[]{0x01}));
                    if (!handshakeReconnectDone) {
                        // The 0x180D service only shows up after a reconnect (as in the Mac script).
                        handshakeReconnectDone = true;
                        handler.postDelayed(WhoopHeartRateMonitor.this::reconnectAfterHandshake, 4000);
                    }
                }
            }
            setState(State.YELLOW, "Waiting for heart rate…");
            handler.removeCallbacks(dataTimeout);
            handler.postDelayed(dataTimeout, DATA_TIMEOUT_MS);
            nextOp();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (g != gatt) return;
            opDone();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (g != gatt) return;
            Log.i(TAG, "write " + c.getUuid() + " status " + status);
            opDone();
        }

        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt g, @NonNull BluetoothGattCharacteristic c, @NonNull byte[] value) {
            if (g != gatt) return;
            Integer bpm = null;
            if (HR_MEASUREMENT.equals(c.getUuid())) {
                bpm = parseStandardHr(value);
            } else if (WHOOP_DATA_FROM_STRAP.equals(c.getUuid())) {
                bpm = parseWhoopRealtimeHr(value);
            }
            if (bpm == null || bpm <= 0) return;
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

    @SuppressLint("MissingPermission")
    private void reconnectAfterHandshake() {
        if (gatt == null || currentDevice == null) return;
        Log.i(TAG, "Reconnecting after WHOOP handshake to pick up 0x180D");
        BluetoothDevice device = currentDevice;
        BluetoothGatt old = gatt;
        gatt = null;
        gattQueue.clear();
        gattBusy = false;
        try {
            old.disconnect();
            old.close();
        } catch (Exception e) {
            Log.w(TAG, "close", e);
        }
        handler.postDelayed(() -> connectGatt(device), 1500);
    }

    // --- GATT op queue (Android allows one outstanding op) ---

    private void enqueueNotify(BluetoothGatt g, BluetoothGattCharacteristic c) {
        gattQueue.add(() -> writeCccd(g, c));
    }

    private void enqueueWrite(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
        gattQueue.add(() -> writeCharacteristic(g, c, value));
    }

    private void nextOp() {
        handler.post(() -> {
            if (gattBusy || gatt == null) return;
            Runnable op = gattQueue.poll();
            if (op == null) return;
            gattBusy = true;
            op.run();
        });
    }

    private void opDone() {
        handler.post(() -> {
            gattBusy = false;
            nextOp();
        });
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private void writeCccd(BluetoothGatt g, BluetoothGattCharacteristic c) {
        g.setCharacteristicNotification(c, true);
        BluetoothGattDescriptor d = c.getDescriptor(CCCD);
        if (d == null) {
            opDone();
            return;
        }
        boolean ok;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ok = g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS;
        } else {
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            ok = g.writeDescriptor(d);
        }
        if (!ok) opDone();
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private void writeCharacteristic(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
        boolean ok;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ok = g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS;
        } else {
            c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            c.setValue(value);
            ok = g.writeCharacteristic(c);
        }
        if (!ok) opDone();
    }

    // --- WHOOP framing: AA | len u16 LE | crc8(len) | payload | crc32(payload) LE ; len = payload + 4 ---

    byte[] whoopCommand(int command, byte[] data) {
        byte[] payload = new byte[3 + data.length];
        payload[0] = (byte) WHOOP_PACKET_COMMAND;
        payload[1] = (byte) (whoopSeq++ & 0xFF);
        payload[2] = (byte) command;
        System.arraycopy(data, 0, payload, 3, data.length);
        return whoopFrame(payload);
    }

    static byte[] whoopFrame(byte[] inner) {
        // Inner content is zero-padded to a multiple of 4 bytes (openstrap pad4).
        byte[] payload = java.util.Arrays.copyOf(inner, (inner.length + 3) / 4 * 4);
        int len = payload.length + 4;
        byte[] out = new byte[4 + payload.length + 4];
        out[0] = (byte) 0xAA;
        out[1] = (byte) (len & 0xFF);
        out[2] = (byte) ((len >> 8) & 0xFF);
        out[3] = (byte) crc8(new byte[]{out[1], out[2]});
        System.arraycopy(payload, 0, out, 4, payload.length);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(payload);
        long v = crc.getValue();
        int o = 4 + payload.length;
        for (int k = 0; k < 4; k++) out[o + k] = (byte) ((v >> (8 * k)) & 0xFF);
        return out;
    }

    static int crc8(byte[] data) {
        int c = 0;
        for (byte b : data) {
            c ^= b & 0xFF;
            for (int k = 0; k < 8; k++) {
                c = (c & 0x80) != 0 ? ((c << 1) ^ 0x07) & 0xFF : (c << 1) & 0xFF;
            }
        }
        return c;
    }

    static Integer parseStandardHr(byte[] v) {
        if (v.length < 2) return null;
        if ((v[0] & 0x01) != 0) {
            return v.length >= 3 ? (v[1] & 0xFF) | ((v[2] & 0xFF) << 8) : null;
        }
        return v[1] & 0xFF;
    }

    /** Realtime frame: AA len crc8 | 0x28 seq unix(4) subsec(2) HR ... | crc32. HR at byte 12. */
    static Integer parseWhoopRealtimeHr(byte[] v) {
        if (v.length < 23 || (v[0] & 0xFF) != 0xAA || (v[4] & 0xFF) != WHOOP_PACKET_REALTIME_DATA) return null;
        if (v[22] != 0) return null; // off-wrist flag (inner[18])
        return v[12] & 0xFF;
    }

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
        handler.removeCallbacksAndMessages(null);
        gattQueue.clear();
        gattBusy = false;
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
