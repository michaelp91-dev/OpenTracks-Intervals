package de.dennisguse.opentracks.services.announcement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

public class PromptPlaybackQueueTest {

    @Test
    public void playsInRequestOrderEvenIfLaterOneIsReadyFirst() {
        PromptPlaybackQueue<String> q = new PromptPlaybackQueue<>();
        q.request("a");
        q.request("b");
        assertTrue(q.ready("b", "b.wav"));
        assertNull(q.next());
        assertTrue(q.ready("a", "a.wav"));
        Map.Entry<String, String> first = q.next();
        assertEquals("a", first.getKey());
        assertNull(q.next());
        assertTrue(q.finished("a"));
        assertEquals("b", q.next().getKey());
        assertTrue(q.finished("b"));
        assertTrue(q.isEmpty());
    }

    @Test
    public void flushDropsPendingAndLateAudioIsDiscarded() {
        PromptPlaybackQueue<String> q = new PromptPlaybackQueue<>();
        q.request("a");
        q.request("b");
        assertTrue(q.ready("a", "a.wav"));
        assertEquals(2, q.flush().size());
        assertFalse(q.ready("b", "b.wav"));
        assertNull(q.next());
        assertTrue(q.isEmpty());
    }

    @Test
    public void cancelledPromptDoesNotBlockTheNext() {
        PromptPlaybackQueue<String> q = new PromptPlaybackQueue<>();
        q.request("a");
        q.request("b");
        q.ready("b", "b.wav");
        assertTrue(q.cancel("a"));
        assertFalse(q.cancel("a"));
        assertEquals("b", q.next().getKey());
    }
}
