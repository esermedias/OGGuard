package wtf.esuh.ogguard.visibility.policy;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongFunction;

/**
 * Values keyed by a long (for example a chunk key) that are valid for one tick only: the first access with a new
 * tick discards everything cached for the previous tick. Not thread-safe; owned by one player's scheduler task.
 */
public final class TickCache<V> {

    private final Map<Long, V> values = new HashMap<>();
    private long tick = Long.MIN_VALUE;

    public V get(long currentTick, long key, LongFunction<V> compute) {
        if (currentTick != tick) {
            values.clear();
            tick = currentTick;
        }
        V v = values.get(key);
        if (v == null) {
            v = compute.apply(key);
            values.put(key, v);
        }
        return v;
    }

    public int size() {
        return values.size();
    }
}
