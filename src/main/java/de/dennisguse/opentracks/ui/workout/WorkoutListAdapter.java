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
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import de.dennisguse.opentracks.R;
import de.dennisguse.opentracks.databinding.WorkoutListItemBinding;
import de.dennisguse.opentracks.workout.Workout;
import de.dennisguse.opentracks.workout.WorkoutFormat;

public class WorkoutListAdapter extends RecyclerView.Adapter<WorkoutListAdapter.Holder> {

    public interface Listener {
        void onStart(Workout workout);

        void onEdit(Workout workout);

        void onDelete(Workout workout);

        void onReset(Workout workout);
    }

    private final Listener listener;
    private final List<Workout> workouts = new ArrayList<>();

    public WorkoutListAdapter(Listener listener) {
        this.listener = listener;
    }

    @SuppressLint("NotifyDataSetChanged") // The whole list is replaced.
    public void setWorkouts(List<Workout> items) {
        workouts.clear();
        workouts.addAll(items);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        WorkoutListItemBinding binding = WorkoutListItemBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new Holder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(workouts.get(position));
    }

    @Override
    public int getItemCount() {
        return workouts.size();
    }

    class Holder extends RecyclerView.ViewHolder {
        private final WorkoutListItemBinding binding;

        Holder(WorkoutListItemBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(Workout workout) {
            int count = workout.segments().size();
            String time = WorkoutFormat.minutesSeconds(workout.totalDuration());
            binding.workoutItemName.setText(workout.name());
            binding.workoutItemSummary.setText(binding.getRoot().getContext().getResources()
                    .getQuantityString(R.plurals.workout_list_summary, count, count, time));
            binding.getRoot().setOnClickListener(v -> listener.onStart(workout));
            binding.getRoot().setOnLongClickListener(v -> {
                showMenu(v, workout);
                return true;
            });
            binding.workoutItemOverflow.setOnClickListener(v -> showMenu(v, workout));
        }

        private void showMenu(View anchor, Workout workout) {
            PopupMenu menu = new PopupMenu(anchor.getContext(), anchor);
            menu.inflate(R.menu.workout_item);
            menu.getMenu().findItem(R.id.workout_item_delete).setVisible(!workout.builtin());
            menu.getMenu().findItem(R.id.workout_item_reset).setVisible(workout.builtin());
            menu.setOnMenuItemClickListener(item -> onMenu(item, workout));
            menu.show();
        }

        private boolean onMenu(MenuItem item, Workout workout) {
            if (item.getItemId() == R.id.workout_item_edit) {
                listener.onEdit(workout);
                return true;
            }
            if (item.getItemId() == R.id.workout_item_delete) {
                listener.onDelete(workout);
                return true;
            }
            if (item.getItemId() == R.id.workout_item_reset) {
                listener.onReset(workout);
                return true;
            }
            return false;
        }
    }
}
