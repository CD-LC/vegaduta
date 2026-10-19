package com.agenticai.core.platform;

/**
 * Strictly internal, system-level tasks that this platform itself uses an
 * LLM for - NOT a tenant-facing "pick your default provider" feature (every
 * tenant already configures its own agents' providers independently via
 * {@code com.agenticai.core.providers.LlmProviderCredential}/{@code Agent}).
 *
 * <p>Exactly two task keys exist today, on purpose - this enum is not meant
 * to grow into a general-purpose registry:
 *
 * <ul>
 *   <li>{@link #TRANSLATION_REFRESH} - the LLM call {@code
 *       web/scripts/translate-messages.mjs} makes when the {@code
 *       .github/workflows/translate-messages.yml} GitHub Action runs (see
 *       {@link TranslationJobSettings} for why that script's actual
 *       provider/model config lives in that workflow's own repo secrets,
 *       not here).</li>
 *   <li>{@link #ASK_AI_HELPER} - the "Ask AI" helper feature backing {@code
 *       com.agenticai.core.agents.assist.AssistController}/{@code
 *       AssistService} (the in-console "help me write this description/
 *       prompt" assistant), when a super-admin wants to point that specific
 *       internal feature at a particular provider/model/key rather than
 *       whatever a tenant's own active provider happens to be.</li>
 * </ul>
 */
public enum PlatformTask {
    TRANSLATION_REFRESH,
    ASK_AI_HELPER
}
