package com.agenticai.core.agents.version;

/**
 * The two kinds of thing {@link VersionLabelEvent} records label moves for -
 * kept deliberately tiny since the table is append-only audit, not a join
 * target.
 *
 * <p>Shared by the agents and workflow modules (workflow already depends on
 * agents), which is the same reason the backing {@code version_label_event}
 * table carries a discriminator column instead of being two near-identical
 * tables.
 */
public enum VersionEntityType {
    AGENT,
    WORKFLOW
}
