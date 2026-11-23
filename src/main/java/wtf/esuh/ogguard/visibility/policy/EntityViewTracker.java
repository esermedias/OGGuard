package wtf.esuh.ogguard.visibility.policy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-connection view of the entities the server is tracking for one client, with their last known position and
 * whether they are currently hidden from that client. Pure Java; the payload type {@code R} carries whatever the
 * packet layer needs to re-spawn the entity later (cached spawn/metadata packets).
 *
 * <p>Not thread-safe by design: an instance is owned by exactly one Netty channel and only touched on that
 * channel's event loop.
 */
public final class EntityViewTracker<R> {

    /** Decides whether an entity at a position is hidden for the current viewer snapshot. */
    @FunctionalInterface
    public interface HideRule {
        boolean hide(double x, double y, double z, boolean player);
    }

    /** What the packet layer must do after a tracker update. */
    public enum Transition {
        /** Entity visible before and after: forward the packet. */
        STAY_VISIBLE,
        /** Entity hidden before and after: drop the packet. */
        STAY_HIDDEN,
        /** Entity just became hidden: drop the packet and send a remove-entities packet. */
        BECAME_HIDDEN,
        /** Entity just became visible: replay the cached spawn state, then forward the packet. */
        BECAME_VISIBLE,
        /** Entity id not tracked: forward unchanged. */
        UNKNOWN
    }

    public static final class Tracked<R> {
        private double x;
        private double y;
        private double z;
        private final boolean player;
        private boolean hidden;
        private final R payload;

        Tracked(double x, double y, double z, boolean player, R payload) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.player = player;
            this.payload = payload;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public double z() {
            return z;
        }

        public boolean player() {
            return player;
        }

        public boolean hidden() {
            return hidden;
        }

        public R payload() {
            return payload;
        }
    }

    private final Map<Integer, Tracked<R>> entities = new HashMap<>();
    private final int selfId;

    /** @param selfId the viewer's own entity id; it is never hidden from itself */
    public EntityViewTracker(int selfId) {
        this.selfId = selfId;
    }

    /** Registers a spawn. Returns true when the spawn must be dropped (entity hidden). */
    public boolean onSpawn(int id, double x, double y, double z, boolean player, R payload, HideRule rule) {
        Tracked<R> t = new Tracked<>(x, y, z, player, payload);
        t.hidden = id != selfId && rule.hide(x, y, z, player);
        entities.put(id, t);
        return t.hidden;
    }

    public Transition onAbsoluteMove(int id, double x, double y, double z, HideRule rule) {
        Tracked<R> t = entities.get(id);
        if (t == null) {
            return Transition.UNKNOWN;
        }
        t.x = x;
        t.y = y;
        t.z = z;
        return reevaluate(id, t, rule);
    }

    public Transition onRelativeMove(int id, double dx, double dy, double dz, HideRule rule) {
        Tracked<R> t = entities.get(id);
        if (t == null) {
            return Transition.UNKNOWN;
        }
        t.x += dx;
        t.y += dy;
        t.z += dz;
        return reevaluate(id, t, rule);
    }

    private Transition reevaluate(int id, Tracked<R> t, HideRule rule) {
        boolean hide = id != selfId && rule.hide(t.x, t.y, t.z, t.player);
        if (hide == t.hidden) {
            return hide ? Transition.STAY_HIDDEN : Transition.STAY_VISIBLE;
        }
        t.hidden = hide;
        return hide ? Transition.BECAME_HIDDEN : Transition.BECAME_VISIBLE;
    }

    public boolean isHidden(int id) {
        Tracked<R> t = entities.get(id);
        return t != null && t.hidden;
    }

    public Tracked<R> get(int id) {
        return entities.get(id);
    }

    public R payload(int id) {
        Tracked<R> t = entities.get(id);
        return t == null ? null : t.payload;
    }

    public void onRemove(int id) {
        entities.remove(id);
    }

    /** Client dropped its world (respawn, dimension change, reconfiguration). */
    public void clear() {
        entities.clear();
    }

    public int size() {
        return entities.size();
    }

    /** Result of {@link #revalidate(HideRule)}. */
    public record Changes(List<Integer> shown, List<Integer> hidden) {
    }

    /** Re-applies the rule to every tracked entity after the viewer snapshot changed. */
    public Changes revalidate(HideRule rule) {
        List<Integer> shown = new ArrayList<>();
        List<Integer> hidden = new ArrayList<>();
        for (Map.Entry<Integer, Tracked<R>> e : entities.entrySet()) {
            Transition tr = reevaluate(e.getKey(), e.getValue(), rule);
            if (tr == Transition.BECAME_VISIBLE) {
                shown.add(e.getKey());
            } else if (tr == Transition.BECAME_HIDDEN) {
                hidden.add(e.getKey());
            }
        }
        return new Changes(shown, hidden);
    }
}
