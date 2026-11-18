/*
 * Modified by Michael Puckett, 2026
 */
package de.dennisguse.opentracks.io.file.exporter;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import de.dennisguse.opentracks.data.ContentProviderUtils;
import de.dennisguse.opentracks.data.models.Track;
import de.dennisguse.opentracks.sensors.raw.SensorLogStore;
import de.dennisguse.opentracks.io.file.TrackFileFormat;
import de.dennisguse.opentracks.io.file.TrackFilenameGenerator;
import de.dennisguse.opentracks.settings.PreferencesUtils;
import de.dennisguse.opentracks.settings.SettingsActivity;
import de.dennisguse.opentracks.util.IntentUtils;

public class ExportUtils {

    private static final String TAG = ExportUtils.class.getSimpleName();

    public static void postWorkoutExport(AppCompatActivity context, Track.Id trackId) {
        WorkRequest exportRequest = enqueueTrackExport(context, trackId);
        if (exportRequest == null) {
            return;
        }
        WorkManager.getInstance(context)
                .getWorkInfoByIdLiveData(exportRequest.getId())
                .observe(context, workInfo -> {
                    if (workInfo != null) {
                        if (workInfo.getState() == WorkInfo.State.FAILED) {
                            Intent intent = new Intent(context, SettingsActivity.class);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            intent.putExtra(SettingsActivity.EXTRAS_EXPORT_ERROR_MESSAGE, workInfo.getProgress().getString(ExportWorker.RESULT_EXPORT_ERROR_MESSAGE_KEY));
                            context.startActivity(intent);
                        }
                    }
                });
    }

    /**
     * Same as {@link #postWorkoutExport(AppCompatActivity, Track.Id)} but without UI (e.g., from the recording service).
     */
    public static void postTrackExport(Context context, Track.Id trackId) {
        enqueueTrackExport(context, trackId);
    }

    @Nullable
    private static WorkRequest enqueueTrackExport(Context context, Track.Id trackId) {
        if (!PreferencesUtils.shouldInstantExportToFile()) {
            return null;
        }
        TrackFileFormat trackFileFormat = PreferencesUtils.getExportTrackFileFormat();
        // Running goes to Runs, walking to Walks (next to each other in the chosen export folder).
        DocumentFile directory = ExportDirectories.forTrack(context, new ContentProviderUtils(context).getTrack(trackId));
        if (directory == null) {
            Log.w(TAG, "No export directory; track was not exported");
            return null;
        }

        WorkRequest exportRequest = new OneTimeWorkRequest.Builder(ExportWorker.class)
                .setInputData(new Data.Builder()
                        .putLongArray(ExportWorker.TRACKIDS_KEY, new long[]{trackId.id()})
                        .putString(ExportWorker.DIRECTORY_URI_KEY, directory.getUri().toString())
                        .putString(ExportWorker.TRACKFILEFORMAT_KEY, trackFileFormat.toString())
                        .putString(ExportWorker.FILENAME_KEY, null)
                        .build())
                .build();
        WorkManager.getInstance(context).enqueue(exportRequest);
        return exportRequest;
    }

    public static void exportTrack(Context context, DocumentFile directory, @Nullable String filenameForMultiple, TrackFileFormat trackFileFormat, List<Track.Id> trackIds) {

        ContentProviderUtils contentProviderUtils = new ContentProviderUtils(context);
        List<Track> tracks = trackIds.stream()
                .map(contentProviderUtils::getTrack)
                .toList();

        Uri exportDocumentFileUri;
        if (tracks.size() == 1) {
            exportDocumentFileUri = getExportDocumentFileUri(context, tracks.get(0), trackFileFormat, directory);
        } else {
            String filename = TrackFilenameGenerator.format(filenameForMultiple, trackFileFormat);
            exportDocumentFileUri = getExportDocumentFileUri(context, filename, trackFileFormat, directory);
        }

        if (exportDocumentFileUri == null) {
            throw new RuntimeException("Couldn't create document file for export");
        }

        TrackExporter trackExporter = trackFileFormat.createTrackExporter(context, contentProviderUtils);
        try (OutputStream outputStream = context.getContentResolver().openOutputStream(exportDocumentFileUri, "wt")) {
            if (!trackExporter.writeTrack(tracks, outputStream)) {
                if (!DocumentFile.fromSingleUri(context, exportDocumentFileUri).delete()) {
                    throw new RuntimeException("Unable to delete exportDocumentFile");
                }
                throw new RuntimeException("Unable to export track");
            }
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Unable to open exportDocumentFile " + exportDocumentFileUri, e);
        } catch (IOException e) {
            throw new RuntimeException("Unable to close exportDocumentFile output stream", e);
        }
        exportSensorFiles(context, directory, trackIds);
    }

