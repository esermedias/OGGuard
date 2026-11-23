package wtf.esuh.ogguard.visibility.policy;

/**
 * Pure decisions for container block replacement (container concealment with {@code replace-block}): which block
 * positions of a chunk packet carry a replacement state instead of the real container state, and what to do with
 * single block updates at container positions.
 *
 * <p>Invariant kept with {@link WithheldContainers}: a position gets the replacement state in a packet exactly when
 * it is recorded as withheld for that client. The container planner resends a chunk as soon as one of its withheld
 * containers becomes visible, so a replacement never outlives the container's visibility.
 */
public final class ContainerBlockMask {

    /**
     * Containers placed or changed this close to the viewer's feet (blocks) are sent with their real state even when
     * not yet confirmed visible, so a player placing a chest does not see the replacement flash until the next check.
     */
    public static final double NEAR_RADIUS = 6.0;

    /** What to do with a block update whose position is not masked by the underground rule. */
    public enum UpdateAction {
        /** Forward unchanged; bookkeeping unchanged. */
        PASS,
        /** Forward unchanged and drop the withheld record (the position no longer holds a container). */
        PASS_AND_RELEASE,
        /** Send the replacement state; the position is already recorded as withheld. */
        REPLACE,
        /** Send the replacement state and record the position as withheld. */
        REPLACE_AND_WITHHOLD
    }

    private ContainerBlockMask() {
    }

    /** Index of a block inside its 16x16x16 section, same order as the paletted container ({@code y, z, x}). */
    public static int localIndex(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    /**
     * Groups the withheld container positions of chunk {@code (cx, cz)} by section.
     *
     * @return array of length {@code sectionCount}; entry {@code i} holds the sorted {@link #localIndex local indices}
     *         to replace in section {@code minSection + i}, or null when none. Positions outside the chunk or the
     *         section range are ignored. Returns null when nothing is to be replaced at all.
     */
    public static int[][] bySection(int cx, int cz, int minSection, int sectionCount, long[] positions) {
        if (positions == null || positions.length == 0) {
            return null;
        }
        int[] counts = new int[sectionCount];
        boolean any = false;
        for (long pos : positions) {
            int i = sectionIndex(cx, cz, minSection, sectionCount, pos);
            if (i >= 0) {
                counts[i]++;
                any = true;
            }
        }
        if (!any) {
            return null;
        }
        int[][] out = new int[sectionCount][];
        int[] fill = new int[sectionCount];
        for (int i = 0; i < sectionCount; i++) {
            if (counts[i] > 0) {
                out[i] = new int[counts[i]];
            }
        }
        for (long pos : positions) {
            int i = sectionIndex(cx, cz, minSection, sectionCount, pos);
            if (i >= 0) {
                out[i][fill[i]++] = localIndex(BlockKeys.x(pos), BlockKeys.y(pos), BlockKeys.z(pos));
            }
        }
        for (int[] arr : out) {
            if (arr != null) {
                java.util.Arrays.sort(arr);
            }
        }
        return out;
    }

    private static int sectionIndex(int cx, int cz, int minSection, int sectionCount, long pos) {
        if ((BlockKeys.x(pos) >> 4) != cx || (BlockKeys.z(pos) >> 4) != cz) {
            return -1;
        }
        int i = (BlockKeys.y(pos) >> 4) - minSection;
        return i >= 0 && i < sectionCount ? i : -1;
    }

    /** True when {@code local} is listed in the (sorted) indices of a section; null means none. */
    public static boolean replaced(int[] sectionIndices, int local) {
        return sectionIndices != null && java.util.Arrays.binarySearch(sectionIndices, local) >= 0;
    }

    /**
     * Decision for one block update at a position the underground rule leaves alone.
     *
     * @param replaceActive  container rule active for the viewer and {@code replace-block} on
     * @param containerState the new state belongs to a concealed container type
     * @param hidden         {@link VisibilityPolicy#hideContainer} for that type and position
     * @param withheld       the position is currently recorded as withheld for this client
     * @param near           the position is within {@link #NEAR_RADIUS} of the viewer
     */
    public static UpdateAction blockUpdate(boolean replaceActive, boolean containerState, boolean hidden,
                                           boolean withheld, boolean near) {
        if (!replaceActive) {
            return UpdateAction.PASS;
        }
        if (!containerState) {
            return withheld ? UpdateAction.PASS_AND_RELEASE : UpdateAction.PASS;
        }
        if (!hidden) {
            // A withheld position that became visible keeps its record: the planner resends the chunk so the
            // block entity arrives too.
            return UpdateAction.PASS;
        }
        if (withheld) {
            return UpdateAction.REPLACE;
        }
        return near ? UpdateAction.PASS : UpdateAction.REPLACE_AND_WITHHOLD;
    }

    /** True when block {@code (x, y, z)}'s centre is within {@link #NEAR_RADIUS} of a known viewer in that world. */
    public static boolean near(ViewerState viewer, String world, int x, int y, int z) {
        if (viewer == null || !viewer.known() || !viewer.world().equals(world)) {
            return false;
        }
        double dx = x + 0.5 - viewer.x();
        double dy = y + 0.5 - viewer.y();
        double dz = z + 0.5 - viewer.z();
        return dx * dx + dy * dy + dz * dz <= NEAR_RADIUS * NEAR_RADIUS;
    }
}
