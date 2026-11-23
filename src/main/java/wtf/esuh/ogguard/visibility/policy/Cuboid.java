package wtf.esuh.ogguard.visibility.policy;

import java.util.Objects;

/**
 * Inclusive block-coordinate box in one world. Pure value type (no Bukkit/Netty).
 * Used for protected regions and reveal zones.
 */
public record Cuboid(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public Cuboid {
        Objects.requireNonNull(world, "world");
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Cuboid min must be <= max: " + minX + "," + minY + "," + minZ
                    + " .. " + maxX + "," + maxY + "," + maxZ);
        }
    }

    /** Builds a cuboid from two arbitrary corners. */
    public static Cuboid of(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Cuboid(world, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    public boolean contains(String w, int x, int y, int z) {
        return world.equals(w) && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean contains(String w, double x, double y, double z) {
        return contains(w, floor(x), floor(y), floor(z));
    }

    /** True when this cuboid overlaps the inclusive box. */
    public boolean intersects(String w, int bMinX, int bMinY, int bMinZ, int bMaxX, int bMaxY, int bMaxZ) {
        return world.equals(w)
                && bMaxX >= minX && bMinX <= maxX
                && bMaxY >= minY && bMinY <= maxY
                && bMaxZ >= minZ && bMinZ <= maxZ;
    }

    /** True when this cuboid fully covers the inclusive box. */
    public boolean covers(String w, int bMinX, int bMinY, int bMinZ, int bMaxX, int bMaxY, int bMaxZ) {
        return world.equals(w)
                && bMinX >= minX && bMaxX <= maxX
                && bMinY >= minY && bMaxY <= maxY
                && bMinZ >= minZ && bMaxZ <= maxZ;
    }

    static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
