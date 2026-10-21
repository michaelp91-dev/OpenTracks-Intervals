/*
 * Copyright 2008 Google Inc.
 * Modified by Michael Puckett, 2026
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

package de.dennisguse.opentracks;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;


import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

import de.dennisguse.opentracks.data.ContentProviderUtils;
import de.dennisguse.opentracks.data.models.Track;
import de.dennisguse.opentracks.databinding.TrackListBinding;
import de.dennisguse.opentracks.publicapi.DataProvider;
import de.dennisguse.opentracks.sensors.GpsStatusValue;
import de.dennisguse.opentracks.services.MissingPermissionException;
import de.dennisguse.opentracks.services.RecordingStatus;
import de.dennisguse.opentracks.services.TrackRecordingService;
import de.dennisguse.opentracks.services.TrackRecordingServiceConnection;
import de.dennisguse.opentracks.intervals.WhoopHeartRateMonitor;
import de.dennisguse.opentracks.settings.PreferencesUtils;
import de.dennisguse.opentracks.settings.SettingsActivity;
import de.dennisguse.opentracks.settings.UnitSystem;
import de.dennisguse.opentracks.share.ShareUtils;
import de.dennisguse.opentracks.ui.TrackListAdapter;
import de.dennisguse.opentracks.ui.aggregatedStatistics.AggregatedStatisticsActivity;
import de.dennisguse.opentracks.ui.aggregatedStatistics.ConfirmDeleteDialogFragment;
import de.dennisguse.opentracks.ui.markers.MarkerListActivity;
import de.dennisguse.opentracks.ui.workout.WorkoutsActivity;
import de.dennisguse.opentracks.ui.util.ActivityUtils;
import de.dennisguse.opentracks.ui.util.RecyclerViewSwipeDeleteCallback;
import de.dennisguse.opentracks.util.IntentUtils;
import de.dennisguse.opentracks.util.PermissionRequester;

/**
 * An activity displaying a list of tracks.
 *
 * @author Leif Hendrik Wilden
 */
public class TrackListActivity extends AbstractTrackDeleteActivity implements ConfirmDeleteDialogFragment.ConfirmDeleteCaller {

    private static final String TAG = TrackListActivity.class.getSimpleName();

    // The following are set in onCreate
    private TrackRecordingServiceConnection recordingStatusConnection;
    private TrackListAdapter adapter;

    private TrackListBinding viewBinding;

    // Preferences
    private UnitSystem unitSystem = UnitSystem.defaultUnitSystem();

    private GpsStatusValue gpsStatusValue = TrackRecordingService.STATUS_GPS_DEFAULT;
    private RecordingStatus recordingStatus = TrackRecordingService.STATUS_DEFAULT;

    // Callback when an item is selected in the contextual action mode
    private final ActivityUtils.ContextualActionModeCallback contextualActionModeCallback = new ActivityUtils.ContextualActionModeCallback() {

        @Override
        public void onPrepare(Menu menu, int[] positions, long[] trackIds, boolean showSelectAll) {
            boolean isSingleSelection = trackIds.length == 1;

            viewBinding.bottomAppBar.performHide(true);
            viewBinding.trackListFabAction.setVisibility(View.INVISIBLE);

            menu.findItem(R.id.list_context_menu_edit).setVisible(isSingleSelection);
            menu.findItem(R.id.list_context_menu_select_all).setVisible(showSelectAll);
        }

        @Override
        public boolean onClick(int itemId, int[] positions, long[] trackIds) {
            return handleContextItem(itemId, trackIds);
        }

        @Override
        public void onDestroy() {
            viewBinding.trackListFabAction.setVisibility(View.VISIBLE);
            viewBinding.bottomAppBar.performShow(true);
        }
    };

    private final OnSharedPreferenceChangeListener sharedPreferenceChangeListener = (sharedPreferences, key) -> {
        if (PreferencesUtils.isKey(R.string.stats_units_key, key)) {
            unitSystem = PreferencesUtils.getUnitSystem();
            if (adapter != null) {
                adapter.updateUnitSystem(unitSystem);
            }
        }
        if (key != null) {
            runOnUiThread(() -> {
                TrackListActivity.this.invalidateOptionsMenu();
                loadData();
            });
        }
    };

