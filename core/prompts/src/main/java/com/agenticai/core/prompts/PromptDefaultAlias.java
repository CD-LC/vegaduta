package com.agenticai.core.prompts;

/**
 * A deprecated {@code app.*} property that used to hold the text of a registry key and now only
 * seeds its platform default. The registry is the one mechanism for model-facing text; an alias
 * keeps an operator's existing configuration working without a second source of truth: the
 * resolver asks it for the text to use as the key's <b>default</b>, in place of the shipped
 * resource file, and nothing else changes. A stored override (agent, tenant, platform) still wins
 * over it, and a call site never reads the property itself.
 *
 * <p>Implementations return {@code null} while the property still holds the shipped text (or
 * nothing), so only a value the operator actually changed is applied, and log the deprecation
 * themselves, once, when they are created.
 */
public interface PromptDefaultAlias {

    /** The registry key this property supplies the default of. */
    String key();

    /**
     * The text to use as the default for {@code locale} (a language tag; may be null or blank for the
     * default locale), or {@code null} when the property sets none for it and the shipped file applies.
     * Never blank.
     */
    String text(String locale);
}
