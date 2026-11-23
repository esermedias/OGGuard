package wtf.esuh.ogguard.visibility.policy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldExclusionTest {

    static final String SPAWN = "spawn";
    static final String WORLD = "world";

    @Test
    void pureDecision() {
        Set<String> ex = WorldExclusion.normalize(List.of("Spawn", " ", "nether "));
        assertEquals(Set.of("spawn", "nether"), ex);
        assertTrue(WorldExclusion.excluded(ex, SPAWN));
        assertFalse(WorldExclusion.excluded(ex, WORLD));
        assertFalse(WorldExclusion.excluded(ex, null));
        assertFalse(WorldExclusion.covered(Set.of(), ex, SPAWN));
        assertTrue(WorldExclusion.covered(Set.of(), ex, WORLD));
        assertFalse(WorldExclusion.covered(Set.of(), ex, null));
        // exclusion wins over an explicit worlds entry
        assertFalse(WorldExclusion.covered(Set.of(SPAWN, WORLD), ex, SPAWN));
        assertFalse(WorldExclusion.covered(Set.of("other"), ex, WORLD));
    }

    @Test
    void defaultsExcludeNoWorld() {
        assertEquals(Set.of(), VisibilityConfig.defaults().excludedWorlds());
        assertEquals(Set.of(), VisibilityConfigParser.parse(Map.of()).excludedWorlds());
        assertEquals(Set.of(), VisibilityConfigParser.parse(Map.of("excluded-worlds", List.of())).excludedWorlds());
        assertEquals(Set.of("a"), VisibilityConfigParser.parse(Map.of("excluded-worlds", List.of("A"))).excludedWorlds());
    }

    @Test
    void everyFeatureLeavesExcludedWorldAlone() {
        ContainerRule containers = ContainerRuleParser.parse(Map.of());
        VisibilityConfig config = VisibilityConfigParser.parse(Map.of("excluded-worlds", List.of(SPAWN)));
        VisibilityPolicy p = new VisibilityPolicy(config,
                List.of(Cuboid.of(SPAWN, -10, -64, -10, 10, -1, 10)), containers);
        ViewerState spawnViewer = new ViewerState(SPAWN, 0, 70, 0, false, false);
        ViewerState unknown = ViewerState.unknown();

        assertTrue(p.worldExcluded(SPAWN));
        assertFalse(p.worldCovered(SPAWN));
        assertFalse(p.isProtected(SPAWN, 0, -10, 0));
        assertFalse(p.chunkHasProtection(SPAWN, 0, 0));
        assertFalse(p.maskBlock(spawnViewer, SPAWN, 0, -10, 0));
        assertFalse(p.maskBlock(unknown, SPAWN, 0, -10, 0));
        assertFalse(p.maskBlockEntity(spawnViewer, SPAWN, 0, -10, 0));
        assertFalse(p.maskEffect(spawnViewer, SPAWN, 0.5, -10.5, 0.5));
        assertFalse(p.hideEntity(spawnViewer, SPAWN, 0, -10, 0, true));
        assertFalse(p.maskLightSection(spawnViewer, SPAWN, 0, -1, 0));
        assertEquals(SectionDecision.PASS, p.sectionDecision(spawnViewer, SPAWN, 0, -4, 0));
        assertFalse(p.hideContainer(spawnViewer, VisibleContainers.none(), SPAWN, "minecraft:chest", 0, 64, 0));
        assertFalse(p.hideContainer(unknown, null, SPAWN, "chest", 0, 64, 0));
        assertTrue(p.resyncPlan(null, spawnViewer, Set.of(ChunkKeys.key(0, 0))).chunks().isEmpty());

        // other worlds are still guarded
        ViewerState worldViewer = new ViewerState(WORLD, 0, 70, 0, false, false);
        assertTrue(p.maskBlock(worldViewer, WORLD, 0, -10, 0));
        assertTrue(p.hideContainer(worldViewer, VisibleContainers.none(), WORLD, "chest", 0, 64, 0));
    }

    @Test
    void unknownWorldNameIsNotExcluded() {
        assertFalse(new VisibilityPolicy(VisibilityConfig.defaults()).worldExcluded(null));
    }
}
