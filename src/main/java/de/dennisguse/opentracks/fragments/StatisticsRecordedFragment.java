/*
 * Copyright 2008 Google Inc.
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

package de.dennisguse.opentracks.fragments;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.dennisguse.opentracks.R;
import de.dennisguse.opentracks.data.ContentProviderUtils;
import de.dennisguse.opentracks.data.TrackPointIterator;
import de.dennisguse.opentracks.data.models.Statistics;
import de.dennisguse.opentracks.data.models.Track;
import de.dennisguse.opentracks.data.models.TrackPoint;
import de.dennisguse.opentracks.databinding.StatisticsRecordedBinding;
import de.dennisguse.opentracks.util.StringUtils;
import de.dennisguse.opentracks.workout.RecordedSegment;
import de.dennisguse.opentracks.workout.RunSummaryCalculator;
import de.dennisguse.opentracks.workout.WorkoutRepository;

/**
 * Intervals: simple metric run/walk overview for a recorded track (no charts; the in-depth
 * analysis lives in the Pi web app). Speeds/paces/HR are computed from the stored trackpoints
 * by {@link RunSummaryCalculator}, so this works for old tracks too.
 */
public class StatisticsRecordedFragment extends Fragment {

    private static final String TAG = StatisticsRecordedFragment.class.getSimpleName();
    private static final String TRACK_ID_KEY = "trackId";
    private static final String NA = "\u2013";

    public static StatisticsRecordedFragment newInstance(Track.Id trackId) {
        Bundle bundle = new Bundle();
        bundle.putParcelable(TRACK_ID_KEY, trackId);
        StatisticsRecordedFragment fragment = new StatisticsRecordedFragment();
        fragment.setArguments(bundle);
        return fragment;
    }

    private Track.Id trackId;
    private StatisticsRecordedBinding viewBinding;
    private ExecutorService executor;

