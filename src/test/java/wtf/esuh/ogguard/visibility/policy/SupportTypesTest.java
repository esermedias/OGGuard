package wtf.esuh.ogguard.visibility.policy;

import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupportTypesTest {

    // ---------------------------------------------------------------- LightMask

    @Test
    void lightMaskRemovesMaskedArraysAndMarksThemEmpty() {
        BitSet data = new BitSet();
        data.set(1);
        data.set(3);
        data.set(5);
        BitSet empty = new BitSet();
        empty.set(0);
        byte[] a = {1};
        byte[] b = {3};
        byte[] c = {5};
        LightMask.Layer out = LightMask.apply(data, empty, List.of(a, b, c), i -> i <= 3, 26);
        assertEquals(List.of(c), out.data());
        assertFalse(out.dataMask().get(1));
        assertFalse(out.dataMask().get(3));
        assertTrue(out.dataMask().get(5));
        assertTrue(out.emptyMask().get(0));
        assertTrue(out.emptyMask().get(1));
        assertTrue(out.emptyMask().get(3));
        assertTrue(data.get(1), "input mask is not modified");
    }

    @Test
    void lightMaskAffectsDetectsWork() {
        BitSet data = new BitSet();
        data.set(10);
        assertFalse(LightMask.affects(data, i -> i < 5, 26));
        assertTrue(LightMask.affects(data, i -> i == 10, 26));
        assertFalse(LightMask.affects(data, i -> true, 10), "indices at or above maxIndex are ignored");
    }

    @Test
    void lightMaskRejectsInconsistentInput() {
        BitSet data = new BitSet();
        data.set(0);
        assertThrows(IllegalArgumentException.class,
                () -> LightMask.apply(data, new BitSet(), List.of(), i -> true, 26));
    }

    // ---------------------------------------------------------------- ClientChunkLedger

    @Test
    void ledgerTracksClientChunksAndGatesResyncs() {
        ClientChunkLedger ledger = new ClientChunkLedger();
        ledger.onChunkSent(1, 2);
        ledger.onChunkSent(-3, 4);
        assertTrue(ledger.admitResync(1, 2));
        ledger.onForget(1, 2);
        assertFalse(ledger.admitResync(1, 2), "resync after forget would create a ghost chunk");
        assertEquals(1, ledger.snapshot().size());
        ledger.clear();
        assertEquals(0, ledger.size());
    }

    @Test
    void chunkKeysRoundTripNegativeCoordinates() {
        long k = ChunkKeys.key(-30000, 29999);
        assertEquals(-30000, ChunkKeys.x(k));
        assertEquals(29999, ChunkKeys.z(k));
    }

    // ---------------------------------------------------------------- Cuboid

    @Test
    void cuboidNormalisesCornersAndChecksContainment() {
        Cuboid c = Cuboid.of("w", 10, -1, 10, 0, -20, 0);
        assertEquals(0, c.minX());
        assertEquals(-20, c.minY());
        assertTrue(c.contains("w", 5, -10, 5));
        assertTrue(c.contains("w", -0.0, -1.5, 9.99));
        assertFalse(c.contains("w", -0.01, -10, 5));
        assertFalse(c.contains("x", 5, -10, 5));
        assertTrue(c.covers("w", 0, -16, 0, 10, -1, 10));
        assertFalse(c.covers("w", 0, -32, 0, 10, -1, 10));
    }

    // ---------------------------------------------------------------- config parsing

    @Test
    void parserReadsAllFieldsAndRegions() {
        Map<String, Object> m = Map.of(
                "enabled", true,
                "boundary-y", 0,
                "reveal-enter-y", 2,
                "conceal-exit-y", 10,
                "reveal-radius-chunks", 5,
                "replacement", "stone",
                "protect-all-below-boundary", false,
                "worlds", List.of("world"),
                "protected-regions", List.of(Map.of("world", "world", "min", List.of(0, -64, 0),
                        "max", List.of(15, -1, 15))),
                "reveal-zones", List.of());
        VisibilityConfig c = VisibilityConfigParser.parse(m);
        assertEquals(Replacement.STONE, c.replacement());
        assertEquals(2, c.revealEnterY());
        assertEquals(5, c.revealRadiusChunks());
        assertFalse(c.protectAllBelowBoundary());
        assertEquals(1, c.protectedRegions().size());
        assertEquals(-64, c.protectedRegions().get(0).minY());
    }

    @Test
    void parserDefaultsMatchDocumentedDefaults() {
        VisibilityConfig c = VisibilityConfigParser.parse(Map.of());
        assertEquals(VisibilityConfig.defaults(), c);
        assertEquals(0, c.boundaryY());
        assertSame(Replacement.DEEPSLATE, c.replacement());
    }

    @Test
    void parserRejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class,
                () -> VisibilityConfigParser.parse(Map.of("replacement", "glass")));
        assertThrows(IllegalArgumentException.class,
                () -> VisibilityConfigParser.parse(Map.of("reveal-enter-y", 20, "conceal-exit-y", 10)));
        assertThrows(IllegalArgumentException.class,
                () -> VisibilityConfigParser.parse(Map.of("reveal-radius-chunks", 99)));
        assertThrows(IllegalArgumentException.class,
                () -> VisibilityConfigParser.parse(Map.of("protected-regions",
                        List.of(Map.of("world", "w", "min", List.of(1, 2)), Map.of()))));
        assertArrayEquals(new Object[0], VisibilityConfigParser.cuboids(null, "x").toArray());
    }
}
