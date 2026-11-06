package de.dennisguse.opentracks.workout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Warm-up jog, prepare and cool-down jog, in any order the user puts them. */
public class NewSegmentTypesTest {

    private static WorkoutSegment seg(SegmentType t, int seconds) {
        return new WorkoutSegment(t, Duration.ofSeconds(seconds));
    }

    private static WorkoutBlock rounds() {
        return new WorkoutBlock(List.of(seg(SegmentType.RUN, 60), seg(SegmentType.WALK, 90)), 3);
    }

    private static List<String> cues(Workout workout, boolean runLastWalk) {
        WorkoutEngine engine = new WorkoutEngine(workout, runLastWalk);
        List<String> cues = new ArrayList<>();
        int total = (int) workout.totalDuration().getSeconds();
        for (int s = 0; s <= total; s++) {
            String cue = engine.onElapsed(Duration.ofSeconds(s)).cue();
            if (cue != null) {
                cues.add(s + ":" + cue);
            }
        }
        return cues;
    }

    @Test
    public void spokenLabels() {
        assertEquals("Prepare, 3 minutes", WorkoutSpeech.intervalCue(SegmentType.PREPARE, Duration.ofMinutes(3)));
        assertEquals("Prepare, 1 minute 30 seconds", WorkoutSpeech.intervalCue(SegmentType.PREPARE, Duration.ofSeconds(90)));
        assertEquals("1 minute warm-up jog", WorkoutSpeech.intervalCue(SegmentType.WARMUP_JOG, Duration.ofMinutes(1)));
        assertEquals("2 minute cool-down jog", WorkoutSpeech.intervalCue(SegmentType.COOLDOWN_JOG, Duration.ofMinutes(2)));
        assertEquals("1 minute run", WorkoutSpeech.intervalCue(SegmentType.RUN, Duration.ofMinutes(1)));
    }

    @Test
    public void newTypesAreNotRunOrWalkIntervals() {
        for (SegmentType t : new SegmentType[]{SegmentType.WARMUP_JOG, SegmentType.PREPARE, SegmentType.COOLDOWN_JOG}) {
            assertFalse(t.isRun());
            assertFalse(t.isWalkInterval());
            assertFalse(t.isAnyWalk());
            assertEquals(t, SegmentType.fromDatabase(t.name()));
        }
    }

    @Test
    public void typicalOrder_callouts_andMergeBeforeCooldownJog() {
        Workout workout = new Workout(1, "w", false, List.of(seg(SegmentType.WARMUP, 300), seg(SegmentType.WARMUP_JOG, 60),
                seg(SegmentType.PREPARE, 180), rounds(), seg(SegmentType.COOLDOWN_JOG, 120), seg(SegmentType.COOLDOWN, 300)));
        assertTrue(LastWalkMerge.applies(workout));
        List<String> cues = cues(workout, true);
        String all = cues.toString();
        assertTrue(all, all.contains("300:1 minute warm-up jog"));
        assertTrue(all, all.contains("360:Prepare, 3 minutes"));
        assertTrue(all, cues.contains("420:2 minutes left"));
        assertTrue(all, cues.contains("450:Halfway"));
        assertTrue(all, cues.contains("480:1 minute left"));
        assertTrue(all, cues.contains("510:30 seconds"));
        assertTrue(all, cues.contains("530:10 seconds"));
        assertTrue(all, cues.contains("535:5"));
        assertTrue(all, all.contains("540:1 minute run, round 1"));
        assertTrue(all, all.contains("840:2 minute 30 second run, round 3, final round"));
        assertTrue(all, all.contains("990:2 minute cool-down jog"));
    }

    @Test
    public void prepareBeforeJog_roundsInMiddle_mergeStillOnRounds() {
        Workout workout = new Workout(1, "w", false, List.of(seg(SegmentType.PREPARE, 120), seg(SegmentType.WARMUP_JOG, 60),
                rounds(), seg(SegmentType.PREPARE, 60), seg(SegmentType.COOLDOWN, 120), seg(SegmentType.COOLDOWN_JOG, 60)));
        assertTrue(LastWalkMerge.applies(workout));
        List<PlannedStep> merged = LastWalkMerge.merge(workout.plannedSteps());
        assertEquals(SegmentType.PREPARE, merged.get(0).segment().type());
        assertEquals(SegmentType.WARMUP_JOG, merged.get(1).segment().type());
        PlannedStep last = merged.get(6);
        assertEquals(SegmentType.RUN, last.segment().type());
        assertEquals(Duration.ofSeconds(150), last.segment().duration());
        assertEquals(SegmentType.PREPARE, merged.get(7).segment().type());
        assertEquals(10, merged.size());
        String all = cues(workout, true).toString();
        assertTrue(all, all.contains("0:Prepare, 2 minutes"));
        assertTrue(all, all.contains("120:1 minute warm-up jog"));
    }

    @Test
    public void roundsFirst_thenExtras() {
        Workout workout = new Workout(1, "w", false, List.of(rounds(), seg(SegmentType.COOLDOWN_JOG, 60), seg(SegmentType.PREPARE, 60)));
        assertTrue(LastWalkMerge.applies(workout));
        assertEquals(4, LastWalkMerge.mergeableWalkIndex(workout.plannedSteps()) - 1);
    }

    @Test
    public void runAfterLastWalk_noMerge() {
        Workout workout = new Workout(1, "w", false, List.of(rounds(), seg(SegmentType.WARMUP_JOG, 60), seg(SegmentType.RUN, 60)));
        assertFalse(LastWalkMerge.applies(workout));
    }
}
