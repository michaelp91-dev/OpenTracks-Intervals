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

package de.dennisguse.opentracks.services;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.ServiceCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;

import de.dennisguse.opentracks.R;
import de.dennisguse.opentracks.data.ContentProviderUtils;
import de.dennisguse.opentracks.data.models.ActivityType;
import de.dennisguse.opentracks.data.models.Distance;
import de.dennisguse.opentracks.data.models.Marker;
import de.dennisguse.opentracks.data.models.Track;
import de.dennisguse.opentracks.data.models.TrackBuilder;
import de.dennisguse.opentracks.data.models.TrackPoint;
import de.dennisguse.opentracks.io.file.exporter.ExportUtils;
import de.dennisguse.opentracks.sensors.GpsStatusValue;
import de.dennisguse.opentracks.sensors.raw.RawSensorLogger;
import de.dennisguse.opentracks.services.announcement.VoiceAnnouncementManager;
import de.dennisguse.opentracks.services.handlers.TrackPointCreator;
import de.dennisguse.opentracks.settings.PreferencesUtils;
import de.dennisguse.opentracks.util.SystemUtils;
import de.dennisguse.opentracks.workout.CuePlayer;
import de.dennisguse.opentracks.workout.Workout;
import de.dennisguse.opentracks.workout.WorkoutBanner;
import de.dennisguse.opentracks.workout.WorkoutController;
import de.dennisguse.opentracks.workout.WorkoutRepository;
import de.dennisguse.opentracks.workout.WorkoutSessionStore;
import de.dennisguse.opentracks.workout.WorkoutSplitsExporter;
import de.dennisguse.opentracks.workout.WorkoutState;

public class TrackRecordingService extends Service implements TrackPointCreator.Callback, SharedPreferences.OnSharedPreferenceChangeListener, TrackRecordingManager.IdleObserver {

    private static final String TAG = TrackRecordingService.class.getSimpleName();

    @VisibleForTesting
    public static final Duration RECORDING_DATA_UPDATE_INTERVAL = Duration.ofSeconds(1);

    /** Long enough for "Workout complete" to be spoken before TTS is shut down. */
    private static final long WORKOUT_AUTO_STOP_DELAY_MS = 2500;

    public static final RecordingStatus STATUS_DEFAULT = RecordingStatus.notRecording();
    public static final GpsStatusValue STATUS_GPS_DEFAULT = GpsStatusValue.GPS_NONE;

    public enum StartGate {
        COUNTDOWN_STARTED,
        START_IMMEDIATELY,
        ALREADY_BUSY
    }

    public TrackPoint getLastStoredTrackPointWithLocation() {
        return trackRecordingManager.getLastStoredTrackPointWithLocation();
    }

    public class Binder extends android.os.Binder {

        private Binder() {
            super();
        }

        public TrackRecordingService getService() {
            return TrackRecordingService.this;
        }
    }

    private final Binder binder = new Binder();

    private final Runnable updateRecordingData = new Runnable() {
        @Override
        public void run() {
            updateRecordingDataWhileRecording();

            TrackRecordingService.this.handler.postDelayed(this, RECORDING_DATA_UPDATE_INTERVAL.toMillis());
        }
    };

    private boolean workoutAutoStopScheduled;

    /** Ticks the workout exactly when its timer ends, instead of up to a second later. */
    private final Runnable workoutEndTick = this::updateRecordingDataWhileRecording;

