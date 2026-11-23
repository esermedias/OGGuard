package wtf.esuh.ogguard.visibility.policy;

/** Per 16x16x16 chunk section masking decision for one viewer. */
public enum SectionDecision {
    /** Send the real section unchanged. */
    PASS,
    /** Every block in the section is hidden: send a single-value section of the replacement block. */
    MASK_ALL,
    /** Some blocks are hidden: rebuild the section block by block (fresh palette, no stale entries). */
    MASK_PARTIAL
}
