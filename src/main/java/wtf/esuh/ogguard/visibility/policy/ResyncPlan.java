package wtf.esuh.ogguard.visibility.policy;

import java.util.Set;

/**
 * Result of a viewer state transition.
 *
 * @param chunks          chunk keys (see {@link ChunkKeys}) the client already holds whose masking changed and
 *                        must be resent with the new state
 * @param revealChanged   true when the set of revealed chunks changed, so hidden/visible entities must be revalidated
 */
public record ResyncPlan(Set<Long> chunks, boolean revealChanged) {

    private static final ResyncPlan NONE = new ResyncPlan(Set.of(), false);

    public ResyncPlan {
        chunks = Set.copyOf(chunks);
    }

    public static ResyncPlan none() {
        return NONE;
    }

    public boolean isEmpty() {
        return chunks.isEmpty() && !revealChanged;
    }
}
