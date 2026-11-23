package wtf.esuh.ogguard.visibility.policy;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure world-scope decision shared by every Guard feature (block masking, light, container concealment, entity
 * hiding and replay, effects). A world listed in {@code packet-visibility.excluded-worlds} is never touched: every
 * packet for it passes unchanged. Excluded names match case-insensitively; exclusion wins over {@code worlds}.
 */
public final class WorldExclusion {

    /** Default excluded worlds: none. */
    public static final Set<String> DEFAULT_EXCLUDED = Set.of();

    private WorldExclusion() {
    }

    /** Lower-cased copy, so lookups are case-insensitive. */
    public static Set<String> normalize(Collection<String> names) {
        return names.stream()
                .filter(n -> n != null && !n.isBlank())
                .map(n -> n.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** True when {@code world} is in the (normalized) excluded set. */
    public static boolean excluded(Set<String> excludedNormalized, String world) {
        return world != null && excludedNormalized.contains(world.toLowerCase(Locale.ROOT));
    }

    /**
     * True when Guard may act in {@code world}: it is known, not excluded, and either {@code worlds} is empty (all
     * worlds) or lists it.
     */
    public static boolean covered(Set<String> worlds, Set<String> excludedNormalized, String world) {
        if (world == null || excluded(excludedNormalized, world)) {
            return false;
        }
        return worlds.isEmpty() || worlds.contains(world);
    }
}
