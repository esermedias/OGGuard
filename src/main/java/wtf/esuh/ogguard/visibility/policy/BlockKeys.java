package wtf.esuh.ogguard.visibility.policy;

/** Packs block coordinates into a long (same layout as vanilla BlockPos.asLong: x 26 bits, z 26 bits, y 12 bits). */
public final class BlockKeys {

    private static final int XZ_BITS = 26;
    private static final int Y_BITS = 12;
    private static final long XZ_MASK = (1L << XZ_BITS) - 1;
    private static final long Y_MASK = (1L << Y_BITS) - 1;
    private static final int X_SHIFT = Y_BITS + XZ_BITS;
    private static final int Z_SHIFT = Y_BITS;

    private BlockKeys() {
    }

    public static long key(int x, int y, int z) {
        return ((x & XZ_MASK) << X_SHIFT) | ((z & XZ_MASK) << Z_SHIFT) | (y & Y_MASK);
    }

    public static int x(long key) {
        return (int) (key << (64 - X_SHIFT - XZ_BITS) >> (64 - XZ_BITS));
    }

    public static int y(long key) {
        return (int) (key << (64 - Y_BITS) >> (64 - Y_BITS));
    }

    public static int z(long key) {
        return (int) (key << (64 - Z_SHIFT - XZ_BITS) >> (64 - XZ_BITS));
    }

    /** Chunk key ({@link ChunkKeys}) of the chunk column containing the block. */
    public static long chunkKey(long key) {
        return ChunkKeys.key(x(key) >> 4, z(key) >> 4);
    }
}
