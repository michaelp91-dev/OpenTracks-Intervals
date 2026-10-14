package de.dennisguse.opentracks;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;

import de.dennisguse.opentracks.data.ContentProviderUtils;
import de.dennisguse.opentracks.data.models.ActivityType;
import de.dennisguse.opentracks.data.models.DistanceFormatter;
import de.dennisguse.opentracks.data.models.SpeedFormatter;
import de.dennisguse.opentracks.data.models.Track;
import de.dennisguse.opentracks.data.models.TrackBuilder;
import de.dennisguse.opentracks.databinding.TrackStoppedBinding;
import de.dennisguse.opentracks.fragments.ChooseActivityTypeDialogFragment;
import de.dennisguse.opentracks.io.file.exporter.ExportUtils;
import de.dennisguse.opentracks.io.healthconnect.exporter.HealthConnectUtils;
import de.dennisguse.opentracks.services.TrackRecordingServiceConnection;
import de.dennisguse.opentracks.settings.PreferencesUtils;
import de.dennisguse.opentracks.ui.aggregatedStatistics.ConfirmDeleteDialogFragment;
import de.dennisguse.opentracks.util.IntentUtils;
import de.dennisguse.opentracks.util.StringUtils;

public class TrackStoppedActivity extends AbstractTrackDeleteActivity implements ChooseActivityTypeDialogFragment.ChooseActivityTypeCaller {

    private static final String TAG = TrackStoppedActivity.class.getSimpleName();

    public static final String EXTRA_TRACK_ID = "track_id";

    private TrackStoppedBinding viewBinding;

    private Track.Id trackId;

    private boolean handled = false;

    private static int foregroundActivities = 0;
    private static boolean showing = false;

    /** Called by activities in onResume/onPause to know whether the app is in the foreground. */
    public static void setAppForeground(boolean resumed) {
        foregroundActivities = Math.max(0, foregroundActivities + (resumed ? 1 : -1));
    }

    public static void showPendingReviewIfForeground(android.content.Context context, Intent intent) {
        if (foregroundActivities > 0 && !showing) {
            context.startActivity(intent);
        }
    }

    /** Opens the review screen for a workout that finished automatically (if any). */
    public static void showPendingReview(android.app.Activity activity) {
        Track.Id pending = de.dennisguse.opentracks.services.PendingReviewStorage.get(activity);
        if (pending == null || showing) {
            return;
        }
        if (new ContentProviderUtils(activity).getTrack(pending) == null) {
            de.dennisguse.opentracks.services.PendingReviewStorage.unset(activity);
            return;
        }
        activity.startActivity(IntentUtils.newIntent(activity, TrackStoppedActivity.class)
                .putExtra(EXTRA_TRACK_ID, pending));
    }

    private boolean isPendingReview() {
        return trackId != null && trackId.equals(de.dennisguse.opentracks.services.PendingReviewStorage.get(this));
    }

    /** Export everything (KMZ, readings CSV, splits CSV) with the final track name. */
    private void exportFinal() {
        handled = true;
        de.dennisguse.opentracks.services.PendingReviewStorage.unset(this);
        de.dennisguse.opentracks.workout.WorkoutSplitsExporter.export(this, trackId);
        ExportUtils.postWorkoutExport(this, trackId);
        HealthConnectUtils.postExportTrack(this, trackId);
    }

    @Override
    protected void onResume() {
        super.onResume();
        setAppForeground(true);
    }

