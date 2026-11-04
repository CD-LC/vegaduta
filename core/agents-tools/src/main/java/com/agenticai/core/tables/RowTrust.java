package com.agenticai.core.tables;

/**
 * Whether a data-table row may be read back: {@code quarantined} rows were written by an agent on a turn
 * that handled untrusted content (migration V678) and are hidden from every read, update and delete path
 * until a person approves them. {@code sourceTaintJson} is the JSON array of what tainted that turn, or
 * null. Rows written by people, workflows, CSV imports and clean agent turns are {@link #TRUSTED}.
 */
public record RowTrust(boolean quarantined, String sourceTaintJson) {

    public static final RowTrust TRUSTED = new RowTrust(false, null);

    /** Held trust naming what tainted the writing turn. */
    public static RowTrust held(java.util.List<String> sources) {
        java.util.List<String> s = sources == null || sources.isEmpty() ? java.util.List.of("unknown") : sources;
        try {
            return new RowTrust(true, com.agenticai.core.json.JsonMappers.plain().writeValueAsString(s));
        } catch (Exception e) {
            return new RowTrust(true, null);
        }
    }

    static java.util.List<String> parseSources(String json) {
        if (json == null || json.isBlank()) {
            return java.util.List.of();
        }
        try {
            return com.agenticai.core.json.JsonMappers.plain()
                    .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {});
        } catch (Exception e) {
            return java.util.List.of();
        }
    }
}
