package wtf.esuh.ogguard.visibility.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds a {@link VisibilityConfig} from plain maps/lists (the shape Bukkit's YAML section produces with
 * {@code getValues(false)} / {@code getMapList}). Pure Java so the parsing rules are unit-tested.
 */
public final class VisibilityConfigParser {

    private VisibilityConfigParser() {
    }

    public static VisibilityConfig parse(Map<String, ?> s) {
        VisibilityConfig d = VisibilityConfig.defaults();
        return new VisibilityConfig(
                bool(s, "enabled", d.enabled()),
                integer(s, "boundary-y", d.boundaryY()),
                integer(s, "reveal-enter-y", d.revealEnterY()),
                integer(s, "conceal-exit-y", d.concealExitY()),
                integer(s, "reveal-radius-chunks", d.revealRadiusChunks()),
                replacement(s.get("replacement"), d.replacement()),
                bool(s, "protect-all-below-boundary", d.protectAllBelowBoundary()),
                Set.copyOf(strings(s.get("worlds"))),
                cuboids(s.get("protected-regions"), "protected-regions"),
                cuboids(s.get("reveal-zones"), "reveal-zones"),
                bool(s, "hide-block-entities", d.hideBlockEntities()),
                bool(s, "hide-entities", d.hideEntities()),
                bool(s, "hide-players", d.hidePlayers()),
                bool(s, "mask-light", d.maskLight()),
                bool(s, "filter-effects", d.filterEffects()),
                s.containsKey("excluded-worlds") ? Set.copyOf(strings(s.get("excluded-worlds"))) : d.excludedWorlds());
    }

    private static boolean bool(Map<String, ?> s, String key, boolean def) {
        Object v = s.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        throw new IllegalArgumentException(key + " must be true or false");
    }

    private static int integer(Map<String, ?> s, String key, int def) {
        Object v = s.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return n.intValue();
        }
        throw new IllegalArgumentException(key + " must be a whole number");
    }

    private static Replacement replacement(Object v, Replacement def) {
        if (v == null) {
            return def;
        }
        try {
            return Replacement.valueOf(v.toString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("replacement must be AIR, STONE or DEEPSLATE");
        }
    }

    private static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                out.add(o.toString());
            }
        }
        return out;
    }

    static List<Cuboid> cuboids(Object v, String key) {
        List<Cuboid> out = new ArrayList<>();
        if (v == null) {
            return out;
        }
        if (!(v instanceof List<?> list)) {
            throw new IllegalArgumentException(key + " must be a list");
        }
        int index = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException(key + "[" + index + "] must be a map with world, min, max");
            }
            Object world = m.get("world");
            int[] min = triple(m.get("min"), key + "[" + index + "].min");
            int[] max = triple(m.get("max"), key + "[" + index + "].max");
            if (world == null) {
                throw new IllegalArgumentException(key + "[" + index + "].world is required");
            }
            out.add(Cuboid.of(world.toString(), min[0], min[1], min[2], max[0], max[1], max[2]));
            index++;
        }
        return out;
    }

    private static int[] triple(Object v, String key) {
        if (v instanceof List<?> l && l.size() == 3) {
            int[] out = new int[3];
            for (int i = 0; i < 3; i++) {
                if (!(l.get(i) instanceof Number n)) {
                    throw new IllegalArgumentException(key + " must be [x, y, z] numbers");
                }
                out[i] = n.intValue();
            }
            return out;
        }
        throw new IllegalArgumentException(key + " must be [x, y, z]");
    }
}
