package wtf.esuh.ogguard.visibility.policy;

import java.util.List;
import java.util.Set;

/**
 * Immutable packet-visibility settings. Pure Java; loaded from config.yml by the platform layer.
 *
 * @param enabled                 master switch; when false every packet passes unchanged
 * @param boundaryY               blocks with y &lt; boundaryY can be protected (default: 0)
 * @param revealEnterY            a concealed viewer whose feet are below this Y becomes revealed. Default 4: with the
 *                                survival block reach of 4.5 a player cannot touch a block below Y=0 before this
 *                                triggers, so the client never interacts with a fake block (no desync)
 * @param concealExitY            a revealed viewer becomes concealed again once its feet are at or above this Y
 *                                (hysteresis, must be &gt;= revealEnterY)
 * @param revealRadiusChunks      revealed viewers see real data only in chunks within this Chebyshev radius
 * @param replacement             block sent in place of hidden blocks
 * @param protectAllBelowBoundary when true everything below boundaryY is protected; otherwise only regions
 * @param worlds                  world names covered; empty means all worlds
 * @param protectedRegions        configured protected cuboids (clipped to y &lt; boundaryY)
 * @param revealZones             cuboids that reveal nearby geometry while the viewer stands inside
 * @param hideBlockEntities       strip block entities at hidden positions
 * @param hideEntities            hide entities at hidden positions
 * @param hidePlayers             also hide other players at hidden positions
 * @param maskLight               zero light data for masked sections
 * @param filterEffects           drop block events, break progress, level events, sounds and particles at hidden positions
 * @param excludedWorlds          world names Guard never touches (case-insensitive; wins over worlds)
 */
public record VisibilityConfig(
        boolean enabled,
        int boundaryY,
        int revealEnterY,
        int concealExitY,
        int revealRadiusChunks,
        Replacement replacement,
        boolean protectAllBelowBoundary,
        Set<String> worlds,
        List<Cuboid> protectedRegions,
        List<Cuboid> revealZones,
        boolean hideBlockEntities,
        boolean hideEntities,
        boolean hidePlayers,
        boolean maskLight,
        boolean filterEffects,
        Set<String> excludedWorlds) {

    public static final int MAX_REVEAL_RADIUS = 32;

    public VisibilityConfig {
        if (replacement == null) {
            throw new IllegalArgumentException("replacement is required");
        }
        if (concealExitY < revealEnterY) {
            throw new IllegalArgumentException("conceal-exit-y (" + concealExitY
                    + ") must be >= reveal-enter-y (" + revealEnterY + ")");
        }
        if (revealRadiusChunks < 0 || revealRadiusChunks > MAX_REVEAL_RADIUS) {
            throw new IllegalArgumentException("reveal-radius-chunks must be 0.." + MAX_REVEAL_RADIUS);
        }
        worlds = Set.copyOf(worlds);
        excludedWorlds = WorldExclusion.normalize(excludedWorlds == null ? Set.of() : excludedWorlds);
        protectedRegions = List.copyOf(protectedRegions);
        revealZones = List.copyOf(revealZones);
    }

    /** Without excluded worlds (pre-exclusion signature, kept for callers and tests). */
    public VisibilityConfig(boolean enabled, int boundaryY, int revealEnterY, int concealExitY,
                            int revealRadiusChunks, Replacement replacement, boolean protectAllBelowBoundary,
                            Set<String> worlds, List<Cuboid> protectedRegions, List<Cuboid> revealZones,
                            boolean hideBlockEntities, boolean hideEntities, boolean hidePlayers, boolean maskLight,
                            boolean filterEffects) {
        this(enabled, boundaryY, revealEnterY, concealExitY, revealRadiusChunks, replacement, protectAllBelowBoundary,
                worlds, protectedRegions, revealZones, hideBlockEntities, hideEntities, hidePlayers, maskLight,
                filterEffects, Set.of());
    }

    /** Defaults: boundary Y=0, deepslate replacement, everything below Y=0 protected, no excluded worlds. */
    public static VisibilityConfig defaults() {
        return new VisibilityConfig(true, 0, 4, 12, 3, Replacement.DEEPSLATE, true,
                Set.of(), List.of(), List.of(), true, true, true, true, true, WorldExclusion.DEFAULT_EXCLUDED);
    }

    public VisibilityConfig withRegions(List<Cuboid> regions) {
        return new VisibilityConfig(enabled, boundaryY, revealEnterY, concealExitY, revealRadiusChunks, replacement,
                protectAllBelowBoundary, worlds, regions, revealZones, hideBlockEntities, hideEntities, hidePlayers,
                maskLight, filterEffects, excludedWorlds);
    }
}
