package com.agenticai.core.settings;

/**
 * For a security setting: which direction is stricter. A tenant or agent value may only be as strict as, or stricter
 * than, the value below it, and at resolution time the stricter of the layers always wins.
 */
public enum Strictness {
    /** Not a security comparison (every non-security setting). */
    NONE,
    /** A higher number is stricter (a wider scan window). */
    HIGHER_IS_STRICTER,
    /** A lower number is stricter (a lower block score). */
    LOWER_IS_STRICTER,
    /** Stricter is later in {@code enumValues} (ordered least to most strict). */
    ENUM_ORDER
}
