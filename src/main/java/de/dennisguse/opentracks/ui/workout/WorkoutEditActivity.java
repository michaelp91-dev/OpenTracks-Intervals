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

import de.dennisguse.opentracks.AbstractActivity;
import de.dennisguse.opentracks.R;
import de.dennisguse.opentracks.databinding.WorkoutEditActivityBinding;
import de.dennisguse.opentracks.workout.DistancePace;
import de.dennisguse.opentracks.workout.SegmentType;
import de.dennisguse.opentracks.workout.Workout;
import de.dennisguse.opentracks.workout.WorkoutBlock;
import de.dennisguse.opentracks.workout.WorkoutFormat;
import de.dennisguse.opentracks.workout.WorkoutItem;
import de.dennisguse.opentracks.workout.WorkoutRepository;
import de.dennisguse.opentracks.workout.WorkoutSegment;

public class WorkoutEditActivity extends AbstractActivity implements WorkoutSegmentEditAdapter.Listener {

    public static final String EXTRA_WORKOUT_ID = "workout_id";

    private static final String STATE_ID = "workout_id";
    private static final String STATE_BUILTIN = "workout_builtin";
    private static final String STATE_NAME = "workout_name";
    private static final String STATE_DISTANCE = "workout_distance";
    private static final String STATE_TYPES = "workout_types";
    private static final String STATE_DURATIONS = "workout_durations";
    /** Per flattened row: -1 for a single segment, otherwise the index of its block. */
    private static final String STATE_BLOCK_IDS = "workout_block_ids";
    private static final String STATE_BLOCK_ROUNDS = "workout_block_rounds";

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
            viewBinding.workoutDistance.setText(savedInstanceState.getString(STATE_DISTANCE, ""));
            adapter.replace(restoreDrafts(savedInstanceState));
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
            if (workout.isDistance()) {
                viewBinding.workoutDistance.setText(DistancePace.shortDistance(workout.distanceMeters()));
            }
            adapter.replace(draftsFrom(workout));
        } else {
            workoutId = 0;
            builtin = false;
            adapter.replace(List.of(new WorkoutSegmentEditAdapter.Draft(SegmentType.RUN, "1:00")));
        }
        updateDistanceMode();
        viewBinding.workoutDistance.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                updateDistanceMode();
            }
        });
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
        outState.putString(STATE_DISTANCE, text(viewBinding.workoutDistance));
        List<String> types = new ArrayList<>();
        List<String> durations = new ArrayList<>();
        List<Integer> blockIds = new ArrayList<>();
        List<Integer> blockRounds = new ArrayList<>();
        int blockId = 0;
        for (WorkoutSegmentEditAdapter.Draft draft : adapter.drafts()) {
            if (draft.isBlock()) {
                for (WorkoutSegmentEditAdapter.Draft step : draft.steps) {
                    types.add(step.type.name());
                    durations.add(step.durationText);
                    blockIds.add(blockId);
                    blockRounds.add(draft.rounds);
                }
                blockId++;
            } else {
                types.add(draft.type.name());
                durations.add(draft.durationText);
                blockIds.add(-1);
                blockRounds.add(0);
            }
        }
        outState.putStringArray(STATE_TYPES, types.toArray(new String[0]));
        outState.putStringArray(STATE_DURATIONS, durations.toArray(new String[0]));
        outState.putIntArray(STATE_BLOCK_IDS, toIntArray(blockIds));
        outState.putIntArray(STATE_BLOCK_ROUNDS, toIntArray(blockRounds));
    }

    private static List<WorkoutSegmentEditAdapter.Draft> restoreDrafts(Bundle state) {
        String[] types = state.getStringArray(STATE_TYPES);
        String[] durations = state.getStringArray(STATE_DURATIONS);
        int[] blockIds = state.getIntArray(STATE_BLOCK_IDS);
        int[] blockRounds = state.getIntArray(STATE_BLOCK_ROUNDS);
        List<WorkoutSegmentEditAdapter.Draft> drafts = new ArrayList<>();
        if (types == null || durations == null) {
            return drafts;
        }
        int count = Math.min(types.length, durations.length);
        int i = 0;
        while (i < count) {
            int blockId = blockIds != null && i < blockIds.length ? blockIds[i] : -1;
            if (blockId < 0) {
                drafts.add(new WorkoutSegmentEditAdapter.Draft(SegmentType.fromDatabase(types[i]), durations[i]));
                i++;
                continue;
            }
            int rounds = blockRounds != null && i < blockRounds.length ? blockRounds[i] : WorkoutBlock.MIN_ROUNDS;
            List<WorkoutSegmentEditAdapter.Draft> steps = new ArrayList<>();
            while (i < count && i < blockIds.length && blockIds[i] == blockId) {
                steps.add(new WorkoutSegmentEditAdapter.Draft(SegmentType.fromDatabase(types[i]), durations[i]));
                i++;
            }
            drafts.add(WorkoutSegmentEditAdapter.Draft.block(steps, Math.max(WorkoutBlock.MIN_ROUNDS, Math.min(WorkoutBlock.MAX_ROUNDS, rounds))));
        }
        return drafts;
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] array = new int[values.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = values.get(i);
        }
        return array;
    }

    private static List<WorkoutSegmentEditAdapter.Draft> draftsFrom(Workout workout) {
        List<WorkoutSegmentEditAdapter.Draft> drafts = new ArrayList<>();
        for (WorkoutItem item : workout.items()) {
            if (item instanceof WorkoutBlock block) {
                List<WorkoutSegmentEditAdapter.Draft> steps = new ArrayList<>();
                for (WorkoutSegment step : block.steps()) {
                    steps.add(new WorkoutSegmentEditAdapter.Draft(step.type(), WorkoutFormat.minutesSeconds(step.duration())));
                }
                drafts.add(WorkoutSegmentEditAdapter.Draft.block(steps, block.rounds()));
            } else if (item instanceof WorkoutSegment segment) {
                drafts.add(new WorkoutSegmentEditAdapter.Draft(segment.type(), WorkoutFormat.minutesSeconds(segment.duration())));
            }
        }
        return drafts;
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
        menu.findItem(R.id.workout_edit_add_segment).setVisible(!distanceMode);
        menu.findItem(R.id.workout_edit_add_rounds).setVisible(!distanceMode);
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
            RoundsBlockDialog.show(this, null, this::addBlock);
            return true;
        }
        if (item.getItemId() == R.id.workout_edit_reset) {
            repository.resetPreset(workoutId);
            Workout workout = repository.getWorkout(workoutId);
            if (workout != null) {
                viewBinding.workoutName.setText(workout.name());
                adapter.replace(draftsFrom(workout));
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
    public void onEditBlock(int position) {
        WorkoutSegmentEditAdapter.Draft block = adapter.drafts().get(position);
        RoundsBlockDialog.show(this, block, edited -> {
            int index = adapter.drafts().indexOf(block);
            if (index < 0) {
                return;
            }
            adapter.drafts().set(index, edited);
            adapter.notifyItemChanged(index);
        });
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
        String distanceText = text(viewBinding.workoutDistance).trim();
        if (!distanceText.isEmpty()) {
            Integer meters = DistancePace.parseDistanceMeters(distanceText);
            if (meters == null) {
                Toast.makeText(this, R.string.workout_distance_invalid, Toast.LENGTH_LONG).show();
                return;
            }
            repository.saveWorkout(new Workout(workoutId, name, builtin, List.of(), meters));
            finish();
            return;
        }
        List<WorkoutSegmentEditAdapter.Draft> drafts = adapter.drafts();
        if (drafts.isEmpty()) {
            Toast.makeText(this, R.string.workout_need_segment, Toast.LENGTH_LONG).show();
            return;
        }
        List<WorkoutItem> items = new ArrayList<>();
        for (WorkoutSegmentEditAdapter.Draft draft : drafts) {
            if (draft.isBlock()) {
                if (draft.steps.isEmpty()) {
                    Toast.makeText(this, R.string.workout_rounds_need_step, Toast.LENGTH_LONG).show();
                    return;
                }
                List<WorkoutSegment> steps = new ArrayList<>();
                for (WorkoutSegmentEditAdapter.Draft step : draft.steps) {
                    WorkoutSegment segment = segment(step);
                    if (segment == null) {
                        return;
                    }
                    steps.add(segment);
                }
                if (draft.rounds < WorkoutBlock.MIN_ROUNDS || draft.rounds > WorkoutBlock.MAX_ROUNDS) {
                    Toast.makeText(this, R.string.workout_rounds_invalid, Toast.LENGTH_LONG).show();
                    return;
                }
                items.add(new WorkoutBlock(steps, draft.rounds));
            } else {
                WorkoutSegment segment = segment(draft);
                if (segment == null) {
                    return;
                }
                items.add(segment);
            }
        }
        repository.saveWorkout(new Workout(workoutId, name, builtin, items));
        finish();
    }

    /** Null (after a message) when the duration is not valid. */
    private WorkoutSegment segment(WorkoutSegmentEditAdapter.Draft draft) {
        Duration duration = WorkoutFormat.parseMinutesSeconds(draft.durationText);
        if (duration == null || duration.isZero() || duration.isNegative()) {
            Toast.makeText(this, R.string.workout_need_duration, Toast.LENGTH_LONG).show();
            return null;
        }
        return new WorkoutSegment(draft.type, duration);
    }

    private void addBlock(WorkoutSegmentEditAdapter.Draft block) {
        int position = adapter.drafts().size();
        adapter.drafts().add(block);
        adapter.notifyItemInserted(position);
        if (position > 0) {
            adapter.notifyItemChanged(position - 1);
        }
        viewBinding.workoutSegments.smoothScrollToPosition(position);
    }

    /** A distance workout has no steps, so the step list and step menu items are hidden. */
    private void updateDistanceMode() {
        boolean distance = !text(viewBinding.workoutDistance).trim().isEmpty();
        viewBinding.workoutSegments.setVisibility(distance ? View.GONE : View.VISIBLE);
        if (distance != distanceMode) {
            distanceMode = distance;
            invalidateOptionsMenu();
        }
    }

    private boolean distanceMode;

    private static String text(EditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString();
    }
}
