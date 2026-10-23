package com.agenticai.core.prompts;

/**
 * A {@code {name}} token a prompt text may contain; the call site supplies the value through {@link
 * PromptContext#variables()}. Any other {@code {identifier}} in a text is rejected on save.
 *
 * @param required the text must contain the token (an override that drops it is rejected)
 */
public record PromptPlaceholder(String name, String description, boolean required) {}
