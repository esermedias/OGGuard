package wtf.esuh.ogguard.visibility.policy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * All packet-visibility decisions, as pure functions of an immutable config and an immutable viewer snapshot.
 * No Netty, Bukkit or Minecraft imports: every rule here is covered by JUnit tests. Instances are immutable and
 * therefore safe to share between region threads and Netty event loops.
 */
public final class VisibilityPolicy {

    private final VisibilityConfig config;
    private final List<Cuboid> regions;
    private final ContainerRule containerRule;

    /**
     * @param config        loaded configuration
     * @param extraRegions  regions registered at runtime through the guard API (for example by a base or claim plugin)
     * @param containerRule container concealment rule
     */
    public VisibilityPolicy(VisibilityConfig config, List<Cuboid> extraRegions, ContainerRule containerRule) {
        this.config = config;
        List<Cuboid> all = new ArrayList<>(config.protectedRegions());
        all.addAll(extraRegions);
        this.regions = List.copyOf(all);
        this.containerRule = containerRule == null ? ContainerRule.disabled() : containerRule;
    }

    /** Container concealment disabled. */
    public VisibilityPolicy(VisibilityConfig config, List<Cuboid> extraRegions) {
        this(config, extraRegions, ContainerRule.disabled());
    }

    public VisibilityPolicy(VisibilityConfig config) {
        this(config, List.of());
    }

    public VisibilityConfig config() {
        return config;
    }

    public List<Cuboid> regions() {
        return regions;
    }

    public ContainerRule containerRule() {
        return containerRule;
    }

    public boolean enabled() {
        return config.enabled();
    }

    /** False for excluded worlds (packet-visibility.excluded-worlds) and worlds outside packet-visibility.worlds. */
    public boolean worldCovered(String world) {
        return WorldExclusion.covered(config.worlds(), config.excludedWorlds(), world);
    }

    /** True when Guard must not touch this world at all; every packet for it passes unchanged. */
    public boolean worldExcluded(String world) {
        return WorldExclusion.excluded(config.excludedWorlds(), world);
    }

    // ------------------------------------------------------------------ geometry

