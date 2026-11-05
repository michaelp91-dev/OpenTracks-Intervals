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
package de.dennisguse.opentracks.workout;

import android.os.SystemClock;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import de.dennisguse.opentracks.data.models.Track;

public final class WorkoutController {

    private static final String TAG = "WorkoutController";
    private static final long FIRST_CUE_HOLD_MS = 5000;

    private final CuePlayer cuePlayer;
    private final WorkoutRepository repository;
    private final Supplier<Instant> nowSupplier;
    private final WorkoutSessionStore sessionStore;
    private final BooleanSupplier ttsReady;
    private final MutableLiveData<WorkoutState> state = new MutableLiveData<>();

    private Workout workout;
    private WorkoutEngine engine;
    private Track.Id trackId;
    private Instant startInstant;
    private long startElapsedRealtime;
    private boolean active;
    private boolean holdingFirstCue;
    private String pendingCue;
    private long holdStartedElapsed;
    /** Set instead of {@link #engine} for a distance workout. */
    private DistancePace.Tracker distance;
    private boolean distanceSkipPending;
    private double lastDistanceM;
    private boolean distanceSegmentWritten;

    public WorkoutController(CuePlayer cuePlayer, WorkoutRepository repository, Supplier<Instant> nowSupplier, WorkoutSessionStore sessionStore, BooleanSupplier ttsReady) {
        this.cuePlayer = cuePlayer;
        this.repository = repository;
        this.nowSupplier = nowSupplier;
        this.sessionStore = sessionStore;
        this.ttsReady = ttsReady;
    }

    public LiveData<WorkoutState> getState() {
        return state;
    }

    public boolean isActive() {
        return active;
    }

    public WorkoutState currentState() {
        return state.getValue();
    }

    public void start(Track.Id trackId, Workout workout, Instant startInstant) {
        this.trackId = trackId;
        this.workout = workout;
        this.startInstant = startInstant;
        this.startElapsedRealtime = SystemClock.elapsedRealtime();
        this.active = true;
        this.holdingFirstCue = false;
        this.pendingCue = null;
        sessionStore.save(trackId.id(), workout.id(), startInstant.toEpochMilli(), startElapsedRealtime);
        if (workout.isDistance()) {
            startDistance(workout, false);
            holdFirstCue(distanceStartCue());
            publish(Duration.ZERO);
            Log.i(TAG, "Started distance workout \"" + workout.name() + "\" " + workout.distanceMeters() + " m on track " + trackId.id());
            return;
        }
        this.distance = null;
        this.engine = new WorkoutEngine(workout, sessionStore.loadRunLastWalk(workout.id()));

        WorkoutEngine.Tick tick = engine.onElapsed(Duration.ZERO);
        writeClosed(tick.closed(), startInstant);
        holdFirstCue(tick.cue());
        publish(Duration.ZERO);
        Log.i(TAG, "Started \"" + workout.name() + "\" on track " + trackId.id());
    }

    public void onTick() {
        onTick(null);
    }

    /** @param distanceM recorded track distance in meters, or null when unknown */
    public void onTick(Double distanceM) {
        if (active && distance != null) {
            onDistanceTick(distanceM);
            return;
        }
        if (!active || engine == null) {
            return;
        }
        Duration elapsed = elapsed();
        if (!engine.isFinished()) {
            WorkoutEngine.Tick tick = engine.onElapsed(elapsed);
            writeClosed(tick.closed(), nowSupplier.get());
            speak(tick.cue());
            if (engine.isFinished()) {
                sessionStore.clear();
            }
        }
        flushFirstCue();
        publish(elapsed);
    }

    public void stop(Instant now) {
        if (active && distance != null) {
            holdingFirstCue = false;
            pendingCue = null;
            writeDistanceSegment(now, false);
            active = false;
            distance = null;
            sessionStore.clear();
            state.setValue(null);
            Log.i(TAG, "Stopped distance workout on track " + (trackId == null ? "?" : trackId.id()));
            return;
        }
        if (!active || engine == null) {
            return;
        }
        holdingFirstCue = false;
        pendingCue = null;
        WorkoutEngine.Tick tick = engine.stop(elapsed());
        writeClosed(tick.closed(), now);
        speak(tick.cue());
        active = false;
        sessionStore.clear();
        state.setValue(null);
        Log.i(TAG, "Stopped workout on track " + (trackId == null ? "?" : trackId.id()));
    }

