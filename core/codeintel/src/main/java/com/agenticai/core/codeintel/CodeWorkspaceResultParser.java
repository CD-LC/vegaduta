package com.agenticai.core.codeintel;

import tools.jackson.databind.JsonNode;

/**
 * Shared response-classification logic for {@link RunCodeWorkspaceApprovalExecutor} and {@link
 * ApplyCodeWorkspaceApprovalExecutor}: both call {@link CodeIntelClient} after a human (or an
 * explicit policy override) has already approved the action, then need to decide what the resulting
 * raw JSON string actually means:
 *
 * <ul>
 *   <li>A genuine execution result (run's {@code {"success":...}} shape, or apply's {@code
 *       {"written":[...],"deleted":[...]}} shape) - including a legitimately FAILING command or a
 *       partially-applied set of files - is a normal outcome, not an executor failure. Nothing is
 *       thrown.
 *   <li>code-intel's own {@code {timestamp,status,error,...}} error envelope (its policy denied the
 *       call, its OWN separate approval flow wants sign-off too, execution is disabled, the
 *       workspace id is bad) is a real decision, not a blip - not retryable.
 *   <li>{@link CodeIntelClient}'s own client-side fallback (network unreachable, non-JSON body) is
 *       plausibly transient - retryable.
 * </ul>
 */
final class CodeWorkspaceResultParser {

    private CodeWorkspaceResultParser() {}

    static void checkForFailure(String toolLabel, JsonNode result) {
        if (result.has("status")) {
            int status = result.path("status").asInt(0);
            String error = result.path("error").asString("code-intel rejected the request");
            throw new CodeWorkspaceExecutionException(
                    toolLabel + " was rejected by code-intel (status " + status + "): " + error);
        }
        if (result.has("error") && !result.has("success") && !result.has("written")) {
            throw new RetryableCodeWorkspaceExecutionException(
                    toolLabel + " failed to reach code-intel: " + result.path("error").asString());
        }
    }
}
