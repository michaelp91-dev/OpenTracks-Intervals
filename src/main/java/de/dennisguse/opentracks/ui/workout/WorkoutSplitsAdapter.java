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
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import de.dennisguse.opentracks.databinding.WorkoutSplitItemBinding;

public class WorkoutSplitsAdapter extends RecyclerView.Adapter<WorkoutSplitsAdapter.Holder> {

    public static final class Row {
        final String index;
        final String type;
        final String time;
        final String distance;
        final String speed;
        final boolean summary;

        public Row(String index, String type, String time, String distance, String speed, boolean summary) {
            this.index = index;
            this.type = type;
            this.time = time;
            this.distance = distance;
            this.speed = speed;
            this.summary = summary;
        }
    }

    private final List<Row> rows = new ArrayList<>();

    @SuppressLint("NotifyDataSetChanged") // The whole split table is replaced.
    public void setRows(List<Row> items) {
        rows.clear();
        rows.addAll(items);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(WorkoutSplitItemBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(rows.get(position));
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        private final WorkoutSplitItemBinding binding;

        Holder(WorkoutSplitItemBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(Row row) {
            binding.splitIndex.setText(row.index);
            binding.splitType.setText(row.type);
            binding.splitTime.setText(row.time);
            binding.splitDistance.setText(row.distance);
            binding.splitSpeed.setText(row.speed);
            int style = row.summary ? Typeface.BOLD : Typeface.NORMAL;
            binding.splitIndex.setTypeface(null, style);
            binding.splitType.setTypeface(null, style);
            binding.splitTime.setTypeface(null, style);
            binding.splitDistance.setTypeface(null, style);
            binding.splitSpeed.setTypeface(null, style);
        }
    }
}
