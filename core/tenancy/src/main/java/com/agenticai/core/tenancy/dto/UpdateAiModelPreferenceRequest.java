package com.agenticai.core.tenancy.dto;

/**
 * Body of {@code PUT /api/tenant/ai-model-preferences/{featureKey}}. Both
 * fields {@code null} clears the override for that feature back to "resolve
 * as if nothing were configured" - both fields non-null sets/replaces it.
 * One-null-one-not is rejected (400) by {@code TenantAiModelPreferenceController} -
 * unlike {@code UpdateExperienceModeRequest}'s per-field null-means-unchanged
 * convention, a preference is a single atomic (provider, model) pair, so a
 * partial update would leave a provider paired with a stale model (or vice
 * versa) with no way for the caller to express "just clear it."
 */
public record UpdateAiModelPreferenceRequest(String providerName, String model) {
}
