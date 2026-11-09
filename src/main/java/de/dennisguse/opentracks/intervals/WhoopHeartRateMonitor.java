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
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

import de.dennisguse.opentracks.R;
import de.dennisguse.opentracks.settings.PreferencesUtils;
import de.dennisguse.opentracks.util.PermissionRequester;

/**
 * Single owner of the BLE connection to a WHOOP 4.0 (or any standard HR strap).
 *
 * Status 133 fixes (10/16): this is a process-wide singleton and the ONLY GATT client for the
 * strap; OpenTracks' recording HR sensor reads from it through {@link WhoopHeartRateDriver}
 * instead of opening a second (TRANSPORT_AUTO) GATT connection to the same address.
 * Each attempt gets a fresh BluetoothDevice: a bonded WHOOP from getBondedDevices() is preferred
 * (it carries the right address type), otherwise / alternately a fresh LE scan is used (scan is
 * stopped and we wait before connecting). Always TRANSPORT_LE, autoConnect=false, the old gatt is
 * closed before each retry, retries back off 1/2/4 s, and failures report attempt + step.
 * Bonds are never removed.
 */
public class WhoopHeartRateMonitor {

    public enum State {RED, YELLOW, GREEN}

    public interface HeartRateListener {
        void onHeartRate(int bpm, String deviceName, String address);
    }

    private static final String TAG = WhoopHeartRateMonitor.class.getSimpleName();
    private static final UUID HR_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    private static final UUID HR_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    // WHOOP 4.0 custom BLE service
    private static final UUID WHOOP_SERVICE = UUID.fromString("61080001-8d6d-82b8-614a-1c8cb0f8dcc6");
    private static final UUID WHOOP_CMD_TO_STRAP = UUID.fromString("61080002-8d6d-82b8-614a-1c8cb0f8dcc6");
    private static final UUID WHOOP_CMD_FROM_STRAP = UUID.fromString("61080003-8d6d-82b8-614a-1c8cb0f8dcc6");
    private static final UUID WHOOP_EVENTS_FROM_STRAP = UUID.fromString("61080004-8d6d-82b8-614a-1c8cb0f8dcc6");
    private static final int WHOOP_PACKET_EVENT = 0x30;
    static final int WHOOP_EVENT_DOUBLE_TAP = 14;
    private static final long DOUBLE_TAP_DEBOUNCE_MS = 1000;
    private static final long HR_FRESH_MS = 10_000;
    private static final UUID BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final int WHOOP_PACKET_COMMAND_RESPONSE = 0x24;
    private static final long BATTERY_REFRESH_MS = 5 * 60_000;
    static final int BATTERY_LOW_PERCENT = 20;
    private static final UUID WHOOP_DATA_FROM_STRAP = UUID.fromString("61080005-8d6d-82b8-614a-1c8cb0f8dcc6");
    private static final int WHOOP_PACKET_COMMAND = 0x23;
    private static final int WHOOP_PACKET_REALTIME_DATA = 0x28;
    private static final int WHOOP_CMD_TOGGLE_HR_BROADCAST = 0x0e; // TOGGLE_GENERIC_HR_PROFILE -> standard 0x180D
    private static final int WHOOP_CMD_TOGGLE_REALTIME_HR = 0x03;  // 1 Hz HR in 0x28 frames on DATA_FROM_STRAP
    private static final int WHOOP_CMD_GET_BATTERY = 0x1a;
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final long SCAN_TIMEOUT_MS = 15_000;
    private static final long CONNECT_TIMEOUT_MS = 20_000;
    private static final long DATA_TIMEOUT_MS = 15_000;
    private static final long POST_SCAN_DELAY_MS = 600;
    static final int MAX_ATTEMPTS = 4;
    private static final String PREFS = "whoop_monitor";
    private static final String KEY_ADDRESS = "address";

    private static WhoopHeartRateMonitor instance;

    public static synchronized WhoopHeartRateMonitor get(Context context) {
        if (instance == null) instance = new WhoopHeartRateMonitor(context);
        return instance;
    }

