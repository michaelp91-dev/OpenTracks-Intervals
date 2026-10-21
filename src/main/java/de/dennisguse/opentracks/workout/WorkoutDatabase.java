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
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.List;

/**
 * Separate from upstream {@code database.db} so track migrations stay untouched.
 */
public final class WorkoutDatabase extends SQLiteOpenHelper {

    static final String NAME = "workouts.db";
    /**
     * 2: sensor file table. 3: repeat blocks ({@code block_id}, {@code block_rounds} on segments).
     * 4: user order of the Workouts list ({@code sort_order} on workouts).
     * 5: distance workouts ({@code distance_m} on workouts, 0 = timed).
     */
    private static final int VERSION = 5;

    static final String TABLE_WORKOUTS = "workouts";
    static final String TABLE_SEGMENTS = "workout_segments";
    static final String TABLE_TRACK_SEGMENTS = "track_segments";
    public static final String TABLE_SENSOR_FILES = "track_sensor_files";

    private static final String CREATE_SENSOR_FILES = "CREATE TABLE IF NOT EXISTS " + TABLE_SENSOR_FILES + " ("
            + "track_id INTEGER PRIMARY KEY,"
            + "file_name TEXT NOT NULL,"
            + "started_at INTEGER NOT NULL)";

    private static WorkoutDatabase instance;

    public static synchronized WorkoutDatabase get(Context context) {
        if (instance == null) {
            instance = new WorkoutDatabase(context.getApplicationContext());
        }
        return instance;
    }

    private WorkoutDatabase(Context context) {
        super(context, NAME, null, VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_WORKOUTS + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL,"
                + "builtin INTEGER NOT NULL DEFAULT 0,"
                + "sort_order INTEGER NOT NULL DEFAULT 0,"
                + "distance_m INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE " + TABLE_SEGMENTS + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "workout_id INTEGER NOT NULL REFERENCES " + TABLE_WORKOUTS + "(_id) ON DELETE CASCADE,"
                + "position INTEGER NOT NULL,"
                + "type TEXT NOT NULL CHECK(type IN ('WARMUP','RUN','WALK','COOLDOWN')),"
                + "duration_s INTEGER NOT NULL,"
                + "block_id INTEGER,"
                + "block_rounds INTEGER)");
        db.execSQL("CREATE TABLE " + TABLE_TRACK_SEGMENTS + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "track_id INTEGER NOT NULL,"
                + "workout_name TEXT NOT NULL,"
                + "position INTEGER NOT NULL,"
                + "type TEXT NOT NULL,"
                + "start_time INTEGER NOT NULL,"
                + "end_time INTEGER NOT NULL,"
                + "completed INTEGER NOT NULL DEFAULT 1)");
        db.execSQL("CREATE INDEX track_segments_track_id_index ON " + TABLE_TRACK_SEGMENTS + "(track_id)");
        db.execSQL(CREATE_SENSOR_FILES);
        seed(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL(CREATE_SENSOR_FILES);
        }
        if (oldVersion < 3) {
            // Existing rows get NULL, which means a single segment, so saved workouts play exactly as before.
            db.execSQL("ALTER TABLE " + TABLE_SEGMENTS + " ADD COLUMN block_id INTEGER");
            db.execSQL("ALTER TABLE " + TABLE_SEGMENTS + " ADD COLUMN block_rounds INTEGER");
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE " + TABLE_WORKOUTS + " ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0");
            db.execSQL(WorkoutOrder.MIGRATE_SQL);
        }
        if (oldVersion < 5) {
            // Existing workouts get 0 = timed, so they play exactly as before.
            db.execSQL("ALTER TABLE " + TABLE_WORKOUTS + " ADD COLUMN distance_m INTEGER NOT NULL DEFAULT 0");
        }
    }

    private static void seed(SQLiteDatabase db) {
        Workout preset = WorkoutPresets.c25kWeek1();
        ContentValues values = new ContentValues();
        values.put("name", preset.name());
        values.put("builtin", 1);
        values.put("sort_order", 0);
        long id = db.insertOrThrow(TABLE_WORKOUTS, null, values);
        insertItems(db, id, preset.items());
    }

    static void insertItems(SQLiteDatabase db, long workoutId, List<WorkoutItem> items) {
        int position = 0;
        for (WorkoutRows.Row row : WorkoutRows.toRows(items)) {
            ContentValues segmentValues = new ContentValues();
            segmentValues.put("workout_id", workoutId);
            segmentValues.put("position", position++);
            segmentValues.put("type", row.type().name());
            segmentValues.put("duration_s", row.durationSeconds());
            if (row.blockId() == null) {
                segmentValues.putNull("block_id");
                segmentValues.putNull("block_rounds");
            } else {
                segmentValues.put("block_id", row.blockId());
                segmentValues.put("block_rounds", row.blockRounds());
            }
            db.insertOrThrow(TABLE_SEGMENTS, null, segmentValues);
        }
    }
}
