/*
 * Copyright 2026 Michael Puckett
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

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;

import de.dennisguse.opentracks.data.models.Track;
import de.dennisguse.opentracks.databinding.StartCountdownActivityBinding;
import de.dennisguse.opentracks.services.RecordingStatus;
import de.dennisguse.opentracks.services.StartCountdown;
import de.dennisguse.opentracks.services.TrackRecordingService;
import de.dennisguse.opentracks.services.TrackRecordingServiceConnection;
import de.dennisguse.opentracks.util.IntentUtils;

/**
 * Shows the start countdown. The timer itself runs in {@link TrackRecordingService}
 * so it keeps going with the screen off.
 */
public class StartCountdownActivity extends AbstractActivity {

    private static final long GO_VISIBLE_MS = 800;

    private StartCountdownActivityBinding viewBinding;
    private TrackRecordingServiceConnection connection;
    private TrackRecordingService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable openRecording = this::openRecording;

    private Track.Id trackId;
    private boolean resumed;
    private boolean openPosted;
    private boolean opened;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showOnLockScreen();
        viewBinding.startCountdownCancel.setOnClickListener(v -> cancelCountdown());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                cancelCountdown();
            }
        });
        connection = new TrackRecordingServiceConnection((connected, unused) -> onServiceConnected(connected));
        connection.bind(this);
    }

    @NonNull
    @Override
    protected View createRootView() {
        viewBinding = StartCountdownActivityBinding.inflate(getLayoutInflater());
        return viewBinding.getRoot();
    }

    @Override
    protected void onStart() {
        super.onStart();
        syncWithService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (syncWithService()) {
            return;
        }
        if (trackId != null) {
            scheduleOpen();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(openRecording);
        if (connection != null) {
            connection.unbind(this);
            connection = null;
        }
        service = null;
        super.onDestroy();
    }

    private void onServiceConnected(TrackRecordingService connected) {
        service = connected;
        connected.getStartCountdownObservable().observe(this, this::onCue);
        connected.getRecordingStatusObservable().observe(this, this::onRecordingStatus);
        syncWithService();
    }

    /**
     * The overlay is only valid while the service counts down or has just started recording.
     * Closes it otherwise, e.g. when the recording already ended while the screen was off.
     *
     * @return true if the overlay was closed.
     */
    private boolean syncWithService() {
        if (service == null || isFinishing()) {
            return isFinishing();
        }
        if (service.isStartCountdownRunning()) {
            return false;
        }
        if (service.isRecording()) {
            if (trackId == null) {
                onRecordingStatus(service.getRecordingStatusObservable().getValue());
            }
            return false;
        }
        handler.removeCallbacks(openRecording);
        finish();
        return true;
    }

    private void onCue(String cue) {
        if (cue == null) {
            if (trackId == null && !isFinishing()) {
                finish();
            }
            return;
        }
        viewBinding.startCountdownValue.setText(cue);
        viewBinding.startCountdownCancel.setVisibility(StartCountdown.GO.equals(cue) ? View.GONE : View.VISIBLE);
    }

    private void onRecordingStatus(RecordingStatus status) {
        if (status == null || !status.isRecording()) {
            syncWithService();
            return;
        }
        trackId = status.trackId();
        viewBinding.startCountdownValue.setText(StartCountdown.GO);
        viewBinding.startCountdownCancel.setVisibility(View.GONE);
        if (resumed) {
            scheduleOpen();
        }
    }

    private void scheduleOpen() {
        if (opened || openPosted) {
            return;
        }
        openPosted = true;
        handler.postDelayed(openRecording, GO_VISIBLE_MS);
    }

    private void openRecording() {
        if (opened || trackId == null || isFinishing()) {
            return;
        }
        if (!resumed) {
            openPosted = false;
            return;
        }
        opened = true;
        handler.removeCallbacks(openRecording);
        startActivity(IntentUtils.newIntent(this, TrackRecordingActivity.class)
                .putExtra(TrackRecordingActivity.EXTRA_TRACK_ID, trackId));
        finish();
    }

    private void cancelCountdown() {
        if (trackId != null) {
            openRecording();
            return;
        }
        if (service == null) {
            return;
        }
        service.cancelStartCountdown();
        finish();
    }

    private void showOnLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        }
    }
}
