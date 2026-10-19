package com.agenticai.core.tenancy.replica;

import java.util.Locale;

/**
 * How many core API replicas run behind one load balancer: {@code app.replica-mode}.
 *
 * <ul>
 *   <li>{@link #SINGLE} (the default; also a blank value): one replica. Per-JVM state is fine.</li>
 *   <li>{@link #MULTI}: two or more replicas, or a rolling revision where old and new images
 *       overlap. The startup safety check ({@code ReplicaModeSafetyCheck} in {@code core/app})
 *       reports every per-JVM setting that is unsafe in that shape.</li>
 * </ul>
 *
 * <p>Compatibility: before this enum existed {@code A2aCardSigningService} read the raw string
 * as a hint ("anything other than blank/single means more than one replica", with example values
 * {@code api}/{@code worker}/{@code jobs}). Those values still parse as {@link #MULTI} so that
 * hint does not change; the new documented values are exactly {@code single} and {@code multi}.
 */
public enum ReplicaMode {
    SINGLE,
    MULTI;

    /** Parses the raw property value; blank / null / {@code single} = SINGLE, anything else = MULTI. */
    public static ReplicaMode parse(String raw) {
        if (raw == null) {
            return SINGLE;
        }
        String value = raw.strip().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || value.equals("single")) {
            return SINGLE;
        }
        return MULTI;
    }

    /** True for the two documented values and blank; false for a legacy / unknown hint that parses as MULTI. */
    public static boolean isDocumentedValue(String raw) {
        if (raw == null) {
            return true;
        }
        String value = raw.strip().toLowerCase(Locale.ROOT);
        return value.isEmpty() || value.equals("single") || value.equals("multi");
    }

    public boolean isMulti() {
        return this == MULTI;
    }
}
