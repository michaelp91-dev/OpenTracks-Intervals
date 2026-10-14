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
package de.dennisguse.opentracks.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StartCountdownTest {

    @Test
    public void fiveSecondsThenGo() {
        StartCountdown countdown = new StartCountdown(5);

        assertEquals("5", countdown.begin());
        assertEquals("4", countdown.tick());
        assertEquals("3", countdown.tick());
        assertEquals("2", countdown.tick());
        assertEquals("1", countdown.tick());
        assertFalse(countdown.isFinished());
        assertEquals(StartCountdown.GO, countdown.tick());
        assertTrue(countdown.isFinished());
        assertNull(countdown.tick());
    }

    @Test
    public void oneSecondThenGo() {
        StartCountdown countdown = new StartCountdown(1);

        assertEquals("1", countdown.begin());
        assertEquals(StartCountdown.GO, countdown.tick());
        assertTrue(countdown.isFinished());
        assertNull(countdown.tick());
    }

    @Test
    public void zeroIsNotACountdown() {
        assertThrows(IllegalArgumentException.class, () -> new StartCountdown(0));
    }
}
