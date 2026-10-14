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
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.dennisguse.opentracks.R;
import de.dennisguse.opentracks.data.ContentProviderUtils;
import de.dennisguse.opentracks.data.models.Track;
import de.dennisguse.opentracks.databinding.WorkoutSplitsFragmentBinding;
import de.dennisguse.opentracks.workout.RecordedSegment;
import de.dennisguse.opentracks.workout.Sample;
import de.dennisguse.opentracks.workout.TrackPointSamples;
import de.dennisguse.opentracks.workout.WorkoutFormat;
import de.dennisguse.opentracks.workout.WorkoutRepository;
import de.dennisguse.opentracks.workout.WorkoutSplitCalculator;

public class WorkoutSplitsFragment extends Fragment {

    private static final String ARG_TRACK_ID = "track_id";

    private WorkoutSplitsFragmentBinding viewBinding;
    private WorkoutSplitsAdapter adapter;
    private ExecutorService executor;
    private Track.Id trackId;

    public static WorkoutSplitsFragment newInstance(@NonNull Track.Id trackId) {
        WorkoutSplitsFragment fragment = new WorkoutSplitsFragment();
        Bundle args = new Bundle();
        args.putParcelable(ARG_TRACK_ID, trackId);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executor = Executors.newSingleThreadExecutor();
        Bundle args = getArguments();
        if (args != null) {
            trackId = args.getParcelable(ARG_TRACK_ID);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        viewBinding = WorkoutSplitsFragmentBinding.inflate(inflater, container, false);
        adapter = new WorkoutSplitsAdapter();
        viewBinding.workoutSplitList.setLayoutManager(new LinearLayoutManager(requireContext()));
        viewBinding.workoutSplitList.setAdapter(adapter);
        return viewBinding.getRoot();
    }

    @Override
    public void onStart() {
        super.onStart();
        load();
    }

    @Override
    public void onDestroyView() {
        viewBinding = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    private void load() {
        if (trackId == null || executor == null) {
            showEmpty();
            return;
        }
        Context appContext = requireContext().getApplicationContext();
        Track.Id id = trackId;
        executor.execute(() -> {
            List<RecordedSegment> recorded = WorkoutRepository.get(appContext).getTrackSegments(id);
            List<Sample> samples = recorded.isEmpty()
                    ? List.of()
                    : TrackPointSamples.from(new ContentProviderUtils(appContext), id);
            WorkoutSplitCalculator.Result result = new WorkoutSplitCalculator().compute(recorded, samples);
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (!isAdded() || viewBinding == null) {
                    return;
                }
                if (recorded.isEmpty()) {
                    showEmpty();
                    return;
                }
                showRows(result);
            });
        });
    }

    private void showEmpty() {
        if (viewBinding == null) {
            return;
        }
        viewBinding.workoutSplitHeader.setVisibility(View.GONE);
        viewBinding.workoutSplitDivider.setVisibility(View.GONE);
        viewBinding.workoutSplitList.setVisibility(View.GONE);
        viewBinding.workoutSplitEmpty.setVisibility(View.VISIBLE);
    }

    private void showRows(WorkoutSplitCalculator.Result result) {
        viewBinding.workoutSplitHeader.setVisibility(View.VISIBLE);
        viewBinding.workoutSplitDivider.setVisibility(View.VISIBLE);
        viewBinding.workoutSplitList.setVisibility(View.VISIBLE);
        viewBinding.workoutSplitEmpty.setVisibility(View.GONE);
        List<WorkoutSplitsAdapter.Row> rows = new ArrayList<>();
        for (WorkoutSplitCalculator.Split split : result.segments()) {
            String type = split.type() == null ? "" : getString(split.type().labelRes());
            if (!split.completed()) {
                type = type + " " + getString(R.string.workout_partial);
            }
            rows.add(new WorkoutSplitsAdapter.Row(
                    Integer.toString(split.position() + 1),
                    type,
                    split.timeText(),
                    split.distanceText(),
                    split.speedText(),
                    false
            ));
        }
        rows.add(summary(getString(R.string.workout_summary_runs), result.runs()));
        rows.add(summary(getString(R.string.workout_summary_walks), result.walks()));
        adapter.setRows(rows);
    }

    private static WorkoutSplitsAdapter.Row summary(String label, WorkoutSplitCalculator.Split split) {
        return new WorkoutSplitsAdapter.Row(
                WorkoutFormat.UNAVAILABLE,
                label,
                split.timeText(),
                split.distanceText(),
                split.speedText(),
                true
        );
    }
}
