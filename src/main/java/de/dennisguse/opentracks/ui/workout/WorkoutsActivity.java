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
package de.dennisguse.opentracks.ui.workout;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;

import java.time.Duration;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import de.dennisguse.opentracks.AbstractActivity;
import de.dennisguse.opentracks.R;
import de.dennisguse.opentracks.StartCountdownActivity;
import de.dennisguse.opentracks.TrackRecordingActivity;
import de.dennisguse.opentracks.data.models.Track;
import de.dennisguse.opentracks.databinding.WorkoutsActivityBinding;
import de.dennisguse.opentracks.services.MissingPermissionException;
import de.dennisguse.opentracks.services.TrackRecordingServiceConnection;
import de.dennisguse.opentracks.util.IntentUtils;
import de.dennisguse.opentracks.util.PermissionRequester;
import de.dennisguse.opentracks.workout.DistancePace;
import de.dennisguse.opentracks.workout.Workout;
import de.dennisguse.opentracks.workout.LastWalkMerge;
import de.dennisguse.opentracks.workout.WorkoutSessionStore;
import de.dennisguse.opentracks.workout.WorkoutRepository;

public class WorkoutsActivity extends AbstractActivity implements WorkoutListAdapter.Listener {

    private WorkoutsActivityBinding viewBinding;
    private WorkoutListAdapter adapter;
    private WorkoutRepository repository;
    private ItemTouchHelper touchHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PermissionRequester.ACTIVITY_RECOGNITION.requestPermissionsIfNeeded(this, this, null, null);
        repository = WorkoutRepository.get(this);
        adapter = new WorkoutListAdapter(this);
        viewBinding.workoutList.setLayoutManager(new LinearLayoutManager(this));
        viewBinding.workoutList.setAdapter(adapter);
        touchHelper = new ItemTouchHelper(new SortAndSwipeCallback());
        touchHelper.attachToRecyclerView(viewBinding.workoutList);
        setSupportActionBar(viewBinding.workoutToolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @NonNull
    @Override
    protected View createRootView() {
        viewBinding = WorkoutsActivityBinding.inflate(getLayoutInflater());
        return viewBinding.getRoot();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.workouts, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.workout_add) {
            startActivity(IntentUtils.newIntent(this, WorkoutEditActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onStart(Workout listed) {
        Workout workout = repository.getWorkout(listed.id());
        if (workout == null) {
            Toast.makeText(this, R.string.workout_missing, Toast.LENGTH_LONG).show();
            reload();
            return;
        }
        if (workout.isDistance()) {
            askPace(workout);
            return;
        }
        WorkoutSessionStore store = new WorkoutSessionStore(this);
        if (!LastWalkMerge.applies(workout)) {
            store.saveRunLastWalk(workout.id(), false);
            launch(workout);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.workout_last_walk_question)
                .setMessage(R.string.workout_last_walk_message)
                .setPositiveButton(R.string.workout_last_walk_yes, (d, w) -> {
                    store.saveRunLastWalk(workout.id(), true);
                    launch(workout);
                })
                .setNegativeButton(R.string.workout_last_walk_no, (d, w) -> {
                    store.saveRunLastWalk(workout.id(), false);
                    launch(workout);
                })
                .show();
    }

    /** "Set a pace?" Yes: goal finish time, then update distance. No: plain distance workout. */
    private void askPace(Workout workout) {
        WorkoutSessionStore store = new WorkoutSessionStore(this);
        new AlertDialog.Builder(this)
                .setTitle(R.string.workout_pace_question)
                .setMessage(getString(R.string.workout_pace_question_message, DistancePace.shortDistance(workout.distanceMeters())))
                .setPositiveButton(R.string.workout_pace_yes, (d, w) -> askGoal(workout, store, ""))
                .setNegativeButton(R.string.workout_pace_no, (d, w) -> {
                    store.savePace(workout.id(), 0, 0);
                    launch(workout);
                })
                .show();
    }

    private void askGoal(Workout workout, WorkoutSessionStore store, String prefill) {
        EditText input = dialogInput(getString(R.string.workout_pace_goal_hint), prefill, InputType.TYPE_CLASS_TEXT);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.workout_pace_goal_title)
                .setView(wrap(input))
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Duration goal = DistancePace.parseFinishTime(input.getText().toString());
            if (goal == null) {
                input.setError(getString(R.string.workout_pace_goal_invalid));
                return;
            }
            dialog.dismiss();
            askEvery(workout, store, goal);
        }));
        dialog.show();
    }

