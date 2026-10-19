package com.agenticai.core.common.diagnostics;

import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Input to {@link FailureExplanationService#explain}. Callers pass the RAW exception
 * and RAW context values - masking happens INSIDE the service (see {@link
 * SensitiveDataMasker}, always the first thing {@link DefaultFailureExplanationService}
 * does with this record's contents) as its literal first step, before any tier
 * strategy ever runs. No strategy in this package is ever handed this record directly
 * - each one only ever sees the already-masked internal carrier ({@code
 * MaskedFailureContext}), so there is no code path that can reach a template or an
 * LLM prompt with unmasked text.
 *
 * @param exception the failure to explain. Its class name drives technical-tier
 *     template lookup; its message is masked and used by every tier.
 * @param toolOrProviderName e.g. {@code "vertex-image-gen"} - matches what {@code
 *     DiagnosticService} already extracts today ({@code
 *     GuardrailViolationException#getToolName()} / {@code
 *     UpstreamProviderException#getProviderName()}).
 * @param issueSourceHint nullable; e.g. an {@code IssueSource} enum constant's {@code
 *     name()} as a plain String - deliberately a {@code String}, not the enum itself,
 *     so this module doesn't need a compile dependency on core/audit's enum type just
 *     to carry a lookup hint. Used for template selection when the exception's own
 *     class name isn't specific enough on its own.
 * @param additionalContext optional recent-log-line snippets or config-key names;
 *     each VALUE is masked before use, keys are not (key names like {@code
 *     "vertexProjectId"} are not secret and are useful for the expert-technical tier).
 *     Pass {@link Map#of()} when there is nothing extra to attach.
 * @param chatClient nullable, same optional-dependency idiom as {@code
 *     SemanticGuardrailService}: {@code null} means the technical/expert-technical
 *     tiers' LLM-fallback branch is never reached for an unmapped exception type - it
 *     degrades straight to a generic templated explanation instead. A non-null client
 *     is only actually used when no template matches the exception type.
 */
public record FailureContext(
    Throwable exception,
    String toolOrProviderName,
    String issueSourceHint,
    Map<String, String> additionalContext,
    ChatClient chatClient
) {
}
