package com.agenticai.core.knowledge.rag;

import java.util.Optional;

/**
 * The one capability query rewriting needs from a language model: a single short completion.
 *
 * <p>This module cannot depend on the chat/provider modules (they sit above it), so the model call is
 * an SPI: the application provides one bean that routes it through the platform's existing cheap-model
 * path (the budget-governed, platform-paid hosted pool - see the agents module's adapter), so query
 * rewriting never grows a provider path of its own and its spend is metered where all other platform
 * spend is. No bean = query rewriting is silently a no-op.
 *
 * <p>Implementations MUST be bounded (small max output) and MAY throw; {@link QueryRewriter} enforces
 * the wall-clock timeout and turns every failure into "use the original query".
 */
@FunctionalInterface
public interface QueryRewriteModel {

    /**
     * @param systemPrompt    fixed instructions
     * @param userPrompt      the (untrusted) text to transform, already delimited as data
     * @param maxOutputTokens hard cap on the completion size
     * @return the completion text, or empty when no model/budget is available right now
     */
    Optional<String> complete(String systemPrompt, String userPrompt, int maxOutputTokens);
}
