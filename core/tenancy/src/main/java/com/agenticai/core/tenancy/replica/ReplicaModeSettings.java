package com.agenticai.core.tenancy.replica;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The ONE place {@code app.replica-mode} is read (apart from the legacy hint in
 * {@code A2aCardSigningService}, which parses its own copy through {@link ReplicaMode#parse}).
 *
 * <ul>
 *   <li>{@code app.replica-mode} ({@code APP_REPLICA_MODE}): {@code single} (default) or {@code multi}.</li>
 *   <li>{@code app.replica-mode-strict} ({@code APP_REPLICA_MODE_STRICT}, default {@code false}): in
 *       {@code multi} mode, turn the startup WARNs for unsafe per-JVM settings into a boot failure.
 *       The alias {@code app.replica-mode.strict} is also honoured (system property / environment
 *       variable only: a YAML scalar {@code replica-mode} cannot also hold a nested {@code strict} key,
 *       which is why the YAML key is {@code replica-mode-strict}).</li>
 * </ul>
 *
 * <p>Defaults keep today's behaviour exactly: single replica, nothing checked, nothing fatal.
 */
@Component
public class ReplicaModeSettings {

    private static final Logger log = LoggerFactory.getLogger(ReplicaModeSettings.class);

    private final String raw;
    private final ReplicaMode mode;
    private final boolean strict;

    public ReplicaModeSettings(
        @Value("${app.replica-mode:single}") String raw,
        @Value("${app.replica-mode-strict:${app.replica-mode.strict:false}}") boolean strict
    ) {
        this.raw = raw == null ? "" : raw.strip();
        this.mode = ReplicaMode.parse(this.raw);
        this.strict = strict;
        if (!ReplicaMode.isDocumentedValue(this.raw)) {
            log.warn("app.replica-mode='{}' is not one of single|multi; treating it as multi (legacy deployment-shape hint)",
                this.raw);
        }
        if (strict && !mode.isMulti()) {
            // Strict only means something in multi mode; say so once instead of silently ignoring the flag.
            log.warn("app.replica-mode-strict (or its alias app.replica-mode.strict) is true but app.replica-mode='{}' is not "
                + "multi, so strict mode has NO effect; set APP_REPLICA_MODE=multi", this.raw);
        }
    }

    /** Test / programmatic seam; the Spring constructor above is the only injectable one. */
    public static ReplicaModeSettings of(String raw, boolean strict) {
        return new ReplicaModeSettings(raw, strict);
    }

    public ReplicaMode mode() {
        return mode;
    }

    public boolean isMulti() {
        return mode.isMulti();
    }

    /** True only in multi mode with {@code app.replica-mode-strict=true}. */
    public boolean isStrict() {
        return strict && mode.isMulti();
    }

    /** The configured value as written (blank when unset), for log lines. */
    public String raw() {
        return raw;
    }
}