    private void askEvery(Workout workout, WorkoutSessionStore store, Duration goal) {
        int total = workout.distanceMeters();
        EditText input = dialogInput(getString(R.string.workout_pace_every_hint, total), Integer.toString(Math.min(1000, total)), InputType.TYPE_CLASS_NUMBER);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.workout_pace_every_title)
                .setView(wrap(input))
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Integer every = DistancePace.parseEvery(input.getText().toString(), total);
            if (every == null) {
                input.setError(getString(R.string.workout_pace_every_invalid, total));
                return;
            }
            dialog.dismiss();
            store.savePace(workout.id(), goal.getSeconds(), every);
            launch(workout);
        }));
        dialog.show();
    }

    private EditText dialogInput(String hint, String text, int type) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setInputType(type);
        input.setSingleLine(true);
        input.setText(text);
        input.setSelectAllOnFocus(true);
        return input;
    }

    private View wrap(EditText input) {
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        frame.setPadding(pad, pad / 2, pad, 0);
        frame.addView(input);
        return frame;
    }

    private void launch(Workout workout) {
        try {
            TrackRecordingServiceConnection.executeForeground(this, (service, connection) -> {
                switch (service.requestStart(workout.id())) {
                    case COUNTDOWN_STARTED, ALREADY_BUSY -> {
                        if (service.isStartCountdownRunning()) {
                            startActivity(IntentUtils.newIntent(this, StartCountdownActivity.class));
                            return;
                        }
                        Toast.makeText(this, R.string.workout_already_recording, Toast.LENGTH_LONG).show();
                    }
                    case START_IMMEDIATELY -> {
                        Track.Id trackId = service.startNewTrackWithWorkout(workout.id());
                        if (trackId == null) {
                            Toast.makeText(this, R.string.workout_already_recording, Toast.LENGTH_LONG).show();
                            return;
                        }
                        Intent intent = IntentUtils.newIntent(this, TrackRecordingActivity.class)
                                .putExtra(TrackRecordingActivity.EXTRA_TRACK_ID, trackId);
                        startActivity(intent);
                    }
                }
            });
        } catch (MissingPermissionException e) {
            Toast.makeText(this, R.string.permission_recording_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onEdit(Workout workout) {
        startActivity(IntentUtils.newIntent(this, WorkoutEditActivity.class)
                .putExtra(WorkoutEditActivity.EXTRA_WORKOUT_ID, workout.id()));
    }

    @Override
    public void onDelete(Workout workout) {
        confirmDelete(workout, null);
    }

    @Override
    public void onStartDrag(RecyclerView.ViewHolder holder) {
        touchHelper.startDrag(holder);
    }

    /** @param onKeep runs when the user cancels, so a swiped row can slide back */
    private void confirmDelete(Workout workout, Runnable onKeep) {
        if (workout.builtin()) {
            Toast.makeText(this, R.string.workout_builtin_kept, Toast.LENGTH_LONG).show();
            if (onKeep != null) {
                onKeep.run();
            }
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.workout_delete_confirm, workout.name()))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (!repository.deleteWorkout(workout.id())) {
                        Toast.makeText(this, R.string.workout_builtin_kept, Toast.LENGTH_LONG).show();
                    }
                    reload();
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    if (onKeep != null) {
                        onKeep.run();
                    }
                })
                .setOnCancelListener(dialog -> {
                    if (onKeep != null) {
                        onKeep.run();
                    }
                })
                .show();
    }

    /** Drag by the handle to reorder (saved when the drag ends); swipe a custom workout sideways to delete it. */
    private final class SortAndSwipeCallback extends ItemTouchHelper.SimpleCallback {
        private boolean moved;

        SortAndSwipeCallback() {
            super(ItemTouchHelper.UP | ItemTouchHelper.DOWN, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT);
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return false; // long-press opens the row menu
        }

        @Override
        public int getSwipeDirs(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            int position = viewHolder.getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION || adapter.workoutAt(position).builtin()) {
                return 0;
            }
            return super.getSwipeDirs(recyclerView, viewHolder);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder from, @NonNull RecyclerView.ViewHolder to) {
            int fromPosition = from.getBindingAdapterPosition();
            int toPosition = to.getBindingAdapterPosition();
            if (fromPosition == RecyclerView.NO_POSITION || toPosition == RecyclerView.NO_POSITION) {
                return false;
            }
            adapter.moveItem(fromPosition, toPosition);
            moved = true;
            return true;
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            if (moved) {
                moved = false;
                repository.saveOrder(adapter.ids());
            }
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            int position = viewHolder.getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION) {
                return;
            }
            confirmDelete(adapter.workoutAt(position), () -> adapter.notifyItemChanged(position));
        }
    }

    @Override
    public void onReset(Workout workout) {
        repository.resetPreset(workout.id());
        reload();
    }

    private void reload() {
        var workouts = repository.listWorkouts();
        adapter.setWorkouts(workouts);
        viewBinding.workoutEmpty.setVisibility(workouts.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
