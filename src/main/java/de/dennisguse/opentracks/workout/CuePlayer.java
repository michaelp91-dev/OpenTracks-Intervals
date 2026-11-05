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

import android.os.SystemClock;

import de.dennisguse.opentracks.services.StartCountdown;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import de.dennisguse.opentracks.services.announcement.VoiceAnnouncementManager;
import de.dennisguse.opentracks.settings.PreferencesUtils;

/** Optional beep and vibration, then one spoken cue about 300 ms later. */
public final class CuePlayer {

    private static final String TAG = "CuePlayer";
    private static final int BEEP_VOLUME = 80;
    private static final int BEEP_MS = 200;
    private static final long SPEAK_DELAY_MS = 300;
    private static final long VIBRATE_MS = 400;

    private final Context context;
    private final Handler handler;
    private final VoiceAnnouncementManager voiceAnnouncementManager;
    private ToneGenerator toneGenerator;
    private Runnable pendingSpeak;

    public CuePlayer(Context context, Handler handler, VoiceAnnouncementManager voiceAnnouncementManager) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.voiceAnnouncementManager = voiceAnnouncementManager;
    }

    /** Time a spoken-only countdown word ("1" or "Go") is given before the next cue may beep or talk over it. */
    private static final long BARE_WORD_MS = 900;
    private long lastBareSpokenAt;

    /** Speaks right away with no beep, no vibration, and no fallback tone. For 5-4-3-2-1 and "Go". */
    public void speakOnly(CharSequence text) {
        if (text == null || text.length() == 0) {
            return;
        }
        if (pendingSpeak != null) {
            handler.removeCallbacks(pendingSpeak);
            pendingSpeak = null;
        }
        lastBareSpokenAt = SystemClock.elapsedRealtime();
        Log.i(TAG, "Speaking (voice only): " + text);
        voiceAnnouncementManager.announceWorkoutCue(text, false);
    }

    public void play(CharSequence text) {
        if (text == null || text.length() == 0) {
            return;
        }
        if (isBareCountdown(text)) {
            speakOnly(text);
            return;
        }
        // Let a just-spoken "1" or "Go" finish before this cue beeps or flushes it.
        long wait = lastBareSpokenAt + BARE_WORD_MS - SystemClock.elapsedRealtime();
        if (wait > 0) {
            if (pendingSpeak != null) {
                handler.removeCallbacks(pendingSpeak);
            }
            pendingSpeak = () -> {
                pendingSpeak = null;
                play(text);
            };
            handler.postDelayed(pendingSpeak, wait);
            return;
        }
        boolean countdownOnly = false;
        if (!countdownOnly && PreferencesUtils.shouldWorkoutCueBeep()) {
            beep();
        }
        if (!countdownOnly && PreferencesUtils.shouldWorkoutCueVibrate()) {
            vibrate();
        }
        if (pendingSpeak != null) {
            handler.removeCallbacks(pendingSpeak);
        }
        pendingSpeak = () -> {
            pendingSpeak = null;
            Log.i(TAG, "Speaking: " + text);
            voiceAnnouncementManager.announceWorkoutCue(text);
        };
        handler.postDelayed(pendingSpeak, countdownOnly ? 0 : SPEAK_DELAY_MS);
    }

    /** 5, 4, 3, 2, 1 and "Go" are spoken only: no beep, no vibration, no fallback tone. The 10-second cue still alerts. */
    private static boolean isBareCountdown(CharSequence text) {
        String value = text.toString();
        return StartCountdown.GO.equals(value) || "1".equals(value) || "2".equals(value) || "3".equals(value) || "4".equals(value) || "5".equals(value);
    }

    public void release() {
        if (pendingSpeak != null && handler != null) {
            handler.removeCallbacks(pendingSpeak);
            pendingSpeak = null;
        }
        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }
    }

    private void beep() {
        try {
            if (toneGenerator == null) {
                toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, BEEP_VOLUME);
            }
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS);
        } catch (RuntimeException e) {
            Log.w(TAG, "Workout beep failed", e);
        }
    }

    private void vibrate() {
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        VibrationEffect effect = VibrationEffect.createOneShot(VIBRATE_MS, VibrationEffect.DEFAULT_AMPLITUDE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
        } else {
            vibrator.vibrate(effect);
        }
    }
}
