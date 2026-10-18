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

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import de.dennisguse.opentracks.R;
import de.dennisguse.opentracks.databinding.WorkoutAddRoundsBinding;
import de.dennisguse.opentracks.databinding.WorkoutSegmentEditItemBinding;
import de.dennisguse.opentracks.workout.SegmentType;
import de.dennisguse.opentracks.workout.WorkoutBlock;
import de.dennisguse.opentracks.workout.WorkoutFormat;

/**
 * Builds or edits one repeat block: any list of run and walk steps plus a round count.
 */
final class RoundsBlockDialog {

    private static final SegmentType[] STEP_TYPES = {SegmentType.RUN, SegmentType.WALK};

    private final Context context;
    private final WorkoutAddRoundsBinding binding;
    private final List<WorkoutSegmentEditAdapter.Draft> steps = new ArrayList<>();
    private final String[] labels;

    private RoundsBlockDialog(Context context, WorkoutSegmentEditAdapter.Draft block) {
        this.context = context;
        this.binding = WorkoutAddRoundsBinding.inflate(LayoutInflater.from(context));
        labels = new String[STEP_TYPES.length];
        for (int i = 0; i < STEP_TYPES.length; i++) {
            labels[i] = context.getString(STEP_TYPES[i].labelRes());
        }
        int rounds;
        if (block != null && block.isBlock()) {
            for (WorkoutSegmentEditAdapter.Draft step : block.steps) {
                steps.add(step.copy());
            }
            rounds = block.rounds;
        } else {
            steps.add(new WorkoutSegmentEditAdapter.Draft(SegmentType.RUN, "1:00"));
            steps.add(new WorkoutSegmentEditAdapter.Draft(SegmentType.WALK, "1:00"));
            rounds = 3;
        }
        binding.roundsCount.setText(String.valueOf(rounds));
        binding.roundsMinus.setOnClickListener(v -> stepRounds(-1));
        binding.roundsPlus.setOnClickListener(v -> stepRounds(1));
        binding.roundsAddStep.setOnClickListener(v -> {
            SegmentType next = steps.isEmpty() || steps.get(steps.size() - 1).type == SegmentType.WALK ? SegmentType.RUN : SegmentType.WALK;
            steps.add(new WorkoutSegmentEditAdapter.Draft(next, "1:00"));
            render();
        });
        render();
    }

    /**
     * @param block    the block to edit, or null to build a new one
     * @param onResult receives a new block draft when the user taps OK with valid input
     */
    static void show(Context context, WorkoutSegmentEditAdapter.Draft block, Consumer<WorkoutSegmentEditAdapter.Draft> onResult) {
        RoundsBlockDialog editor = new RoundsBlockDialog(context, block);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(block == null ? R.string.workout_rounds_title : R.string.workout_rounds_edit_title)
                .setView(editor.binding.getRoot())
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            WorkoutSegmentEditAdapter.Draft result = editor.result();
            if (result != null) {
                onResult.accept(result);
                dialog.dismiss();
            }
        }));
        dialog.show();
    }

    private void stepRounds(int delta) {
        Integer current = parseRounds();
        int value = current == null ? WorkoutBlock.MIN_ROUNDS : current + delta;
        value = Math.max(WorkoutBlock.MIN_ROUNDS, Math.min(WorkoutBlock.MAX_ROUNDS, value));
        binding.roundsCount.setText(String.valueOf(value));
        binding.roundsCount.setSelection(binding.roundsCount.length());
    }

    private Integer parseRounds() {
        try {
            CharSequence text = binding.roundsCount.getText();
            return Integer.parseInt(text == null ? "" : text.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Null (after a message) when the input is not a valid block. */
    private WorkoutSegmentEditAdapter.Draft result() {
        if (steps.isEmpty()) {
            Toast.makeText(context, R.string.workout_rounds_need_step, Toast.LENGTH_LONG).show();
            return null;
        }
        List<WorkoutSegmentEditAdapter.Draft> cleaned = new ArrayList<>();
        for (WorkoutSegmentEditAdapter.Draft step : steps) {
            Duration duration = WorkoutFormat.parseMinutesSeconds(step.durationText);
            if (duration == null || duration.isZero() || duration.isNegative()) {
                Toast.makeText(context, R.string.workout_need_duration, Toast.LENGTH_LONG).show();
                return null;
            }
            cleaned.add(new WorkoutSegmentEditAdapter.Draft(step.type, WorkoutFormat.minutesSeconds(duration)));
        }
        Integer rounds = parseRounds();
        if (rounds == null || rounds < WorkoutBlock.MIN_ROUNDS || rounds > WorkoutBlock.MAX_ROUNDS) {
            Toast.makeText(context, R.string.workout_rounds_invalid, Toast.LENGTH_LONG).show();
            return null;
        }
        return WorkoutSegmentEditAdapter.Draft.block(cleaned, rounds);
    }

    /** Rebuilds the step rows; the list is short, so a full rebuild keeps positions simple. */
    private void render() {
        binding.roundsSteps.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(context);
        for (int i = 0; i < steps.size(); i++) {
            binding.roundsSteps.addView(row(inflater, i));
        }
    }

    private View row(LayoutInflater inflater, int index) {
        WorkoutSegmentEditAdapter.Draft step = steps.get(index);
        WorkoutSegmentEditItemBinding row = WorkoutSegmentEditItemBinding.inflate(inflater, binding.roundsSteps, false);
        row.segmentType.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, labels));
        row.segmentType.setSelection(step.type == SegmentType.WALK ? 1 : 0, false);
        row.segmentType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                step.type = STEP_TYPES[position];
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        row.segmentDuration.setText(step.durationText);
        row.segmentDuration.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                step.durationText = s.toString();
            }
        });
        boolean canUp = index > 0;
        boolean canDown = index < steps.size() - 1;
        row.segmentMoveUp.setEnabled(canUp);
        row.segmentMoveDown.setEnabled(canDown);
        row.segmentMoveUp.setAlpha(canUp ? 1f : 0.3f);
        row.segmentMoveDown.setAlpha(canDown ? 1f : 0.3f);
        row.segmentMoveUp.setOnClickListener(v -> {
            if (canUp) {
                steps.add(index - 1, steps.remove(index));
                render();
            }
        });
        row.segmentMoveDown.setOnClickListener(v -> {
            if (canDown) {
                steps.add(index + 1, steps.remove(index));
                render();
            }
        });
        row.segmentDelete.setOnClickListener(v -> {
            steps.remove(index);
            render();
        });
        return row.getRoot();
    }
}
