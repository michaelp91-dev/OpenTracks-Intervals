package de.dennisguse.opentracks.services.announcement;

import androidx.annotation.NonNull;

import java.util.HashSet;
import java.util.Set;

/**
 * Bookkeeping for the audio focus held while voice prompts (or the fallback tone) play.
 * <p>
 * Every request and abandon happens under one lock together with the pending-prompt state, so a
 * callback finishing an old prompt on the TTS thread can never abandon focus that a new prompt has
 * just requested on another thread.
 * Pure Java so it can be unit tested without Android.
 */
class PromptFocusTracker {

    interface Focus {
        /** @return true if focus was granted (or delayed); false if the request failed */
        boolean request();

        void abandon();
    }

    private final Focus focus;

    private final Set<String> pending = new HashSet<>();
    private boolean focusHeld;

    PromptFocusTracker(@NonNull Focus focus) {
        this.focus = focus;
    }

    /**
     * Registers a prompt that is about to play and requests focus before it starts.
     *
     * @param flush true if the prompt flushes everything still queued or speaking (TextToSpeech.QUEUE_FLUSH);
     *              those prompts are dropped right away instead of waiting for an onStop() the engine may never send
     */
    synchronized void begin(@NonNull String id, boolean flush) {
        if (flush) {
            pending.clear();
        }
        pending.add(id);
        if (focus.request()) {
            focusHeld = true;
        }
    }

    /** A prompt finished, errored, was stopped or timed out; focus is released once nothing is pending. */
    synchronized void finish(@NonNull String id) {
        pending.remove(id);
        if (pending.isEmpty()) {
            abandonIfHeld();
        }
    }

    /** Drops every pending prompt and always releases focus (recording stopped). */
    synchronized void reset() {
        pending.clear();
        abandonIfHeld();
    }

    synchronized boolean isFocusHeld() {
        return focusHeld;
    }

    synchronized int pendingCount() {
        return pending.size();
    }

    private void abandonIfHeld() {
        if (!focusHeld) {
            return;
        }
        focusHeld = false;
        focus.abandon();
    }
}
