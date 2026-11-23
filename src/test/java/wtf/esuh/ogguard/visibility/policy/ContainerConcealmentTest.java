package wtf.esuh.ogguard.visibility.policy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerConcealmentTest {

    static final ContainerSight.Solidity AIR = (x, y, z) -> false;

    /** Solid wall: the plane x == wallX, everywhere. */
    static ContainerSight.Solidity wallAtX(int wallX) {
        return (x, y, z) -> x == wallX;
    }

    static ContainerSight.Solidity blocks(Set<Long> solid) {
        return (x, y, z) -> solid.contains(BlockKeys.key(x, y, z));
    }

    // ---------------------------------------------------------------- keys

    @Test
    void blockKeysRoundTripIncludingNegatives() {
        int[][] samples = {{0, 0, 0}, {-1, -64, -1}, {123456, 319, -98765}, {-33554432, -2048, 33554431}};
        for (int[] s : samples) {
            long k = BlockKeys.key(s[0], s[1], s[2]);
            assertEquals(s[0], BlockKeys.x(k));
            assertEquals(s[1], BlockKeys.y(k));
            assertEquals(s[2], BlockKeys.z(k));
        }
        assertEquals(ChunkKeys.key(-1, 2), BlockKeys.chunkKey(BlockKeys.key(-5, 10, 40)));
    }

    // ---------------------------------------------------------------- rule + parser

    @Nested
    class Rule {
        @Test
        void defaultsCoverTheRequiredContainers() {
            ContainerRule r = ContainerRule.defaults();
            assertTrue(r.enabled());
            assertEquals(24.0, r.maxDistance());
            for (String id : List.of("chest", "trapped_chest", "ender_chest", "furnace", "blast_furnace", "smoker",
                    "barrel", "shulker_box", "hopper", "dispenser", "dropper", "brewing_stand")) {
                assertTrue(r.matches(id), id);
                assertTrue(r.matches("minecraft:" + id), id);
            }
            assertFalse(r.matches("minecraft:sign"));
            assertFalse(r.matches(null));
        }

        @Test
        void idsAreNormalized() {
            ContainerRule r = new ContainerRule(true, 10, 2, Set.of(" Chest ", "mymod:CRATE", ""));
            assertEquals(Set.of("minecraft:chest", "mymod:crate"), r.blockEntityTypes());
            assertTrue(r.matches("CHEST"));
            assertTrue(r.matches("mymod:crate"));
        }

        @Test
        void parserReadsAllKeys() {
            ContainerRule r = ContainerRuleParser.parse(Map.of("enabled", false, "max-distance", 16.5,
                    "check-interval-ticks", 10, "block-entity-types", List.of("barrel")));
            assertFalse(r.enabled());
            assertEquals(16.5, r.maxDistance());
            assertEquals(10, r.checkIntervalTicks());
            assertEquals(Set.of("minecraft:barrel"), r.blockEntityTypes());
            assertEquals(ContainerRule.defaults(), ContainerRuleParser.parse(Map.of()));
        }

        @Test
        void parserRejectsInvalidValues() {
            assertThrows(IllegalArgumentException.class, () -> ContainerRuleParser.parse(Map.of("max-distance", 0)));
            assertThrows(IllegalArgumentException.class, () -> ContainerRuleParser.parse(Map.of("max-distance", 500)));
            assertThrows(IllegalArgumentException.class,
                    () -> ContainerRuleParser.parse(Map.of("check-interval-ticks", 0)));
            assertThrows(IllegalArgumentException.class,
                    () -> ContainerRuleParser.parse(Map.of("check-interval-ticks", 2.5)));
            assertThrows(IllegalArgumentException.class, () -> ContainerRuleParser.parse(Map.of("enabled", "yes")));
            assertThrows(IllegalArgumentException.class,
                    () -> ContainerRuleParser.parse(Map.of("block-entity-types", "chest")));
        }
    }

    // ---------------------------------------------------------------- geometry

    @Nested
    class Sight {
        @Test
        void distanceUsesBlockCentre() {
            assertTrue(ContainerSight.withinDistance(0.5, 0.5, 0.5, 24, 0, 0, 24));
            assertFalse(ContainerSight.withinDistance(0.5, 0.5, 0.5, 25, 0, 0, 24));
            assertTrue(ContainerSight.chunkInRange(0, 0, 1, 0, 24));
            assertFalse(ContainerSight.chunkInRange(0, 0, 3, 0, 24)); // chunk 3 starts at x=48
        }

        @Test
        void openAirIsVisible() {
            assertTrue(ContainerSight.lineOfSight(0.5, 1.6, 0.5, 10, 0, 7, AIR));
            assertTrue(ContainerSight.visible(0.5, 1.6, 0.5, 10, 0, 7, 24, AIR));
        }

        @Test
        void wallInBetweenHides() {
            assertFalse(ContainerSight.lineOfSight(0.5, 1.6, 0.5, 10, 1, 0, wallAtX(5)));
            // wall behind the container does not matter
            assertTrue(ContainerSight.lineOfSight(0.5, 1.6, 0.5, 10, 1, 0, wallAtX(12)));
        }

        @Test
        void outOfRangeIsHiddenEvenInOpenAir() {
            assertFalse(ContainerSight.visible(0.5, 1.6, 0.5, 30, 1, 0, 24, AIR));
        }

        @Test
        void containerItselfAndEyeCellDoNotBlock() {
            Set<Long> solid = new HashSet<>();
            solid.add(BlockKeys.key(10, 1, 0)); // the target (e.g. a barrel is an opaque cube)
            solid.add(BlockKeys.key(0, 1, 0));  // the cell the eye is in
            assertTrue(ContainerSight.lineOfSight(0.5, 1.6, 0.5, 10, 1, 0, blocks(solid)));
        }

        @Test
        void holeInWallRevealsThroughCornerRays() {
            // Wall at x=5 spanning y 0..3, z -3..3 with a single hole at (5,1,0): the centre ray passes the hole.
            Set<Long> solid = new HashSet<>();
            for (int y = 0; y <= 3; y++) {
                for (int z = -3; z <= 3; z++) {
                    if (!(y == 1 && z == 0)) {
                        solid.add(BlockKeys.key(5, y, z));
                    }
                }
            }
            assertTrue(ContainerSight.lineOfSight(0.5, 1.5, 0.5, 10, 1, 0, blocks(solid)));
            solid.add(BlockKeys.key(5, 1, 0));
            assertFalse(ContainerSight.lineOfSight(0.5, 1.5, 0.5, 10, 1, 0, blocks(solid)));
        }

        @Test
        void containerBelowFloorIsHidden() {
            ContainerSight.Solidity floor = (x, y, z) -> y == 0;
            assertFalse(ContainerSight.lineOfSight(0.5, 2.6, 0.5, 3, -1, 2, floor));
        }

        @Test
        void negativeCoordinatesWork() {
            assertFalse(ContainerSight.lineOfSight(-0.5, 1.6, -0.5, -10, 1, -1, wallAtX(-5)));
            assertTrue(ContainerSight.lineOfSight(-0.5, 1.6, -0.5, -10, 1, -1, AIR));
        }
    }

    // ---------------------------------------------------------------- cache + withheld bookkeeping

    @Test
    void tickCacheReusesWithinTickAndClearsOnNextTick() {
        TickCache<Map<Long, Boolean>> cache = new TickCache<>();
        AtomicInteger computed = new AtomicInteger();
        Map<Long, Boolean> a = cache.get(5, 1L, k -> {
            computed.incrementAndGet();
            return new HashMap<>();
        });
        Map<Long, Boolean> b = cache.get(5, 1L, k -> {
            computed.incrementAndGet();
            return new HashMap<>();
        });
        assertTrue(a == b);
        assertEquals(1, computed.get());
        cache.get(6, 1L, k -> {
            computed.incrementAndGet();
            return new HashMap<>();
        });
        assertEquals(2, computed.get());
        assertEquals(1, cache.size());
    }

    @Test
    void withheldTracksPerChunk() {
        WithheldContainers w = new WithheldContainers();
        w.setChunk(0, 0, new long[] {BlockKeys.key(1, 2, 3)});
        w.add(1, 2, 3); // duplicate ignored
        w.add(4, 5, 6);
        w.add(-1, 5, -1); // chunk -1,-1
        assertTrue(w.contains(4, 5, 6));
        assertEquals(2, w.snapshot().get(ChunkKeys.key(0, 0)).length);
        w.remove(1, 2, 3);
        assertFalse(w.contains(1, 2, 3));
        w.setChunk(0, 0, new long[0]);
        assertFalse(w.snapshot().containsKey(ChunkKeys.key(0, 0)));
        w.forgetChunk(-1, -1);
        assertTrue(w.isEmpty());
    }

    // ---------------------------------------------------------------- planner

    @Nested
    class Planner {
        final ContainerRule rule = ContainerRule.defaults();

        Map<Long, long[]> withheld(long... positions) {
            Map<Long, long[]> m = new HashMap<>();
            for (long p : positions) {
                long chunk = BlockKeys.chunkKey(p);
                long[] old = m.getOrDefault(chunk, new long[0]);
                long[] next = java.util.Arrays.copyOf(old, old.length + 1);
                next[old.length] = p;
                m.put(chunk, next);
            }
            return m;
        }

        @Test
        void visibleWithheldContainerTriggersChunkResend() {
            long near = BlockKeys.key(10, 64, 3);
            long far = BlockKeys.key(100, 64, 3);
            ContainerPlanner.Result r = ContainerPlanner.plan(rule, 0.5, 65.6, 0.5, withheld(near, far), Set.of(),
                    AIR, new TickCache<>(), 1);
            assertEquals(Set.of(near), r.visible());
            assertEquals(Set.of(ChunkKeys.key(0, 0)), r.resendChunks());
        }

        @Test
        void containerBehindWallStaysWithheld() {
            long behind = BlockKeys.key(10, 64, 3);
            ContainerPlanner.Result r = ContainerPlanner.plan(rule, 0.5, 65.6, 0.5, withheld(behind), Set.of(),
                    wallAtX(5), new TickCache<>(), 1);
            assertTrue(r.visible().isEmpty());
            assertTrue(r.resendChunks().isEmpty());
        }

        @Test
        void previouslyVisibleStaysVisibleWithoutResend() {
            long delivered = BlockKeys.key(-6, 64, -6);
            ContainerPlanner.Result r = ContainerPlanner.plan(rule, 0.5, 65.6, 0.5, Map.of(), Set.of(delivered),
                    AIR, new TickCache<>(), 1);
            assertEquals(Set.of(delivered), r.visible());
            assertTrue(r.resendChunks().isEmpty());
            // walking away drops it from the visible set
            ContainerPlanner.Result later = ContainerPlanner.plan(rule, 60.5, 65.6, 0.5, Map.of(), r.visible(),
                    AIR, new TickCache<>(), 2);
            assertTrue(later.visible().isEmpty());
        }

        @Test
        void lineOfSightIsCachedPerChunkPerTick() {
            long pos = BlockKeys.key(10, 64, 3);
            AtomicInteger lookups = new AtomicInteger();
            ContainerSight.Solidity counting = (x, y, z) -> {
                lookups.incrementAndGet();
                return false;
            };
            TickCache<Map<Long, Boolean>> cache = new TickCache<>();
            ContainerPlanner.plan(rule, 0.5, 65.6, 0.5, withheld(pos), Set.of(), counting, cache, 7);
            int first = lookups.get();
            assertTrue(first > 0);
            ContainerPlanner.plan(rule, 0.5, 65.6, 0.5, withheld(pos), Set.of(), counting, cache, 7);
            assertEquals(first, lookups.get()); // same tick: cached
            ContainerPlanner.plan(rule, 0.5, 65.6, 0.5, withheld(pos), Set.of(), counting, cache, 8);
            assertEquals(2 * first, lookups.get()); // next tick: recomputed
        }
    }

    // ---------------------------------------------------------------- policy decision

    @Nested
    class PolicyDecision {
        final VisibilityPolicy policy = new VisibilityPolicy(VisibilityConfig.defaults(), List.of(),
                ContainerRule.defaults());
        final ViewerState viewer = new ViewerState("world", 0, 70, 0, false, false);

        @Test
        void containerHiddenUnlessInVisibleSnapshot() {
            VisibleContainers none = VisibleContainers.none();
            VisibleContainers seen = new VisibleContainers("world", Set.of(BlockKeys.key(5, 64, 5)));
            assertTrue(policy.hideContainer(viewer, none, "world", "minecraft:chest", 5, 64, 5));
            assertFalse(policy.hideContainer(viewer, seen, "world", "minecraft:chest", 5, 64, 5));
            assertTrue(policy.hideContainer(viewer, seen, "world", "minecraft:chest", 6, 64, 5));
            // snapshot of another world does not count
            assertTrue(policy.hideContainer(viewer, seen, "world_nether", "minecraft:chest", 5, 64, 5));
        }

        @Test
        void nonContainersAreNeverTouched() {
            assertFalse(policy.hideContainer(viewer, VisibleContainers.none(), "world", "minecraft:sign", 5, 64, 5));
            assertFalse(policy.hideContainer(viewer, VisibleContainers.none(), "world", null, 5, 64, 5));
        }

        @Test
        void bypassDisabledRuleAndMasterSwitch() {
            ViewerState bypass = new ViewerState("world", 0, 70, 0, false, true);
            assertFalse(policy.hideContainer(bypass, VisibleContainers.none(), "world", "chest", 5, 64, 5));
            VisibilityPolicy off = new VisibilityPolicy(VisibilityConfig.defaults(), List.of(),
                    ContainerRule.disabled());
            assertFalse(off.hideContainer(viewer, VisibleContainers.none(), "world", "chest", 5, 64, 5));
            VisibilityPolicy legacy = new VisibilityPolicy(VisibilityConfig.defaults());
            assertFalse(legacy.containerRuleActive(viewer));
        }

        @Test
        void unknownViewerFailsClosed() {
            VisibleContainers seen = new VisibleContainers("world", Set.of(BlockKeys.key(5, 64, 5)));
            assertTrue(policy.hideContainer(ViewerState.unknown(), seen, "world", "chest", 5, 64, 5));
        }
    }

    @Test
    void visibleContainersPositionsByWorld() {
        VisibleContainers v = new VisibleContainers("world", Set.of(1L));
        assertEquals(Set.of(1L), v.positionsIn("world"));
        assertTrue(v.positionsIn("other").isEmpty());
        assertArrayEquals(new Object[0], VisibleContainers.none().positionsIn("world").toArray());
    }
}
