package com.agenticai.core.posture;

/** Overall security posture of this process, derived from {@link PostureChecks}. */
public enum Posture {
    /** No permissive governance setting is active. */
    STRICT,
    /**
     * Some, but not all, of the lethal-trifecta settings are permissive (or only auxiliary ones
     * are).
     */
    MIXED,
    /** Every lethal-trifecta ("core") setting is at its permissive value. */
    PERMISSIVE
}