    // Menu items

    private String searchQuery;

    private final TrackRecordingServiceConnection.Callback bindChangedCallback = (service, unused) -> {
        service.recoverUnfinishedTrack();

        service.getRecordingStatusObservable()
                .observe(TrackListActivity.this, this::onRecordingStatusChanged);

        service.getGpsStatusObservable()
                .observe(TrackListActivity.this, this::onGpsStatusChanged);

        updateGpsMenuItem(true, recordingStatus.isRecording());
        autoStartGps();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setDefaultKeyMode(DEFAULT_KEYS_SEARCH_LOCAL);

        requestRequiredPermissions();

        recordingStatusConnection = new TrackRecordingServiceConnection(bindChangedCallback);

        viewBinding.aggregatedStatsButton.setOnClickListener((view) -> startActivity(IntentUtils.newIntent(this, AggregatedStatisticsActivity.class)));
        hrMonitor = WhoopHeartRateMonitor.get(this);
        hrMonitor.getState().observe(this, state -> updateHrLight());
        hrMonitor.getHeartRate().observe(this, bpm -> updateHrLight());
        hrMonitor.getBattery().observe(this, pct -> updateHrLight());
        hrMonitor.getBatteryUnavailable().observe(this, missing -> updateHrLight());
        viewBinding.statusLightGps.setOnClickListener(v -> onGpsLightTapped());
        viewBinding.statusLightGps.setOnLongClickListener(v -> {
            if (!recordingStatus.isRecording() && gpsStatusValue.isGpsStarted()) {
                recordingStatusConnection.stopService(this);
                gpsAutoStartAttempted = true;
                Toast.makeText(this, "GPS stopped", Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        });
        viewBinding.statusSwitchHr.setChecked(hrMonitor.isEnabled());
        // Only the switch turns HR on/off; the label next to it is display only.
        viewBinding.statusSwitchHr.setOnCheckedChangeListener((button, checked) -> onHrSwitchChanged(checked));
        updateGpsLight();
        updateHrLight();

        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        adapter = new TrackListAdapter(this, viewBinding.trackList, recordingStatus, unitSystem);
        viewBinding.trackList.setLayoutManager(layoutManager);
        viewBinding.trackList.setAdapter(adapter);

        ItemTouchHelper itemTouchhelper = new ItemTouchHelper(new RecyclerViewSwipeDeleteCallback(this) {
            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                TrackListActivity.this.deleteTracks(new Track.Id(viewHolder.getItemId()));
            }
        });
        itemTouchhelper.attachToRecyclerView(viewBinding.trackList);
        viewBinding.trackListFabAction.setOnClickListener((view) -> {
            if (recordingStatus.isRecording()) {
                Toast.makeText(TrackListActivity.this, getString(R.string.hold_to_stop), Toast.LENGTH_LONG).show();
                return;
            }

            // Not Recording -> Recording
            Log.i(TAG, "Starting recording");
            updateGpsMenuItem(false, true);
            startSensorsOrRecording((service, connection) -> {
                switch (service.requestStart(null)) {
                    case COUNTDOWN_STARTED, ALREADY_BUSY -> {
                        if (service.isStartCountdownRunning()) {
                            startActivity(IntentUtils.newIntent(TrackListActivity.this, StartCountdownActivity.class));
                        } else {
                            Toast.makeText(TrackListActivity.this, getString(R.string.hold_to_stop), Toast.LENGTH_LONG).show();
                        }
                    }
                    case START_IMMEDIATELY -> {
                        Track.Id trackId = service.startNewTrack();
                        Intent newIntent = IntentUtils.newIntent(TrackListActivity.this, TrackRecordingActivity.class);
                        newIntent.putExtra(TrackRecordingActivity.EXTRA_TRACK_ID, trackId);
                        startActivity(newIntent);
                    }
                }
            });
        });
        viewBinding.trackListFabAction.setOnLongClickListener((view) -> {
            if (!recordingStatus.isRecording()) {
                return false;
            }

            // Recording -> Stop
            ActivityUtils.vibrate(this, Duration.ofSeconds(1));
            updateGpsMenuItem(false, false);
            recordingStatusConnection.stopRecording(TrackListActivity.this);
            viewBinding.trackListFabAction.setImageResource(R.drawable.ic_baseline_record_24);
            viewBinding.trackListFabAction.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.red_dark));
            return true;
        });

