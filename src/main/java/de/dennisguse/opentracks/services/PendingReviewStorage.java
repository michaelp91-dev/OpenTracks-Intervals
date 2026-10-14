package de.dennisguse.opentracks.services;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import de.dennisguse.opentracks.data.models.Track;

/**
 * Track that was stopped automatically (finished workout) and still needs to be reviewed (name, activity type, description) in TrackStoppedActivity.
 * Its file export is postponed until the review is done.
 */
public class PendingReviewStorage {

    private static final String SHARED_PREFERENCE_NAME = "PendingReview_pref";
    private static final String KEY = "trackId_key";

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(SHARED_PREFERENCE_NAME, Context.MODE_PRIVATE);
    }

    @Nullable
    public static Track.Id get(Context context) {
        long value = prefs(context).getLong(KEY, -1);
        return value < 0 ? null : new Track.Id(value);
    }

    public static void set(Context context, @NonNull Track.Id trackId) {
        prefs(context).edit().putLong(KEY, trackId.id()).commit();
    }

    public static void unset(Context context) {
        prefs(context).edit().remove(KEY).commit();
    }
}
