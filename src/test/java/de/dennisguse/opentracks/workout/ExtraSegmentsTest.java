package de.dennisguse.opentracks.workout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class ExtraSegmentsTest {

    private static WorkoutSegment seg(SegmentType t, int seconds) {
        return new WorkoutSegment(t, Duration.ofSeconds(seconds));
    }

    private static List<WorkoutItem> apply(List<WorkoutItem> items, int jog, int prepare, int cdJog) {
        return ExtraSegments.apply(items,
                item -> item instanceof WorkoutSegment s ? s.type() : null,
                Duration.ofSeconds(jog), Duration.ofSeconds(prepare), Duration.ofSeconds(cdJog),
                (type, d) -> new WorkoutSegment(type, d));
    }

    private static List<SegmentType> types(List<WorkoutItem> items) {
        List<SegmentType> out = new ArrayList<>();
        for (WorkoutItem item : items) {
            out.add(item instanceof WorkoutSegment s ? s.type() : null);
        }
        return out;
    }

    private static List<WorkoutItem> base() {
        return List.of(seg(SegmentType.WARMUP, 300),
                new WorkoutBlock(List.of(seg(SegmentType.RUN, 60), seg(SegmentType.WALK, 90)), 3),
                seg(SegmentType.COOLDOWN, 300));
    }

    @Test
    public void insertsInFixedOrder() {
        assertEquals(java.util.Arrays.asList(SegmentType.WARMUP, SegmentType.WARMUP_JOG, SegmentType.PREPARE, null,
                        SegmentType.COOLDOWN_JOG, SegmentType.COOLDOWN),
                types(apply(base(), 60, 180, 120)));
    }

    @Test
    public void zeroSkips_andReapplyReplaces() {
        List<WorkoutItem> once = apply(base(), 60, 180, 120);
        List<WorkoutItem> again = apply(once, 0, 120, 0);
        assertEquals(java.util.Arrays.asList(SegmentType.WARMUP, SegmentType.PREPARE, null, SegmentType.COOLDOWN), types(again));
        assertEquals(Duration.ofSeconds(120), ((WorkoutSegment) again.get(1)).duration());
        assertEquals(types(base()), types(apply(once, 0, 0, 0)));
    }

    @Test
    public void noWarmupOrCooldown_goesAtEnds() {
        List<WorkoutItem> items = List.of(seg(SegmentType.RUN, 60), seg(SegmentType.WALK, 60));
        assertEquals(List.of(SegmentType.WARMUP_JOG, SegmentType.PREPARE, SegmentType.RUN, SegmentType.WALK, SegmentType.COOLDOWN_JOG),
                types(apply(items, 60, 60, 60)));
    }

    @Test
    public void prepareCue_andJogCue() {
        assertEquals("Prepare, 3 minutes", WorkoutSpeech.intervalCue(SegmentType.PREPARE, Duration.ofMinutes(3)));
        assertEquals("Prepare, 1 minute 30 seconds", WorkoutSpeech.intervalCue(SegmentType.PREPARE, Duration.ofSeconds(90)));
        assertEquals("1 minute warm-up jog", WorkoutSpeech.intervalCue(SegmentType.WARMUP_JOG, Duration.ofMinutes(1)));
        assertEquals("1 minute run", WorkoutSpeech.intervalCue(SegmentType.RUN, Duration.ofMinutes(1)));
    }

    @Test
    public void lastWalkMerge_skipsCooldownJog() {
        Workout workout = new Workout(1, "w", false, apply(base(), 60, 180, 120));
        assertTrue(LastWalkMerge.applies(workout));
        List<PlannedStep> merged = LastWalkMerge.merge(workout.plannedSteps());
        PlannedStep lastRun = merged.get(merged.size() - 3);
        assertEquals(SegmentType.RUN, lastRun.segment().type());
        assertEquals(Duration.ofSeconds(150), lastRun.segment().duration());
        assertEquals(SegmentType.COOLDOWN_JOG, merged.get(merged.size() - 2).segment().type());
        // Warm-up jog and prepare are untouched.
        assertEquals(SegmentType.WARMUP_JOG, merged.get(1).segment().type());
        assertEquals(SegmentType.PREPARE, merged.get(2).segment().type());
    }

    @Test
    public void jogsAreNotRunIntervals() {
        for (SegmentType t : new SegmentType[]{SegmentType.WARMUP_JOG, SegmentType.PREPARE, SegmentType.COOLDOWN_JOG}) {
            assertFalse(t.isRun());
            assertFalse(t.isWalkInterval());
            assertFalse(t.isAnyWalk());
        }
    }

    @Test
    public void prepareGetsNormalCallouts_andRoundsUnchanged() {
        WorkoutEngine engine = new WorkoutEngine(new Workout(1, "w", false, apply(base(), 60, 180, 120)), true);
        List<String> cues = new ArrayList<>();
        for (int s = 0; s <= 2000; s++) {
            String cue = engine.onElapsed(Duration.ofSeconds(s)).cue();
            if (cue != null) {
                cues.add(s + ":" + cue);
            }
        }
        String all = cues.toString();
        assertTrue(all, all.contains("300:1 minute warm-up jog"));
        assertTrue(all, all.contains("360:Prepare, 3 minutes"));
        assertTrue(all, cues.contains("420:2 minutes left"));
        assertTrue(all, cues.contains("450:Halfway"));
        assertTrue(all, cues.contains("510:30 seconds"));
        assertTrue(all, cues.contains("530:10 seconds"));
        assertTrue(all, cues.contains("535:5"));
        assertTrue(all, cues.contains("480:1 minute left"));
        assertTrue(all, all.contains("540:1 minute run, round 1"));
        // Last walk merged into the final run; the cool-down jog still follows.
        assertTrue(all, all.contains("840:2 minute 30 second run, round 3, final round"));
        assertTrue(all, all.contains("990:2 minute cool-down jog"));
    }
}