    private final Runnable autoStopFinishedWorkout = () -> {
        if (!isRecording() || !PreferencesUtils.shouldWorkoutAutoStop()) {
            workoutAutoStopScheduled = false;
            return;
        }
        Log.i(TAG, "Auto-stopping recording because the workout finished");
        Track.Id finishedTrackId = TrackRecordingService.this.recordingStatus.trackId();
        // End track, GPS and sensor logs now so every file stops at the timer; keep TTS alive for "Workout complete".
        endCurrentTrack(true);
        // Let the user review (rename) the track first; TrackStoppedActivity exports it afterwards.
        PendingReviewStorage.set(this, finishedTrackId);
        Intent review = new Intent(TrackRecordingService.this, de.dennisguse.opentracks.TrackStoppedActivity.class)
                .putExtra(de.dennisguse.opentracks.TrackStoppedActivity.EXTRA_TRACK_ID, finishedTrackId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        de.dennisguse.opentracks.TrackStoppedActivity.showPendingReviewIfForeground(TrackRecordingService.this, review);
        TrackRecordingService.this.handler.postDelayed(() -> {
            if (!isRecording()) {
                if (TrackRecordingService.this.voiceAnnouncementManager != null) {
                    TrackRecordingService.this.voiceAnnouncementManager.stop();
                }
                stopSelf();
            }
        }, WORKOUT_AUTO_STOP_DELAY_MS);
    };

    // The following variables are set in onCreate:
    private RecordingStatus recordingStatus;
    private MutableLiveData<RecordingStatus> recordingStatusObservable;
    private MutableLiveData<GpsStatusValue> gpsStatusObservable;
    private MutableLiveData<RecordingData> recordingDataObservable;

    private Handler handler;

    private TrackPointCreator trackPointCreator;
    private TrackRecordingManager trackRecordingManager;

    private VoiceAnnouncementManager voiceAnnouncementManager;
    private TrackRecordingServiceNotificationManager notificationManager;
    private WorkoutController workoutController;
    private CuePlayer cuePlayer;
    private RawSensorLogger rawSensorLogger;
    private final PocketTracker pocketTracker = new PocketTracker();

    private final Runnable countdownTick = this::onCountdownTick;
    private MutableLiveData<String> startCountdownObservable;
    private StartCountdown startCountdown;
    private Long pendingWorkoutId;
    private boolean countingDown;
    private boolean countdownInForeground;
    private PowerManager.WakeLock countdownWakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Create");

        handler = new Handler(Looper.getMainLooper());

        recordingStatusObservable = new MutableLiveData<>();
        startCountdownObservable = new MutableLiveData<>();
        updateRecordingStatus(STATUS_DEFAULT);
        gpsStatusObservable = new MutableLiveData<>(STATUS_GPS_DEFAULT);
        recordingDataObservable = new MutableLiveData<>(RecordingData.NOT_RECORDING);

        trackPointCreator = new TrackPointCreator(this);
        trackRecordingManager = new TrackRecordingManager(this, trackPointCreator, this, handler);

        voiceAnnouncementManager = new VoiceAnnouncementManager(this);
        notificationManager = new TrackRecordingServiceNotificationManager(this);
        cuePlayer = new CuePlayer(this, handler, voiceAnnouncementManager);
        workoutController = new WorkoutController(
                cuePlayer,
                WorkoutRepository.get(this),
                trackPointCreator::getNow,
                new WorkoutSessionStore(this),
                voiceAnnouncementManager::isTtsReady);
        rawSensorLogger = new RawSensorLogger();

        PreferencesUtils.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Destroying");
        countingDown = false;
        countdownWakeLock = SystemUtils.releaseWakeLock(countdownWakeLock);
        if (rawSensorLogger != null) {
            rawSensorLogger.shutdown();
            rawSensorLogger = null;
        }
        pocketTracker.stop();
        if (trackPointCreator != null && trackPointCreator.isStarted()) {
            stopSensors();
        }

        PreferencesUtils.unregisterOnSharedPreferenceChangeListener(this);

        if (cuePlayer != null) {
            cuePlayer.release();
            cuePlayer = null;
        }
        workoutController = null;

        trackPointCreator = null;

        handler.removeCallbacksAndMessages(null); //Some tests do not finish the recording completely
        handler = null;

        trackRecordingManager = null;

        // Reverse order from onCreate
        notificationManager = null;

        voiceAnnouncementManager = null;

        recordingStatusObservable = null;
        gpsStatusObservable = null;
        recordingDataObservable = null;

        Log.d(TAG, "Destroyed");
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Log.i(TAG, "Service got killed and is restarting");

            //Best guess: do we need to resume a recording?
            Track.Id trackId = TrackIdStorage.get(this);
            if (trackId != null) {
                Log.i(TAG, "After restart: resuming recording for " + trackId);
                resumeTrack(trackId);
                if (workoutController != null) {
                    workoutController.resumeIfPersisted(trackId);
                }
            }
        }
        return START_STICKY;
    }

    @Override
    public Binder onBind(Intent intent) {
        return binder;
    }

    /**
     * Finalizes a track that was left recording when the app was killed (e.g., force-close),
     * so it is stored and exported like a normally stopped track.
     *
     * @return the recovered track or null.
     */
    public Track.Id recoverUnfinishedTrack() {
        if (isRecording() || isStartCountdownRunning()) {
            return null;
        }
        Track.Id trackId = TrackIdStorage.get(this);
        if (trackId == null) {
            return null;
        }
        Log.i(TAG, "Recovering unfinished track " + trackId);
        TrackIdStorage.unset(this);
        if (!trackRecordingManager.resumeExistingTrack(trackId)) {
            return null;
        }
        trackRecordingManager.endCurrentTrack();
        WorkoutSplitsExporter.export(this, trackId);
        ExportUtils.postTrackExport(this, trackId);
        return trackId;
    }

    public Track.Id startNewTrack() {
        if (isRecording()) {
            Log.w(TAG, "Ignore startNewTrack. Already recording.");
            return null;
        }
        Log.i(TAG, "startNewTrack");

        // Set recording status
        Track.Id trackId = trackRecordingManager.startNewTrack();
        startRecording(trackId);
        return trackId;
    }

    public Track.Id startNewTrackWithWorkout(long workoutId) {
        if (isRecording()) {
            Log.w(TAG, "Ignore startNewTrackWithWorkout. Already recording.");
            return null;
        }
        Workout workout = WorkoutRepository.get(this).getWorkout(workoutId);
        if (workout == null) {
            Log.w(TAG, "Workout not found: " + workoutId);
            return null;
        }
        Track.Id trackId = startNewTrack();
        if (trackId == null) {
            return null;
        }
        workoutController.start(trackId, workout, trackPointCreator.getNow());
        ContentProviderUtils utils = new ContentProviderUtils(this);
        Track track = utils.getTrack(trackId);
        if (track != null) {
            TrackBuilder builder = new TrackBuilder(track);
            builder.setName(workout.name());
            builder.setActivityType(ActivityType.RUNNING);
            builder.setActivityTypeLocalized(getString(ActivityType.RUNNING.getLocalizedStringId()));
            utils.updateTrack(builder.getTrack());
        }
        return trackId;
    }

    /**
     * Starts the pre-recording countdown, or tells the caller to start immediately.
     * Nothing is recorded until the countdown reaches {@link StartCountdown#GO}.
     *
     * @param workoutId workout to start when the countdown ends, or null for a plain recording
     */
    public StartGate requestStart(@Nullable Long workoutId) {
        if (isRecording() || countingDown) {
            return StartGate.ALREADY_BUSY;
        }
        int seconds = PreferencesUtils.getStartCountdownSeconds();
        if (seconds <= 0) {
            return StartGate.START_IMMEDIATELY;
        }
        beginStartCountdown(workoutId, seconds);
        return StartGate.COUNTDOWN_STARTED;
    }

    public boolean isStartCountdownRunning() {
        return countingDown;
    }

    public LiveData<String> getStartCountdownObservable() {
        return startCountdownObservable;
    }

    public void cancelStartCountdown() {
        if (!countingDown) {
            return;
        }
        Log.i(TAG, "Start countdown cancelled");
        countingDown = false;
        pendingWorkoutId = null;
        startCountdown = null;
        countdownInForeground = false;
        if (handler != null) {
            handler.removeCallbacks(countdownTick);
        }
        if (startCountdownObservable != null) {
            startCountdownObservable.setValue(null);
        }
        countdownWakeLock = SystemUtils.releaseWakeLock(countdownWakeLock);
        if (isRecording()) {
            return;
        }
        if (voiceAnnouncementManager != null) {
            voiceAnnouncementManager.stop();
        }
        boolean sensorsStarted = trackPointCreator != null && trackPointCreator.isStarted();
        if (!sensorsStarted) {
            stopForeground(true);
            if (notificationManager != null) {
                notificationManager.stop();
            }
            stopSelf();
        } else if (notificationManager != null) {
            notificationManager.onSensorsStarted(this);
        }
    }

    private void beginStartCountdown(Long workoutId, int seconds) {
        Log.i(TAG, "Start countdown for " + seconds + " seconds");
        pendingWorkoutId = workoutId;
        startCountdown = new StartCountdown(seconds);
        countingDown = true;
        countdownWakeLock = SystemUtils.acquireWakeLock(this, countdownWakeLock);
        if (voiceAnnouncementManager != null) {
            voiceAnnouncementManager.start(null);
        }
        publishStartCue(startCountdown.begin());
        handler.postDelayed(countdownTick, 1000);
    }

    private void onCountdownTick() {
        if (!countingDown || startCountdown == null || handler == null) {
            return;
        }
        String cue = startCountdown.tick();
        if (cue == null) {
            return;
        }
        if (startCountdown.isFinished()) {
            completeStartCountdown(cue);
            return;
        }
        publishStartCue(cue);
        handler.postDelayed(countdownTick, 1000);
    }

    private void completeStartCountdown(String goCue) {
        countingDown = false;
        Long workoutId = pendingWorkoutId;
        pendingWorkoutId = null;
        startCountdown = null;
        if (startCountdownObservable != null) {
            startCountdownObservable.setValue(goCue);
        }
        if (notificationManager != null) {
            notificationManager.showCountdown(this, goCue, countdownInForeground);
        }
        speakStartCue(goCue);
        Track.Id trackId = workoutId == null ? startNewTrack() : startNewTrackWithWorkout(workoutId);
        countdownInForeground = false;
        countdownWakeLock = SystemUtils.releaseWakeLock(countdownWakeLock);
        if (trackId == null && !isRecording()) {
            stopForeground(true);
            if (notificationManager != null) {
                notificationManager.stop();
            }
            stopSelf();
        }
    }

    private void publishStartCue(String cue) {
        if (startCountdownObservable != null) {
            startCountdownObservable.setValue(cue);
        }
        if (notificationManager != null) {
            Notification notification = notificationManager.showCountdown(this, cue, countdownInForeground);
            if (!countdownInForeground) {
                ServiceCompat.startForeground(this, TrackRecordingServiceNotificationManager.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION + ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
                countdownInForeground = true;
            }
        }
        speakStartCue(cue);
    }

    private void speakStartCue(String cue) {
        if (cue == null) {
            return;
        }
        // "Go" is spoken immediately so the workout's first cue cannot cancel it.
        if (StartCountdown.GO.equals(cue)) {
            if (voiceAnnouncementManager != null) {
                voiceAnnouncementManager.announceWorkoutCue(cue);
            }
            return;
        }
        if (cuePlayer != null) {
            cuePlayer.play(cue);
        }
    }

    public LiveData<WorkoutState> getWorkoutStateObservable() {
        if (workoutController == null) {
            return new MutableLiveData<>(null);
        }
        return workoutController.getState();
    }

    public void resumeTrack(Track.Id trackId) {
        if (!trackRecordingManager.resumeExistingTrack(trackId)) {
            Log.w(TAG, "Cannot resume a non-existing track.");
            return;
        }
        Log.i(TAG, "resumeTrack");

        startRecording(trackId);
    }

    private void startRecording(Track.Id trackId) {
        updateRecordingStatus(RecordingStatus.record(trackId));
        TrackIdStorage.set(this, trackId);

        startSensors();
        if (rawSensorLogger != null) {
            rawSensorLogger.start(this, trackId);
        }
        pocketTracker.start(this, trackId);

        voiceAnnouncementManager.start(trackRecordingManager.getTrackStatistics());

        notificationManager.onRecordingStarted(this, recordingStatus.trackId());

        // Update instance variables
        updateRecordingData.run();
        handler.postDelayed(updateRecordingData, RECORDING_DATA_UPDATE_INTERVAL.toMillis());
    }

    public void tryStartSensors() {
        if (trackPointCreator.isStarted()) return;

        Log.i(TAG, "tryStartSensors");

        startSensors();
    }

    private synchronized void startSensors() {
        if (trackPointCreator.isStarted()) {
            Log.i(TAG, "sensors already started; skipping");
            return;
        }
        Log.i(TAG, "startSensors");
        trackPointCreator.start(this, handler);

        ServiceCompat.startForeground(this, TrackRecordingServiceNotificationManager.NOTIFICATION_ID, notificationManager.onSensorsStarted(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION + ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
    }

    public void endCurrentTrack() {
        endCurrentTrack(false);
    }

    private void endCurrentTrack(boolean keepVoiceForWorkoutEnd) {
        if (!isRecording()) {
            Log.w(TAG, "Ignore endCurrentTrack. Not recording.");
            return;
        }

        if (handler != null) {
            handler.removeCallbacks(autoStopFinishedWorkout);
            handler.removeCallbacks(workoutEndTick);
        }
        workoutAutoStopScheduled = false;
        Track.Id finishedTrackId = recordingStatus.trackId();

        // Set recording status
        updateRecordingStatus(STATUS_DEFAULT);

        trackRecordingManager.endCurrentTrack();
        TrackIdStorage.unset(this);

        stopUpdateRecordingData();

        if (workoutController != null && trackPointCreator != null) {
            workoutController.stop(trackPointCreator.getNow());
        }
        if (rawSensorLogger != null) {
            rawSensorLogger.stop();
        }
        pocketTracker.stop();
        // Splits are exported after the track got its final name (TrackStoppedActivity).
        if (notificationManager != null) {
            notificationManager.setWorkoutStatus(null);
        }

        if (!keepVoiceForWorkoutEnd) {
            voiceAnnouncementManager.stop();
        }

        stopSensors();
    }

    void stopSensors() {
        trackPointCreator.stop();
        stopForeground(true);
        notificationManager.stop();
        gpsStatusObservable.postValue(STATUS_GPS_DEFAULT);
    }

    public Marker.Id createMarker() {
        if (!isRecording()) {
            return null;
        }

        //TODO This contains some duplication to TrackRecodingActivity's Marker creation
        TrackPoint trackPoint = trackRecordingManager.getLastStoredTrackPointWithLocation();
        if (trackPoint == null) {
            return null;
        }
        Marker marker = new Marker(
                null,
                recordingStatus.trackId(),
                "",
                "",
                "",
                trackPoint.position(),
                null
        );
        return new ContentProviderUtils(this).insertMarker(marker);
    }

    @Override
    public boolean newTrackPoint(TrackPoint trackPoint, Distance thresholdHorizontalAccuracy) {
        if (!isRecording()) {
            Log.w(TAG, "Ignore newTrackPoint. Not recording.");
            return false;
        }

        boolean stored = trackRecordingManager.onNewTrackPoint(trackPoint);
        //TODO Should be done during updateRecordingDataWhileRecording()
        notificationManager.updateTrackPoint(this, trackRecordingManager.getDataForUI(), thresholdHorizontalAccuracy);
        return stored;
    }

    @Override
    public void newGpsStatus(GpsStatusValue gpsStatusValue) {
        Log.i(TAG, "newGpsStatus: " + gpsStatusValue.message);

        if (notificationManager == null) {

            StringWriter writer = new StringWriter();
            Exception e = new RuntimeException("TrackRecording.newGpsStatus() called after onDestroy(); objectID: " + this + " with thread: " + Thread.currentThread());
            e.printStackTrace(new PrintWriter(writer));

            Log.e(TAG, e.getMessage() + " " + writer);
            return;
        }
        notificationManager.updateGPSStatus(getString(gpsStatusValue.message));
        gpsStatusObservable.postValue(gpsStatusValue);
    }

    @Deprecated
    @VisibleForTesting
    public TrackPointCreator getTrackPointCreator() {
        return trackPointCreator;
    }

    @Deprecated
    @VisibleForTesting
    public TrackRecordingManager getTrackRecordingManager() {
        return trackRecordingManager;
    }

    public LiveData<GpsStatusValue> getGpsStatusObservable() {
        return gpsStatusObservable;
    }

    public LiveData<RecordingData> getRecordingDataObservable() {
        return recordingDataObservable;
    }

    private void updateRecordingDataWhileRecording() {
        if (!isRecording()) {
            Log.w(TAG, "Currently not recording; cannot update data.");
            return;
        }

        // Compute temporary track statistics using sensorData and update time.
        RecordingData data = trackRecordingManager.getDataForUI();

        if (workoutController != null && workoutController.isActive()) {
            Double distanceM = data != null && data.track() != null && data.trackStatistics() != null
                    ? data.trackStatistics().totalDistance().toM() : null;
            workoutController.onTick(distanceM);
            WorkoutState workoutState = workoutController.currentState();
            if (notificationManager != null && workoutState != null) {
                notificationManager.setWorkoutStatus(WorkoutBanner.format(this, workoutState));
            }
            if (workoutState != null && workoutState.finished()) {
                scheduleWorkoutAutoStop();
            } else if (workoutState != null && handler != null) {
                long remainingMs = workoutState.total().minus(workoutState.elapsed()).toMillis();
                if (remainingMs >= 0 && remainingMs < RECORDING_DATA_UPDATE_INTERVAL.toMillis()) {
                    handler.removeCallbacks(workoutEndTick);
                    handler.postDelayed(workoutEndTick, remainingMs);
                }
            }
        } else {
            voiceAnnouncementManager.announceStatisticsIfNeeded(data);
        }

        recordingDataObservable.postValue(data);
    }

    public void onIdle() {
        voiceAnnouncementManager.announceIdle();
    }

    /** Writes any buffered sensor samples. Safe to call when sensor logging is off. */
    public void flushSensorLog() {
        if (rawSensorLogger != null) {
            rawSensorLogger.flush();
        }
    }

    @VisibleForTesting
    public void stopUpdateRecordingData() {
        handler.removeCallbacks(updateRecordingData);
    }

    private void scheduleWorkoutAutoStop() {
        if (workoutAutoStopScheduled || handler == null || !PreferencesUtils.shouldWorkoutAutoStop()) {
            return;
        }
        workoutAutoStopScheduled = true;
        handler.post(autoStopFinishedWorkout);
    }

    public LiveData<RecordingStatus> getRecordingStatusObservable() {
        return recordingStatusObservable;
    }

    private void updateRecordingStatus(RecordingStatus status) {
        Log.i(TAG, "new status " + recordingStatus + " -> " + status);
        recordingStatus = status;
        recordingStatusObservable.postValue(recordingStatus);
    }

    @Deprecated //TODO Should be @VisibleForTesting
    public boolean isRecording() {
        return recordingStatus.isRecording();
    }


    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, @Nullable String key) {
        voiceAnnouncementManager.onSharedPreferenceChanged(sharedPreferences, key);
        trackRecordingManager.onSharedPreferenceChanged(sharedPreferences, key);
        trackPointCreator.onSharedPreferenceChanged(sharedPreferences, key);
        notificationManager.onSharedPreferenceChanged(sharedPreferences, key);
        if (rawSensorLogger != null && isRecording() && PreferencesUtils.isKey(R.string.record_sensor_data_key, key)) {
            if (PreferencesUtils.shouldRecordSensorData()) {
                rawSensorLogger.start(this, recordingStatus.trackId());
            } else {
                rawSensorLogger.pause();
            }
        }
    }
}