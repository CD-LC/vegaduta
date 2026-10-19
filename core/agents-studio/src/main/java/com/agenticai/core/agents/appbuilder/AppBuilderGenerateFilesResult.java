package com.agenticai.core.agents.appbuilder;

import java.util.List;

/**
 * HTTP response of POST /generate-files: the file edits plus which engine
 * produced them, so the app-builder pipeline can pass "powered by
 * {provider}{model}" all the way to the person who asked for the change.
 * Additive-only evolution of the previous {files:[...]} body - consumers
 * that predate {@code providerName}/{@code model} simply ignore them.
 *
 * @param providerName ProviderName enum name ("PLATFORM_HOSTED" = included
 *     platform pool).
 * @param model        resolved model id for BYOK; null for the pool (a
 *     fallback chain, not one knowable model).
 */
public record AppBuilderGenerateFilesResult(List<AppBuilderFileEdit> files, String providerName, String model) {
}