    /**
     * Rebuilds the timer after the process died. Missed cues are not repeated.
     * {@code startEpochMs} from the old monotonic clock is kept for the log; elapsed time uses
     * {@link SystemClock#elapsedRealtime()}, and new boundary timestamps are rebased onto the new clock.
     */
    public void resumeIfPersisted(Track.Id trackId) {
        if (active) {
            return;
        }
        WorkoutSessionStore.Saved saved = sessionStore.load();
        if (saved == null || saved.trackId() != trackId.id()) {
            return;
        }
        Workout stored = repository.getWorkout(saved.workoutId());
        if (stored != null && stored.isDistance()) {
            long elapsedMs = Math.max(0, SystemClock.elapsedRealtime() - saved.startElapsedMs());
            this.trackId = trackId;
            this.workout = stored;
            this.startInstant = nowSupplier.get().minusMillis(elapsedMs);
            this.startElapsedRealtime = saved.startElapsedMs();
            this.engine = null;
            this.active = true;
            this.holdingFirstCue = false;
            this.pendingCue = null;
            startDistance(stored, true);
            publish(Duration.ofMillis(elapsedMs));
            Log.i(TAG, "Resumed distance workout \"" + stored.name() + "\" at " + elapsedMs + " ms");
            return;
        }
        if (stored == null || stored.segments().isEmpty()) {
            sessionStore.clear();
            return;
        }
        long elapsedMs = SystemClock.elapsedRealtime() - saved.startElapsedMs();
        if (elapsedMs < 0) {
            elapsedMs = 0;
        }
        Instant now = nowSupplier.get();
        this.trackId = trackId;
        this.workout = stored;
        this.startInstant = now.minusMillis(elapsedMs);
        this.startElapsedRealtime = saved.startElapsedMs();
        this.distance = null;
        this.engine = new WorkoutEngine(stored, sessionStore.loadRunLastWalk(stored.id()));
        this.engine.resumeAt(Duration.ofMillis(elapsedMs));
        this.active = true;
        this.holdingFirstCue = false;
        this.pendingCue = null;
        publish(Duration.ofMillis(elapsedMs));
        Log.i(TAG, "Resumed \"" + stored.name() + "\" at " + elapsedMs + " ms (stored start epoch " + saved.startEpochMs() + ")");
    }

    private void startDistance(Workout workout, boolean resumed) {
        long[] pace = sessionStore.loadPace(workout.id());
        Duration goal = pace == null ? null : Duration.ofSeconds(pace[0]);
        Integer every = pace == null || pace[1] <= 0 ? null : (int) pace[1];
        this.engine = null;
        this.distance = new DistancePace.Tracker(workout.distanceMeters(), goal, every);
        this.distanceSkipPending = resumed;
        this.lastDistanceM = 0;
        this.distanceSegmentWritten = false;
        Log.i(TAG, "Distance plan " + workout.distanceMeters() + " m, goal " + goal + ", cues at " + distance.points());
    }

    private String distanceStartCue() {
        String cue = DistancePace.spokenDistance(workout.distanceMeters());
        long[] pace = sessionStore.loadPace(workout.id());
        if (pace != null) {
            cue += ", goal " + DistancePace.spokenClock(Duration.ofSeconds(pace[0]));
        }
        return cue;
    }

    private void onDistanceTick(Double distanceM) {
        Duration elapsed = elapsed();
        if (distanceM != null && !distance.isFinished()) {
            lastDistanceM = Math.max(lastDistanceM, distanceM);
            if (distanceSkipPending) {
                distanceSkipPending = false;
                distance.skipTo(lastDistanceM, elapsed);
            } else {
                speak(distance.onProgress(lastDistanceM, elapsed));
            }
            if (distance.isFinished()) {
                writeDistanceSegment(nowSupplier.get(), true);
                sessionStore.clear();
            }
        }
        flushFirstCue();
        publish(elapsed);
    }

