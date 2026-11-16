/*
 * Copyright 2009 Google Inc.
 * Modified by Michael Puckett, 2026
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

package de.dennisguse.opentracks.services.announcement;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Locale;

import de.dennisguse.opentracks.R;
import de.dennisguse.opentracks.settings.PreferencesUtils;


public class TTSManager {

    public final static int AUDIO_STREAM = TextToSpeech.Engine.DEFAULT_STREAM;

    private static final String TAG = TTSManager.class.getSimpleName();

    private final Context context;

    private final AudioManager audioManager;

    /**
     * Prompts are navigation-style guidance: they duck music and other media instead of waiting for it,
     * and they still play while a call or voice assistant has the audio mode.
     */
    private static final AudioAttributes PROMPT_ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();

    /**
     * A prompt is short, so it always finishes its sentence: losing focus (another app, a notification,
     * a navigation voice or the voice assistant) is only logged and never stops speech.
     */
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener =
            focusChange -> Log.d(TAG, "Audio focus changed to " + focusChange + "; prompt keeps playing.");

    private final AudioFocusRequest audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(PROMPT_ATTRIBUTES)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .setWillPauseWhenDucked(false)
            .build();

    /** Utterances queued or speaking; focus is held until the last one finishes, errors, is flushed or times out. */
    private final PromptFocusTracker focusTracker;
    private long utteranceCounter;
    private String fallbackToneId;

    /**
     * Safety net if the engine never reports an utterance as done/stopped (or the tone never completes):
     * the prompt is dropped from the pending set after this long so music is never left ducked.
     */
    static final long FOCUS_TIMEOUT_BASE_MS = 10_000;
    static final long FOCUS_TIMEOUT_PER_CHAR_MS = 200;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Object timeoutToken = new Object();

    private final UtteranceProgressListener utteranceListener = new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) {
            // Focus is already requested in speak(), before the utterance starts.
        }

        @Override
        public void onDone(String utteranceId) {
            onUtteranceFinished(utteranceId);
        }

        @Override
        public void onError(String utteranceId) {
            Log.e(TAG, "An error occurred for utteranceId " + utteranceId);
            onUtteranceFinished(utteranceId);
        }

        @Override
        public void onStop(String utteranceId, boolean interrupted) {
            onUtteranceFinished(utteranceId);
        }
    };

    private TextToSpeech tts;
    // Response from TTS after its initialization
    private int ttsInitStatus = TextToSpeech.ERROR;

    public boolean isReady() {
        return ttsInitStatus == TextToSpeech.SUCCESS;
    }

    private boolean ttsReady = false;

    private MediaPlayer ttsFallback;

    public TTSManager(Context context) {
        this.context = context;
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        focusTracker = new PromptFocusTracker(new PromptFocusTracker.Focus() {
            /** Asked for before speech starts, so the first syllable is not lost while other audio ducks. */
            @Override
            public boolean request() {
                if (audioManager.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
                    Log.w(TAG, "Failed to request audio focus; speaking anyway.");
                    return false;
                }
                return true;
            }

            @Override
            public void abandon() {
                if (audioManager.abandonAudioFocusRequest(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
                    Log.w(TAG, "Failed to relinquish audio focus.");
                }
            }
        });
    }

    public void start() {
        Log.d(TAG, "Start");

        if (tts == null) {
            tts = new TextToSpeech(context, status -> {
                Log.i(TAG, "TextToSpeech initialized with status " + status);
                ttsInitStatus = status;
            });
            tts.setAudioAttributes(PROMPT_ATTRIBUTES);
        }
        if (ttsFallback == null) {
            ttsFallback = MediaPlayer.create(context, R.raw.tts_fallback);
            if (ttsFallback == null) {
                Log.w(TAG, "MediaPlayer for ttsFallback could not be created.");
            } else {
                ttsFallback.setAudioAttributes(PROMPT_ATTRIBUTES);
                ttsFallback.setLooping(false);
                ttsFallback.setOnCompletionListener(player -> onFallbackToneFinished());
            }
        }
    }

    public void speak(@NonNull CharSequence announcement) {
        speak(announcement, true);
    }

    public void speak(@NonNull CharSequence announcement, boolean fallbackTone) {
        speak(announcement, fallbackTone, false);
    }

    /**
     * @param queue true adds the announcement after anything still being spoken (countdown words), false flushes it
     */
    public void speak(@NonNull CharSequence announcement, boolean fallbackTone, boolean queue) {
        synchronized (this) {
            if (!ttsReady) {
                ttsReady = ttsInitStatus == TextToSpeech.SUCCESS;
                if (ttsReady) {
                    onTtsReady();
                }
            }
        }

        if (!ttsReady) {
            if (!fallbackTone) {
                Log.i(TAG, "TTS not ready; voice-only cue skipped: " + announcement);
                return;
            }
            if (ttsFallback == null) {
                Log.w(TAG, "MediaPlayer for ttsFallback was not created.");
            } else {
                Log.i(TAG, "TTS not ready/available, just generating a tone.");
                String previousToneId;
                String toneId;
                synchronized (this) {
                    previousToneId = fallbackToneId;
                    toneId = "tone-" + (++utteranceCounter);
                    fallbackToneId = toneId;
                }
                focusTracker.begin(toneId, false);
                if (previousToneId != null) {
                    // The tone restarts from the beginning, so the previous play is over.
                    focusTracker.finish(previousToneId);
                }
                scheduleFocusTimeout(toneId, 0);
                ttsFallback.seekTo(0);
                ttsFallback.start();
            }
            return;
        }

        if (announcement.length() > 0) {
            // A unique utterance id lets focus be abandoned only after the last queued utterance is done.
            String utteranceId;
            synchronized (this) {
                utteranceId = "prompt-" + (++utteranceCounter);
            }
            // A flush drops older utterances right away instead of relying on onStop() for each of them.
            focusTracker.begin(utteranceId, !queue);
            scheduleFocusTimeout(utteranceId, announcement.length());
            int result = tts.speak(announcement, queue ? TextToSpeech.QUEUE_ADD : TextToSpeech.QUEUE_FLUSH, null, utteranceId);
            if (result == TextToSpeech.ERROR) {
                onUtteranceFinished(utteranceId);
            }
        }
    }

    private void scheduleFocusTimeout(String id, int length) {
        long timeoutMs = FOCUS_TIMEOUT_BASE_MS + FOCUS_TIMEOUT_PER_CHAR_MS * length;
        handler.postAtTime(() -> {
            Log.d(TAG, "Focus timeout for " + id);
            focusTracker.finish(id);
        }, timeoutToken, SystemClock.uptimeMillis() + timeoutMs);
    }

    private void onUtteranceFinished(String utteranceId) {
        focusTracker.finish(utteranceId);
    }

    private void onFallbackToneFinished() {
        String toneId;
        synchronized (this) {
            toneId = fallbackToneId;
            fallbackToneId = null;
        }
        if (toneId != null) {
            focusTracker.finish(toneId);
        }
    }

    public void stop() {
        handler.removeCallbacksAndMessages(timeoutToken);
        synchronized (this) {
            fallbackToneId = null;
        }
        // Always release focus, even if the fallback tone is still playing (it is released below).
        focusTracker.reset();
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }

        if (ttsFallback != null) {
            ttsFallback.release();
            ttsFallback = null;
        }
    }

    private void onTtsReady() {
        Locale locale = Locale.getDefault();
        int languageAvailability = tts.isLanguageAvailable(locale);
        if (languageAvailability == TextToSpeech.LANG_MISSING_DATA || languageAvailability == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Default locale not available, use English.");
            locale = Locale.ENGLISH;
            /*
             * TODO: instead of using english, load the language if missing and show a toast if not supported.
             *  Not able to change the resource strings to English.
             */
        }
        tts.setLanguage(locale);
        tts.setSpeechRate(PreferencesUtils.getVoiceSpeedRate());
        tts.setOnUtteranceProgressListener(utteranceListener);
    }
}
