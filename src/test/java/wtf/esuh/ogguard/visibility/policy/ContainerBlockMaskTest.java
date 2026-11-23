package wtf.esuh.ogguard.visibility.policy;

import wtf.esuh.ogguard.visibility.policy.ContainerBlockMask.UpdateAction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerBlockMaskTest {

    static final int MIN_SECTION = -4;
    static final int SECTIONS = 24;

    // ---------------------------------------------------------------- config

    @Test
    void replaceBlockDefaultsOnWithDeepslate() {
        ContainerRule d = ContainerRule.defaults();
        assertTrue(d.replaceBlock());
        assertEquals(Replacement.DEEPSLATE, d.replacementBlock());
        ContainerRule parsed = ContainerRuleParser.parse(Map.of());
        assertTrue(parsed.replaceBlock());
        assertEquals(Replacement.DEEPSLATE, parsed.replacementBlock());
    }

    @Test
    void replaceBlockKeysAreParsed() {
        ContainerRule r = ContainerRuleParser.parse(Map.of("replace-block", false, "replacement-block", "stone"));
        assertFalse(r.replaceBlock());
        assertEquals(Replacement.STONE, r.replacementBlock());
        assertThrows(IllegalArgumentException.class,
                () -> ContainerRuleParser.parse(Map.of("replace-block", "yes")));
        assertThrows(IllegalArgumentException.class,
                () -> ContainerRuleParser.parse(Map.of("replacement-block", "GOLD_BLOCK")));
    }

    // ---------------------------------------------------------------- chunk positions

    @Test
    void onlyWithheldPositionsAreReplacedInTheirSections() {
        long a = BlockKeys.key(16 + 3, 64 + 5, 32 + 7);   // chunk (1, 2), section 4 -> index 8
        long b = BlockKeys.key(16 + 15, -64, 32);          // section -4 -> index 0
        long c = BlockKeys.key(16, 64 + 15, 32 + 15);      // same section as a
        int[][] mask = ContainerBlockMask.bySection(1, 2, MIN_SECTION, SECTIONS, new long[] {a, b, c});
        assertEquals(SECTIONS, mask.length);
        assertArrayEquals(new int[] {ContainerBlockMask.localIndex(15, 0, 0)}, mask[0]);
        int[] expected = {ContainerBlockMask.localIndex(3, 5, 7), ContainerBlockMask.localIndex(0, 15, 15)};
        java.util.Arrays.sort(expected);
        assertArrayEquals(expected, mask[8]);
        for (int i = 0; i < SECTIONS; i++) {
            if (i != 0 && i != 8) {
                assertNull(mask[i], "section " + i + " must be forwarded untouched");
            }
        }
        // Neighbours of a replaced block keep their real state.
        assertTrue(ContainerBlockMask.replaced(mask[8], ContainerBlockMask.localIndex(3, 5, 7)));
        assertFalse(ContainerBlockMask.replaced(mask[8], ContainerBlockMask.localIndex(4, 5, 7)));
        assertFalse(ContainerBlockMask.replaced(mask[8], ContainerBlockMask.localIndex(3, 6, 7)));
        assertFalse(ContainerBlockMask.replaced(mask[8], ContainerBlockMask.localIndex(3, 5, 6)));
        assertFalse(ContainerBlockMask.replaced(null, 0));
    }

    @Test
    void nothingWithheldMeansNoRewrite() {
        assertNull(ContainerBlockMask.bySection(0, 0, MIN_SECTION, SECTIONS, new long[0]));
        assertNull(ContainerBlockMask.bySection(0, 0, MIN_SECTION, SECTIONS, null));
    }

    @Test
    void positionsOutsideChunkOrHeightAreIgnored() {
        long otherChunk = BlockKeys.key(-1, 10, 0);   // chunk (-1, 0)
        long tooHigh = BlockKeys.key(1, 400, 1);
        long tooLow = BlockKeys.key(1, -65, 1);
        assertNull(ContainerBlockMask.bySection(0, 0, MIN_SECTION, SECTIONS,
                new long[] {otherChunk, tooHigh, tooLow}));
        int[][] neg = ContainerBlockMask.bySection(-1, -1, MIN_SECTION, SECTIONS,
                new long[] {BlockKeys.key(-1, -1, -16)});
        assertArrayEquals(new int[] {ContainerBlockMask.localIndex(15, 15, 0)}, neg[3]);
    }

    @Test
    void visibleContainersKeepTheRealState() {
        // The packet layer replaces exactly what the gate withheld; a visible container is never withheld.
        ContainerRule rule = ContainerRule.defaults();
        VisibilityPolicy policy = new VisibilityPolicy(VisibilityConfig.defaults(), List.of(), rule);
        ViewerState viewer = new ViewerState("world", 0, 70, 0, false, false);
        VisibleContainers visible = new VisibleContainers("world", Set.of(BlockKeys.key(2, 70, 2)));
        long[] candidates = {BlockKeys.key(2, 70, 2), BlockKeys.key(5, 70, 5)};
        List<Long> withheld = new java.util.ArrayList<>();
        for (long pos : candidates) {
            if (policy.hideContainer(viewer, visible, "world", "minecraft:chest",
                    BlockKeys.x(pos), BlockKeys.y(pos), BlockKeys.z(pos))) {
                withheld.add(pos);
            }
        }
        assertEquals(List.of(BlockKeys.key(5, 70, 5)), withheld);
        int[][] mask = ContainerBlockMask.bySection(0, 0, MIN_SECTION, SECTIONS,
                withheld.stream().mapToLong(Long::longValue).toArray());
        int section = (70 >> 4) - MIN_SECTION;
        assertTrue(ContainerBlockMask.replaced(mask[section], ContainerBlockMask.localIndex(5, 70, 5)));
        assertFalse(ContainerBlockMask.replaced(mask[section], ContainerBlockMask.localIndex(2, 70, 2)));
    }

    // ---------------------------------------------------------------- resend on change

    @Test
    void withheldContainerBecomingVisibleSchedulesResendOfItsChunk() {
        WithheldContainers withheld = new WithheldContainers();
        withheld.setChunk(0, 0, new long[] {BlockKeys.key(4, 64, 4)});
        Map<Long, long[]> snapshot = withheld.snapshot();

        // Behind a wall: stays replaced, no resend.
        ContainerPlanner.Result blocked = ContainerPlanner.plan(ContainerRule.defaults(), 10.5, 65.6, 4.5,
                snapshot, Set.of(), (x, y, z) -> x == 7, new TickCache<>(), 1);
        assertTrue(blocked.resendChunks().isEmpty());
        assertFalse(blocked.visible().contains(BlockKeys.key(4, 64, 4)));

        // Wall gone: the chunk is resent so the real state and block entity replace the substitute.
        ContainerPlanner.Result open = ContainerPlanner.plan(ContainerRule.defaults(), 10.5, 65.6, 4.5,
                snapshot, Set.of(), (x, y, z) -> false, new TickCache<>(), 2);
        assertEquals(Set.of(ChunkKeys.key(0, 0)), open.resendChunks());
        assertTrue(open.visible().contains(BlockKeys.key(4, 64, 4)));
    }

    @Test
    void resentChunkClearsTheWithheldRecordSoNoReplacementIsStale() {
        WithheldContainers withheld = new WithheldContainers();
        long pos = BlockKeys.key(4, 64, 4);
        withheld.setChunk(0, 0, new long[] {pos});
        // The resend carries the container (now visible): the gate withholds nothing in that chunk.
        withheld.setChunk(0, 0, new long[0]);
        assertFalse(withheld.contains(4, 64, 4));
        assertNull(ContainerBlockMask.bySection(0, 0, MIN_SECTION, SECTIONS,
                withheld.snapshot().getOrDefault(ChunkKeys.key(0, 0), new long[0])));
        // A delivered container that stays in view does not trigger further resends.
        ContainerPlanner.Result again = ContainerPlanner.plan(ContainerRule.defaults(), 10.5, 65.6, 4.5,
                withheld.snapshot(), Set.of(pos), (x, y, z) -> false, new TickCache<>(), 3);
        assertTrue(again.resendChunks().isEmpty());
        assertTrue(again.visible().contains(pos));
    }

    // ---------------------------------------------------------------- block updates

    @Test
    void blockUpdateDecisions() {
        // Rule or replacement off: untouched.
        assertEquals(UpdateAction.PASS, ContainerBlockMask.blockUpdate(false, true, true, true, false));
        // Hidden container far away, not yet withheld: replaced and recorded (planner resends when visible).
        assertEquals(UpdateAction.REPLACE_AND_WITHHOLD, ContainerBlockMask.blockUpdate(true, true, true, false, false));
        // Hidden container already withheld: stays replaced.
        assertEquals(UpdateAction.REPLACE, ContainerBlockMask.blockUpdate(true, true, true, true, false));
        assertEquals(UpdateAction.REPLACE, ContainerBlockMask.blockUpdate(true, true, true, true, true));
        // Container placed next to the viewer: real state, no replacement flash.
        assertEquals(UpdateAction.PASS, ContainerBlockMask.blockUpdate(true, true, true, false, true));
        // Visible container: real state; a withheld record is kept so its chunk is resent with the block entity.
        assertEquals(UpdateAction.PASS, ContainerBlockMask.blockUpdate(true, true, false, true, false));
        // Container removed at a withheld position: real state and the record is dropped (nothing left to resend).
        assertEquals(UpdateAction.PASS_AND_RELEASE, ContainerBlockMask.blockUpdate(true, false, false, true, false));
        assertEquals(UpdateAction.PASS, ContainerBlockMask.blockUpdate(true, false, false, false, false));
    }

    @Test
    void nearIsMeasuredFromTheViewerInTheSameWorld() {
        ViewerState viewer = new ViewerState("world", 0.5, 64, 0.5, false, false);
        assertTrue(ContainerBlockMask.near(viewer, "world", 3, 64, 3));
        assertFalse(ContainerBlockMask.near(viewer, "world", 10, 64, 0));
        assertFalse(ContainerBlockMask.near(viewer, "nether", 0, 64, 0));
        assertFalse(ContainerBlockMask.near(ViewerState.unknown(), "world", 0, 0, 0));
    }

    @Test
    void releaseAndWithholdKeepBookkeepingConsistent() {
        WithheldContainers withheld = new WithheldContainers();
        withheld.add(20, 64, 20);
        assertTrue(withheld.contains(20, 64, 20));
        Map<Long, long[]> snap = withheld.snapshot();
        assertArrayEquals(new long[] {BlockKeys.key(20, 64, 20)}, snap.get(ChunkKeys.key(1, 1)));
        withheld.remove(20, 64, 20);
        assertTrue(withheld.isEmpty());
    }
}
