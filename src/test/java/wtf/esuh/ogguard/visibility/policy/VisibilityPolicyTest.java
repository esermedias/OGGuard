package wtf.esuh.ogguard.visibility.policy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static wtf.esuh.ogguard.visibility.policy.SectionDecision.MASK_ALL;
import static wtf.esuh.ogguard.visibility.policy.SectionDecision.MASK_PARTIAL;
import static wtf.esuh.ogguard.visibility.policy.SectionDecision.PASS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisibilityPolicyTest {

    static final String W = "world";

    static VisibilityConfig cfg(boolean protectAll, List<Cuboid> regions, List<Cuboid> zones) {
        return new VisibilityConfig(true, 0, 4, 12, 3, Replacement.DEEPSLATE, protectAll, Set.of(),
                regions, zones, true, true, true, true, true);
    }

    static ViewerState surface(double x, double z) {
        return new ViewerState(W, x, 70, z, false, false);
    }

    static ViewerState revealedAt(double x, double y, double z) {
        return new ViewerState(W, x, y, z, true, false);
    }

    @Nested
    class YBoundary {
        final VisibilityPolicy p = new VisibilityPolicy(cfg(true, List.of(), List.of()));

        @Test
        void blocksBelowZeroAreProtectedAndZeroIsNot() {
            assertTrue(p.isProtected(W, 5, -1, 5));
            assertTrue(p.isProtected(W, 5, -64, 5));
            assertFalse(p.isProtected(W, 5, 0, 5));
            assertFalse(p.isProtected(W, 5, 100, 5));
        }

        @Test
        void sectionMinusOneIsFullyMaskedAndSectionZeroPasses() {
            ViewerState v = surface(0, 0);
            assertEquals(MASK_ALL, p.sectionDecision(v, W, 0, -1, 0));
            assertEquals(MASK_ALL, p.sectionDecision(v, W, 0, -4, 0));
            assertEquals(PASS, p.sectionDecision(v, W, 0, 0, 0));
            assertEquals(PASS, p.sectionDecision(v, W, 0, 5, 0));
        }

        @Test
        void unalignedBoundaryMakesStraddlingSectionPartial() {
            VisibilityConfig c = new VisibilityConfig(true, 8, 12, 20, 3, Replacement.AIR, true, Set.of(),
                    List.of(), List.of(), true, true, true, true, true);
            VisibilityPolicy q = new VisibilityPolicy(c);
            ViewerState v = surface(0, 0);
            assertEquals(MASK_PARTIAL, q.sectionDecision(v, W, 0, 0, 0));
            assertTrue(q.maskBlock(v, W, 0, 7, 0));
            assertFalse(q.maskBlock(v, W, 0, 8, 0));
        }

        @Test
        void disabledPassesEverything() {
            VisibilityConfig c = new VisibilityConfig(false, 0, 4, 12, 3, Replacement.AIR, true, Set.of(),
                    List.of(), List.of(), true, true, true, true, true);
            VisibilityPolicy q = new VisibilityPolicy(c);
            assertEquals(PASS, q.sectionDecision(surface(0, 0), W, 0, -1, 0));
            assertFalse(q.maskBlock(surface(0, 0), W, 0, -10, 0));
        }

        @Test
        void worldFilterLimitsProtection() {
            VisibilityConfig c = new VisibilityConfig(true, 0, 4, 12, 3, Replacement.AIR, true, Set.of(W),
                    List.of(), List.of(), true, true, true, true, true);
            VisibilityPolicy q = new VisibilityPolicy(c);
            assertTrue(q.isProtected(W, 0, -5, 0));
            assertFalse(q.isProtected("other", 0, -5, 0));
        }
    }

    @Nested
    class Regions {
        // Region covers chunk (0,0) x/z fully, y -32..-1 => sections -2 and -1 fully; chunk (1,0) half.
        final Cuboid base = Cuboid.of(W, 0, -32, 0, 23, -1, 15);
        final VisibilityPolicy p = new VisibilityPolicy(cfg(false, List.of(base), List.of()));

        @Test
        void fullyCoveredSectionIsMaskAll() {
            assertEquals(MASK_ALL, p.sectionDecision(surface(0, 0), W, 0, -1, 0));
            assertEquals(MASK_ALL, p.sectionDecision(surface(0, 0), W, 0, -2, 0));
        }

        @Test
        void partlyCoveredSectionIsPartial() {
            assertEquals(MASK_PARTIAL, p.sectionDecision(surface(0, 0), W, 1, -1, 0));
            assertTrue(p.maskBlock(surface(0, 0), W, 23, -1, 0));
            assertFalse(p.maskBlock(surface(0, 0), W, 24, -1, 0));
        }

        @Test
        void sectionsOutsideRegionPass() {
            assertEquals(PASS, p.sectionDecision(surface(0, 0), W, 0, -3, 0));
            assertEquals(PASS, p.sectionDecision(surface(0, 0), W, 5, -1, 5));
            assertEquals(PASS, p.sectionDecision(surface(0, 0), "other", 0, -1, 0));
        }

        @Test
        void regionPartAboveBoundaryIsNeverMasked() {
            Cuboid tall = Cuboid.of(W, 0, -16, 0, 15, 40, 15);
            VisibilityPolicy q = new VisibilityPolicy(cfg(false, List.of(tall), List.of()));
            assertEquals(MASK_ALL, q.sectionDecision(surface(0, 0), W, 0, -1, 0));
            assertEquals(PASS, q.sectionDecision(surface(0, 0), W, 0, 0, 0));
            assertFalse(q.isProtected(W, 3, 10, 3));
        }

        @Test
        void runtimeRegionsAreMergedWithConfigRegions() {
            Cuboid extra = Cuboid.of(W, 100, -10, 100, 110, -1, 110);
            VisibilityPolicy q = new VisibilityPolicy(cfg(false, List.of(base), List.of()), List.of(extra));
            assertTrue(q.isProtected(W, 105, -5, 105));
            assertTrue(q.chunkHasProtection(W, 6, 6));
            assertFalse(q.chunkHasProtection(W, 20, 20));
        }
    }

    @Nested
    class Reveal {
        final Cuboid zone = Cuboid.of(W, 500, 60, 500, 505, 80, 505);
        final VisibilityPolicy p = new VisibilityPolicy(cfg(true, List.of(), List.of(zone)));

        @Test
        void hysteresisEnterBelowFourExitAtTwelve() {
            ViewerState s = p.nextState(null, W, 0, 70, 0, false);
            assertFalse(s.revealed());
            s = p.nextState(s, W, 0, 4.0, 0, false);
            assertFalse(s.revealed(), "feet exactly at reveal-enter-y stay concealed");
            s = p.nextState(s, W, 0, 3.9, 0, false);
            assertTrue(s.revealed());
            s = p.nextState(s, W, 0, 11.9, 0, false);
            assertTrue(s.revealed(), "stays revealed below conceal-exit-y");
            s = p.nextState(s, W, 0, 12.0, 0, false);
            assertFalse(s.revealed());
        }

        @Test
        void undergroundPlayerIsRevealed() {
            assertTrue(p.nextState(null, W, 0, -20, 0, false).revealed());
        }

        @Test
        void worldChangeResetsHysteresis() {
            ViewerState s = p.nextState(null, W, 0, -20, 0, false);
            ViewerState n = p.nextState(s, "other", 0, 8, 0, false);
            assertFalse(n.revealed());
        }

        @Test
        void revealZoneRevealsRegardlessOfY() {
            assertTrue(p.nextState(null, W, 502.5, 70, 502.5, false).revealed());
            assertFalse(p.nextState(null, W, 510, 70, 510, false).revealed());
        }

        @Test
        void revealIsLimitedToRadius() {
            ViewerState v = revealedAt(8, -10, 8); // chunk 0,0, radius 3
            assertTrue(p.chunkRevealed(v, W, 3, -3));
            assertFalse(p.chunkRevealed(v, W, 4, 0));
            assertEquals(PASS, p.sectionDecision(v, W, 2, -1, 2));
            assertEquals(MASK_ALL, p.sectionDecision(v, W, 4, -1, 0));
            assertFalse(p.maskBlock(v, W, 40, -5, 40));
            assertTrue(p.maskBlock(v, W, 70, -5, 0));
        }

        @Test
        void revealDoesNotCrossWorlds() {
            ViewerState v = revealedAt(8, -10, 8);
            assertFalse(p.chunkRevealed(v, "other", 0, 0));
        }

        @Test
        void bypassSeesEverything() {
            ViewerState v = new ViewerState(W, 0, 100, 0, false, true);
            assertEquals(PASS, p.sectionDecision(v, W, 50, -1, 50));
        }

        @Test
        void unknownViewerFailsClosed() {
            ViewerState v = ViewerState.unknown();
            assertEquals(MASK_ALL, p.sectionDecision(v, W, 0, -1, 0));
            assertTrue(p.maskBlock(v, W, 0, -1, 0));
        }
    }

    @Nested
    class EntitiesAndLight {
        @Test
        void entitiesBelowBoundaryHiddenFromSurfaceViewer() {
            VisibilityPolicy p = new VisibilityPolicy(cfg(true, List.of(), List.of()));
            assertTrue(p.hideEntity(surface(0, 0), W, 1.5, -0.01, 1.5, false));
            assertFalse(p.hideEntity(surface(0, 0), W, 1.5, 0.0, 1.5, false));
            assertFalse(p.hideEntity(revealedAt(0, -5, 0), W, 1.5, -10, 1.5, false));
        }

        @Test
        void hidePlayersFlagIsRespected() {
            VisibilityConfig c = new VisibilityConfig(true, 0, 4, 12, 3, Replacement.AIR, true, Set.of(),
                    List.of(), List.of(), true, true, false, true, true);
            VisibilityPolicy p = new VisibilityPolicy(c);
            assertFalse(p.hideEntity(surface(0, 0), W, 0, -10, 0, true));
            assertTrue(p.hideEntity(surface(0, 0), W, 0, -10, 0, false));
        }

        @Test
        void blockEntitiesAndEffectsFollowMasking() {
            VisibilityPolicy p = new VisibilityPolicy(cfg(true, List.of(), List.of()));
            assertTrue(p.maskBlockEntity(surface(0, 0), W, 3, -3, 3));
            assertFalse(p.maskBlockEntity(surface(0, 0), W, 3, 3, 3));
            assertTrue(p.maskEffect(surface(0, 0), W, 3.5, -2.5, 3.5));
        }

        @Test
        void lightMaskedOnlyForMaskedSections() {
            VisibilityPolicy p = new VisibilityPolicy(cfg(true, List.of(), List.of()));
            assertTrue(p.maskLightSection(surface(0, 0), W, 0, -1, 0));
            assertTrue(p.maskLightSection(surface(0, 0), W, 0, -5, 0)); // padding light section below world
            assertFalse(p.maskLightSection(surface(0, 0), W, 0, 0, 0));
            assertFalse(p.maskLightSection(revealedAt(0, -5, 0), W, 0, -1, 0));
        }
    }
}
