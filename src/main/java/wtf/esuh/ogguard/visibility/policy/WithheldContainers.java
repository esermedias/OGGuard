package wtf.esuh.ogguard.visibility.policy;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Container block entities that were stripped from packets sent to one client, grouped by chunk. Written only by
 * the connection's Netty event loop (in send order); read by the player's region thread to know which containers to
 * test and which chunks to resend once they become visible. Values are immutable arrays (copy on write), so a
 * reader never sees a partially updated chunk.
 */
public final class WithheldContainers {

    private final ConcurrentHashMap<Long, long[]> byChunk = new ConcurrentHashMap<>();

    /** A chunk packet was sent: replaces everything recorded for the chunk. */
    public void setChunk(int chunkX, int chunkZ, long[] positions) {
        long key = ChunkKeys.key(chunkX, chunkZ);
        if (positions.length == 0) {
            byChunk.remove(key);
        } else {
            byChunk.put(key, positions.clone());
        }
    }

    public void add(int x, int y, int z) {
        long pos = BlockKeys.key(x, y, z);
        byChunk.compute(ChunkKeys.key(x >> 4, z >> 4), (k, old) -> {
            if (old == null) {
                return new long[] {pos};
            }
            for (long p : old) {
                if (p == pos) {
                    return old;
                }
            }
            long[] next = java.util.Arrays.copyOf(old, old.length + 1);
            next[old.length] = pos;
            return next;
        });
    }

    public void remove(int x, int y, int z) {
        long pos = BlockKeys.key(x, y, z);
        byChunk.computeIfPresent(ChunkKeys.key(x >> 4, z >> 4), (k, old) -> {
            int n = 0;
            long[] next = new long[old.length];
            for (long p : old) {
                if (p != pos) {
                    next[n++] = p;
                }
            }
            if (n == old.length) {
                return old;
            }
            return n == 0 ? null : java.util.Arrays.copyOf(next, n);
        });
    }

    public void forgetChunk(int chunkX, int chunkZ) {
        byChunk.remove(ChunkKeys.key(chunkX, chunkZ));
    }

    public void clear() {
        byChunk.clear();
    }

    public boolean contains(int x, int y, int z) {
        long[] arr = byChunk.get(ChunkKeys.key(x >> 4, z >> 4));
        if (arr == null) {
            return false;
        }
        long pos = BlockKeys.key(x, y, z);
        for (long p : arr) {
            if (p == pos) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return byChunk.isEmpty();
    }

    /** Point-in-time copy: chunk key to withheld positions. The arrays must not be modified. */
    public Map<Long, long[]> snapshot() {
        return new HashMap<>(byChunk);
    }
}
