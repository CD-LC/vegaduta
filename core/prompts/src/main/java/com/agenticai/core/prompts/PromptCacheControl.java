package com.agenticai.core.prompts;

/** Drops cached overrides after a write. Implemented by the resolver. */
public interface PromptCacheControl {

    /** Drops the current tenant's cached overrides (TENANT and AGENT scope). */
    void evictCurrentTenant();

    /** Drops the cached PLATFORM overrides. */
    void evictPlatform();

    void evictAll();
}
