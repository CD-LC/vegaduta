package com.agenticai.core.agents.version;

/**
 * What a {@link VersionLabelEvent} row records happening to a label pointer.
 *
 * <ul>
 *   <li>{@code PROMOTE} - a caller pointed a label at a version
 *       ({@code AgentVersionService#setLabel}, workflow promote).</li>
 *   <li>{@code ASSIGN} - a label move that was not a human promotion
 *       decision: automatic assignment on save in compatibility
 *       (auto-publish) mode.</li>
 *   <li>{@code BACKFILL} - a label placed by the V342 migration or the
 *       boot-time {@code VersionSnapshotBackfillRunner} rather than by a
 *       request at all.</li>
 *   <li>{@code CLEAR} - a label was removed from a version.</li>
 *   <li>{@code ROLLBACK} - {@code PRODUCTION} was moved back to a previous
 *       version via {@code rollback(entityId)}. ROLLBACK rows are what a
 *       "previous production version" lookup reads - one row per pointer
 *       move, newest first.</li>
 * </ul>
 */
public enum LabelEventAction {
    PROMOTE,
    ASSIGN,
    CLEAR,
    ROLLBACK,
    BACKFILL
}
