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
package de.dennisguse.opentracks.workout;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import de.dennisguse.opentracks.data.ContentProviderUtils;
import de.dennisguse.opentracks.data.models.Track;
import de.dennisguse.opentracks.io.file.exporter.ExportDirectories;
import de.dennisguse.opentracks.settings.PreferencesUtils;
import de.dennisguse.opentracks.util.IntentUtils;

/** Writes one splits CSV into the same folder as the automatic track export. */
public final class WorkoutSplitsExporter {

    private static final String TAG = "WorkoutSplitsExporter";

    private WorkoutSplitsExporter() {
    }

    public static void export(Context context, Track.Id trackId) {
        if (context == null || trackId == null) {
            return;
        }
        try {
            Context appContext = context.getApplicationContext();
            WorkoutRepository repository = WorkoutRepository.get(appContext);
            List<PocketDetector.Event> pocketEvents = PocketStore.load(appContext, trackId);
            if (!repository.hasWorkout(trackId) && pocketEvents == null) {
                return;
            }
            ContentProviderUtils contentProviderUtils = new ContentProviderUtils(appContext);
            Track track = contentProviderUtils.getTrack(trackId);
            if (track == null) {
                Log.w(TAG, "Track " + trackId.id() + " is missing; workout splits file was not written");
                return;
            }
            // Same folder as the track export: Runs for running, Walks otherwise.
            DocumentFile directory = ExportDirectories.forTrack(appContext, track);
            if (directory == null || !directory.canWrite()) {
                Log.w(TAG, "No writable export directory; workout splits file was not written");
                return;
            }
            String filename = PreferencesUtils.getTrackFileformatGenerator().splitsFilename(track);
            if (pocketEvents != null) {
                write(appContext, directory, filename.replace("-splits.csv", "-pocket.csv"), PocketDetector.toCsv(pocketEvents));
            }
            if (!repository.hasWorkout(trackId)) {
                return;
            }
            List<RecordedSegment> recorded = repository.getTrackSegments(trackId);
            if (recorded.isEmpty()) {
                return;
            }
            List<Sample> samples = TrackPointSamples.from(contentProviderUtils, trackId);
            WorkoutSplitCalculator.Result result = new WorkoutSplitCalculator().compute(recorded, samples);
            String text = WorkoutSplitsText.format(result);
            if (pocketEvents != null) {
                text += "\n# " + PocketDetector.summary(pocketEvents) + "\n"
                        + "# out_of_pocket_count=" + pocketEvents.size()
                        + ",out_of_pocket_total_s=" + Math.round(PocketDetector.totalMs(pocketEvents) / 1000.0) + "\n";
            }
            DocumentFile file = directory.findFile(filename);
            if (file == null) {
                file = directory.createFile("text/csv", filename);
            }
            if (file == null) {
                Log.w(TAG, "Could not create " + filename);
                return;
            }
            try (OutputStream outputStream = appContext.getContentResolver().openOutputStream(file.getUri(), "wt")) {
                if (outputStream == null) {
                    Log.w(TAG, "Could not open " + filename);
                    return;
                }
                outputStream.write(text.getBytes(StandardCharsets.UTF_8));
            }
            Log.i(TAG, "Wrote workout splits to " + filename);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Workout splits file was not written", e);
        }
    }

    private static void write(Context context, DocumentFile directory, String filename, String text) throws IOException {
        DocumentFile file = directory.findFile(filename);
        if (file == null) {
            file = directory.createFile("text/csv", filename);
        }
        if (file == null) {
            Log.w(TAG, "Could not create " + filename);
            return;
        }
        try (OutputStream outputStream = context.getContentResolver().openOutputStream(file.getUri(), "wt")) {
            if (outputStream != null) {
                outputStream.write(text.getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}
