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
package de.dennisguse.opentracks.io.file.exporter;

import android.content.Context;
import android.content.UriPermission;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.documentfile.provider.DocumentFile;

import de.dennisguse.opentracks.data.models.ActivityType;
import de.dennisguse.opentracks.data.models.Track;
import de.dennisguse.opentracks.settings.PreferencesUtils;
import de.dennisguse.opentracks.util.IntentUtils;

/**
 * Picks the automatic-export folder for one track: running goes to "Runs", everything else to "Walks".
 * <p>
 * Export folder set to the parent (e.g. GrokDrop): Walks/ and Runs/ are found or created inside it.
 * Export folder set to Walks itself: runs go to the sibling Runs/ if the app has access to the parent
 * (or to Runs directly); otherwise they stay in Walks so nothing is ever lost.
 */
public final class ExportDirectories {

    private static final String TAG = ExportDirectories.class.getSimpleName();

    public static final String WALKS = "Walks";
    public static final String RUNS = "Runs";

    private ExportDirectories() {
    }

    @VisibleForTesting
    static boolean isRunning(@Nullable ActivityType activityType) {
        return activityType == ActivityType.RUNNING
                || activityType == ActivityType.STREET_RUNNING
                || activityType == ActivityType.TRACK_RUNNING
                || activityType == ActivityType.TRAIL_RUNNING;
    }

    @VisibleForTesting
    static String folderFor(@Nullable ActivityType activityType) {
        return isRunning(activityType) ? RUNS : WALKS;
    }

    @VisibleForTesting
    static boolean isCategoryFolder(@Nullable String name) {
        return WALKS.equalsIgnoreCase(name) || RUNS.equalsIgnoreCase(name);
    }

    /** @return the parent document id ("primary:Sync/GrokDrop/Walks" gives "primary:Sync/GrokDrop"), or null at a root. */
    @VisibleForTesting
    @Nullable
    static String parentDocumentId(@Nullable String documentId) {
        if (documentId == null) {
            return null;
        }
        String trimmed = documentId.endsWith("/") ? documentId.substring(0, documentId.length() - 1) : documentId;
        int slash = trimmed.lastIndexOf('/');
        if (slash >= 0) {
            return trimmed.substring(0, slash);
        }
        int colon = trimmed.indexOf(':');
        if (colon >= 0 && colon < trimmed.length() - 1) {
            return trimmed.substring(0, colon + 1);
        }
        return null;
    }

    @VisibleForTesting
    static String childDocumentId(String parentDocumentId, String name) {
        return parentDocumentId.endsWith(":") || parentDocumentId.endsWith("/") ? parentDocumentId + name : parentDocumentId + "/" + name;
    }

    /** Folder for the automatic export of this track; falls back to the chosen export folder. */
    @Nullable
    public static DocumentFile forTrack(Context context, @Nullable Track track) {
        DocumentFile base = IntentUtils.toDocumentFile(context, PreferencesUtils.getDefaultExportDirectoryUri());
        if (base == null || track == null) {
            return base;
        }
        try {
            return resolve(context, base, folderFor(track.activityType()));
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not pick a category folder; using the export folder", e);
            return base;
        }
    }

    private static DocumentFile resolve(Context context, DocumentFile base, String wanted) {
        String baseName = base.getName();
        if (!isCategoryFolder(baseName)) {
            DocumentFile sub = findOrCreateDirectory(base, wanted);
            return sub != null ? sub : base;
        }
        if (wanted.equalsIgnoreCase(baseName)) {
            return base;
        }

        String baseDocId = DocumentsContract.getTreeDocumentId(base.getUri());
        String parentDocId = parentDocumentId(baseDocId);
        if (parentDocId != null) {
            String siblingDocId = childDocumentId(parentDocId, wanted);
            for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
                if (!permission.isWritePermission()) {
                    continue;
                }
                Uri tree = permission.getUri();
                if (!base.getUri().getAuthority().equals(tree.getAuthority())) {
                    continue;
                }
                String treeDocId;
                try {
                    treeDocId = DocumentsContract.getTreeDocumentId(tree);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (siblingDocId.equals(treeDocId)) {
                    DocumentFile sibling = DocumentFile.fromTreeUri(context, tree);
                    if (sibling != null && sibling.canWrite()) {
                        return sibling;
                    }
                }
                if (parentDocId.equals(treeDocId)) {
                    DocumentFile parent = DocumentFile.fromTreeUri(context, tree);
                    DocumentFile sibling = parent == null ? null : findOrCreateDirectory(parent, wanted);
                    if (sibling != null) {
                        return sibling;
                    }
                }
            }
        }
        Log.w(TAG, "No access to the " + wanted + " folder next to " + baseName + "; exporting to " + baseName
                + ". Choose the parent folder as export folder to split walks and runs.");
        return base;
    }

    @Nullable
    private static DocumentFile findOrCreateDirectory(DocumentFile parent, String name) {
        DocumentFile existing = parent.findFile(name);
        if (existing != null) {
            return existing.isDirectory() ? existing : null;
        }
        DocumentFile created = parent.createDirectory(name);
        if (created == null) {
            Log.w(TAG, "Could not create folder " + name);
        }
        return created;
    }
}
