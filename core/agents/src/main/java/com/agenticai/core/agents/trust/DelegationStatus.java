package com.agenticai.core.agents.trust;

/** Lifecycle of a {@link DelegationGrant} / minted {@link DelegationTokenRecord}. */
public enum DelegationStatus {
    ACTIVE,
    REVOKED,
    EXPIRED
}
