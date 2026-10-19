package com.agenticai.core.platform.reliability;

/**
 * Verdict of one dependency probe. {@link #UNKNOWN} means "no answer to give":
 * the dependency is not configured in this deployment, or the check itself
 * could not be carried out. It is deliberately NOT {@link #DOWN} - an optional
 * dependency that was never deployed must not read as an outage.
 */
public enum DependencyStatus {
    UP,
    DEGRADED,
    DOWN,
    UNKNOWN
}
