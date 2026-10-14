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

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.google.android.material.tabs.TabLayoutMediator;

import de.dennisguse.opentracks.chart.ChartFragment;
import de.dennisguse.opentracks.chart.TrackDataHubInterface;
import de.dennisguse.opentracks.data.ContentProviderUtils;
import de.dennisguse.opentracks.data.TrackDataHub;
import de.dennisguse.opentracks.data.models.Track;
import de.dennisguse.opentracks.databinding.TrackRecordedBinding;
import de.dennisguse.opentracks.fragments.StatisticsRecordedFragment;
import de.dennisguse.opentracks.publicapi.DataProvider;
import de.dennisguse.opentracks.sensors.raw.SensorLogStore;
import de.dennisguse.opentracks.services.RecordingStatus;
import de.dennisguse.opentracks.services.TrackRecordingService;
import de.dennisguse.opentracks.services.TrackRecordingServiceConnection;
import de.dennisguse.opentracks.settings.PreferencesUtils;
import de.dennisguse.opentracks.settings.SettingsActivity;
import de.dennisguse.opentracks.share.ShareUtils;
import de.dennisguse.opentracks.ui.aggregatedStatistics.ConfirmDeleteDialogFragment;
import de.dennisguse.opentracks.ui.intervals.IntervalsFragment;
import de.dennisguse.opentracks.ui.markers.MarkerListActivity;
import de.dennisguse.opentracks.ui.workout.WorkoutSplitsFragment;
import de.dennisguse.opentracks.util.FileUtils;
import de.dennisguse.opentracks.util.IntentUtils;
import de.dennisguse.opentracks.workout.WorkoutRepository;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * An activity to show the track detail, record a new track or resumes an existing one.
 *
 * @author Leif Hendrik Wilden
 * @author Rodrigo Damazio
 */
//TODO Should not use TrackRecordingServiceConnection; only used to determine if there is NO current recording, to enable resume functionality.
public class TrackRecordedActivity extends AbstractTrackDeleteActivity implements ConfirmDeleteDialogFragment.ConfirmDeleteCaller, TrackDataHubInterface {

    private static final String TAG = TrackRecordedActivity.class.getSimpleName();

    public static final String EXTRA_TRACK_ID = "track_id";

    private static final String CURRENT_TAB_TAG_KEY = "current_tab_tag_key";

    private TrackDataHub trackDataHub;

    private TrackRecordedBinding viewBinding;

    private Track.Id trackId;
    private boolean hasWorkout;
    private RecordingStatus recordingStatus = TrackRecordingService.STATUS_DEFAULT;

    private TrackRecordingServiceConnection trackRecordingServiceConnection;

    private final TrackRecordingServiceConnection.Callback bindCallback = (service, unused) -> service.getRecordingStatusObservable()
            .observe(TrackRecordedActivity.this, this::onRecordingStatusChanged);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        handleIntent(getIntent());
        hasWorkout = trackId != null && WorkoutRepository.get(this).hasWorkout(trackId);

        trackDataHub = new TrackDataHub(this);

        CustomFragmentPagerAdapter pagerAdapter = new CustomFragmentPagerAdapter(this);
        viewBinding.trackDetailActivityViewPager.setAdapter(pagerAdapter);
        new TabLayoutMediator(viewBinding.trackDetailActivityTablayout, viewBinding.trackDetailActivityViewPager,
                (tab, position) -> tab.setText(pagerAdapter.getPageTitle(position))).attach();
        if (savedInstanceState != null) {
            viewBinding.trackDetailActivityViewPager.setCurrentItem(savedInstanceState.getInt(CURRENT_TAB_TAG_KEY));
        }

        trackRecordingServiceConnection = new TrackRecordingServiceConnection(bindCallback);

