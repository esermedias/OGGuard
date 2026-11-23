package wtf.esuh.ogguard.visibility.policy;

/** Packs chunk coordinates into a long (same layout as vanilla ChunkPos.asLong: x low, z high). */
public final class ChunkKeys {

    private ChunkKeys() {
    }

    public static long key(int chunkX, int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((chunkZ & 0xFFFFFFFFL) << 32);
    }

    public static int x(long key) {
        return (int) key;
    }

    public static int z(long key) {
        return (int) (key >>> 32);
    }
}