    private record Loaded(Track track, RunSummaryCalculator.Summary summary, List<RunSummaryCalculator.IntervalRow> intervals) {
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        trackId = getArguments().getParcelable(TRACK_ID_KEY);
        executor = Executors.newSingleThreadExecutor();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        viewBinding = StatisticsRecordedBinding.inflate(inflater, container, false);
        return viewBinding.getRoot();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadStatistics();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        viewBinding = null;
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    public void loadStatistics() {
        Context appContext = requireContext().getApplicationContext();
        Track.Id id = trackId;
        executor.execute(() -> {
            ContentProviderUtils cpu = new ContentProviderUtils(appContext);
            Track track = cpu.getTrack(id);
            Loaded loaded = null;
            if (track != null) {
                List<RunSummaryCalculator.Point> points = readPoints(cpu, id);
                List<RecordedSegment> segments = WorkoutRepository.get(appContext).getTrackSegments(id);
                loaded = new Loaded(track, RunSummaryCalculator.compute(points),
                        segments.isEmpty() ? List.of() : RunSummaryCalculator.intervals(segments, points));
            }
            Loaded result = loaded;
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded() || viewBinding == null) return;
                if (result == null) {
                    Log.e(TAG, "track cannot be null");
                    requireActivity().finish();
                    return;
                }
                show(result);
            });
        });
    }

    private static List<RunSummaryCalculator.Point> readPoints(ContentProviderUtils cpu, Track.Id id) {
        List<RunSummaryCalculator.Point> points = new ArrayList<>();
        try (TrackPointIterator it = cpu.getTrackPointIterator(id, null)) {
            while (it.hasNext()) {
                TrackPoint tp = it.next();
                if (tp.getTime() == null || tp.type() == TrackPoint.Type.SEGMENT_END_MANUAL) {
                    // An end marker carries no movement; the following start marker breaks the chain.
                    if (tp.getTime() != null) {
                        points.add(new RunSummaryCalculator.Point(tp.getTime().toEpochMilli(), true, null, null, null, null));
                    }
                    continue;
                }
                boolean start = tp.type() == TrackPoint.Type.SEGMENT_START_MANUAL || tp.type() == TrackPoint.Type.SEGMENT_START_AUTOMATIC;
                boolean loc = tp.position().hasLocation();
                points.add(new RunSummaryCalculator.Point(
                        tp.getTime().toEpochMilli(),
                        start,
                        loc ? tp.position().latitude() : null,
                        loc ? tp.position().longitude() : null,
                        tp.sensorDistance() != null ? tp.sensorDistance().toM() : null,
                        tp.heartRate() != null ? tp.heartRate().getBPM() : null));
            }
        }
        return points;
    }

    private void show(Loaded data) {
        Track track = data.track();
        viewBinding.statsNameValue.setText(track.name());
        viewBinding.statsDescriptionValue.setText(track.description());
        viewBinding.statsDescriptionValue.setVisibility(track.description() == null || track.description().isEmpty() ? View.GONE : View.VISIBLE);
        viewBinding.statsStartDatetimeValue.setText(StringUtils.formatDateTimeWithOffsetIfDifferent(track.startTime()));
        viewBinding.statsActivityTypeIcon.setImageDrawable(ContextCompat.getDrawable(requireContext(), track.activityType().getIconDrawableId()));

        Statistics st = track.statistics();
        RunSummaryCalculator.Summary s = data.summary();
        LinearLayout c = viewBinding.runSummaryContainer;
        c.removeAllViews();

        section(c, "Time & distance");
        grid(c, new String[][]{
                {"Total time", hms(st.totalDuration())},
                {"Moving time", s.movingSeconds() > 0 ? hms(Duration.ofMillis((long) (s.movingSeconds() * 1000))) : NA},
                {"Distance", String.format(Locale.US, "%.2f km", st.totalDistance().toM() / 1000.0)},
        });

        section(c, "Speed (km/h, moving only)");
        grid(c, new String[][]{
                {"Average", num(s.avgKmh(), "%.1f")},
                {"Max", num(s.maxKmh(), "%.1f")},
                {"Min", num(s.minKmh(), "%.1f")},
        });

        section(c, "Pace (min/km, moving only)");
        grid(c, new String[][]{
                {"Average", pace(s.avgPace())},
                {"Best", pace(s.bestPace())},
                {"Slowest", pace(s.slowestPace())},
        });

        section(c, "Heart rate (bpm)");
        grid(c, new String[][]{
                {"Average", bpm(s.avgHr())},
                {"Max", bpm(s.maxHr())},
                {"Min", bpm(s.minHr())},
        });

        Float gain = st.altitudeGainLoss() != null ? st.altitudeGainLoss().gain_m() : null;
        Float loss = st.altitudeGainLoss() != null ? st.altitudeGainLoss().loss_m() : null;
        section(c, "Elevation (m)");
        grid(c, new String[][]{
                {"Gain", gain == null ? NA : String.format(Locale.US, "%.0f", gain)},
                {"Loss", loss == null ? NA : String.format(Locale.US, "%.0f", loss)},
        });

        if (!data.intervals().isEmpty()) {
            section(c, "Intervals");
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{"#", "Type", "Time", "km", "km/h", "bpm"});
            for (RunSummaryCalculator.IntervalRow r : data.intervals()) {
                String type = r.type() == null ? NA : getString(r.type().labelRes());
                if (!r.completed()) type += " " + getString(R.string.workout_partial);
                rows.add(new String[]{
                        Integer.toString(r.position() + 1), type,
                        mss(r.seconds()),
                        String.format(Locale.US, "%.2f", r.meters() / 1000.0),
                        num(r.kmh(), "%.1f"),
                        bpm(r.avgHr())});
            }
            table(c, rows);
        }
    }

    // ---- formatting ----

    private static String hms(Duration d) {
        long t = Math.max(0, d.getSeconds());
        return t >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", t / 3600, (t / 60) % 60, t % 60)
                : String.format(Locale.US, "%d:%02d", t / 60, t % 60);
    }

    private static String mss(double seconds) {
        return hms(Duration.ofSeconds(Math.round(seconds)));
    }

    private static String num(double v, String fmt) {
        return Double.isNaN(v) || Double.isInfinite(v) ? NA : String.format(Locale.US, fmt, v);
    }

    private static String pace(double minPerKm) {
        if (Double.isNaN(minPerKm) || Double.isInfinite(minPerKm)) return NA;
        return mss(minPerKm * 60.0);
    }

    private static String bpm(Float v) {
        return v == null ? NA : Integer.toString(Math.round(v));
    }

    // ---- simple views ----

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void section(LinearLayout c, String title) {
        TextView t = new TextView(requireContext());
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(16), 0, dp(4));
        c.addView(t);
    }

    /** One row of label-over-value cells, equal width. */
    private void grid(LinearLayout c, String[][] cells) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String[] cell : cells) {
            LinearLayout col = new LinearLayout(requireContext());
            col.setOrientation(LinearLayout.VERTICAL);
            TextView label = new TextView(requireContext());
            label.setText(cell[0]);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            label.setAlpha(0.7f);
            TextView value = new TextView(requireContext());
            value.setText(cell[1]);
            value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            col.addView(label);
            col.addView(value);
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        c.addView(row);
    }

    private void table(LinearLayout c, List<String[]> rows) {
        float[] weights = {0.5f, 2f, 1.2f, 1f, 1f, 0.9f};
        for (int i = 0; i < rows.size(); i++) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(3), 0, dp(3));
            String[] cells = rows.get(i);
            for (int j = 0; j < cells.length; j++) {
                TextView t = new TextView(requireContext());
                t.setText(cells[j]);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                t.setGravity(j >= 2 ? Gravity.END : Gravity.START);
                if (i == 0) {
                    t.setTypeface(Typeface.DEFAULT_BOLD);
                    t.setAlpha(0.7f);
                }
                row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weights[j]));
            }
            c.addView(row);
        }
    }
}
