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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Pure timer. A late tick closes every segment it crosses and speaks only the latest cue.
 */
public final class WorkoutEngine {

    public static final String HALFWAY_CUE = "Halfway point";
    public static final String COMPLETE_CUE = "Workout complete";
    public static final String THIRTY_SECONDS_CUE = "30 seconds";
    public static final String TEN_SECONDS_CUE = "10 seconds";
    /** Midpoint of the current interval. Workout-level midpoint stays {@link #HALFWAY_CUE}. */
    public static final String INTERVAL_HALFWAY_CUE = "Halfway";
    /** Intervals shorter than this get no interval halfway cue (it would crowd the start cue and countdown). */
    static final long INTERVAL_HALFWAY_MIN_MS = 20_000L;

    /** Spoken once per segment: 30 (only if the segment is longer), then 10, 5, 4, 3, 2, 1. */
    private static final int[] COUNTDOWN_MARKS = {30, 10, 5, 4, 3, 2, 1};

    public record ClosedSegment(int position, SegmentType type, Duration startOffset, Duration endOffset, boolean completed) {
    }

    public record Snapshot(
            int segmentIndex,
            int segmentCount,
            SegmentType type,
            Duration remainingInSegment,
            Duration elapsed,
            Duration total,
            int runNumber,
            int runCount,
            int walkNumber,
            int walkCount,
            /* Round of the current block step, 1-based; 0 outside a block. */
            int round,
            /* Rounds in the current block; 0 outside a block. */
            int roundCount,
            boolean finished
    ) {
    }

    public record Tick(List<ClosedSegment> closed, String cue, Snapshot snapshot) {
    }

    private final List<PlannedStep> plan;
    private final List<WorkoutSegment> segments;
    private final WorkoutTimeline timeline;
    private int nextToClose;
    private int announcedIndex = -1;
    private boolean halfwayAnnounced;
    /** Interval halfway already spoken (or skipped) for the current segment. */
    private boolean intervalHalfwayDone;
    private boolean completeAnnounced;
    private boolean finished;
    private boolean stopped;
    /** Smallest countdown mark already spoken in the current segment. */
    private int countdownAnnounced = Integer.MAX_VALUE;

    /** Plain segments with no repeat blocks, so no round cues. */
    public WorkoutEngine(List<WorkoutSegment> segments) {
        this.plan = PlannedStep.ofSegments(segments);
        this.segments = List.copyOf(segments);
        this.timeline = new WorkoutTimeline(this.segments);
    }

    /** Expands the workout's repeat blocks into rounds; the first run of each round announces the round. */
    public WorkoutEngine(Workout workout) {
        this.plan = workout.plannedSteps();
        List<WorkoutSegment> expanded = new ArrayList<>();
        for (PlannedStep step : plan) {
            expanded.add(step.segment());
        }
        this.segments = List.copyOf(expanded);
        this.timeline = new WorkoutTimeline(this.segments);
    }

    public boolean isFinished() {
        return finished;
    }

    public Snapshot snapshot(Duration elapsed) {
        Duration safe = elapsed == null || elapsed.isNegative() ? Duration.ZERO : elapsed;
        int count = segments.size();
        int runCount = countWhere(type -> type.isRun());
        int walkCount = countWhere(SegmentType::isWalkInterval);
        if (finished || timeline.indexAt(safe) >= count) {
            return new Snapshot(count, count, null, Duration.ZERO, timeline.total(), timeline.total(), runCount, runCount, walkCount, walkCount, 0, 0, true);
        }
        int index = timeline.indexAt(safe);
        Duration remaining = timeline.endOffset(index).minus(safe);
        if (remaining.isNegative()) {
            remaining = Duration.ZERO;
        }
        return new Snapshot(
                index,
                count,
                segments.get(index).type(),
                remaining,
                safe,
                timeline.total(),
                countUntil(index, SegmentType::isRun),
                runCount,
                countUntil(index, SegmentType::isWalkInterval),
                walkCount,
                plan.get(index).round(),
                plan.get(index).roundCount(),
                false
        );
    }

