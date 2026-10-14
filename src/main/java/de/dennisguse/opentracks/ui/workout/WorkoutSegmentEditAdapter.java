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

import java.util.ArrayList;
import java.util.List;

import de.dennisguse.opentracks.databinding.WorkoutSegmentEditItemBinding;
import de.dennisguse.opentracks.workout.SegmentType;

public class WorkoutSegmentEditAdapter extends RecyclerView.Adapter<WorkoutSegmentEditAdapter.Holder> {

    public interface Listener {
        void onMove(int from, int to);

        void onDelete(int position);
    }

    public static final class Draft {
        public SegmentType type;
        public String durationText;

        public Draft(SegmentType type, String durationText) {
            this.type = type;
            this.durationText = durationText;
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

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        return new Holder(WorkoutSegmentEditItemBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(drafts.get(position), position);
    }

    @Override
    public int getItemCount() {
        return drafts.size();
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