    /** True if this address is the strap this monitor owns (so nobody else may open a GATT to it). */
    public static boolean isManagedAddress(Context context, String address) {
        if (address == null) return false;
        String saved = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ADDRESS, null);
        return address.equalsIgnoreCase(saved);
    }

    /**
     * Whether a scan result may be connected to. Once a strap has been saved, only that strap
     * (same address, or a device bonded to this phone that is that address) is accepted; any other
     * WHOOP-named or WHOOP-service device nearby (a coworker's strap) is ignored. With nothing saved
     * yet, the first WHOOP is accepted so a new strap can be found.
     */
    static boolean acceptsScanResult(@Nullable String savedAddress, @Nullable String address, @Nullable String name, boolean advertisesWhoopService) {
        if (address == null) return false;
        if (savedAddress != null) return savedAddress.equalsIgnoreCase(address);
        return isWhoopName(name) || advertisesWhoopService;
    }

    /** Bonded devices: with a saved strap only that address; otherwise the first bonded WHOOP. */
    static boolean acceptsBonded(@Nullable String savedAddress, @Nullable String address, @Nullable String name) {
        if (address == null) return false;
        if (savedAddress != null) return savedAddress.equalsIgnoreCase(address);
        return isWhoopName(name);
    }

    static boolean isWhoopName(@Nullable String name) {
        return name != null && name.toUpperCase(Locale.US).startsWith("WHOOP");
    }

    /** Backoff before attempt n+1 after attempt n failed: 1 s, 2 s, 4 s ... */
    static long backoffMs(int failedAttempt) {
        return 1000L << Math.max(0, Math.min(failedAttempt - 1, 4));
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MutableLiveData<State> state = new MutableLiveData<>(State.RED);
    private final MutableLiveData<Integer> heartRate = new MutableLiveData<>(null);
    private final MutableLiveData<Integer> battery = new MutableLiveData<>(null);
    private final MutableLiveData<String> detail = new MutableLiveData<>("Not connected");
    /** True once connected for a while without the strap reporting battery (no 0x2A19 and no WHOOP reply). */
    private final MutableLiveData<Boolean> batteryUnavailable = new MutableLiveData<>(false);
    private static final long BATTERY_FIRST_RETRY_MS = 3_000;
    private static final long BATTERY_GIVE_UP_MS = 20_000;
    private final Set<Object> holders = new HashSet<>();
    private final CopyOnWriteArraySet<HeartRateListener> listeners = new CopyOnWriteArraySet<>();

    private BluetoothGatt gatt;
    private final java.util.ArrayDeque<Runnable> gattQueue = new java.util.ArrayDeque<>();
    private boolean gattBusy = false;
    private int whoopSeq = 1;
    private boolean handshakeReconnectDone = false;
    private BluetoothDevice currentDevice;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private String deviceName;

    private boolean inProgress = false;
    private boolean connected = false;
    private int attempt = 0;
    private String step = "idle";
    private Runnable timeout;
    private Runnable pendingRetry;
    private long lastTapMs = 0;
    private long lastHrMs = 0;
    private int lastBpm = 0;
    private Integer lastBattery = null;
    private BluetoothGattCharacteristic batteryChar;
    private BluetoothGattCharacteristic cmdOutChar;
    private final Runnable batteryRefresh = new Runnable() {
        @Override
        public void run() {
            if (gatt != null && connected) requestBattery(gatt);
            handler.postDelayed(this, BATTERY_REFRESH_MS);
        }
    };
    private de.dennisguse.opentracks.services.announcement.TTSManager tts;
    private final Runnable batteryFirstRetry = () -> {
        if (gatt != null && connected && lastBattery == null) requestBattery(gatt);
    };
    private final Runnable batteryGiveUp = () -> {
        if (connected && lastBattery == null) {
            Log.w(TAG, "Strap did not report battery level");
            batteryUnavailable.postValue(true);
        }
    };

    private WhoopHeartRateMonitor(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // Migration: earlier builds saved the WHOOP straight into the OpenTracks HR sensor setting.
        if (prefs.getString(KEY_ADDRESS, null) == null) {
            try {
                String hr = PreferencesUtils.getString(R.string.settings_sensor_bluetooth_heart_rate_key, null);
                if (hr != null && BluetoothAdapter.checkBluetoothAddress(hr)) prefs.edit().putString(KEY_ADDRESS, hr).apply();
            } catch (Exception e) {
                Log.w(TAG, "migrate", e);
            }
        }
    }

    public LiveData<State> getState() { return state; }
    public LiveData<Integer> getHeartRate() { return heartRate; }
    public LiveData<String> getDetail() { return detail; }
    public LiveData<Integer> getBattery() { return battery; }
    public LiveData<Boolean> getBatteryUnavailable() { return batteryUnavailable; }

    @Nullable
    public String getDeviceName() { return deviceName; }

    public boolean isConnected() { return connected; }

    /** Label next to the switch, e.g. "HR ♥ 72  85%". Never shows a value unless connected. */
    public static String hrLabel(boolean enabled, @Nullable State state, @Nullable Integer bpm, @Nullable Integer batteryPct, boolean batteryUnavailable) {
        if (!enabled) return "HR off";
        if (state == State.GREEN) {
            String s = bpm != null ? "HR \u2665 " + bpm : "HR connected";
            if (batteryPct != null) s += "  " + batteryPct + "%";
            else if (batteryUnavailable) s += "  battery n/a";
            else s += "  --%";
            return s;
        }
        if (state == State.YELLOW) return "HR connecting\u2026";
        return "HR not connected";
    }

    public void addListener(HeartRateListener l) { listeners.add(l); }
    public void removeListener(HeartRateListener l) { listeners.remove(l); }

    /** Heart-rate switch on the start screen (persisted, default off). */
    public boolean isEnabled() {
        return PreferencesUtils.isHeartRateEnabled();
    }

    /**
     * Turns HR on or off. Off: stops any scan/connection and clears the value. On: connects if someone
     * holds the monitor. The recording's HR sensor follows the same setting via SensorManager.
     */
    public void setEnabled(boolean enabled) {
        PreferencesUtils.setHeartRateEnabled(enabled);
        handler.post(() -> {
            if (!enabled) {
                stopAll("Heart rate off");
            } else if (!holders.isEmpty() && !connected && !inProgress) {
                start();
            }
        });
    }

    /** Drops the saved strap so the next connection picks up the first WHOOP found. */
    public void forgetStrap() {
        prefs.edit().remove(KEY_ADDRESS).apply();
        if (isEnabled()) connect();
    }

    @Nullable
    public String getSavedAddress() {
        return prefs.getString(KEY_ADDRESS, null);
    }

    /** Someone (start screen, recording) wants HR. Connects if not already connected/connecting. */
    public void acquire(Object holder) {
        handler.post(() -> {
            holders.add(holder);
            if (!connected && !inProgress && isEnabled()) start();
        });
    }

    /** Drops a holder; disconnects when nobody needs HR any more. */
    public void release(Object holder) {
        handler.post(() -> {
            holders.remove(holder);
            if (holders.isEmpty()) disconnect();
        });
    }

    /** Explicit (re)try, e.g. tapping the red light. */
    public void connect() {
        handler.post(this::start);
    }

    public void disconnect() {
        handler.post(() -> stopAll("Not connected"));
    }

    private void stopAll(String why) {
        inProgress = false;
        connected = false;
        closeInternal();
        heartRate.postValue(null);
        battery.postValue(null);
        batteryUnavailable.postValue(false);
        lastBattery = null;
        lastHrMs = 0;
        setState(State.RED, why);
    }

    private void start() {
        if (!isEnabled()) {
            stopAll("Heart rate off");
            return;
        }
        closeInternal();
        connected = false;
        inProgress = true;
        attempt = 0;
        handshakeReconnectDone = false;
        attemptOnce();
    }

    @Nullable
    private BluetoothAdapter adapter() {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return manager != null ? manager.getAdapter() : null;
    }

    /** Bonded WHOOP*, preferring the address we connected to before. */
    @SuppressLint("MissingPermission")
    @Nullable
    private BluetoothDevice bondedWhoop(BluetoothAdapter adapter) {
        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException e) {
            return null;
        }
        if (bonded == null) return null;
        String saved = prefs.getString(KEY_ADDRESS, null);
        for (BluetoothDevice d : bonded) {
            if (acceptsBonded(saved, d.getAddress(), d.getName())) return d;
        }
        return null;
    }

    @SuppressLint("MissingPermission")
    private void attemptOnce() {
        if (!inProgress) return;
        pendingRetry = null;
        attempt++;
        handshakeReconnectDone = false;
        if (!PermissionRequester.BLUETOOTH.hasPermission(context)) {
            failFinal("Bluetooth permission missing");
            return;
        }
        BluetoothAdapter adapter = adapter();
        if (adapter == null || !adapter.isEnabled()) {
            failFinal("Bluetooth is off");
            return;
        }

        // Odd attempts: bonded strap directly (paired in Android settings). Even attempts, or no bond: fresh scan.
        BluetoothDevice bonded = bondedWhoop(adapter);
        if (bonded != null && (attempt % 2 == 1)) {
            Log.i(TAG, "Attempt " + attempt + ": bonded " + bonded.getName() + " " + bonded.getAddress());
            connectGatt(bonded);
            return;
        }

        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            attemptFailed("BLE scanner unavailable");
            return;
        }
        step = "scan";
        setState(State.YELLOW, "Searching for WHOOP" + attemptLabel() + "…");
        armTimeout(SCAN_TIMEOUT_MS, "WHOOP not found (is it charged and on your wrist?)");
        String saved = prefs.getString(KEY_ADDRESS, null);
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                String name = result.getScanRecord() != null ? result.getScanRecord().getDeviceName() : null;
                if (name == null) name = device.getName();
                boolean whoopService = result.getScanRecord() != null && result.getScanRecord().getServiceUuids() != null
                        && result.getScanRecord().getServiceUuids().contains(new ParcelUuid(WHOOP_SERVICE));
                if (acceptsScanResult(saved, device.getAddress(), name, whoopService)) {
                    handler.post(() -> {
                        if (scanCallback != this) return;
                        Log.i(TAG, "Found " + device.getAddress());
                        stopScan();
                        cancelTimeout();
                        step = "wait after scan";
                        handler.postDelayed(() -> {
                            if (inProgress && gatt == null) connectGatt(device);
                        }, POST_SCAN_DELAY_MS);
                    });
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                handler.post(() -> attemptFailed("scan error " + errorCode));
            }
        };
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try {
            scanner.startScan(null, settings, scanCallback);
        } catch (Exception e) {
            attemptFailed("scan error " + e.getMessage());
        }
    }

    private String attemptLabel() {
        return " (attempt " + attempt + "/" + MAX_ATTEMPTS + ")";
    }

    private void armTimeout(long ms, String why) {
        cancelTimeout();
        timeout = () -> attemptFailed(why);
        handler.postDelayed(timeout, ms);
    }

    private void cancelTimeout() {
        if (timeout != null) handler.removeCallbacks(timeout);
        timeout = null;
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
        closeGatt();
        currentDevice = device;
        String n = null;
        try { n = device.getName(); } catch (SecurityException ignored) { }
        deviceName = n != null ? n : device.getAddress();
        step = "connect";
        setState(State.YELLOW, "Connecting to " + deviceName + attemptLabel() + "…");
        armTimeout(CONNECT_TIMEOUT_MS, "connect timed out");
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) attemptFailed("connectGatt returned null");
    }

    private void attemptFailed(String why) {
        if (!inProgress) return;
        String msg = "Attempt " + attempt + "/" + MAX_ATTEMPTS + " failed at " + step + ": " + why;
        Log.w(TAG, msg);
        closeInternal();
        heartRate.postValue(null);
        if (attempt < MAX_ATTEMPTS) {
            long wait = backoffMs(attempt);
            setState(State.YELLOW, msg + " – retrying in " + (wait / 1000) + " s");
            pendingRetry = this::attemptOnce;
            handler.postDelayed(pendingRetry, wait);
        } else {
            failFinal(msg);
        }
    }

    private void failFinal(String msg) {
        Log.w(TAG, msg);
        inProgress = false;
        connected = false;
        closeInternal();
        heartRate.postValue(null);
        setState(State.RED, msg);
    }

    private void onLostAfterConnected(String why) {
        Log.w(TAG, "Lost connection: " + why);
        connected = false;
        closeInternal();
        heartRate.postValue(null);
        lastHrMs = 0;
        if (holders.isEmpty() || !isEnabled()) {
            setState(State.RED, "Disconnected: " + why);
            return;
        }
        // Reconnect with a fresh set of attempts.
        inProgress = true;
        attempt = 0;
        setState(State.YELLOW, "Reconnecting (" + why + ")…");
        pendingRetry = this::attemptOnce;
        handler.postDelayed(pendingRetry, 1000);
    }

    private void onHeartRateValue(int bpm) {
        if (!isEnabled()) {
            stopAll("Heart rate off");
            return;
        }
        cancelTimeout();
        handler.removeCallbacks(dataTimeout);
        handler.postDelayed(dataTimeout, DATA_TIMEOUT_MS);
        if (!connected) {
            connected = true;
            inProgress = false;
            step = "streaming";
            handler.removeCallbacks(batteryRefresh);
            handler.postDelayed(batteryRefresh, BATTERY_REFRESH_MS);
            if (lastBattery == null) {
                handler.removeCallbacks(batteryFirstRetry);
                handler.removeCallbacks(batteryGiveUp);
                handler.postDelayed(batteryFirstRetry, BATTERY_FIRST_RETRY_MS);
                handler.postDelayed(batteryGiveUp, BATTERY_GIVE_UP_MS);
            }
            String saved = prefs.getString(KEY_ADDRESS, null);
            if (currentDevice != null && (saved == null || saved.equalsIgnoreCase(currentDevice.getAddress()))) {
                // Only the first strap is adopted; a different device never replaces the saved one.
                String address = currentDevice.getAddress();
                prefs.edit().putString(KEY_ADDRESS, address).apply();
                // Recording HR sensor = this strap; HeartRateHandler routes it through this monitor.
                PreferencesUtils.setString(R.string.settings_sensor_bluetooth_heart_rate_key, address);
            }
        }
        lastBpm = bpm;
        lastHrMs = android.os.SystemClock.elapsedRealtime();
        if (tts == null) {
            tts = new de.dennisguse.opentracks.services.announcement.TTSManager(context);
            tts.start(); // init early so the first double tap speaks instead of beeping
        }
        heartRate.postValue(bpm);
        setState(State.GREEN, "Connected to " + deviceName);
        String address = currentDevice != null ? currentDevice.getAddress() : null;
        for (HeartRateListener l : listeners) l.onHeartRate(bpm, deviceName, address);
    }

    private final Runnable dataTimeout = () -> {
        if (connected) onLostAfterConnected("no heart rate data for 15 s");
        else attemptFailed("no heart rate data");
    };

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            handler.post(() -> {
                if (g != gatt) return;
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    step = "discover services";
                    setState(State.YELLOW, "Discovering services" + attemptLabel() + "…");
                    // Short pause helps bonded links finish encryption before discovery.
                    handler.postDelayed(() -> {
                        if (g == gatt && !g.discoverServices()) attemptFailed("discoverServices refused");
                    }, 400);
                } else {
                    String why = "disconnected (status " + status + ")";
                    if (connected) onLostAfterConnected(why);
                    else attemptFailed(why);
                }
            });
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            handler.post(() -> {
                if (g != gatt) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    attemptFailed("service discovery status " + status);
                    return;
                }
                BluetoothGattService hrService = g.getService(HR_SERVICE);
                BluetoothGattCharacteristic hr = hrService != null ? hrService.getCharacteristic(HR_MEASUREMENT) : null;
                BluetoothGattService whoop = g.getService(WHOOP_SERVICE);
                if (hr == null && whoop == null) {
                    failFinal("Device has no heart rate or WHOOP service");
                    return;
                }
                step = "subscribe";
                BluetoothGattService batService = g.getService(BATTERY_SERVICE);
                batteryChar = batService != null ? batService.getCharacteristic(BATTERY_LEVEL) : null;
                cmdOutChar = whoop != null ? whoop.getCharacteristic(WHOOP_CMD_TO_STRAP) : null;
                if (hr != null) enqueueNotify(g, hr);
                if (whoop != null) {
                    BluetoothGattCharacteristic cmdIn = whoop.getCharacteristic(WHOOP_CMD_FROM_STRAP);
                    BluetoothGattCharacteristic data = whoop.getCharacteristic(WHOOP_DATA_FROM_STRAP);
                    BluetoothGattCharacteristic cmdOut = whoop.getCharacteristic(WHOOP_CMD_TO_STRAP);
                    if (cmdIn != null) enqueueNotify(g, cmdIn);
                    if (data != null) enqueueNotify(g, data);
                    BluetoothGattCharacteristic events = whoop.getCharacteristic(WHOOP_EVENTS_FROM_STRAP);
                    if (events != null) enqueueNotify(g, events);
                    if (cmdOut != null) {
                        // Handshake (Michael's working 10/14 Mac script hr_broadcast_on.py): battery query,
                        // TOGGLE_GENERIC_HR_PROFILE [01], TOGGLE_REALTIME_HR [01] (HR on DATA_FROM_STRAP meanwhile).
                        step = "handshake";
                        enqueueWrite(g, cmdOut, whoopCommand(WHOOP_CMD_GET_BATTERY, new byte[0]));
                        enqueueWrite(g, cmdOut, whoopCommand(WHOOP_CMD_TOGGLE_HR_BROADCAST, new byte[]{0x01}));
                        enqueueWrite(g, cmdOut, whoopCommand(WHOOP_CMD_TOGGLE_REALTIME_HR, new byte[]{0x01}));
                        if (hr == null && !handshakeReconnectDone) {
                            // 0x180D only shows up after a reconnect (as in the Mac script); realtime HR
                            // keeps flowing meanwhile, so only reconnect if no HR arrives at all.
                            handshakeReconnectDone = true;
                            handler.postDelayed(() -> {
                                if (g == gatt && !connected) reconnectAfterHandshake();
                            }, 6000);
                        }
                    }
                }
                if (batteryChar != null) {
                    enqueueRead(g, batteryChar);
                    if ((batteryChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) enqueueNotify(g, batteryChar);
                }
                Log.i(TAG, "Battery sources: 0x2A19=" + (batteryChar != null) + " whoopCmd=" + (cmdOutChar != null));
                setState(State.YELLOW, "Waiting for heart rate" + attemptLabel() + "…");
                cancelTimeout();
                handler.removeCallbacks(dataTimeout);
                handler.postDelayed(dataTimeout, DATA_TIMEOUT_MS);
                nextOp();
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            handler.post(() -> {
                if (g != gatt) return;
                opDone();
            });
        }

        @Override
        public void onCharacteristicRead(@NonNull BluetoothGatt g, @NonNull BluetoothGattCharacteristic c, @NonNull byte[] value, int status) {
            handler.post(() -> {
                if (g != gatt) return;
                if (status == BluetoothGatt.GATT_SUCCESS && BATTERY_LEVEL.equals(c.getUuid()) && value.length > 0) {
                    int pct = value[0] & 0xFF;
                    if (pct <= 100) onBattery(pct, "0x2A19");
                }
                opDone();
            });
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                byte[] v = c.getValue();
                onCharacteristicRead(g, c, v != null ? v.clone() : new byte[0], status);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            handler.post(() -> {
                if (g != gatt) return;
                Log.i(TAG, "write " + c.getUuid() + " status " + status);
                opDone();
            });
        }

        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt g, @NonNull BluetoothGattCharacteristic c, @NonNull byte[] value) {
            Integer bpm = null;
            if (HR_MEASUREMENT.equals(c.getUuid())) {
                bpm = parseStandardHr(value);
            } else if (WHOOP_DATA_FROM_STRAP.equals(c.getUuid())) {
                bpm = parseWhoopRealtimeHr(value);
            }
            if (BATTERY_LEVEL.equals(c.getUuid())) {
                if (value.length > 0 && (value[0] & 0xFF) <= 100) {
                    int pct = value[0] & 0xFF;
                    handler.post(() -> { if (g == gatt) onBattery(pct, "0x2A19 notify"); });
                }
                return;
            }
            if (WHOOP_CMD_FROM_STRAP.equals(c.getUuid())) {
                Integer pct = parseWhoopBatteryResponse(value);
                if (pct != null) handler.post(() -> { if (g == gatt) onBattery(pct, "cmd 0x1a"); });
                return;
            }
            if (WHOOP_EVENTS_FROM_STRAP.equals(c.getUuid())) {
                Integer eventId = parseWhoopEventId(value);
                Log.i(TAG, "WHOOP event " + eventId);
                if (eventId != null && eventId == WHOOP_EVENT_DOUBLE_TAP) {
                    handler.post(() -> { if (g == gatt) onDoubleTap(); });
                }
                return;
            }
            if (bpm == null || bpm <= 0) return;
            final int b = bpm;
            handler.post(() -> {
                if (g != gatt) return;
                onHeartRateValue(b);
            });
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                byte[] v = c.getValue();
                if (v != null) onCharacteristicChanged(g, c, v.clone());
            }
        }
    };

    private void onDoubleTap() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastTapMs < DOUBLE_TAP_DEBOUNCE_MS) return;
        lastTapMs = now;
        if (tts == null) {
            tts = new de.dennisguse.opentracks.services.announcement.TTSManager(context);
            tts.start();
        }
        tts.speak(heartRateAnnouncement(lastHrMs > 0 ? lastBpm : null, now - lastHrMs, lastBattery));
    }

    static String heartRateAnnouncement(@Nullable Integer bpm, long ageMs) {
        return heartRateAnnouncement(bpm, ageMs, null);
    }

    static String heartRateAnnouncement(@Nullable Integer bpm, long ageMs, @Nullable Integer batteryPct) {
        String s = (bpm == null || bpm <= 0 || ageMs > HR_FRESH_MS) ? "heart rate unavailable" : bpm + " beats per minute";
        if (batteryPct != null && batteryPct < BATTERY_LOW_PERCENT) s += ". WHOOP battery low, " + batteryPct + " percent";
        return s;
    }

    private void onBattery(int pct, String source) {
        Log.i(TAG, "Battery " + pct + "% via " + source);
        lastBattery = pct;
        battery.postValue(pct);
        batteryUnavailable.postValue(false);
        handler.removeCallbacks(batteryGiveUp);
    }

    private void requestBattery(BluetoothGatt g) {
        if (cmdOutChar != null) enqueueWrite(g, cmdOutChar, whoopCommand(WHOOP_CMD_GET_BATTERY, new byte[0]));
        if (batteryChar != null) enqueueRead(g, batteryChar);
        nextOp();
    }

    /**
     * WHOOP 4.0 COMMAND_RESPONSE (type 0x24) to GET_BATTERY_LEVEL (0x1a):
     * AA len crc8 | [4]=0x24 [5]=seq [6]=resp_cmd [7]=resp_seq [8]=result(1=SUCCESS) [9..10]=u16 LE deci-percent | crc32.
     * (noop docs: 4.0 reports deci-percent, /10.) Returns 0..100 or null.
     */
    static Integer parseWhoopBatteryResponse(byte[] v) {
        if (v.length < 11 || (v[0] & 0xFF) != 0xAA || (v[4] & 0xFF) != WHOOP_PACKET_COMMAND_RESPONSE) return null;
        if ((v[6] & 0xFF) != WHOOP_CMD_GET_BATTERY || (v[8] & 0xFF) != 1) return null;
        int raw = (v[9] & 0xFF) | ((v[10] & 0xFF) << 8);
        if (raw > 1000) return null;
        return Math.round(raw / 10f);
    }

    /** EVENT frame: AA len crc8 | 0x30 seq event_id(u16 LE) ... ; returns event id or null. */
    static Integer parseWhoopEventId(byte[] v) {
        if (v.length < 8 || (v[0] & 0xFF) != 0xAA || (v[4] & 0xFF) != WHOOP_PACKET_EVENT) return null;
        return (v[6] & 0xFF) | ((v[7] & 0xFF) << 8);
    }

    @SuppressLint("MissingPermission")
    private void reconnectAfterHandshake() {
        if (gatt == null || currentDevice == null) return;
        Log.i(TAG, "Reconnecting after WHOOP handshake to pick up 0x180D");
        BluetoothDevice device = currentDevice;
        closeGatt();
        step = "reconnect after handshake";
        armTimeout(CONNECT_TIMEOUT_MS, "reconnect timed out");
        handler.postDelayed(() -> {
            if (inProgress && gatt == null) connectGatt(device);
        }, 1500);
    }
    // --- GATT op queue (Android allows one outstanding op) ---

    private void enqueueNotify(BluetoothGatt g, BluetoothGattCharacteristic c) {
        gattQueue.add(() -> writeCccd(g, c));
    }

    @SuppressLint("MissingPermission")
    private void enqueueRead(BluetoothGatt g, BluetoothGattCharacteristic c) {
        gattQueue.add(() -> readCharacteristicOp(g, c));
    }

    private void enqueueWrite(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
        gattQueue.add(() -> writeCharacteristic(g, c, value));
    }

    /** If a GATT callback never arrives, later ops (like the battery read) would wait forever. */
    private static final long GATT_OP_TIMEOUT_MS = 3_000;
    private final Runnable opWatchdog = () -> {
        if (gattBusy) {
            Log.w(TAG, "GATT op timed out; continuing with the next one");
            gattBusy = false;
            nextOp();
        }
    };

    private void nextOp() {
        handler.post(() -> {
            if (gattBusy || gatt == null) return;
            Runnable op = gattQueue.poll();
            if (op == null) return;
            gattBusy = true;
            handler.removeCallbacks(opWatchdog);
            handler.postDelayed(opWatchdog, GATT_OP_TIMEOUT_MS);
            op.run();
        });
    }

    private void opDone() {
        handler.post(() -> {
            handler.removeCallbacks(opWatchdog);
            gattBusy = false;
            nextOp();
        });
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private void readCharacteristicOp(BluetoothGatt g, BluetoothGattCharacteristic c) {
        if (!g.readCharacteristic(c)) opDone();
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

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        gattQueue.clear();
        gattBusy = false;
        handler.removeCallbacks(opWatchdog);
        if (gatt != null) {
            BluetoothGatt old = gatt;
            gatt = null;
            try {
                old.disconnect();
                old.close();
            } catch (Exception e) {
                Log.w(TAG, "close", e);
            }
        }
    }

    private void closeInternal() {
        cancelTimeout();
        handler.removeCallbacks(dataTimeout);
        handler.removeCallbacks(batteryRefresh);
        handler.removeCallbacks(batteryFirstRetry);
        handler.removeCallbacks(batteryGiveUp);
        if (pendingRetry != null) handler.removeCallbacks(pendingRetry);
        pendingRetry = null;
        stopScan();
        closeGatt();
    }
}
