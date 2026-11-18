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
package de.dennisguse.opentracks.services.announcement;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps synthesized prompts in the order they were asked for, so a prompt that finishes synthesizing
 * early still plays after the one before it. A flush drops everything that has not started playing.
 *
 * @param <T> the synthesized audio (a file)
 */
final class PromptPlaybackQueue<T> {

    /** Requested prompts in order; value is null until its audio is ready. */
    private final LinkedHashMap<String, T> pending = new LinkedHashMap<>();
    private String playingId;

    synchronized void request(@NonNull String id) {
        pending.put(id, null);
    }

    /** @return false if the prompt was flushed or never requested (its audio should be discarded). */
    synchronized boolean ready(@NonNull String id, @NonNull T audio) {
        if (!pending.containsKey(id)) {
            return false;
        }
        pending.put(id, audio);
        return true;
    }

    /** Drops a prompt whose synthesis failed or was stopped. */
    synchronized boolean cancel(@NonNull String id) {
        if (!pending.containsKey(id)) {
            return false;
        }
        pending.remove(id);
        return true;
    }

    /**
     * @return the next prompt to play if nothing is playing and the head of the queue is ready; it is marked playing.
     */
    @Nullable
    synchronized Map.Entry<String, T> next() {
        if (playingId != null) {
            return null;
        }
        Iterator<Map.Entry<String, T>> it = pending.entrySet().iterator();
        if (!it.hasNext()) {
            return null;
        }
        Map.Entry<String, T> head = it.next();
        if (head.getValue() == null) {
            return null;
        }
        it.remove();
        playingId = head.getKey();
        return new AbstractMap.SimpleImmutableEntry<>(head.getKey(), head.getValue());
    }

    /** @return true if id was the prompt playing. */
    synchronized boolean finished(@NonNull String id) {
        if (id.equals(playingId)) {
            playingId = null;
            return true;
        }
        return false;
    }

    @Nullable
    synchronized String playing() {
        return playingId;
    }

    /** Drops every prompt not yet playing. @return their ids and any audio already synthesized. */
    synchronized List<Map.Entry<String, T>> flush() {
        List<Map.Entry<String, T>> dropped = new ArrayList<>();
        for (Map.Entry<String, T> e : pending.entrySet()) {
            dropped.add(new AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue()));
        }
        pending.clear();
        return dropped;
    }

    synchronized boolean isEmpty() {
        return pending.isEmpty() && playingId == null;
    }
}
