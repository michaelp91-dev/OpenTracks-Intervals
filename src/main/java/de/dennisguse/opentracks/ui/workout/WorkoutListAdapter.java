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
import android.view.MotionEvent;
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

        /** The user pressed the drag handle of this row. */
        void onStartDrag(RecyclerView.ViewHolder holder);
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

    public Workout workoutAt(int position) {
        return workouts.get(position);
    }

    /** Moves a row while dragging; the caller stores the order when the drag ends. */
    public void moveItem(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= workouts.size() || to >= workouts.size()) {
            return;
        }
        workouts.add(to, workouts.remove(from));
        notifyItemMoved(from, to);
    }

    /** Workout ids in the order shown, top first. */
    public List<Long> ids() {
        List<Long> ids = new ArrayList<>();
        for (Workout workout : workouts) {
            ids.add(workout.id());
        }
        return ids;
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

        @SuppressLint("ClickableViewAccessibility") // The handle only starts a drag; the row menu offers the same actions.
        Holder(WorkoutListItemBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            binding.workoutItemDrag.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    listener.onStartDrag(this);
                }
                return false;
            });
        }

        void bind(Workout workout) {
            int count = workout.segmentCount();
            String time = WorkoutFormat.minutesSeconds(workout.totalDuration());
            binding.workoutItemName.setText(workout.name());
            if (workout.isDistance()) {
                binding.workoutItemSummary.setText(binding.getRoot().getContext().getString(R.string.workout_distance_summary,
                        de.dennisguse.opentracks.workout.DistancePace.shortDistance(workout.distanceMeters())));
            } else {
                binding.workoutItemSummary.setText(binding.getRoot().getContext().getResources()
                        .getQuantityString(R.plurals.workout_list_summary, count, count, time));
            }
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
