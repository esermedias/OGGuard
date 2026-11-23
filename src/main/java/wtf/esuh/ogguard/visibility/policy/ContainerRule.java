package wtf.esuh.ogguard.visibility.policy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Container concealment settings: block entities of the listed types are sent to a viewer only while the container
 * is within {@code maxDistance} of the viewer's eyes and in unobstructed line of sight.
 *
 * @param enabled            rule switch (the packet-visibility master switch must also be on)
 * @param maxDistance        eye-to-block-centre distance in blocks
 * @param checkIntervalTicks how often each player's container visibility is recomputed
 * @param blockEntityTypes   namespaced block entity type ids (for example {@code minecraft:chest})
 * @param replaceBlock       also send {@code replacementBlock} instead of the real block state at withheld container
 *                           positions (so client-side storage ESP that reads block states sees nothing)
 * @param replacementBlock   block sent in place of a withheld container
 */
public record ContainerRule(boolean enabled, double maxDistance, int checkIntervalTicks, Set<String> blockEntityTypes,
                            boolean replaceBlock, Replacement replacementBlock) {

    public static final double MAX_DISTANCE_LIMIT = 128;
    public static final int MAX_CHECK_INTERVAL = 200;

    /**
     * Block entity types that hold an inventory (or, for the decorated pot, a displayed item). One type covers every
     * colour/variant of its blocks (all 17 shulker boxes share {@code minecraft:shulker_box}).
     */
    public static final List<String> DEFAULT_TYPES = List.of(
            "minecraft:chest", "minecraft:trapped_chest", "minecraft:ender_chest",
            "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker",
            "minecraft:barrel", "minecraft:shulker_box", "minecraft:hopper",
            "minecraft:dispenser", "minecraft:dropper", "minecraft:brewing_stand",
            "minecraft:crafter", "minecraft:decorated_pot");

    public ContainerRule {
        if (!(maxDistance > 0) || maxDistance > MAX_DISTANCE_LIMIT) {
            throw new IllegalArgumentException("container-concealment.max-distance must be > 0 and <= "
                    + (int) MAX_DISTANCE_LIMIT);
        }
        if (checkIntervalTicks < 1 || checkIntervalTicks > MAX_CHECK_INTERVAL) {
            throw new IllegalArgumentException("container-concealment.check-interval-ticks must be 1.."
                    + MAX_CHECK_INTERVAL);
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String id : blockEntityTypes) {
            String n = normalizeId(id);
            if (n != null) {
                normalized.add(n);
            }
        }
        blockEntityTypes = Set.copyOf(normalized);
        if (replacementBlock == null) {
            replacementBlock = DEFAULT_REPLACEMENT;
        }
    }

    public static final Replacement DEFAULT_REPLACEMENT = Replacement.DEEPSLATE;

    /** Block replacement on, with the default replacement block. */
    public ContainerRule(boolean enabled, double maxDistance, int checkIntervalTicks, Set<String> blockEntityTypes) {
        this(enabled, maxDistance, checkIntervalTicks, blockEntityTypes, true, DEFAULT_REPLACEMENT);
    }

    public static ContainerRule defaults() {
        return new ContainerRule(true, 24, 4, Set.copyOf(DEFAULT_TYPES));
    }

    public static ContainerRule disabled() {
        return new ContainerRule(false, 24, 4, Set.copyOf(DEFAULT_TYPES));
    }

    /** True when block entities of this (namespaced or bare) type id are concealed by the rule. */
    public boolean matches(String typeId) {
        String n = normalizeId(typeId);
        return n != null && blockEntityTypes.contains(n);
    }

    /** Lower-cases and adds the {@code minecraft:} namespace when missing; null for blank input. */
    public static String normalizeId(String id) {
        if (id == null) {
            return null;
        }
        String t = id.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return null;
        }
        return t.indexOf(':') < 0 ? "minecraft:" + t : t;
    }
}
