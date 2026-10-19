package com.agenticai.core.agents.version;

/**
 * The entity-neutral sibling of {@link AgentVersionLabel}: the same two
 * deployment pointers (STAGING = under test, PRODUCTION = the blessed
 * snapshot runtime resolves), shared with the workflow module, which
 * already depends on this one. {@code AgentVersionLabel} predates it and
 * stays untouched for API compatibility - both serialize to the same
 * {@code "STAGING"/"PRODUCTION"} strings the V342 check constraints accept.
 */
public enum VersionLabel {
    STAGING,
    PRODUCTION
}