    /** True when the real block at this position belongs to protected geometry (independent of any viewer). */
    public boolean isProtected(String world, int x, int y, int z) {
        if (!config.enabled() || y >= config.boundaryY() || !worldCovered(world)) {
            return false;
        }
        if (config.protectAllBelowBoundary()) {
            return true;
        }
        for (Cuboid region : regions) {
            if (region.contains(world, x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /** True when any block of the chunk column is protected (cheap pre-check for resync planning). */
    public boolean chunkHasProtection(String world, int chunkX, int chunkZ) {
        if (!config.enabled() || !worldCovered(world)) {
            return false;
        }
        if (config.protectAllBelowBoundary()) {
            return true;
        }
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        for (Cuboid region : regions) {
            if (region.intersects(world, minX, Integer.MIN_VALUE, minZ, minX + 15, config.boundaryY() - 1, minZ + 15)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ viewer state

    public boolean inRevealZone(String world, double x, double y, double z) {
        for (Cuboid zone : config.revealZones()) {
            if (zone.contains(world, x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Computes the next viewer snapshot with hysteresis: a concealed viewer is revealed when its feet drop below
     * {@code revealEnterY} (or it stands in a reveal zone); a revealed viewer stays revealed until its feet reach
     * {@code concealExitY}. A world change resets the hysteresis.
     */
    public ViewerState nextState(ViewerState previous, String world, double x, double y, double z, boolean bypass) {
        boolean sameWorld = previous != null && previous.known() && previous.world().equals(world);
        boolean wasRevealed = sameWorld && previous.revealed();
        boolean revealed;
        if (inRevealZone(world, x, y, z)) {
            revealed = true;
        } else if (wasRevealed) {
            revealed = y < config.concealExitY();
        } else {
            revealed = y < config.revealEnterY();
        }
        return new ViewerState(world, x, y, z, revealed, bypass);
    }

    /** True when real data of this chunk may be sent to the viewer. */
    public boolean chunkRevealed(ViewerState viewer, String world, int chunkX, int chunkZ) {
        if (!config.enabled() || viewer.bypass()) {
            return true;
        }
        if (!viewer.known() || !viewer.revealed() || !viewer.world().equals(world)) {
            return false;
        }
        int r = config.revealRadiusChunks();
        return Math.abs(chunkX - viewer.chunkX()) <= r && Math.abs(chunkZ - viewer.chunkZ()) <= r;
    }

    /** True when the block at this position must not be sent as-is to the viewer. */
    public boolean maskBlock(ViewerState viewer, String world, int x, int y, int z) {
        return isProtected(world, x, y, z) && !chunkRevealed(viewer, world, x >> 4, z >> 4);
    }

    public boolean maskBlockEntity(ViewerState viewer, String world, int x, int y, int z) {
        return config.hideBlockEntities() && maskBlock(viewer, world, x, y, z);
    }

    public boolean maskEffect(ViewerState viewer, String world, double x, double y, double z) {
        return config.filterEffects()
                && maskBlock(viewer, world, Cuboid.floor(x), Cuboid.floor(y), Cuboid.floor(z));
    }

    public boolean hideEntity(ViewerState viewer, String world, double x, double y, double z, boolean player) {
        if (!config.hideEntities() || (player && !config.hidePlayers())) {
            return false;
        }
        return maskBlock(viewer, world, Cuboid.floor(x), Cuboid.floor(y), Cuboid.floor(z));
    }

    /** Light is zeroed for every section that has at least one masked block. */
    public boolean maskLightSection(ViewerState viewer, String world, int chunkX, int sectionY, int chunkZ) {
        return config.maskLight() && sectionDecision(viewer, world, chunkX, sectionY, chunkZ) != SectionDecision.PASS;
    }

    /**
     * Section-level masking rule. {@code sectionY} is the section index (block y &gt;&gt; 4).
     */
    public SectionDecision sectionDecision(ViewerState viewer, String world, int chunkX, int sectionY, int chunkZ) {
        int minY = sectionY << 4;
        int maxY = minY + 15;
        if (!config.enabled() || minY >= config.boundaryY() || !worldCovered(world)) {
            return SectionDecision.PASS;
        }
        if (chunkRevealed(viewer, world, chunkX, chunkZ)) {
            return SectionDecision.PASS;
        }
        boolean entirelyBelow = maxY < config.boundaryY();
        if (config.protectAllBelowBoundary()) {
            return entirelyBelow ? SectionDecision.MASK_ALL : SectionDecision.MASK_PARTIAL;
        }
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        int clippedMaxY = Math.min(maxY, config.boundaryY() - 1);
        boolean any = false;
        for (Cuboid region : regions) {
            if (!region.intersects(world, minX, minY, minZ, minX + 15, clippedMaxY, minZ + 15)) {
                continue;
            }
            if (entirelyBelow && region.covers(world, minX, minY, minZ, minX + 15, maxY, minZ + 15)) {
                return SectionDecision.MASK_ALL;
            }
            any = true;
        }
        return any ? SectionDecision.MASK_PARTIAL : SectionDecision.PASS;
    }

    // ------------------------------------------------------------------ containers

    /** True when the container rule applies to this viewer at all (master switch, rule switch, no bypass). */
    public boolean containerRuleActive(ViewerState viewer) {
        return config.enabled() && containerRule.enabled() && !viewer.bypass();
    }

    /**
     * True when the block entity of this type at this position must be withheld from the viewer: it is a configured
     * container type and it is not in the viewer's current {@link VisibleContainers} snapshot (distance and line of
     * sight are evaluated on the player's region thread). Fail closed: an unknown viewer sees no container.
     */
    public boolean hideContainer(ViewerState viewer, VisibleContainers visible, String world, String typeId,
                                 int x, int y, int z) {
        if (!containerRuleActive(viewer) || !worldCovered(world) || !containerRule.matches(typeId)) {
            return false;
        }
        if (!viewer.known()) {
            return true;
        }
        return visible == null || !visible.contains(world, x, y, z);
    }

    // ------------------------------------------------------------------ resync

    /**
     * Chunks the client holds whose masking differs between {@code before} and {@code after}. Only chunks that
     * contain protected geometry and that the client currently holds are returned; resending anything else would
     * either be useless or create ghost chunks client-side.
     */
    public ResyncPlan resyncPlan(ViewerState before, ViewerState after, Set<Long> clientChunks) {
        if (!config.enabled() || after == null || !after.known()) {
            return ResyncPlan.none();
        }
        String world = after.world();
        if (before != null && before.known() && before.world().equals(world)
                && before.bypass() == after.bypass() && before.revealed() == after.revealed()
                && (!after.revealed()
                || (before.chunkX() == after.chunkX() && before.chunkZ() == after.chunkZ()))) {
            return ResyncPlan.none(); // fast path: the revealed area did not move
        }
        Set<Long> candidates = new HashSet<>();
        boolean revealChanged;
        if (before == null || !before.known() || !before.world().equals(world)) {
            // Data may have been sent masked under the previous (or fail-closed) state.
            addRevealed(after, candidates);
            if (after.bypass()) {
                candidates.addAll(clientChunks);
            }
            revealChanged = true;
        } else if (before.bypass() != after.bypass()) {
            candidates.addAll(clientChunks);
            revealChanged = true;
        } else if (after.bypass()) {
            return ResyncPlan.none();
        } else {
            Set<Long> a = new HashSet<>();
            Set<Long> b = new HashSet<>();
            addRevealed(before, a);
            addRevealed(after, b);
            for (Long k : a) {
                if (!b.contains(k)) {
                    candidates.add(k);
                }
            }
            for (Long k : b) {
                if (!a.contains(k)) {
                    candidates.add(k);
                }
            }
            revealChanged = !candidates.isEmpty() || !a.equals(b);
        }
        Set<Long> out = new HashSet<>();
        for (Long k : candidates) {
            if (clientChunks.contains(k) && chunkHasProtection(world, ChunkKeys.x(k), ChunkKeys.z(k))) {
                out.add(k);
            }
        }
        return new ResyncPlan(out, revealChanged);
    }

    private void addRevealed(ViewerState viewer, Set<Long> out) {
        if (!viewer.known() || !viewer.revealed()) {
            return;
        }
        int r = config.revealRadiusChunks();
        int cx = viewer.chunkX();
        int cz = viewer.chunkZ();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                out.add(ChunkKeys.key(cx + dx, cz + dz));
            }
        }
    }
}