    @Override
    protected void onPause() {
        setAppForeground(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (!handled && isFinishing() && isPendingReview() && new ContentProviderUtils(this).getTrack(trackId) != null) {
            // Left without pressing finish: keep the automatic name but still save the files.
            handled = true;
            de.dennisguse.opentracks.services.PendingReviewStorage.unset(this);
            de.dennisguse.opentracks.workout.WorkoutSplitsExporter.export(this, trackId);
            ExportUtils.postTrackExport(this, trackId);
        }
        showing = false;
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showing = true;

        trackId = getIntent().getParcelableExtra(EXTRA_TRACK_ID);
        if (trackId == null) {
            Log.e(TAG, "TrackStoppedActivity needs EXTRA_TRACK_ID.");
            finish();
        }

        ContentProviderUtils contentProviderUtils = new ContentProviderUtils(this);
        Track track = contentProviderUtils.getTrack(trackId);

        viewBinding.trackEditName.setText(track.name());

        viewBinding.trackEditActivityType.setText(track.activityTypeLocalized());

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, ActivityType.getLocalizedStrings(this));
        viewBinding.trackEditActivityType.setAdapter(adapter);
        viewBinding.trackEditActivityType.setOnItemClickListener((parent, view, position, id) -> {
            String localizedActivityType = (String) viewBinding.trackEditActivityType.getAdapter().getItem(position);
            setActivityTypeIcon(ActivityType.findByLocalizedString(this, localizedActivityType));
        });
        viewBinding.trackEditActivityType.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                String localizedActivityType = viewBinding.trackEditActivityType.getText().toString();
                setActivityTypeIcon(ActivityType.findByLocalizedString(this, localizedActivityType));
            }
        });

        setActivityTypeIcon(track.activityType());
        viewBinding.trackEditActivityTypeIcon.setOnClickListener(v -> ChooseActivityTypeDialogFragment.showDialog(getSupportFragmentManager(), this, viewBinding.trackEditActivityType.getText().toString()));

        viewBinding.trackEditDescription.setText(track.description());

        viewBinding.time.setText(StringUtils.formatElapsedTime(track.statistics().movingDuration()));

        java.util.List<de.dennisguse.opentracks.workout.PocketDetector.Event> pocketEvents = de.dennisguse.opentracks.workout.PocketStore.load(this, trackId);
        if (pocketEvents != null) {
            viewBinding.outOfPocket.setText(de.dennisguse.opentracks.workout.PocketDetector.summary(pocketEvents));
            viewBinding.outOfPocket.setVisibility(android.view.View.VISIBLE);
        }

        {
            Pair<String, String> parts = SpeedFormatter.Builder()
                    .setUnit(PreferencesUtils.getUnitSystem())
                    .setReportSpeedOrPace(PreferencesUtils.isReportSpeed(track))
                    .build(this)
                    .getSpeedParts(track.statistics().getAverageMovingSpeed());
            viewBinding.speed.setText(parts.first);
            viewBinding.speedUnit.setText(parts.second);
        }

        {
            Pair<String, String> parts = DistanceFormatter.Builder()
                    .setUnit(PreferencesUtils.getUnitSystem())
                    .build(this)
                    .getDistanceParts(track.statistics().totalDistance());
            viewBinding.distance.setText(parts.first);
            viewBinding.distanceUnit.setText(parts.second);
        }

        viewBinding.finishButton.setOnClickListener(v -> {
            storeTrackMetaData(contentProviderUtils, track);
            exportFinal();
            finish();
        });

        viewBinding.resumeButton.setOnClickListener(v -> {
            storeTrackMetaData(contentProviderUtils, track);
            handled = true;
            de.dennisguse.opentracks.services.PendingReviewStorage.unset(this);
            resumeTrackAndFinish();
        });

        viewBinding.discardButton.setOnClickListener(v -> ConfirmDeleteDialogFragment.showDialog(getSupportFragmentManager(), trackId));
    }

    private void storeTrackMetaData(ContentProviderUtils contentProviderUtils, Track track) {
        TrackBuilder trackBuilder = new TrackBuilder(track);
        trackBuilder.setName(viewBinding.trackEditName.getText().toString());
        trackBuilder.setDescription(viewBinding.trackEditDescription.getText().toString());
        trackBuilder.setActivityTypeLocalizedAndUpdateActivityType(this, viewBinding.trackEditActivityType.getText().toString());

        contentProviderUtils.updateTrack(trackBuilder.getTrack());
    }

    @NonNull
    @Override
    protected View createRootView() {
        viewBinding = TrackStoppedBinding.inflate(getLayoutInflater());
        return viewBinding.getRoot();
    }

    private void setActivityTypeIcon(ActivityType activityType) {
        viewBinding.trackEditActivityTypeIcon.setImageResource(activityType.getIconDrawableId());
    }

    @Override
    public void onChooseActivityTypeDone(ActivityType activityType) {
        setActivityTypeIcon(activityType);
        viewBinding.trackEditActivityType.setText(getString(activityType.getLocalizedStringId()));
    }

    private void resumeTrackAndFinish() {
        TrackRecordingServiceConnection.executeForeground(this, (service, connection) -> {
            service.resumeTrack(trackId);

            Intent newIntent = IntentUtils.newIntent(TrackStoppedActivity.this, TrackRecordingActivity.class)
                    .putExtra(TrackRecordingActivity.EXTRA_TRACK_ID, trackId);
            startActivity(newIntent);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);

            finish();
        });
    }

    @Override
    protected void onDeleteConfirmed() {
        viewBinding.loadingLayout.loadingText.setText(getString(R.string.track_discarding));
        viewBinding.contentLinearLayout.setVisibility(View.GONE);
        viewBinding.loadingLayout.loadingIndeterminate.setVisibility(View.VISIBLE);
    }

    @Override
    public void onDeleteFinished() {
        handled = true;
        de.dennisguse.opentracks.services.PendingReviewStorage.unset(this);
        finish();
    }

    @Override
    protected Track.Id getRecordingTrackId() {
        return null;
    }
}