        viewBinding.bottomAppBarLayout.bottomAppBar.replaceMenu(R.menu.track_detail);
        setSupportActionBar(viewBinding.bottomAppBarLayout.bottomAppBar);
    }

    @Override
    protected void onStart() {
        super.onStart();

        trackDataHub.start();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Update UI
        this.invalidateOptionsMenu();

        if (trackId != null) {
            trackDataHub.loadTrack(trackId);
        }

        trackRecordingServiceConnection.bind(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        trackRecordingServiceConnection.unbind(this);
        trackDataHub.stop();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(CURRENT_TAB_TAG_KEY, viewBinding.trackDetailActivityViewPager.getCurrentItem());
    }

    @NonNull
    @Override
    protected View createRootView() {
        viewBinding = TrackRecordedBinding.inflate(getLayoutInflater());
        return viewBinding.getRoot();
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.track_detail, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.track_detail_markers).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.findItem(R.id.track_detail_resume_track).setVisible(!recordingStatus.isRecording());
        MenuItem sensorItem = menu.findItem(R.id.track_detail_export_sensors);
        if (sensorItem != null) {
            sensorItem.setVisible(trackId != null && SensorLogStore.fileFor(this, trackId) != null);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.track_detail_share) {
            Intent intent = Intent.createChooser(ShareUtils.newShareFileIntent(this, trackId), null);
            startActivity(intent);
            return true;
        }

        if (item.getItemId() == R.id.track_detail_export_sensors) {
            exportSensorData();
            return true;
        }

        if (item.getItemId() == R.id.track_detail_menu_show_on_map) {
            DataProvider.showTrackOnMap(this, false, trackId);
            return true;
        }

        if (item.getItemId() == R.id.track_detail_markers) {
            Intent intent = IntentUtils.newIntent(this, MarkerListActivity.class)
                    .putExtra(MarkerListActivity.EXTRA_TRACK_ID, trackId);
            startActivity(intent);
            return true;
        }

        if (item.getItemId() == R.id.track_detail_edit) {
            Intent intent = IntentUtils.newIntent(this, TrackEditActivity.class)
                    .putExtra(TrackEditActivity.EXTRA_TRACK_ID, trackId);
            startActivity(intent);
            return true;
        }

        if (item.getItemId() == R.id.track_detail_delete) {
            deleteTracks(trackId);
            return true;
        }

        if (item.getItemId() == R.id.track_detail_resume_track) {
            TrackRecordingServiceConnection.executeForeground(this, (service, connection) -> {
                service.resumeTrack(trackId);

                Intent newIntent = IntentUtils.newIntent(TrackRecordedActivity.this, TrackRecordingActivity.class)
                        .putExtra(TrackRecordingActivity.EXTRA_TRACK_ID, trackId);
                startActivity(newIntent);
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);

                finish();
            });
            return true;
        }

        if (item.getItemId() == R.id.track_detail_settings) {
            startActivity(IntentUtils.newIntent(this, SettingsActivity.class));
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Nullable
    @Override
    protected Track.Id getRecordingTrackId() {
        return recordingStatus.trackId();
    }

    @Override
    protected void onDeleteConfirmed() {
        runOnUiThread(this::finish);
    }

    public void onDeleteFinished() {
        runOnUiThread(this::finish);
    }

    /**
     * Gets the {@link TrackDataHub}.
     */
    @Override
    public TrackDataHub getTrackDataHub() {
        return trackDataHub;
    }

    private void exportSensorData() {
        if (trackId == null) {
            return;
        }
        TrackRecordingService service = trackRecordingServiceConnection.getTrackRecordingService();
        if (service != null) {
            service.flushSensorLog();
        }
        File file = SensorLogStore.fileFor(this, trackId);
        Track track = new ContentProviderUtils(this).getTrack(trackId);
        if (file == null || track == null) {
            Toast.makeText(this, R.string.sensor_export_missing, Toast.LENGTH_LONG).show();
            return;
        }
        String exportName = PreferencesUtils.getTrackFileformatGenerator().readingsFilename(track);
        File shared = copySensorExport(file, exportName);
        if (shared == null) {
            Toast.makeText(this, R.string.sensor_export_missing, Toast.LENGTH_LONG).show();
            return;
        }
        Uri uri = FileUtils.getUriForFile(this, shared);
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/csv")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, shared.getName())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        share.setClipData(ClipData.newRawUri(shared.getName(), uri));
        Intent chooser = Intent.createChooser(share, getString(R.string.sensor_export_title));
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(chooser);
    }

    /** Cache copy so the share sheet shows the readings name, not the internal file name. */
    private File copySensorExport(File source, String exportName) {
        File dir = new File(getCacheDir(), "sensors-export");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "Could not create sensor export cache");
            return null;
        }
        File dest = new File(dir, exportName);
        try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return dest;
        } catch (IOException e) {
            Log.w(TAG, "Could not copy sensor export", e);
            return null;
        }
    }

    private void handleIntent(Intent intent) {
        trackId = intent.getParcelableExtra(EXTRA_TRACK_ID);
        if (trackId == null) {
            Log.e(TAG, TrackRecordedActivity.class.getSimpleName() + " needs EXTRA_TRACK_ID.");
            finish();
        }
    }

    private class CustomFragmentPagerAdapter extends FragmentStateAdapter {

        public CustomFragmentPagerAdapter(@NonNull FragmentActivity fa) {
            super(fa);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            if (hasWorkout && position == 1) {
                return WorkoutSplitsFragment.newInstance(trackId);
            }
            int shifted = hasWorkout && position > 1 ? position - 1 : position;
            return switch (shifted) {
                case 0 -> StatisticsRecordedFragment.newInstance(trackId);
                case 1 -> IntervalsFragment.newInstance(trackId, true);
                case 2 -> ChartFragment.newInstance(false);
                case 3 -> ChartFragment.newInstance(true);
                default ->
                        throw new RuntimeException("There isn't Fragment associated with the position: " + position);
            };
        }

        @Override
        public int getItemCount() {
            return hasWorkout ? 5 : 4;
        }

        public CharSequence getPageTitle(int position) {
            if (hasWorkout && position == 1) {
                return getString(R.string.workout_tab);
            }
            int shifted = hasWorkout && position > 1 ? position - 1 : position;
            return switch (shifted) {
                case 0 -> getString(R.string.track_detail_stats_tab);
                case 1 -> getString(R.string.track_detail_intervals_tab);
                case 2 -> getString(R.string.settings_chart_by_time);
                case 3 -> getString(R.string.settings_chart_by_distance);
                default ->
                        throw new RuntimeException("There isn't Fragment associated with the position: " + position);
            };
        }
    }

    private void onRecordingStatusChanged(RecordingStatus status) {
        recordingStatus = status;
    }
}