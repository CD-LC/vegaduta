package com.agenticai.core.settings;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.settings.*}: caching, history paging and operator pins of the settings catalog.
 *
 * <p>{@link #getLocked()} pins keys: {@code app.settings.locked.agent.limits.maxToolSteps=true} makes that key the
 * platform value for everybody, read-only in the UI, whatever a tenant or agent stored. Key matching ignores case,
 * dashes and underscores, so an environment variable spelling also works.
 */
@ConfigurationProperties(prefix = "app.settings")
public class SettingsProperties {

    /**
     * How long a tenant's (and an agent's) stored overrides are cached before they are read again. A change made on
     * one replica reaches the others within this time; the replica that took the change sees it at once.
     */
    private Duration cacheTtl = Duration.ofSeconds(10);

    /** Most (tenant, agent) override sets the cache holds before the oldest are dropped. */
    private int cacheMaxEntries = 20_000;

    /** Rows a history request returns when it names no limit. */
    private int historyDefaultLimit = 50;

    /** Most rows a history request may return. */
    private int historyMaxLimit = 200;

    /** Rows of the all-keys history ({@code GET /api/settings/history}) returned when the caller names no limit. */
    private int bulkHistoryDefaultLimit = 100;

    /** Most rows one all-keys history request may return. */
    private int bulkHistoryMaxLimit = 500;

    /** Longest reason a change may carry (longer is cut). */
    private int reasonMaxLength = 500;

    /** Keys the operator pinned: {@code key -> true}. */
    private Map<String, Boolean> locked = new LinkedHashMap<>();

    /** Optional text shown for a pinned key: {@code key -> reason}. */
    private Map<String, String> lockedReasons = new LinkedHashMap<>();

    public static String normalizeKey(String key) {
        return key == null ? "" : key.toLowerCase(Locale.ROOT).replace(".", "").replace("-", "").replace("_", "");
    }

    public boolean isLocked(String key) {
        String wanted = normalizeKey(key);
        return locked.entrySet().stream().anyMatch(e -> Boolean.TRUE.equals(e.getValue()) && normalizeKey(e.getKey()).equals(wanted));
    }

    public String lockedReason(String key) {
        String wanted = normalizeKey(key);
        return lockedReasons.entrySet().stream()
                .filter(e -> normalizeKey(e.getKey()).equals(wanted) && e.getValue() != null && !e.getValue().isBlank())
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("Pinned by the platform operator (app.settings.locked).");
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl == null || cacheTtl.isNegative() ? Duration.ZERO : cacheTtl;
    }

    public int getCacheMaxEntries() {
        return cacheMaxEntries;
    }

    public void setCacheMaxEntries(int cacheMaxEntries) {
        this.cacheMaxEntries = Math.max(1, cacheMaxEntries);
    }

    public int getHistoryDefaultLimit() {
        return historyDefaultLimit;
    }

    public void setHistoryDefaultLimit(int historyDefaultLimit) {
        this.historyDefaultLimit = Math.max(1, historyDefaultLimit);
    }

    public int getHistoryMaxLimit() {
        return historyMaxLimit;
    }

    public void setHistoryMaxLimit(int historyMaxLimit) {
        this.historyMaxLimit = Math.max(1, historyMaxLimit);
    }

    public int getBulkHistoryDefaultLimit() {
        return bulkHistoryDefaultLimit;
    }

    public void setBulkHistoryDefaultLimit(int bulkHistoryDefaultLimit) {
        this.bulkHistoryDefaultLimit = Math.max(1, bulkHistoryDefaultLimit);
    }

    public int getBulkHistoryMaxLimit() {
        return bulkHistoryMaxLimit;
    }

    public void setBulkHistoryMaxLimit(int bulkHistoryMaxLimit) {
        this.bulkHistoryMaxLimit = Math.max(1, bulkHistoryMaxLimit);
    }

    public int getReasonMaxLength() {
        return reasonMaxLength;
    }

    public void setReasonMaxLength(int reasonMaxLength) {
        this.reasonMaxLength = Math.max(20, reasonMaxLength);
    }

    public Map<String, Boolean> getLocked() {
        return locked;
    }

    public void setLocked(Map<String, Boolean> locked) {
        this.locked = locked == null ? new LinkedHashMap<>() : locked;
    }

    public Map<String, String> getLockedReasons() {
        return lockedReasons;
    }

    public void setLockedReasons(Map<String, String> lockedReasons) {
        this.lockedReasons = lockedReasons == null ? new LinkedHashMap<>() : lockedReasons;
    }
}
