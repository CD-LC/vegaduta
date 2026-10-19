package com.agenticai.core.common.diagnostics;

import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Internal carrier produced by {@link DefaultFailureExplanationService} from a raw
 * {@link FailureContext} - every text field here has already been through {@link
 * SensitiveDataMasker#mask(String)}. This is package-private and deliberately NOT
 * {@link FailureContext} itself: {@code FailureContext} carries the raw {@link
 * Throwable}, and a {@code Throwable}'s message can't itself be "masked" in place
 * (it's not a settable field) - handing strategies the original {@code
 * FailureContext} would leave {@code ctx.exception().getMessage()} reachable as an
 * unmasked escape hatch. Routing every strategy through this masked-only carrier
 * instead is what makes the "no code path can reach a template or an LLM prompt with
 * unmasked text" guarantee in {@link FailureContext}'s javadoc actually hold, rather
 * than merely being true by convention.
 *
 * @param exceptionSimpleName e.g. {@code "GuardrailViolationException"} - drives
 *     template lookup. Class names/shapes are not treated as sensitive.
 * @param exceptionClassName fully-qualified class name, for the expert-technical
 *     tier only.
 * @param maskedMessage {@code exception.getMessage()} (or {@code toString()} if the
 *     message was null), after masking.
 * @param maskedToolOrProviderName masked {@link FailureContext#toolOrProviderName()}.
 * @param issueSourceHint passed through unchanged - not sensitive (an enum name).
 * @param maskedAdditionalContext {@link FailureContext#additionalContext()} with
 *     every value masked; keys unchanged.
 * @param chatClient passed through unchanged - not text, nothing to mask.
 */
record MaskedFailureContext(
    String exceptionSimpleName,
    String exceptionClassName,
    String maskedMessage,
    String maskedToolOrProviderName,
    String issueSourceHint,
    Map<String, String> maskedAdditionalContext,
    ChatClient chatClient
) {
}
