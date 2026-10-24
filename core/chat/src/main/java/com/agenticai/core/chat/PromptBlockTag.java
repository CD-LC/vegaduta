package com.agenticai.core.chat;

/**
 * Where one block of a system message came from, as the request inspector shows it: the prompt-registry
 * key, the scope that supplied the wording (default, platform, tenant or agent), its version and a short
 * hash of the wording. {@code key} is null for text that is not a registry prompt (the agent's own system
 * prompt, procedures, skills, recalled memory); {@code id} then names the section.
 */
public record PromptBlockTag(
    String id, String key, String scope, String source, int version, String hash, int chars, int estimatedTokens
) {}
