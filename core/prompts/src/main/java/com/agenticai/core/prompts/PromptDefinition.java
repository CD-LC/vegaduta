package com.agenticai.core.prompts;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One registered prompt: its stable key and everything an editor needs to know before changing it.
 * Declared in the front matter of the default-locale resource file {@code
 * prompts/<locale>/<key>.md}.
 *
 * @param key stable dotted key, see {@link PromptKeys}
 * @param title short name shown in the console
 * @param description when the text is sent and what it does
 * @param riskClass see {@link PromptRiskClass}
 * @param securityCritical changing or switching it off needs {@code acknowledgeRisk=true}
 * @param disableable an override may switch the prompt off (resolves to no text) instead of
 *     rewording it; true for standing directives, false for the instruction of a secondary call
 *     that cannot run without one
 * @param retired the platform sends nothing under this key today (kept so stored settings and old
 *     API calls stay valid); it takes no override
 * @param strictPlaceholders true (default): any {@code {identifier}} that is not a declared placeholder is
 *     rejected on save; false for free text (custom instructions), where braces are plain text
 * @param placeholders the {@code {name}} tokens the text may use
 * @param minLength shortest allowed override (0 = none)
 * @param maxLength longest allowed override, in characters
 */
public record PromptDefinition(
        String key,
        String title,
        String description,
        PromptRiskClass riskClass,
        boolean securityCritical,
        boolean disableable,
        boolean retired,
        boolean strictPlaceholders,
        List<PromptPlaceholder> placeholders,
        int minLength,
        int maxLength) {

    public PromptDefinition {
        placeholders = placeholders == null ? List.of() : List.copyOf(placeholders);
    }

    public Set<String> placeholderNames() {
        return placeholders.stream().map(PromptPlaceholder::name).collect(Collectors.toUnmodifiableSet());
    }

    public Set<String> requiredPlaceholderNames() {
        return placeholders.stream()
                .filter(PromptPlaceholder::required)
                .map(PromptPlaceholder::name)
                .collect(Collectors.toUnmodifiableSet());
    }
}
