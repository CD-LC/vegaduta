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
 * @param summary  the model's plain-English explanation of the change
 *     (null when absent) - rendered as the headline of the change in the
 *     /build chat so the user knows what happened without reading a diff.
 */
public record AppBuilderGenerateFilesResult(List<AppBuilderFileEdit> files, String providerName, String model, String summary) {
}
