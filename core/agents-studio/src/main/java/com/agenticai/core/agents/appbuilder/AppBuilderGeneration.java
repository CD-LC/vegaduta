package com.agenticai.core.agents.appbuilder;

import java.util.List;

/**
 * Result of one {@link AppBuilderCompletionService#generateFiles} call: the parsed file edits PLUS
 * which engine produced them, so the caller (app-builder service -> web UI) can disclose "what
 * powered this generation" to the person who asked for it.
 *
 * @param files the file edits the model produced (never null, may be empty - an empty list already
 *     means "the model replied but wrote nothing usable", not an error).
 * @param providerName ProviderName enum name - "PLATFORM_HOSTED" for the included platform pool,
 *     else the tenant's active BYOK provider.
 * @param model the resolved model id for BYOK calls; null for the platform pool, where {@code
 *     FreeTierChatClientService} runs a self-healing fallback chain and no single model is knowable
 *     before the call. Consumers must render null as "provider's pick", never fabricate one.
 * @param summary the model's own plain-English explanation of the change (null when the reply
 *     predates the summary field or the model left it blank) - the "what changed" headline in the
 *     /build chat.
 */
public record AppBuilderGeneration(
        List<AppBuilderFileEdit> files, String providerName, String model, String summary) {}
