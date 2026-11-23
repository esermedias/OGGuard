package wtf.esuh.ogguard.visibility.policy;

/**
 * Distance and line-of-sight tests for container concealment. Pure functions over a {@link Solidity} predicate, so
 * the geometry is unit-tested without a world.
 *
 * <p>Line of sight: rays are cast (voxel traversal, Amanatides &amp; Woo) from the eye to the block centre and to the
 * eight corners of the block (inset slightly). The container is visible when any ray reaches the container's cell
 * without entering a solid cell. The eye's own cell is ignored (a player whose head is inside a block still counts
 * that block as transparent) and the target cell itself never blocks.
 */
public final class ContainerSight {

    /** True when the block at the coordinates blocks view (an opaque full cube). */
    @FunctionalInterface
    public interface Solidity {
        boolean solid(int x, int y, int z);
    }

    /** Offset from the block centre to the sampled corners (0.5 would be the exact corner). */
    static final double CORNER = 0.45;
    private static final int MAX_STEPS = 1024;

    private ContainerSight() {
    }

    /** Eye-to-block-centre distance check. */
    public static boolean withinDistance(double ex, double ey, double ez, int bx, int by, int bz, double max) {
        double dx = bx + 0.5 - ex;
        double dy = by + 0.5 - ey;
        double dz = bz + 0.5 - ez;
        return dx * dx + dy * dy + dz * dz <= max * max;
    }

    /** True when the closest point of the chunk column is within {@code max} (horizontal distance only). */
    public static boolean chunkInRange(double ex, double ez, int chunkX, int chunkZ, double max) {
        double minX = chunkX << 4;
        double minZ = chunkZ << 4;
        double dx = ex < minX ? minX - ex : Math.max(0, ex - (minX + 16));
        double dz = ez < minZ ? minZ - ez : Math.max(0, ez - (minZ + 16));
        return dx * dx + dz * dz <= max * max;
    }

    /** Distance and line of sight together. */
    public static boolean visible(double ex, double ey, double ez, int bx, int by, int bz, double max,
                                  Solidity solid) {
        return withinDistance(ex, ey, ez, bx, by, bz, max) && lineOfSight(ex, ey, ez, bx, by, bz, solid);
    }

    /** True when at least one sample ray from the eye reaches the block without crossing a solid cell. */
    public static boolean lineOfSight(double ex, double ey, double ez, int bx, int by, int bz, Solidity solid) {
        double cx = bx + 0.5;
        double cy = by + 0.5;
        double cz = bz + 0.5;
        if (rayClear(ex, ey, ez, cx, cy, cz, bx, by, bz, solid)) {
            return true;
        }
        for (int i = 0; i < 8; i++) {
            double tx = cx + ((i & 1) == 0 ? -CORNER : CORNER);
            double ty = cy + ((i & 2) == 0 ? -CORNER : CORNER);
            double tz = cz + ((i & 4) == 0 ? -CORNER : CORNER);
            if (rayClear(ex, ey, ez, tx, ty, tz, bx, by, bz, solid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walks the voxels from {@code (sx,sy,sz)} towards {@code (tx,ty,tz)} (a point inside the target cell) and
     * returns false as soon as a solid cell other than the start cell is entered before the target cell.
     */
    static boolean rayClear(double sx, double sy, double sz, double tx, double ty, double tz,
                            int targetX, int targetY, int targetZ, Solidity solid) {
        int x = Cuboid.floor(sx);
        int y = Cuboid.floor(sy);
        int z = Cuboid.floor(sz);
        int startX = x;
        int startY = y;
        int startZ = z;
        double dx = tx - sx;
        double dy = ty - sy;
        double dz = tz - sz;
        int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        int stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tDeltaX = stepX != 0 ? Math.abs(1.0 / dx) : Double.POSITIVE_INFINITY;
        double tDeltaY = stepY != 0 ? Math.abs(1.0 / dy) : Double.POSITIVE_INFINITY;
        double tDeltaZ = stepZ != 0 ? Math.abs(1.0 / dz) : Double.POSITIVE_INFINITY;
        double tMaxX = stepX > 0 ? (x + 1 - sx) / dx : stepX < 0 ? (sx - x) / -dx : Double.POSITIVE_INFINITY;
        double tMaxY = stepY > 0 ? (y + 1 - sy) / dy : stepY < 0 ? (sy - y) / -dy : Double.POSITIVE_INFINITY;
        double tMaxZ = stepZ > 0 ? (z + 1 - sz) / dz : stepZ < 0 ? (sz - z) / -dz : Double.POSITIVE_INFINITY;
        for (int i = 0; i < MAX_STEPS; i++) {
            if (x == targetX && y == targetY && z == targetZ) {
                return true;
            }
            if ((x != startX || y != startY || z != startZ) && solid.solid(x, y, z)) {
                return false;
            }
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                if (tMaxX > 1) {
                    return true; // segment ended (end point lies in the target cell)
                }
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                if (tMaxY > 1) {
                    return true;
                }
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                if (tMaxZ > 1) {
                    return true;
                }
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
        }
        return false; // fail closed on absurdly long rays
    }
}