        setSupportActionBar(viewBinding.trackListToolbar);
        adapter.setActionModeCallback(contextualActionModeCallback);
    }

    private void requestRequiredPermissions() {
        PermissionRequester.ALL.requestPermissionsIfNeeded(this, this, () -> {
            // Permissions just granted: kick off the GPS and WHOOP connections.
            gpsAutoStartAttempted = false;
            if (recordingStatusConnection != null) autoStartGps();
            if (hrMonitor != null && hrMonitor.isEnabled()) hrMonitor.connect();
        }, (requester) -> {
            // Activity recognition is only for the step sensors. Denying it must not block a recording.
            if (!PermissionRequester.RECORDING.hasPermission(this) || !PermissionRequester.NOTIFICATION.hasPermission(this)) {
                Toast.makeText(this, R.string.permission_recording_failed, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();

        PreferencesUtils.registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
        recordingStatusConnection.bind(this);
        hrMonitor.acquire(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        TrackStoppedActivity.setAppForeground(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        TrackStoppedActivity.setAppForeground(true);
        TrackStoppedActivity.showPendingReview(this);

        // Update UI
        this.invalidateOptionsMenu();
        loadData();

        // Float button
        setFloatButton();
    }

    @Override
    protected void onStop() {
        super.onStop();

        PreferencesUtils.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
        recordingStatusConnection.unbind(this);
        hrMonitor.release(this);
    }

    @Override
    protected void onDestroy() {
        if (isFinishing() && !recordingStatus.isRecording() && recordingStatusConnection != null && gpsStatusValue.isGpsStarted()) {
            // Leaving the app without recording: release GPS that was auto-started on open.
            recordingStatusConnection.stopService(this);
        }
        super.onDestroy();
        viewBinding = null;
        recordingStatusConnection = null;
        adapter = null;
    }

    @NonNull
    @Override
    protected View createRootView() {
        viewBinding = TrackListBinding.inflate(getLayoutInflater());

        viewBinding.trackListSearchView.getEditText().setOnEditorActionListener((v, actionId, event) -> {
            searchQuery = viewBinding.trackListSearchView.getEditText().getText().toString();
            viewBinding.trackListSearchView.hide();
            loadData();
            return true;
        });

        return viewBinding.getRoot();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.track_list, menu);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        updateGpsMenuItem(gpsStatusValue.isGpsStarted(), recordingStatus.isRecording());
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.track_list_workouts) {
            startActivity(IntentUtils.newIntent(this, WorkoutsActivity.class));
            return true;
        }

        if (item.getItemId() == R.id.track_list_markers) {
            startActivity(IntentUtils.newIntent(this, MarkerListActivity.class));
            return true;
        }

        if (item.getItemId() == R.id.track_list_settings) {
            startActivity(IntentUtils.newIntent(this, SettingsActivity.class));
            return true;
        }

        if (item.getItemId() == R.id.track_list_help) {
            startActivity(IntentUtils.newIntent(this, HelpActivity.class));
            return true;
        }

        if (item.getItemId() == R.id.track_list_about) {
            startActivity(IntentUtils.newIntent(this, AboutActivity.class));
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void loadData() {
        viewBinding.trackListToolbar.setText(searchQuery);

        viewBinding.trackListToolbar.setTitle(Objects.requireNonNullElseGet(searchQuery, () -> getString(R.string.app_name)));

        adapter.swapData(new ContentProviderUtils(this).searchTracks(searchQuery));
    }

    @Override
    public void onConfirmDeleteAbort(Track.Id... trackIds) {
        // This is not nice, but it will happen rarely (as deletion is not a standard case).
        adapter.notifyItemRangeChanged(0, adapter.getItemCount());
    }

    @Override
    protected void onDeleteConfirmed() {
        // Do nothing
    }

    @Override
    public void onDeleteFinished() {
        // Do nothing
    }

    @Nullable
    @Override
    protected Track.Id getRecordingTrackId() {
        return recordingStatus.trackId();
    }

    /**
     * Updates the menu items with the icon specified.
     *
     * @param isGpsStarted true if gps is started
     * @param isRecording  true if recording
     */
    //TODO Check if if can be avoided to call this outside of onGpsStatusChanged()
    private void updateGpsMenuItem(boolean isGpsStarted, boolean isRecording) {
        updateGpsLight();
    }

    // --- Intervals: status lights (GPS + WHOOP heart rate) ---

    private static final int COLOR_RED = 0xFFE53935;
    private static final int COLOR_YELLOW = 0xFFFDD835;
    private static final int COLOR_GREEN = 0xFF43A047;

    private WhoopHeartRateMonitor hrMonitor;
    private boolean gpsAutoStartAttempted = false;
    private boolean gpsStartRequested = false;

    private boolean isGpsProviderEnabled() {
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        return locationManager == null || locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    private void autoStartGps() {
        if (gpsAutoStartAttempted || recordingStatus.isRecording() || gpsStatusValue.isGpsStarted()) {
            updateGpsLight();
            return;
        }
        gpsAutoStartAttempted = true;
        startGps();
    }

    private void startGps() {
        if (!PermissionRequester.RECORDING.hasPermission(this) || !isGpsProviderEnabled()) {
            gpsStartRequested = false;
            updateGpsLight();
            return;
        }
        gpsStartRequested = true;
        updateGpsLight();
        startSensorsOrRecording((service, connection) -> service.tryStartSensors());
    }

    private void onGpsLightTapped() {
        int color = gpsColor();
        if (color == COLOR_RED) {
            if (!isGpsProviderEnabled()) {
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                return;
            }
            if (!PermissionRequester.RECORDING.hasPermission(this)) {
                openAppPermissionSettings();
                return;
            }
            Toast.makeText(this, "Retrying GPS…", Toast.LENGTH_SHORT).show();
            startGps();
        } else {
            Toast.makeText(this, getString(gpsStatusValue.message), Toast.LENGTH_SHORT).show();
        }
    }

    private int gpsColor() {
        if (!isGpsProviderEnabled() || !PermissionRequester.RECORDING.hasPermission(this)) {
            return COLOR_RED;
        }
        switch (gpsStatusValue) {
            case GPS_SIGNAL_FIX:
                return COLOR_GREEN;
            case GPS_ENABLED:
            case GPS_SIGNAL_BAD:
            case GPS_SIGNAL_LOST:
                return COLOR_YELLOW;
            case GPS_DISABLED:
                return COLOR_RED;
            case GPS_NONE:
            default:
                return gpsStartRequested ? COLOR_YELLOW : COLOR_RED;
        }
    }

    private void tintLight(android.widget.TextView view, int color) {
        android.graphics.drawable.Drawable[] d = view.getCompoundDrawablesRelative();
        android.graphics.drawable.Drawable dot = d[2];
        if (dot != null) {
            dot = dot.mutate();
            dot.setTint(color);
            view.setCompoundDrawablesRelativeWithIntrinsicBounds(d[0], d[1], dot, d[3]);
        }
    }

    private void openAppPermissionSettings() {
        Toast.makeText(this, "Grant Location / Nearby devices permission", Toast.LENGTH_LONG).show();
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", getPackageName(), null)));
    }

    private void updateGpsLight() {
        if (viewBinding == null) return;
        tintLight(viewBinding.statusLightGps, gpsColor());
    }

    private void onHrSwitchChanged(boolean checked) {
        if (checked == hrMonitor.isEnabled()) {
            updateHrLight();
            return;
        }
        if (checked && !PermissionRequester.BLUETOOTH.hasPermission(this)) {
            viewBinding.statusSwitchHr.setChecked(false);
            openAppPermissionSettings();
            return;
        }
        // Persisted; the recording's HR sensor follows it too (SensorManager reconnects/disconnects).
        hrMonitor.setEnabled(checked);
        updateHrLight();
    }

    /** Label left of the switch: "HR off", "HR connecting…", "HR ♥ 72  85%", or "HR not connected". */
    private void updateHrLight() {
        if (viewBinding == null || hrMonitor == null) return;
        boolean enabled = hrMonitor.isEnabled();
        if (viewBinding.statusSwitchHr.isChecked() != enabled) viewBinding.statusSwitchHr.setChecked(enabled);
        WhoopHeartRateMonitor.State state = hrMonitor.getState().getValue();
        Integer battery = hrMonitor.getBattery().getValue();
        Boolean missing = hrMonitor.getBatteryUnavailable().getValue();
        String label = WhoopHeartRateMonitor.hrLabel(enabled, state, hrMonitor.getHeartRate().getValue(), battery, missing != null && missing);
        int color = viewBinding.statusLightGps.getCurrentTextColor();
        if (enabled && state == WhoopHeartRateMonitor.State.GREEN) {
            color = battery != null && battery < 20 ? COLOR_RED : COLOR_GREEN;
        } else if (enabled && state == WhoopHeartRateMonitor.State.RED) {
            color = COLOR_RED;
        }
        viewBinding.statusLabelHr.setText(label);
        viewBinding.statusLabelHr.setTextColor(color);
    }

    /**
     * Handles a context item selection.
     *
     * @param itemId       the menu item id
     * @param longTrackIds the track ids
     * @return true if handled.
     */
    private boolean handleContextItem(int itemId, long... longTrackIds) {
        Track.Id[] trackIds = new Track.Id[longTrackIds.length];
        for (int i = 0; i < longTrackIds.length; i++) {
            trackIds[i] = new Track.Id(longTrackIds[i]);
        }

        if (itemId == R.id.list_context_menu_show_on_map) {
            DataProvider.showTrackOnMap(this, recordingStatus.isRecording(), trackIds);
            return true;
        }

        if (itemId == R.id.list_context_menu_share) {
            Intent intent = ShareUtils.newShareFileIntent(this, trackIds);
            intent = Intent.createChooser(intent, null);
            startActivity(intent);
            return true;
        }

        if (itemId == R.id.list_context_menu_edit) {
            Intent intent = IntentUtils.newIntent(this, TrackEditActivity.class)
                    .putExtra(TrackEditActivity.EXTRA_TRACK_ID, trackIds[0]);
            startActivity(intent);
            return true;
        }

        if (itemId == R.id.list_context_menu_delete) {
            deleteTracks(trackIds);
            return true;
        }

        if (itemId == R.id.list_context_menu_aggregated_stats) {
            Intent intent = IntentUtils.newIntent(this, AggregatedStatisticsActivity.class)
                    .putParcelableArrayListExtra(AggregatedStatisticsActivity.EXTRA_TRACK_IDS, new ArrayList<>(Arrays.asList(trackIds)));
            startActivity(intent);
            return true;
        }

        if (itemId == R.id.list_context_menu_select_all) {
            adapter.setAllSelected(true);
            return false;
        }

        return false;
    }

    public void onGpsStatusChanged(GpsStatusValue newStatus) {
        gpsStatusValue = newStatus;
        if (newStatus.isGpsStarted()) {
            gpsStartRequested = false;
        }
        updateGpsMenuItem(true, recordingStatus.isRecording());
    }

    private void setFloatButton() {
        viewBinding.trackListFabAction.setImageResource(recordingStatus.isRecording() ? R.drawable.ic_baseline_stop_24 : R.drawable.ic_baseline_record_24);
        viewBinding.trackListFabAction.setBackgroundTintList(ContextCompat.getColorStateList(this, recordingStatus.isRecording() ? R.color.opentracks : R.color.red_dark));
    }

    private void onRecordingStatusChanged(RecordingStatus status) {
        recordingStatus = status;
        setFloatButton();
        adapter.updateRecordingStatus(recordingStatus);
    }

    private void startSensorsOrRecording(TrackRecordingServiceConnection.Callback callback) {
        try {
            TrackRecordingServiceConnection.executeForeground(this, callback);
        } catch (MissingPermissionException e) {
            Toast.makeText(this, R.string.permission_recording_failed, Toast.LENGTH_LONG).show();
        }
    }
}