    /** One RUN row so the recorded track shows the workout name and its time in the run summary. */
    private void writeDistanceSegment(Instant now, boolean completed) {
        if (distanceSegmentWritten || trackId == null || workout == null || startInstant == null) {
            return;
        }
        distanceSegmentWritten = true;
        Instant end = completed && distance.finishTime() != null ? startInstant.plus(distance.finishTime()) : now;
        if (end.isBefore(startInstant)) {
            end = startInstant;
        }
        repository.insertTrackSegment(trackId, workout.name(), 0, SegmentType.RUN, startInstant, end, completed);
    }

    private void holdFirstCue(String cue) {
        if (cue == null) {
            return;
        }
        holdingFirstCue = true;
        pendingCue = cue;
        holdStartedElapsed = SystemClock.elapsedRealtime();
        Log.i(TAG, "Holding first cue until TTS is ready: " + cue);
        flushFirstCue();
    }

    private void speak(String cue) {
        if (cue == null) {
            return;
        }
        if (holdingFirstCue) {
            pendingCue = cue;
            Log.i(TAG, "Replacing held cue with " + cue);
            flushFirstCue();
            return;
        }
        Log.i(TAG, "Cue: " + cue);
        cuePlayer.play(cue);
    }

    private void flushFirstCue() {
        if (!holdingFirstCue || pendingCue == null) {
            return;
        }
        long waited = SystemClock.elapsedRealtime() - holdStartedElapsed;
        if (!ttsReady.getAsBoolean() && waited < FIRST_CUE_HOLD_MS) {
            return;
        }
        String cue = pendingCue;
        pendingCue = null;
        holdingFirstCue = false;
        Log.i(TAG, "Cue: " + cue);
        cuePlayer.play(cue);
    }

    private void writeClosed(List<WorkoutEngine.ClosedSegment> closed, Instant partialNow) {
        if (closed == null || closed.isEmpty() || workout == null || trackId == null || startInstant == null) {
            return;
        }
        for (WorkoutEngine.ClosedSegment segment : closed) {
            Instant start = startInstant.plus(segment.startOffset());
            Instant end = segment.completed() ? startInstant.plus(segment.endOffset()) : partialNow;
            if (end.isBefore(start)) {
                end = start;
            }
            repository.insertTrackSegment(trackId, workout.name(), segment.position(), segment.type(), start, end, segment.completed());
            Log.i(TAG, "Stored segment " + segment.position() + " " + segment.type() + " completed=" + segment.completed());
        }
    }

    private void publish(Duration elapsed) {
        if (active && distance != null && workout != null) {
            boolean done = distance.isFinished();
            Duration shown = done && distance.finishTime() != null ? distance.finishTime() : elapsed;
            String status = DistancePace.shortDistance(workout.distanceMeters())
                    + (done ? " done" : " · " + DistancePace.formatKm((int) Math.round(Math.min(lastDistanceM, workout.distanceMeters()))) + " km")
                    + " · " + WorkoutFormat.minutesSeconds(shown);
            state.setValue(new WorkoutState(workout.name(), 0, 0, null, Duration.ZERO, shown, Duration.ZERO,
                    0, 0, 0, 0, 0, 0, done, status));
            return;
        }
        if (!active || engine == null || workout == null) {
            state.setValue(null);
            return;
        }
        WorkoutEngine.Snapshot snapshot = engine.snapshot(elapsed);
        state.setValue(new WorkoutState(
                workout.name(),
                snapshot.segmentIndex(),
                snapshot.segmentCount(),
                snapshot.type(),
                snapshot.remainingInSegment(),
                snapshot.elapsed(),
                snapshot.total(),
                snapshot.runNumber(),
                snapshot.runCount(),
                snapshot.walkNumber(),
                snapshot.walkCount(),
                snapshot.round(),
                snapshot.roundCount(),
                snapshot.finished()
        ));
    }

    private Duration elapsed() {
        long millis = SystemClock.elapsedRealtime() - startElapsedRealtime;
        if (millis < 0) {
            millis = 0;
        }
        return Duration.ofMillis(millis);
    }
}
