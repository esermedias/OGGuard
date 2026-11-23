package wtf.esuh.ogguard.visibility.policy;

/**
 * Immutable snapshot of what one viewer is allowed to see. Published through a volatile reference by the
 * player's own scheduler task and read by the Netty event loop, so it must stay immutable.
 *
 * @param world    world name, or null when unknown (fail closed: nothing is revealed)
 * @param x        feet position
 * @param y        feet position
 * @param z        feet position
 * @param revealed true when real geometry near the viewer may be sent
 * @param bypass   staff bypass: nothing is masked for this viewer
 */
public record ViewerState(String world, double x, double y, double z, boolean revealed, boolean bypass) {

    private static final ViewerState UNKNOWN = new ViewerState(null, 0, 0, 0, false, false);

    /** Fail-closed state used before the first real snapshot: every protected block is masked. */
    public static ViewerState unknown() {
        return UNKNOWN;
    }

    public int chunkX() {
        return Cuboid.floor(x) >> 4;
    }

    public int chunkZ() {
        return Cuboid.floor(z) >> 4;
    }

    public boolean known() {
        return world != null;
    }
}
