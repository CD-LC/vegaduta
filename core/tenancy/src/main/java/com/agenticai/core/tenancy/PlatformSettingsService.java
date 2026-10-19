package com.agenticai.core.tenancy;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reads/writes {@link PlatformSetting} rows - the "central control in admin
 * platform maintenance to set all these values in UI" ask. No caching (same
 * "admin-panel usage volumes, not a hot path" posture {@link
 * com.agenticai.core.admin.keycloak.KeycloakAdminClient}'s own javadoc
 * states honestly) - every read hits Postgres fresh, so a change made in
 * the admin UI takes effect on the very next call anywhere that reads it,
 * no restart. {@link SignupRateLimiter} is the first, but not intended to
 * be the only, caller - any future hardcoded {@code @Value}-only knob that
 * an admin should be able to tune at runtime is a candidate to move here:
 * add one migration row, add one typed getter below.
 */
@Service
public class PlatformSettingsService {

    private static final Logger log = LoggerFactory.getLogger(PlatformSettingsService.class);

    private final PlatformSettingRepository repository;

    public PlatformSettingsService(PlatformSettingRepository repository) {
        this.repository = repository;
    }

    public List<PlatformSetting> listAll() {
        return repository.findAll();
    }

    /**
     * @param defaultIfMissing used only if no row exists for this key (e.g. a
     *     fresh database that predates a given setting's seed migration) or
     *     the stored value isn't parseable as an int - callers should never
     *     have to handle a missing/corrupt setting as a hard failure.
     */
    public int getInt(String key, int defaultIfMissing) {
        return repository.findById(key)
            .map(PlatformSetting::getValue)
            .map(value -> {
                try {
                    return Integer.parseInt(value.trim());
                } catch (NumberFormatException e) {
                    log.warn("Platform setting '{}' has a non-numeric value '{}' - using default {}", key, value, defaultIfMissing);
                    return defaultIfMissing;
                }
            })
            .orElse(defaultIfMissing);
    }

    /**
     * String counterpart of {@link #getInt} - returns the stored value
     * verbatim, or {@code defaultIfMissing} when no row exists for this key.
     * Same "callers should never have to handle a missing setting as a hard
     * failure" contract; a blank stored value is treated as present (returned
     * as-is), only a genuinely absent row falls back to the default.
     */
    public String getString(String key, String defaultIfMissing) {
        return repository.findById(key)
            .map(PlatformSetting::getValue)
            .orElse(defaultIfMissing);
    }

    /**
     * {@code long} counterpart of {@link #getInt} - same missing/unparseable
     * fallback posture (an out-of-int-range operational knob, e.g. a
     * tokens-per-minute burn threshold, needs the wider type).
     */
    public long getLong(String key, long defaultIfMissing) {
        return repository.findById(key)
            .map(PlatformSetting::getValue)
            .map(value -> {
                try {
                    return Long.parseLong(value.trim());
                } catch (NumberFormatException e) {
                    log.warn("Platform setting '{}' has a non-numeric value '{}' - using default {}", key, value, defaultIfMissing);
                    return defaultIfMissing;
                }
            })
            .orElse(defaultIfMissing);
    }

    /**
     * {@code double} counterpart of {@link #getInt} - for fractional knobs
     * (spend-spike multipliers, scoring weights, failure-ratio thresholds)
     * that an integer can't express. Same missing/unparseable fallback.
     */
    public double getDouble(String key, double defaultIfMissing) {
        return repository.findById(key)
            .map(PlatformSetting::getValue)
            .map(value -> {
                try {
                    return Double.parseDouble(value.trim());
                } catch (NumberFormatException e) {
                    log.warn("Platform setting '{}' has a non-numeric value '{}' - using default {}", key, value, defaultIfMissing);
                    return defaultIfMissing;
                }
            })
            .orElse(defaultIfMissing);
    }

    /**
     * Boolean counterpart of {@link #getInt} - accepts {@code "true"}/{@code
     * "false"} (case-insensitive); any other stored value falls back to
     * {@code defaultIfMissing} rather than silently reading as {@code false},
     * so a typo in an admin-editable safety flag (e.g. abuse auto-suspend)
     * can't quietly disable a control the operator meant to enable.
     */
    public boolean getBoolean(String key, boolean defaultIfMissing) {
        return repository.findById(key)
            .map(PlatformSetting::getValue)
            .map(value -> {
                String trimmed = value.trim();
                if (trimmed.equalsIgnoreCase("true")) {
                    return true;
                }
                if (trimmed.equalsIgnoreCase("false")) {
                    return false;
                }
                log.warn("Platform setting '{}' has a non-boolean value '{}' - using default {}", key, value, defaultIfMissing);
                return defaultIfMissing;
            })
            .orElse(defaultIfMissing);
    }

    /**
     * Upserts by key - callers pass whichever key they need updated; a key
     * with no existing row (a setting this platform doesn't actually define)
     * is created rather than rejected, since this is a generic control
     * surface, not a fixed enum of allowed keys.
     */
    public PlatformSetting update(String key, String value, String updatedBy) {
        PlatformSetting setting = repository.findById(key)
            .orElseGet(() -> new PlatformSetting(key, value, null));
        setting.setValue(value, updatedBy);
        return repository.save(setting);
    }
}
