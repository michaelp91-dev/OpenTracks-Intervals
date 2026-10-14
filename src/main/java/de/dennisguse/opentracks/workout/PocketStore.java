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
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import de.dennisguse.opentracks.data.models.Track;

/** Keeps each track's out-of-pocket events in app storage (files/pocket/<trackId>.csv). */
public final class PocketStore {

    private static final String TAG = "PocketStore";

    private PocketStore() {
    }

    private static File file(Context context, Track.Id trackId) {
        return new File(new File(context.getFilesDir(), "pocket"), trackId.id() + ".csv");
    }

    public static void save(Context context, Track.Id trackId, List<PocketDetector.Event> events) {
        try {
            File f = file(context, trackId);
            f.getParentFile().mkdirs();
            Files.write(f.toPath(), PocketDetector.toCsv(events).getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not save pocket events", e);
        }
    }

    /** @return null if this track has no pocket data (e.g. recorded before this feature). */
    public static List<PocketDetector.Event> load(Context context, Track.Id trackId) {
        if (context == null || trackId == null) {
            return null;
        }
        File f = file(context, trackId);
        if (!f.exists()) {
            return null;
        }
        try {
            return PocketDetector.fromCsv(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "Could not read pocket events", e);
            return new ArrayList<>();
        }
    }
}