    public Tick onElapsed(Duration elapsed) {
        Duration safe = elapsed == null || elapsed.isNegative() ? Duration.ZERO : elapsed;
        if (stopped || finished) {
            return new Tick(List.of(), null, snapshot(safe));
        }

        int index = timeline.indexAt(safe);
        int count = segments.size();
        boolean nowFinished = index >= count;
        List<ClosedSegment> closed = new ArrayList<>();
        int lastExclusive = nowFinished ? count : index;
        for (int i = nextToClose; i < lastExclusive; i++) {
            closed.add(new ClosedSegment(i, segments.get(i).type(), timeline.startOffset(i), timeline.endOffset(i), true));
        }
        nextToClose = lastExclusive;

        boolean crossedHalfway = !halfwayAnnounced && safe.compareTo(timeline.halfway()) >= 0;
        String cue;
        if (nowFinished) {
            cue = completeAnnounced ? null : COMPLETE_CUE;
            completeAnnounced = true;
            finished = true;
            if (crossedHalfway) {
                halfwayAnnounced = true;
            }
        } else {
            boolean entered = index != announcedIndex && index >= 0 && index < count;
            StringBuilder spoken = new StringBuilder();
            if (entered) {
                spoken.append(WorkoutSpeech.stepCue(plan.get(index), segmentLength(index)));
                announcedIndex = index;
                countdownAnnounced = Integer.MAX_VALUE;
                // A late tick that lands past the midpoint does not say "Halfway" on top of the start cue.
                intervalHalfwayDone = !intervalHalfwayApplies(index) || safe.compareTo(intervalHalfway(index)) >= 0;
            }
            if (crossedHalfway) {
                appendPhrase(spoken, HALFWAY_CUE);
                halfwayAnnounced = true;
            }
            if (!intervalHalfwayDone && safe.compareTo(intervalHalfway(index)) >= 0) {
                // When the workout's "Halfway point" is said on this tick, a second "Halfway" adds nothing.
                if (!crossedHalfway) {
                    appendPhrase(spoken, INTERVAL_HALFWAY_CUE);
                }
                intervalHalfwayDone = true;
            }
            int mark = takeCountdown(timeline.endOffset(index).minus(safe), segmentLength(index));
            if (mark > 0) {
                appendPhrase(spoken, countdownPhrase(mark));
            }
            cue = spoken.length() == 0 ? null : spoken.toString();
        }
        return new Tick(List.copyOf(closed), cue, snapshot(safe));
    }

    /**
     * Closes the open segment early. Does not say "Workout complete" unless the plan has already ended.
     */
    public Tick stop(Duration elapsed) {
        Duration safe = elapsed == null || elapsed.isNegative() ? Duration.ZERO : elapsed;
        if (stopped) {
            return new Tick(List.of(), null, snapshot(safe));
        }
        if (finished) {
            stopped = true;
            return new Tick(List.of(), null, snapshot(safe));
        }
        if (safe.compareTo(timeline.total()) >= 0) {
            Tick tick = onElapsed(safe);
            stopped = true;
            return tick;
        }
        stopped = true;
        int index = timeline.indexAt(safe);
        int count = segments.size();
        List<ClosedSegment> closed = new ArrayList<>();
        for (int i = nextToClose; i < index && i < count; i++) {
            closed.add(new ClosedSegment(i, segments.get(i).type(), timeline.startOffset(i), timeline.endOffset(i), true));
        }
        if (index >= 0 && index < count) {
            Duration end = safe.compareTo(timeline.startOffset(index)) < 0 ? timeline.startOffset(index) : safe;
            closed.add(new ClosedSegment(index, segments.get(index).type(), timeline.startOffset(index), end, false));
        }
        nextToClose = count;
        finished = true;
        return new Tick(List.copyOf(closed), null, snapshot(safe));
    }

