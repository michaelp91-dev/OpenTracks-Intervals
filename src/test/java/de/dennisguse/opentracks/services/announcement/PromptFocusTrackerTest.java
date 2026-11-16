package de.dennisguse.opentracks.services.announcement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class PromptFocusTrackerTest {

    private static class FakeFocus implements PromptFocusTracker.Focus {
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger abandons = new AtomicInteger();
        volatile boolean grant = true;
        /** Models the system: true while our focus request is active. */
        volatile boolean held;

        @Override
        public boolean request() {
            requests.incrementAndGet();
            if (grant) {
                held = true;
            }
            return grant;
        }

        @Override
        public void abandon() {
            abandons.incrementAndGet();
            held = false;
        }
    }

    private FakeFocus focus;
    private PromptFocusTracker tracker;

    @Before
    public void setUp() {
        focus = new FakeFocus();
        tracker = new PromptFocusTracker(focus);
    }

    @Test
    public void focusRequestedBeforeAndReleasedAfterPrompt() {
        tracker.begin("a", true);
        assertTrue(focus.held);
        tracker.finish("a");
        assertFalse(focus.held);
        assertEquals(1, focus.abandons.get());
    }

    @Test
    public void queuedPromptsHoldFocusUntilLastFinishes() {
        tracker.begin("a", true);
        tracker.begin("b", false);
        tracker.finish("a");
        assertTrue(focus.held);
        tracker.finish("b");
        assertFalse(focus.held);
    }

    @Test
    public void flushDropsStaleIdsWithoutOnStop() {
        tracker.begin("a", false);
        tracker.begin("b", false);
        // Flush: the engine may never call onStop() for a and b.
        tracker.begin("c", true);
        assertEquals(1, tracker.pendingCount());
        tracker.finish("c");
        assertFalse(focus.held);
    }

    @Test
    public void lateOnStopForFlushedIdDoesNotReleaseNewPrompt() {
        tracker.begin("a", true);
        tracker.begin("b", true);
        tracker.finish("a");
        assertTrue(focus.held);
        assertEquals(0, focus.abandons.get());
    }

    @Test
    public void timeoutReleasesFocusWhenCallbackIsMissed() {
        tracker.begin("a", true);
        // No onDone/onStop; the safety timeout finishes it.
        tracker.finish("a");
        assertFalse(focus.held);
        // A late callback afterwards is harmless.
        tracker.finish("a");
        assertEquals(1, focus.abandons.get());
    }

    @Test
    public void resetAlwaysReleasesFocus() {
        tracker.begin("tone-1", false); // fallback tone still playing
        tracker.begin("b", false);
        tracker.reset();
        assertFalse(focus.held);
        assertFalse(tracker.isFocusHeld());
        assertEquals(0, tracker.pendingCount());
    }

    @Test
    public void resetWithoutFocusDoesNotAbandon() {
        tracker.reset();
        assertEquals(0, focus.abandons.get());
    }

    @Test
    public void failedRequestIsNotAbandoned() {
        focus.grant = false;
        tracker.begin("a", true);
        tracker.finish("a");
        assertEquals(0, focus.abandons.get());
    }

    @Test
    public void concurrentFinishAndBeginNeverLeaveNewPromptWithoutFocus() throws InterruptedException {
        for (int i = 0; i < 2000; i++) {
            FakeFocus f = new FakeFocus();
            PromptFocusTracker t = new PromptFocusTracker(f);
            t.begin("old", false);

            CountDownLatch go = new CountDownLatch(1);
            Thread callback = new Thread(() -> {
                await(go);
                t.finish("old");
            });
            Thread speaker = new Thread(() -> {
                await(go);
                t.begin("new", false);
            });
            callback.start();
            speaker.start();
            go.countDown();
            callback.join();
            speaker.join();

            // "new" is still pending, so focus must be held both in our state and in the system.
            assertEquals(1, t.pendingCount());
            assertTrue(t.isFocusHeld());
            assertTrue("iteration " + i, f.held);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
