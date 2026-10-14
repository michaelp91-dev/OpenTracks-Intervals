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
package de.dennisguse.opentracks.sensors.raw;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.File;
import java.util.List;

import de.dennisguse.opentracks.data.models.Track;
import de.dennisguse.opentracks.workout.WorkoutDatabase;

/**
 * Links a recording to its sensor CSV in app-private storage.
 * The row lives in workouts.db so the upstream track database stays unchanged.
 */
public final class SensorLogStore {

    private static final String TAG = "SensorLogStore";

    private SensorLogStore() {
    }

    public static File directory(Context context) {
        File dir = new File(context.getFilesDir(), "sensors");
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            Log.w(TAG, "Could not create " + dir);
        }
        return dir;
    }

    /** @return the CSV for this track, or null when none is stored */
    public static File fileFor(Context context, Track.Id trackId) {
        if (context == null || trackId == null) {
            return null;
        }
        try {
            String name = fileName(context, trackId.id());
            if (!safeName(name)) {
                return null;
            }
            File file = new File(directory(context), name);
            if (!file.isFile()) {
                return null;
            }
            return file;
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not read the sensor file for track " + trackId.id(), e);
            return null;
        }
    }

    public static void upsert(Context context, Track.Id trackId, String fileName, long startedAtEpochMs) {
        if (context == null || trackId == null || !safeName(fileName)) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("track_id", trackId.id());
        values.put("file_name", fileName);
        values.put("started_at", startedAtEpochMs);
        long row = WorkoutDatabase.get(context).getWritableDatabase().insertWithOnConflict(
                WorkoutDatabase.TABLE_SENSOR_FILES,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE);
        if (row < 0) {
            Log.e(TAG, "Could not link sensor file for track " + trackId.id());
        }
    }

    public static void deleteForTracks(Context context, List<Track.Id> trackIds) {
        if (context == null || trackIds == null || trackIds.isEmpty()) {
            return;
        }
        File dir = directory(context);
        SQLiteDatabase db = WorkoutDatabase.get(context).getWritableDatabase();
        for (Track.Id trackId : trackIds) {
            if (trackId == null) {
                continue;
            }
            deleteNamedFiles(dir, trackId.id());
            db.delete(WorkoutDatabase.TABLE_SENSOR_FILES, "track_id=?", new String[]{Long.toString(trackId.id())});
        }
    }

    public static void deleteAll(Context context) {
        if (context == null) {
            return;
        }
        WorkoutDatabase.get(context).getWritableDatabase().delete(WorkoutDatabase.TABLE_SENSOR_FILES, null, null);
        File dir = new File(context.getFilesDir(), "sensors");
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!file.delete()) {
                Log.w(TAG, "Could not delete " + file.getName());
            }
        }
    }

    private static void deleteNamedFiles(File dir, long trackId) {
        String prefix = "track-" + trackId + "-";
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith(prefix) && name.endsWith(".csv") && !file.delete()) {
                Log.w(TAG, "Could not delete " + name);
            }
        }
    }

    private static String fileName(Context context, long trackId) {
        try (Cursor cursor = WorkoutDatabase.get(context).getReadableDatabase().query(
                WorkoutDatabase.TABLE_SENSOR_FILES,
                new String[]{"file_name"},
                "track_id=?",
                new String[]{Long.toString(trackId)},
                null,
                null,
                null)) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return cursor.getString(0);
        }
    }

    private static boolean safeName(String name) {
        return name != null
                && !name.isEmpty()
                && name.endsWith(".csv")
                && !name.contains("/")
                && !name.contains("\\")
                && !name.contains("..");
    }
}
