package wtf.esuh.ogguard.visibility.policy;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The set of chunks a client currently holds, derived from the outbound packet stream in send order
 * (chunk-with-light adds, forget-chunk removes, respawn/login/reconfiguration clears). Written only by the
 * channel's event loop; read (snapshot) by the player's scheduler task when planning a resync.
 *
 * <p>Resync packets are admitted only if the client still holds the chunk at the moment the packet reaches the
 * pipeline. Because the event loop sees packets in exact send order, this removes the race in which a resync
 * would arrive after a forget-chunk and create a ghost chunk client-side.
 */
public final class ClientChunkLedger {

    private final Set<Long> loaded = ConcurrentHashMap.newKeySet();

    public void onChunkSent(int chunkX, int chunkZ) {
        loaded.add(ChunkKeys.key(chunkX, chunkZ));
    }

    public void onForget(int chunkX, int chunkZ) {
        loaded.remove(ChunkKeys.key(chunkX, chunkZ));
    }

    public void clear() {
        loaded.clear();
    }

    public boolean holds(int chunkX, int chunkZ) {
        return loaded.contains(ChunkKeys.key(chunkX, chunkZ));
    }

    /** Decision for a resync packet: forward only while the client still holds the chunk. */
    public boolean admitResync(int chunkX, int chunkZ) {
        return holds(chunkX, chunkZ);
    }

    /** Immutable copy for planning on another thread. */
    public Set<Long> snapshot() {
        return Set.copyOf(loaded);
    }

    public int size() {
        return loaded.size();
    }
}
