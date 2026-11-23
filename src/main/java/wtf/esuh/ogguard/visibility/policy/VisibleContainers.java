package wtf.esuh.ogguard.visibility.policy;

import java.util.Set;

/**
 * Immutable snapshot of the container positions ({@link BlockKeys}) that one viewer may currently receive block
 * entity data for. Published by the player's region thread, read by the Netty event loop.
 *
 * @param world     world the positions belong to; null for the empty fail-closed snapshot
 * @param positions visible container positions
 */
public record VisibleContainers(String world, Set<Long> positions) {

    private static final VisibleContainers NONE = new VisibleContainers(null, Set.of());

    public VisibleContainers {
        positions = Set.copyOf(positions);
    }

    public static VisibleContainers none() {
        return NONE;
    }

    public boolean contains(String world, int x, int y, int z) {
        return this.world != null && this.world.equals(world) && positions.contains(BlockKeys.key(x, y, z));
    }

    /** Positions for this world, or an empty set when the snapshot is for another world. */
    public Set<Long> positionsIn(String world) {
        return this.world != null && this.world.equals(world) ? positions : Set.of();
    }
}
