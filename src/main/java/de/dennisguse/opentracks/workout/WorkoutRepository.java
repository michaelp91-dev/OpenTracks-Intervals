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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import de.dennisguse.opentracks.data.models.Track;

public final class WorkoutRepository {

    private static final String TAG = "WorkoutRepository";

    private static WorkoutRepository instance;

    private final WorkoutDatabase helper;

    public static synchronized WorkoutRepository get(Context context) {
        if (instance == null) {
            instance = new WorkoutRepository(context.getApplicationContext());
        }
        return instance;
    }

    private WorkoutRepository(Context context) {
        helper = WorkoutDatabase.get(context);
    }

    public synchronized List<Workout> listWorkouts() {
        SQLiteDatabase db = helper.getReadableDatabase();
        List<Long> ids = new ArrayList<>();
        try (Cursor cursor = db.query(WorkoutDatabase.TABLE_WORKOUTS, new String[]{"_id"}, null, null, null, null, WorkoutOrder.ORDER_BY)) {
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(0));
            }
        }
        List<Workout> workouts = new ArrayList<>();
        for (Long id : ids) {
            Workout workout = getWorkout(id);
            if (workout != null) {
                workouts.add(workout);
            }
        }
        return workouts;
    }

    public synchronized Workout getWorkout(long id) {
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor cursor = db.query(WorkoutDatabase.TABLE_WORKOUTS, new String[]{"_id", "name", "builtin", "distance_m"}, "_id=?", new String[]{Long.toString(id)}, null, null, null)) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            String name = cursor.getString(1);
            boolean builtin = cursor.getInt(2) != 0;
            return new Workout(cursor.getLong(0), name, builtin, loadItems(db, id), Math.max(0, cursor.getInt(3)));
        }
    }

    /**
     * Inserts when {@code workout.id() <= 0}. Replaces segments of an existing workout.
     * The built-in flag of an existing row is left unchanged.
     *
     * @return the stored id
     */
    public synchronized long saveWorkout(Workout workout) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            long id = workout.id();
            ContentValues values = new ContentValues();
            values.put("name", workout.name());
            values.put("distance_m", workout.distanceMeters());
            if (id <= 0) {
                values.put("builtin", workout.builtin() ? 1 : 0);
                values.put("sort_order", WorkoutOrder.next(maxSortOrder(db)));
                id = db.insertOrThrow(WorkoutDatabase.TABLE_WORKOUTS, null, values);
            } else {
                db.update(WorkoutDatabase.TABLE_WORKOUTS, values, "_id=?", new String[]{Long.toString(id)});
                db.delete(WorkoutDatabase.TABLE_SEGMENTS, "workout_id=?", new String[]{Long.toString(id)});
            }
            WorkoutDatabase.insertItems(db, id, workout.items());
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    /** Stores the Workouts list order; {@code ids} is the full list, top first. */
    public synchronized void saveOrder(List<Long> ids) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < ids.size(); i++) {
                ContentValues values = new ContentValues();
                values.put("sort_order", i);
                db.update(WorkoutDatabase.TABLE_WORKOUTS, values, "_id=?", new String[]{Long.toString(ids.get(i))});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static Long maxSortOrder(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("SELECT MAX(sort_order) FROM " + WorkoutDatabase.TABLE_WORKOUTS, null)) {
            if (!cursor.moveToFirst() || cursor.isNull(0)) {
                return null;
            }
            return cursor.getLong(0);
        }
    }

    /** @return false when the workout is missing or built-in */
    public synchronized boolean deleteWorkout(long id) {
        Workout workout = getWorkout(id);
        if (workout == null || workout.builtin()) {
            return false;
        }
        helper.getWritableDatabase().delete(WorkoutDatabase.TABLE_WORKOUTS, "_id=?", new String[]{Long.toString(id)});
        return true;
    }

    public synchronized void resetPreset(long id) {
        Workout existing = getWorkout(id);
        if (existing == null || !existing.builtin()) {
            return;
        }
        Workout preset = WorkoutPresets.c25kWeek1();
        saveWorkout(new Workout(id, preset.name(), true, preset.items()));
    }

    public synchronized void insertTrackSegment(Track.Id trackId, String workoutName, int position, SegmentType type, Instant start, Instant end, boolean completed) {
        ContentValues values = new ContentValues();
        values.put("track_id", trackId.id());
        values.put("workout_name", workoutName);
        values.put("position", position);
        values.put("type", type.name());
        values.put("start_time", start.toEpochMilli());
        values.put("end_time", end.toEpochMilli());
        values.put("completed", completed ? 1 : 0);
        try {
            helper.getWritableDatabase().insertOrThrow(WorkoutDatabase.TABLE_TRACK_SEGMENTS, null, values);
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not store track segment " + position, e);
        }
    }

    public synchronized List<RecordedSegment> getTrackSegments(Track.Id trackId) {
        List<RecordedSegment> segments = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().query(
                WorkoutDatabase.TABLE_TRACK_SEGMENTS,
                new String[]{"position", "type", "start_time", "end_time", "completed"},
                "track_id=?",
                new String[]{Long.toString(trackId.id())},
                null,
                null,
                "position ASC, _id ASC")) {
            while (cursor.moveToNext()) {
                segments.add(new RecordedSegment(
                        cursor.getInt(0),
                        SegmentType.fromDatabase(cursor.getString(1)),
                        Instant.ofEpochMilli(cursor.getLong(2)),
                        Instant.ofEpochMilli(cursor.getLong(3)),
                        cursor.getInt(4) != 0
                ));
            }
        }
        return segments;
    }

    public synchronized String getRecordedWorkoutName(Track.Id trackId) {
        if (trackId == null) {
            return null;
        }
        try (Cursor cursor = helper.getReadableDatabase().query(
                WorkoutDatabase.TABLE_TRACK_SEGMENTS,
                new String[]{"workout_name"},
                "track_id=?",
                new String[]{Long.toString(trackId.id())},
                null,
                null,
                "position ASC, _id ASC",
                "1")) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return cursor.getString(0);
        }
    }

    public synchronized boolean hasWorkout(Track.Id trackId) {
        if (trackId == null) {
            return false;
        }
        try (Cursor cursor = helper.getReadableDatabase().query(
                WorkoutDatabase.TABLE_TRACK_SEGMENTS,
                new String[]{"_id"},
                "track_id=?",
                new String[]{Long.toString(trackId.id())},
                null,
                null,
                null,
                "1")) {
            return cursor.moveToFirst();
        }
    }

    public synchronized void deleteForTracks(List<Track.Id> trackIds) {
        if (trackIds == null || trackIds.isEmpty()) {
            return;
        }
        StringBuilder placeholders = new StringBuilder();
        String[] args = new String[trackIds.size()];
        for (int i = 0; i < trackIds.size(); i++) {
            if (i > 0) {
                placeholders.append(',');
            }
            placeholders.append('?');
            args[i] = Long.toString(trackIds.get(i).id());
        }
        helper.getWritableDatabase().delete(WorkoutDatabase.TABLE_TRACK_SEGMENTS, "track_id IN (" + placeholders + ")", args);
    }

    public synchronized void deleteAllTrackSegments() {
        helper.getWritableDatabase().delete(WorkoutDatabase.TABLE_TRACK_SEGMENTS, null, null);
    }

    private static List<WorkoutItem> loadItems(SQLiteDatabase db, long workoutId) {
        List<WorkoutRows.Row> rows = new ArrayList<>();
        try (Cursor cursor = db.query(
                WorkoutDatabase.TABLE_SEGMENTS,
                new String[]{"type", "duration_s", "block_id", "block_rounds"},
                "workout_id=?",
                new String[]{Long.toString(workoutId)},
                null,
                null,
                "position ASC, _id ASC")) {
            while (cursor.moveToNext()) {
                rows.add(new WorkoutRows.Row(
                        SegmentType.fromDatabase(cursor.getString(0)),
                        cursor.getLong(1),
                        cursor.isNull(2) ? null : cursor.getInt(2),
                        cursor.isNull(3) ? null : cursor.getInt(3)
                ));
            }
        }
        return WorkoutRows.fromRows(rows);
    }
}
