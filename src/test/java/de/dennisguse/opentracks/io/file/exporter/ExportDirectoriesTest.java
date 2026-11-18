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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import de.dennisguse.opentracks.data.models.ActivityType;

public class ExportDirectoriesTest {

    @Test
    public void runningGoesToRuns() {
        assertEquals("Runs", ExportDirectories.folderFor(ActivityType.RUNNING));
        assertEquals("Runs", ExportDirectories.folderFor(ActivityType.TRAIL_RUNNING));
        assertEquals("Runs", ExportDirectories.folderFor(ActivityType.STREET_RUNNING));
        assertEquals("Runs", ExportDirectories.folderFor(ActivityType.TRACK_RUNNING));
    }

    @Test
    public void everythingElseGoesToWalks() {
        assertEquals("Walks", ExportDirectories.folderFor(ActivityType.WALKING));
        assertEquals("Walks", ExportDirectories.folderFor(ActivityType.HIKING));
        assertEquals("Walks", ExportDirectories.folderFor(null));
    }

    @Test
    public void categoryFolderNames() {
        assertTrue(ExportDirectories.isCategoryFolder("Walks"));
        assertTrue(ExportDirectories.isCategoryFolder("runs"));
        assertFalse(ExportDirectories.isCategoryFolder("GrokDrop"));
        assertFalse(ExportDirectories.isCategoryFolder(null));
    }

    @Test
    public void parentAndSiblingDocumentIds() {
        assertEquals("primary:Sync/GrokDrop", ExportDirectories.parentDocumentId("primary:Sync/GrokDrop/Walks"));
        assertEquals("primary:", ExportDirectories.parentDocumentId("primary:Walks"));
        assertNull(ExportDirectories.parentDocumentId("primary:"));
        assertEquals("primary:Sync/GrokDrop/Runs", ExportDirectories.childDocumentId("primary:Sync/GrokDrop", "Runs"));
        assertEquals("primary:Runs", ExportDirectories.childDocumentId("primary:", "Runs"));
    }
}
