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

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import de.dennisguse.opentracks.R;
import de.dennisguse.opentracks.databinding.WorkoutBlockEditItemBinding;
import de.dennisguse.opentracks.databinding.WorkoutSegmentEditItemBinding;
import de.dennisguse.opentracks.workout.SegmentType;
import de.dennisguse.opentracks.workout.WorkoutBlock;
import de.dennisguse.opentracks.workout.WorkoutFormat;

public class WorkoutSegmentEditAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_SEGMENT = 0;
    private static final int VIEW_BLOCK = 1;

    public interface Listener {
        void onMove(int from, int to);

        void onDelete(int position);

        /** The user tapped a repeat block to change its steps. */
        void onEditBlock(int position);
    }

    /**
     * Editable row. A single segment uses {@link #type} and {@link #durationText}.
     * A repeat block has non-null {@link #steps} (single-segment drafts) and {@link #rounds}.
     */
    public static final class Draft {
        public SegmentType type;
        public String durationText;
        public List<Draft> steps;
        public int rounds;

        public Draft(SegmentType type, String durationText) {
            this.type = type;
            this.durationText = durationText;
        }

        public static Draft block(List<Draft> steps, int rounds) {
            Draft draft = new Draft(SegmentType.RUN, "");
            draft.steps = new ArrayList<>(steps);
            draft.rounds = rounds;
            return draft;
        }

        public boolean isBlock() {
            return steps != null;
        }

        public Draft copy() {
            if (!isBlock()) {
                return new Draft(type, durationText);
            }
            List<Draft> copied = new ArrayList<>();
            for (Draft step : steps) {
                copied.add(step.copy());
            }
            return block(copied, rounds);
        }
    }

    private final List<Draft> drafts = new ArrayList<>();
    private final Listener listener;
    private final String[] labels;
    private final SegmentType[] types = SegmentType.values();

    public WorkoutSegmentEditAdapter(Context context, Listener listener) {
        this.listener = listener;
        labels = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            labels[i] = context.getString(types[i].labelRes());
        }
    }

    public List<Draft> drafts() {
        return drafts;
    }

    @SuppressLint("NotifyDataSetChanged") // The whole draft list is replaced.
    public void replace(List<Draft> items) {
        drafts.clear();
        drafts.addAll(items);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return drafts.get(position).isBlock() ? VIEW_BLOCK : VIEW_SEGMENT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_BLOCK) {
            return new BlockHolder(WorkoutBlockEditItemBinding.inflate(inflater, parent, false));
        }
        return new Holder(WorkoutSegmentEditItemBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof BlockHolder blockHolder) {
            blockHolder.bind(drafts.get(position), position);
        } else {
            ((Holder) holder).bind(drafts.get(position), position);
        }
    }

    @Override
    public int getItemCount() {
        return drafts.size();
    }

    /** "Run 1:00" lines for the steps of a block. */
    static String stepLines(Context context, List<Draft> steps) {
        StringBuilder text = new StringBuilder();
        for (Draft step : steps) {
            if (text.length() > 0) {
                text.append('\n');
            }
            Duration duration = WorkoutFormat.parseMinutesSeconds(step.durationText);
            text.append(context.getString(step.type.labelRes()))
                    .append("  ")
                    .append(duration == null ? WorkoutFormat.UNAVAILABLE : WorkoutFormat.minutesSeconds(duration));
        }
        return text.toString();
    }

    /** Length of one round, or null when a step has no valid duration. */
    static Duration roundDuration(List<Draft> steps) {
        Duration total = Duration.ZERO;
        for (Draft step : steps) {
            Duration duration = WorkoutFormat.parseMinutesSeconds(step.durationText);
            if (duration == null) {
                return null;
            }
            total = total.plus(duration);
        }
        return total;
    }

    class BlockHolder extends RecyclerView.ViewHolder {
        private final WorkoutBlockEditItemBinding binding;

        BlockHolder(WorkoutBlockEditItemBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            View.OnClickListener edit = v -> {
                int row = getBindingAdapterPosition();
                if (row != RecyclerView.NO_POSITION) {
                    listener.onEditBlock(row);
                }
            };
            binding.blockCard.setOnClickListener(edit);
            binding.blockEdit.setOnClickListener(edit);
            binding.blockRoundsMinus.setOnClickListener(v -> changeRounds(-1));
            binding.blockRoundsPlus.setOnClickListener(v -> changeRounds(1));
            binding.blockMoveUp.setOnClickListener(v -> {
                int row = getBindingAdapterPosition();
                if (row != RecyclerView.NO_POSITION && row > 0) {
                    listener.onMove(row, row - 1);
                }
            });
            binding.blockMoveDown.setOnClickListener(v -> {
                int row = getBindingAdapterPosition();
                if (row != RecyclerView.NO_POSITION && row < drafts.size() - 1) {
                    listener.onMove(row, row + 1);
                }
            });
            binding.blockDelete.setOnClickListener(v -> {
                int row = getBindingAdapterPosition();
                if (row != RecyclerView.NO_POSITION) {
                    listener.onDelete(row);
                }
            });
        }

        private void changeRounds(int delta) {
            int row = getBindingAdapterPosition();
            if (row == RecyclerView.NO_POSITION) {
                return;
            }
            Draft draft = drafts.get(row);
            int rounds = Math.max(WorkoutBlock.MIN_ROUNDS, Math.min(WorkoutBlock.MAX_ROUNDS, draft.rounds + delta));
            if (rounds != draft.rounds) {
                draft.rounds = rounds;
                showRounds(draft);
            }
        }

        void bind(Draft draft, int position) {
            Context context = binding.getRoot().getContext();
            binding.blockSteps.setText(stepLines(context, draft.steps));
            showRounds(draft);
            binding.blockMoveUp.setEnabled(position > 0);
            binding.blockMoveDown.setEnabled(position < drafts.size() - 1);
            binding.blockMoveUp.setAlpha(position > 0 ? 1f : 0.3f);
            binding.blockMoveDown.setAlpha(position < drafts.size() - 1 ? 1f : 0.3f);
        }

        private void showRounds(Draft draft) {
            Context context = binding.getRoot().getContext();
            binding.blockTitle.setText(context.getString(R.string.workout_block_title, draft.rounds));
            binding.blockRounds.setText(String.valueOf(draft.rounds));
            Duration round = roundDuration(draft.steps);
            String perRound = round == null ? WorkoutFormat.UNAVAILABLE : WorkoutFormat.minutesSeconds(round);
            String total = round == null ? WorkoutFormat.UNAVAILABLE : WorkoutFormat.minutesSeconds(round.multipliedBy(draft.rounds));
            binding.blockSummary.setText(context.getString(R.string.workout_block_summary, perRound, total));
            binding.blockRoundsMinus.setEnabled(draft.rounds > WorkoutBlock.MIN_ROUNDS);
            binding.blockRoundsPlus.setEnabled(draft.rounds < WorkoutBlock.MAX_ROUNDS);
        }
    }

    class Holder extends RecyclerView.ViewHolder {
        private final WorkoutSegmentEditItemBinding binding;
        private boolean updating;

        Holder(WorkoutSegmentEditItemBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            ArrayAdapter<String> adapter = new ArrayAdapter<>(binding.getRoot().getContext(), android.R.layout.simple_spinner_dropdown_item, labels);
            binding.segmentType.setAdapter(adapter);
            binding.segmentType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    int row = getBindingAdapterPosition();
                    if (updating || row == RecyclerView.NO_POSITION) {
                        return;
                    }
                    drafts.get(row).type = types[position];
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
            binding.segmentDuration.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    int row = getBindingAdapterPosition();
                    if (updating || row == RecyclerView.NO_POSITION) {
                        return;
                    }
                    drafts.get(row).durationText = s.toString();
                }
            });
            binding.segmentMoveUp.setOnClickListener(v -> {
                int row = getBindingAdapterPosition();
                if (row > 0) {
                    listener.onMove(row, row - 1);
                }
            });
            binding.segmentMoveDown.setOnClickListener(v -> {
                int row = getBindingAdapterPosition();
                if (row != RecyclerView.NO_POSITION && row < drafts.size() - 1) {
                    listener.onMove(row, row + 1);
                }
            });
            binding.segmentDelete.setOnClickListener(v -> {
                int row = getBindingAdapterPosition();
                if (row != RecyclerView.NO_POSITION) {
                    listener.onDelete(row);
                }
            });
        }

        void bind(Draft draft, int position) {
            updating = true;
            binding.segmentType.setSelection(draft.type.ordinal());
            binding.segmentDuration.setText(draft.durationText);
            binding.segmentMoveUp.setEnabled(position > 0);
            binding.segmentMoveDown.setEnabled(position < drafts.size() - 1);
            binding.segmentMoveUp.setAlpha(position > 0 ? 1f : 0.3f);
            binding.segmentMoveDown.setAlpha(position < drafts.size() - 1 ? 1f : 0.3f);
            updating = false;
        }
    }
}
