package wtf.esuh.ogguard.visibility.policy;

/** What a hidden block is replaced with in transmitted data. The real world is never changed. */
public enum Replacement {
    /** Hidden blocks are sent as air (looks like a void; cheapest to recognise as masking). */
    AIR,
    /** Hidden blocks are sent as plain stone. */
    STONE,
    /** Hidden blocks are sent as deepslate, the natural fill below Y=0 ("stone-equivalent"). */
    DEEPSLATE
}
