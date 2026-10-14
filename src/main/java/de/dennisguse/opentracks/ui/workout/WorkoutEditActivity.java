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

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.dennisguse.opentracks.AbstractActivity;
import de.dennisguse.opentracks.R;
import de.dennisguse.opentracks.databinding.WorkoutAddRoundsBinding;
import de.dennisguse.opentracks.databinding.WorkoutEditActivityBinding;
import de.dennisguse.opentracks.workout.SegmentType;
import de.dennisguse.opentracks.workout.Workout;
import de.dennisguse.opentracks.workout.WorkoutFormat;
import de.dennisguse.opentracks.workout.WorkoutRepository;
import de.dennisguse.opentracks.workout.WorkoutSegment;

public class WorkoutEditActivity extends AbstractActivity implements WorkoutSegmentEditAdapter.Listener {

    public static final String EXTRA_WORKOUT_ID = "workout_id";

    private static final String STATE_ID = "workout_id";
    private static final String STATE_BUILTIN = "workout_builtin";
    private static final String STATE_NAME = "workout_name";
    private static final String STATE_TYPES = "workout_types";
    private static final String STATE_DURATIONS = "workout_durations";

    private WorkoutEditActivityBinding viewBinding;
    private WorkoutSegmentEditAdapter adapter;
    private WorkoutRepository repository;
    private long workoutId;
    private boolean builtin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repository = WorkoutRepository.get(this);
        adapter = new WorkoutSegmentEditAdapter(this, this);
        viewBinding.workoutSegments.setLayoutManager(new LinearLayoutManager(this));
        viewBinding.workoutSegments.setAdapter(adapter);
        setSupportActionBar(viewBinding.workoutEditToolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        if (savedInstanceState != null) {
            workoutId = savedInstanceState.getLong(STATE_ID);
            builtin = savedInstanceState.getBoolean(STATE_BUILTIN);
            viewBinding.workoutName.setText(savedInstanceState.getString(STATE_NAME, ""));
            String[] types = savedInstanceState.getStringArray(STATE_TYPES);
            String[] durations = savedInstanceState.getStringArray(STATE_DURATIONS);
            List<WorkoutSegmentEditAdapter.Draft> drafts = new ArrayList<>();
            if (types != null && durations != null) {
                for (int i = 0; i < types.length && i < durations.length; i++) {
                    drafts.add(new WorkoutSegmentEditAdapter.Draft(SegmentType.fromDatabase(types[i]), durations[i]));
                }
            }
            adapter.replace(drafts);
        } else if (getIntent().hasExtra(EXTRA_WORKOUT_ID)) {
            workoutId = getIntent().getLongExtra(EXTRA_WORKOUT_ID, 0);
            Workout workout = repository.getWorkout(workoutId);
            if (workout == null) {
                Toast.makeText(this, R.string.workout_missing, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            builtin = workout.builtin();
            viewBinding.workoutName.setText(workout.name());
            List<WorkoutSegmentEditAdapter.Draft> drafts = new ArrayList<>();
            for (WorkoutSegment segment : workout.segments()) {
                drafts.add(new WorkoutSegmentEditAdapter.Draft(segment.type(), WorkoutFormat.minutesSeconds(segment.duration())));
            }
            adapter.replace(drafts);
        } else {
            workoutId = 0;
            builtin = false;
            adapter.replace(List.of(new WorkoutSegmentEditAdapter.Draft(SegmentType.RUN, "1:00")));
        }
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(workoutId == 0 ? R.string.workout_new : R.string.workout_edit_title);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(STATE_ID, workoutId);
        outState.putBoolean(STATE_BUILTIN, builtin);
        outState.putString(STATE_NAME, text(viewBinding.workoutName));
        List<WorkoutSegmentEditAdapter.Draft> drafts = adapter.drafts();
        String[] types = new String[drafts.size()];
        String[] durations = new String[drafts.size()];
        for (int i = 0; i < drafts.size(); i++) {
            types[i] = drafts.get(i).type.name();
            durations[i] = drafts.get(i).durationText;
        }
        outState.putStringArray(STATE_TYPES, types);
        outState.putStringArray(STATE_DURATIONS, durations);
    }

    @NonNull
    @Override
    protected View createRootView() {
        viewBinding = WorkoutEditActivityBinding.inflate(getLayoutInflater());
        return viewBinding.getRoot();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.workout_edit, menu);
        menu.findItem(R.id.workout_edit_reset).setVisible(builtin);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.workout_edit_save) {
            save();
            return true;
        }
        if (item.getItemId() == R.id.workout_edit_add_segment) {
            adapter.drafts().add(new WorkoutSegmentEditAdapter.Draft(SegmentType.RUN, "1:00"));
            adapter.notifyItemInserted(adapter.drafts().size() - 1);
            viewBinding.workoutSegments.smoothScrollToPosition(adapter.drafts().size() - 1);
            return true;
        }
        if (item.getItemId() == R.id.workout_edit_add_rounds) {
            showAddRounds();
            return true;
        }
        if (item.getItemId() == R.id.workout_edit_reset) {
            repository.resetPreset(workoutId);
            Workout workout = repository.getWorkout(workoutId);
            if (workout != null) {
                viewBinding.workoutName.setText(workout.name());
                List<WorkoutSegmentEditAdapter.Draft> drafts = new ArrayList<>();
                for (WorkoutSegment segment : workout.segments()) {
                    drafts.add(new WorkoutSegmentEditAdapter.Draft(segment.type(), WorkoutFormat.minutesSeconds(segment.duration())));
                }
                adapter.replace(drafts);
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onMove(int from, int to) {
        List<WorkoutSegmentEditAdapter.Draft> drafts = adapter.drafts();
        drafts.add(to, drafts.remove(from));
        adapter.notifyItemRangeChanged(Math.min(from, to), 2);
    }

    @Override
    public void onDelete(int position) {
        adapter.drafts().remove(position);
        adapter.notifyItemRemoved(position);
        int rebindFrom = Math.max(0, position - 1);
        if (rebindFrom < adapter.drafts().size()) {
            adapter.notifyItemRangeChanged(rebindFrom, adapter.drafts().size() - rebindFrom);
        }
    }

    private void save() {
        String name = text(viewBinding.workoutName).trim();
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.workout_need_name, Toast.LENGTH_LONG).show();
            return;
        }
        List<WorkoutSegmentEditAdapter.Draft> drafts = adapter.drafts();
        if (drafts.isEmpty()) {
            Toast.makeText(this, R.string.workout_need_segment, Toast.LENGTH_LONG).show();
            return;
        }
        List<WorkoutSegment> segments = new ArrayList<>();
        for (WorkoutSegmentEditAdapter.Draft draft : drafts) {
            Duration duration = WorkoutFormat.parseMinutesSeconds(draft.durationText);
            if (duration == null || duration.isZero() || duration.isNegative()) {
                Toast.makeText(this, R.string.workout_need_duration, Toast.LENGTH_LONG).show();
                return;
            }
            segments.add(new WorkoutSegment(draft.type, duration));
        }
        repository.saveWorkout(new Workout(workoutId, name, builtin, segments));
        finish();
    }

    private void showAddRounds() {
        WorkoutAddRoundsBinding dialogBinding = WorkoutAddRoundsBinding.inflate(LayoutInflater.from(this));
        dialogBinding.roundsCount.setText(String.format(Locale.US, "%d", 8));
        dialogBinding.roundsRunSeconds.setText(String.format(Locale.US, "%d", 60));
        dialogBinding.roundsWalkSeconds.setText(String.format(Locale.US, "%d", 90));
        new AlertDialog.Builder(this)
                .setTitle(R.string.workout_rounds_title)
                .setView(dialogBinding.getRoot())
                .setPositiveButton(android.R.string.ok, (dialog, which) -> addRounds(dialogBinding))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void addRounds(WorkoutAddRoundsBinding dialogBinding) {
        Integer rounds = positiveInt(dialogBinding.roundsCount);
        Integer runSeconds = positiveInt(dialogBinding.roundsRunSeconds);
        Integer walkSeconds = positiveInt(dialogBinding.roundsWalkSeconds);
        if (rounds == null || runSeconds == null || walkSeconds == null || rounds > 100) {
            Toast.makeText(this, R.string.workout_rounds_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        int start = adapter.drafts().size();
        for (int i = 0; i < rounds; i++) {
            adapter.drafts().add(new WorkoutSegmentEditAdapter.Draft(SegmentType.RUN, WorkoutFormat.minutesSeconds(Duration.ofSeconds(runSeconds))));
            adapter.drafts().add(new WorkoutSegmentEditAdapter.Draft(SegmentType.WALK, WorkoutFormat.minutesSeconds(Duration.ofSeconds(walkSeconds))));
        }
        adapter.notifyItemRangeInserted(start, rounds * 2);
        if (start > 0) {
            adapter.notifyItemChanged(start - 1);
        }
        viewBinding.workoutSegments.smoothScrollToPosition(adapter.drafts().size() - 1);
    }

    private static Integer positiveInt(EditText editText) {
        try {
            int value = Integer.parseInt(text(editText).trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(EditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString();
    }
}
