package wtf.esuh.ogguard.visibility.policy;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResyncPlanTest {

    static final String W = "world";

    final VisibilityPolicy all = new VisibilityPolicy(VisibilityPolicyTest.cfg(true, List.of(), List.of()));

    /** Client holds a 21x21 chunk view around the origin. */
    static Set<Long> view(int radius) {
        Set<Long> s = new HashSet<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                s.add(ChunkKeys.key(x, z));
            }
        }
        return s;
    }

    @Test
    void goingUndergroundResendsRevealedChunks() {
        ViewerState before = all.nextState(null, W, 8, 70, 8, false);
        ViewerState after = all.nextState(before, W, 8, -5, 8, false);
        ResyncPlan plan = all.resyncPlan(before, after, view(10));
        assertEquals(49, plan.chunks().size()); // (2*3+1)^2
        assertTrue(plan.chunks().contains(ChunkKeys.key(3, -3)));
        assertFalse(plan.chunks().contains(ChunkKeys.key(4, 0)));
        assertTrue(plan.revealChanged());
    }

    @Test
    void goingBackUpResendsSameChunksMasked() {
        ViewerState down = all.nextState(null, W, 8, -5, 8, false);
        ViewerState up = all.nextState(down, W, 8, 20, 8, false);
        assertFalse(up.revealed());
        assertEquals(49, all.resyncPlan(down, up, view(10)).chunks().size());
    }

    @Test
    void movingOneChunkUndergroundResendsOnlyTheEdges() {
        ViewerState a = all.nextState(null, W, 8, -5, 8, false);
        ViewerState b = all.nextState(a, W, 24, -5, 8, false); // chunk x 0 -> 1
        ResyncPlan plan = all.resyncPlan(a, b, view(10));
        assertEquals(14, plan.chunks().size()); // 7 leaving (x=-3), 7 entering (x=4)
        assertTrue(plan.chunks().contains(ChunkKeys.key(-3, 0)));
        assertTrue(plan.chunks().contains(ChunkKeys.key(4, 0)));
    }

    @Test
    void movingWithinTheSameChunkIsNoop() {
        ViewerState a = all.nextState(null, W, 1, -5, 1, false);
        ViewerState b = all.nextState(a, W, 14, -9, 14, false);
        assertTrue(all.resyncPlan(a, b, view(10)).isEmpty());
        ViewerState c = all.nextState(null, W, 1, 70, 1, false);
        ViewerState d = all.nextState(c, W, 200, 70, 200, false);
        assertTrue(all.resyncPlan(c, d, view(10)).isEmpty(), "concealed movement never resyncs");
    }

    @Test
    void onlyChunksTheClientHoldsAreResent() {
        ViewerState before = all.nextState(null, W, 8, 70, 8, false);
        ViewerState after = all.nextState(before, W, 8, -5, 8, false);
        Set<Long> held = Set.of(ChunkKeys.key(0, 0), ChunkKeys.key(50, 50));
        assertEquals(Set.of(ChunkKeys.key(0, 0)), all.resyncPlan(before, after, held).chunks());
    }

    @Test
    void chunksWithoutProtectionAreSkipped() {
        Cuboid base = Cuboid.of(W, 0, -20, 0, 15, -1, 15); // only chunk 0,0
        VisibilityPolicy regions = new VisibilityPolicy(VisibilityPolicyTest.cfg(false, List.of(base), List.of()));
        ViewerState before = regions.nextState(null, W, 8, 70, 8, false);
        ViewerState after = regions.nextState(before, W, 8, -5, 8, false);
        assertEquals(Set.of(ChunkKeys.key(0, 0)), regions.resyncPlan(before, after, view(10)).chunks());
    }

    @Test
    void bypassToggleResendsEveryProtectedHeldChunk() {
        ViewerState a = all.nextState(null, W, 8, 70, 8, false);
        ViewerState b = all.nextState(a, W, 8, 70, 8, true);
        assertEquals(view(2).size(), all.resyncPlan(a, b, view(2)).chunks().size());
    }

    @Test
    void worldChangeResendsRevealedArea() {
        ViewerState a = all.nextState(null, "other", 8, 70, 8, false);
        ViewerState b = all.nextState(a, W, 8, -5, 8, false);
        ResyncPlan plan = all.resyncPlan(a, b, view(10));
        assertEquals(49, plan.chunks().size());
        assertTrue(plan.revealChanged());
    }

    @Test
    void firstSnapshotAfterUnknownResendsRevealedArea() {
        ViewerState b = all.nextState(ViewerState.unknown(), W, 8, -5, 8, false);
        assertEquals(49, all.resyncPlan(ViewerState.unknown(), b, view(10)).chunks().size());
    }
}
