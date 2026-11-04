package com.agenticai.core.tables;

/**
 * Whether a data-table row may be read back: {@code quarantined} rows were written by an agent on a turn
 * that handled untrusted content (migration V678) and are hidden from every read, update and delete path
 * until a person approves them. {@code sourceTaintJson} is the JSON array of what tainted that turn, or
 * null. Rows written by people, workflows, CSV imports and clean agent turns are {@link #TRUSTED}.
 */
public record RowTrust(boolean quarantined, String sourceTaintJson) {

    public static final RowTrust TRUSTED = new RowTrust(false, null);
}
