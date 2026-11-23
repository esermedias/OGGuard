package wtf.esuh.ogguard.visibility.policy;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * Pure rewrite of one light layer (sky or block) of the 1.21.11 light payload, verified against
 * {@code ClientboundLightUpdatePacketData}: a "has data" BitSet whose set bits, in ascending order, correspond
 * one-to-one to the 2048-byte data arrays, plus an "empty" BitSet whose set bits tell the client to use an
 * all-zero layer for that light section. Bit i is light section {@code minLightSection + i}.
 *
 * <p>Masking a light section removes its data array, clears its "has data" bit and sets its "empty" bit, so the
 * client sees zero light there instead of the light emitted inside a hidden base.
 */
public final class LightMask {

    /** Rewritten layer. */
    public record Layer(BitSet dataMask, BitSet emptyMask, List<byte[]> data) {
    }

    private LightMask() {
    }

    /**
     * @param dataMask   original "has data" mask (not modified)
     * @param emptyMask  original "empty" mask (not modified)
     * @param data       original arrays, in ascending bit order of {@code dataMask}
     * @param masked     predicate over light-section index (bit index)
     * @param maxIndex   number of light sections (sections count + 2); indices at or above are ignored
     */
    public static Layer apply(BitSet dataMask, BitSet emptyMask, List<byte[]> data, IntPredicate masked, int maxIndex) {
        if (dataMask.cardinality() != data.size()) {
            throw new IllegalArgumentException("light mask has " + dataMask.cardinality()
                    + " bits but " + data.size() + " arrays");
        }
        BitSet newData = (BitSet) dataMask.clone();
        BitSet newEmpty = (BitSet) emptyMask.clone();
        List<byte[]> out = new ArrayList<>(data.size());
        int ordinal = 0;
        for (int i = dataMask.nextSetBit(0); i >= 0; i = dataMask.nextSetBit(i + 1)) {
            byte[] arr = data.get(ordinal++);
            if (i < maxIndex && masked.test(i)) {
                newData.clear(i);
                newEmpty.set(i);
            } else {
                out.add(arr);
            }
        }
        return new Layer(newData, newEmpty, out);
    }

    /** True when {@link #apply} would change anything (fast path to keep the original packet). */
    public static boolean affects(BitSet dataMask, IntPredicate masked, int maxIndex) {
        for (int i = dataMask.nextSetBit(0); i >= 0 && i < maxIndex; i = dataMask.nextSetBit(i + 1)) {
            if (masked.test(i)) {
                return true;
            }
        }
        return false;
    }
}