    @Deprecated
    public static void exportTrack(Context context, DocumentFile directory, ExportTask exportTask) {
        ContentProviderUtils contentProviderUtils = new ContentProviderUtils(context);
        List<Track> tracks = exportTask.getTrackIds().stream()
                .map(contentProviderUtils::getTrack)
                .toList();
        Uri exportDocumentFileUri;
        if (tracks.size() == 1) {
            exportDocumentFileUri = getExportDocumentFileUri(context, tracks.get(0), exportTask.getTrackFileFormat(), directory);
        } else {
            String filename = TrackFilenameGenerator.format(exportTask.getFilename(), exportTask.getTrackFileFormat());
            exportDocumentFileUri = getExportDocumentFileUri(context, filename, exportTask.getTrackFileFormat(), directory);
        }

        if (exportDocumentFileUri == null) {
            throw new RuntimeException("Couldn't create document file for export");
        }

        TrackExporter trackExporter = exportTask.getTrackFileFormat().createTrackExporter(context, contentProviderUtils);
        try (OutputStream outputStream = context.getContentResolver().openOutputStream(exportDocumentFileUri, "wt")) {
            if (!trackExporter.writeTrack(tracks, outputStream)) {
                if (!DocumentFile.fromSingleUri(context, exportDocumentFileUri).delete()) {
                    throw new RuntimeException("Unable to delete exportDocumentFile");
                }
                throw new RuntimeException("Unable to export track");
            }
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Unable to open exportDocumentFile " + exportDocumentFileUri, e);
        } catch (IOException e) {
            throw new RuntimeException("Unable to close exportDocumentFile output stream", e);
        }
        exportSensorFiles(context, directory, exportTask.getTrackIds());
    }

    public static List<String> getAllFiles(Context context, Uri directoryUri) {
        List<String> fileNames = new ArrayList<>();
        final ContentResolver resolver = context.getContentResolver();
        final Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(directoryUri, DocumentsContract.getDocumentId(directoryUri));

        try (Cursor c = resolver.query(childrenUri, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            while (c.moveToNext()) {
                fileNames.add(c.getString(0));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed query: " + e);
        }

        return fileNames;
    }

    private static Uri getExportDocumentFileUri(Context context, Track track, TrackFileFormat trackFileFormat, DocumentFile directory) {
        String exportFileName = PreferencesUtils.getTrackFileformatGenerator().format(track, trackFileFormat);
        return getExportDocumentFileUri(context, exportFileName, trackFileFormat, directory);
    }

    private static Uri getExportDocumentFileUri(Context context, String exportFileName, TrackFileFormat trackFileFormat, DocumentFile directory) {
        Uri exportDocumentFileUri = findFile(context, directory.getUri(), exportFileName);
        if (exportDocumentFileUri == null) {
            final DocumentFile file = directory.createFile(trackFileFormat.getMimeType(), exportFileName);
            if (file != null) {
                exportDocumentFileUri = file.getUri();
            }
        }
        return exportDocumentFileUri;
    }

    private static Uri findFile(Context context, Uri directoryUri, String exportFileName) {
        final ContentResolver resolver = context.getContentResolver();
        final Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(directoryUri, DocumentsContract.getDocumentId(directoryUri));

        try (Cursor c = resolver.query(childrenUri, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {

            while (c.moveToNext()) {
                final String documentId = c.getString(0);
                final String documentName = c.getString(1);
                if (documentName.equals(exportFileName)) {
                    return DocumentsContract.buildDocumentUriUsingTree(directoryUri, documentId);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Find file error: failed query: " + e);
        }

        return null;
    }

    /**
     * Copies each track's sensor CSV into the same folder as the exported track file.
     * A missing sensor file is skipped. A copy failure does not fail the track export.
     */
    private static void exportSensorFiles(Context context, DocumentFile directory, List<Track.Id> trackIds) {
        if (directory == null || trackIds == null) {
            return;
        }
        ContentProviderUtils contentProviderUtils = new ContentProviderUtils(context);
        TrackFilenameGenerator generator = PreferencesUtils.getTrackFileformatGenerator();
        for (Track.Id trackId : trackIds) {
            File source = SensorLogStore.fileFor(context, trackId);
            if (source == null) {
                continue;
            }
            Track track = contentProviderUtils.getTrack(trackId);
            if (track == null) {
                Log.w(TAG, "Track " + trackId.id() + " is missing; sensor file was not exported");
                continue;
            }
            String exportName = generator.readingsFilename(track);
            Uri dest = findFile(context, directory.getUri(), exportName);
            if (dest == null) {
                DocumentFile created = directory.createFile("text/csv", exportName);
                if (created == null) {
                    Log.w(TAG, "Could not create sensor export " + exportName);
                    continue;
                }
                dest = created.getUri();
            }
            try (InputStream in = new FileInputStream(source); OutputStream out = context.getContentResolver().openOutputStream(dest, "wt")) {
                if (out == null) {
                    Log.w(TAG, "Could not open sensor export " + exportName);
                    continue;
                }
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            } catch (IOException e) {
                Log.w(TAG, "Could not export sensor file " + exportName, e);
            }
        }
    }
}
