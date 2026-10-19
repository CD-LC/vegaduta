package com.agenticai.core.agents.hooks;

/**
 * Which tool calls a hook applies to. Every present field must hold (AND); an absent
 * field does not constrain. Ignored at the model and run points.
 *
 * @param tool a glob over the tool name ({@code *} any run of characters, {@code ?} one
 *     character; case-sensitive), e.g. {@code delete_*} or {@code mcp__github__*}. Null
 *     means any tool. At {@code ON_ERROR} a null tool also matches run-level errors.
 * @param trust a {@code ToolTrustTier} name ({@code TRUSTED_INTERNAL}, {@code TENANT_SCOPED},
 *     {@code UNTRUSTED_EXTERNAL}) or {@code UNCLASSIFIED} for a tool with no classification.
 * @param sensitivity a {@code ToolSensitivity} name ({@code READ_PUBLIC}, {@code READ_TENANT},
 *     {@code WRITE_TENANT}, {@code CREDENTIALED}, {@code EGRESS}) or {@code UNCLASSIFIED}.
 * @param irreversible when set, matches only tools whose classification says irreversible
 *     (true) or reversible (false). A tool with no classification never matches this field.
 */
public record HookMatch(String tool, String trust, String sensitivity, Boolean irreversible) {

    public static final String UNCLASSIFIED = "UNCLASSIFIED";

    /** No constraint at all. */
    public static final HookMatch ANY = new HookMatch(null, null, null, null);

    /** True when matching needs the tool's classification (a registry lookup). */
    public boolean needsClassification() {
        return trust != null || sensitivity != null || irreversible != null;
    }

    /** True when no field constrains. */
    public boolean isAny() {
        return tool == null && !needsClassification();
    }
}
