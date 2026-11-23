package wtf.esuh.ogguard.visibility.policy;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One container-visibility check for one viewer (pure; runs on the player's region thread).
 *
 * <p>Candidates are the containers withheld from the client plus the containers that were visible at the previous
 * check (so a delivered container that is still in view stays allowed when its chunk is resent for another reason).
 * Each candidate within range is ray-cast; results are cached per chunk per tick in a {@link TickCache}. Chunks that
 * hold a withheld container which is now visible must be resent so the client receives its block entity.
 */
public final class ContainerPlanner {

    /**
     * @param visible      container positions the viewer may receive now
     * @param resendChunks chunk keys that hold a withheld container which became visible
     */
    public record Result(Set<Long> visible, Set<Long> resendChunks) {
    }

    private ContainerPlanner() {
    }

    public static Result plan(ContainerRule rule, double ex, double ey, double ez, Map<Long, long[]> withheld,
                              Set<Long> previouslyVisible, ContainerSight.Solidity solid,
                              TickCache<Map<Long, Boolean>> cache, long tick) {
        double max = rule.maxDistance();
        Map<Long, Set<Long>> candidates = new HashMap<>();
        Set<Long> withheldPositions = new HashSet<>();
        for (Map.Entry<Long, long[]> e : withheld.entrySet()) {
            long chunk = e.getKey();
            if (!ContainerSight.chunkInRange(ex, ez, ChunkKeys.x(chunk), ChunkKeys.z(chunk), max)) {
                continue;
            }
            for (long pos : e.getValue()) {
                candidates.computeIfAbsent(chunk, k -> new HashSet<>()).add(pos);
                withheldPositions.add(pos);
            }
        }
        for (long pos : previouslyVisible) {
            candidates.computeIfAbsent(BlockKeys.chunkKey(pos), k -> new HashSet<>()).add(pos);
        }

        Set<Long> visible = new HashSet<>();
        Set<Long> resend = new HashSet<>();
        for (Map.Entry<Long, Set<Long>> e : candidates.entrySet()) {
            long chunk = e.getKey();
            Map<Long, Boolean> results = null;
            for (long pos : e.getValue()) {
                int x = BlockKeys.x(pos);
                int y = BlockKeys.y(pos);
                int z = BlockKeys.z(pos);
                if (!ContainerSight.withinDistance(ex, ey, ez, x, y, z, max)) {
                    continue;
                }
                if (results == null) {
                    results = cache.get(tick, chunk, k -> new HashMap<>());
                }
                Boolean seen = results.get(pos);
                if (seen == null) {
                    seen = ContainerSight.lineOfSight(ex, ey, ez, x, y, z, solid);
                    results.put(pos, seen);
                }
                if (seen) {
                    visible.add(pos);
                    if (withheldPositions.contains(pos)) {
                        resend.add(chunk);
                    }
                }
            }
        }
        return new Result(visible, resend);
    }
}