    /**
     * Continues from {@code elapsed} without repeating cues or rewriting segments already stored.
     */
    public void resumeAt(Duration elapsed) {
        Duration safe = elapsed == null || elapsed.isNegative() ? Duration.ZERO : elapsed;
        int index = timeline.indexAt(safe);
        int count = segments.size();
        if (index >= count) {
            nextToClose = count;
            announcedIndex = count;
            halfwayAnnounced = true;
            completeAnnounced = true;
            finished = true;
            return;
        }
        nextToClose = index;
        announcedIndex = index;
        halfwayAnnounced = safe.compareTo(timeline.halfway()) >= 0;
        completeAnnounced = false;
        finished = false;
        stopped = false;
        countdownAnnounced = Integer.MAX_VALUE;
        intervalHalfwayDone = !intervalHalfwayApplies(index) || safe.compareTo(intervalHalfway(index)) >= 0;
        Duration remaining = timeline.endOffset(index).minus(safe);
        long remainingMs = Math.max(0, remaining.toMillis());
        long lengthMs = segmentLength(index).toMillis();
        for (int mark : COUNTDOWN_MARKS) {
            if (countdownApplies(mark, lengthMs) && remainingMs <= mark * 1000L) {
                countdownAnnounced = mark;
            }
        }
    }

    /** Exact midpoint offset of an interval, to the millisecond (7:30 into a 15:00 walk, 22.5 s into a 45 s run). */
    private Duration intervalHalfway(int index) {
        return timeline.startOffset(index).plus(segmentLength(index).dividedBy(2));
    }

    private boolean intervalHalfwayApplies(int index) {
        return segmentLength(index).toMillis() >= INTERVAL_HALFWAY_MIN_MS;
    }

    private Duration segmentLength(int index) {
        return timeline.endOffset(index).minus(timeline.startOffset(index));
    }

    /**
     * Latest countdown mark this segment has reached and not yet spoken.
     * A mark counts only when the segment is at least that long, so an 8 s segment never says "10 seconds".
     */
    private int takeCountdown(Duration remaining, Duration segmentLength) {
        long remainingMs = remaining == null ? 0 : Math.max(0, remaining.toMillis());
        long lengthMs = segmentLength == null ? 0 : Math.max(0, segmentLength.toMillis());
        int chosen = 0;
        for (int mark : COUNTDOWN_MARKS) {
            if (countdownApplies(mark, lengthMs) && remainingMs <= mark * 1000L && mark < countdownAnnounced) {
                chosen = mark;
            }
        }
        if (chosen > 0) {
            countdownAnnounced = chosen;
        }
        return chosen;
    }

    /** A 30-second warning is skipped when the interval itself is 30 seconds or shorter. */
    private static boolean countdownApplies(int mark, long lengthMs) {
        if (mark == 30) {
            return lengthMs > 30_000L;
        }
        return lengthMs >= mark * 1000L;
    }

    private static String countdownPhrase(int mark) {
        if (mark == 30) {
            return THIRTY_SECONDS_CUE;
        }
        return mark == 10 ? TEN_SECONDS_CUE : Integer.toString(mark);
    }

    private static void appendPhrase(StringBuilder spoken, String phrase) {
        if (spoken.length() > 0) {
            spoken.append(". ").append(phrase).append('.');
        } else {
            spoken.append(phrase);
        }
    }

    private int countWhere(Predicate<SegmentType> predicate) {
        int count = 0;
        for (WorkoutSegment segment : segments) {
            if (predicate.test(segment.type())) {
                count++;
            }
        }
        return count;
    }

    private int countUntil(int indexInclusive, Predicate<SegmentType> predicate) {
        int count = 0;
        for (int i = 0; i <= indexInclusive && i < segments.size(); i++) {
            if (predicate.test(segments.get(i).type())) {
                count++;
            }
        }
        return count;
    }
}
