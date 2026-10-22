package com.agenticai.core.mcp.dto;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.mcp.McpConnectionPhase;
import com.agenticai.core.mcp.McpConnectionStatus;
import com.agenticai.core.mcp.McpReviewStatus;
import com.agenticai.core.mcp.McpServerRegistration;
import com.agenticai.core.mcp.McpServerType;
import com.agenticai.core.mcp.McpUsageStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code env} is deliberately absent - it's this registration's decrypted secret (API keys,
 * tokens), and this response is the read path every list/ detail call goes through, so there's no
 * code path that could accidentally echo a secret back to the browser (compare {@code
 * command}/{@code args}, which are never sensitive and are safe to show back for confirmation).
 *
 * <p>MCP-DOCTOR (2026-07-25): {@code connectionPhase}/{@code connectionHint} are the structured
 * halves of {@code connectionError} (which step failed + what to do about it), and {@code
 * usageStatus} is the readiness gate the UI should render as "Tested &amp; ready" vs "Not yet
 * verified - agents won't use this until it passes a test" (see {@link McpUsageStatus}).
 */
public record McpServerRegistrationResponse(
        UUID id,
        String name,
        McpServerType type,
        String description,
        boolean enabled,
        boolean dynamic,
        String command,
        String args,
        String url,
        McpConnectionStatus connectionStatus,
        String connectionError,
        McpConnectionPhase connectionPhase,
        String connectionHint,
        McpUsageStatus usageStatus,
        Instant connectedAt,
        Instant createdAt,
        Instant updatedAt,
        // MCP-SELF-HEAL (migration V135) - the persisted counterpart of
        // POST /{id}/test's ephemeral suggestedFix, so a registration-time
        // connect failure (not just a freshly-clicked /test) can also offer
        // "Apply suggested fix". Null whenever no fix was found/stored.
        McpConnectionTestResult.SuggestedFix suggestedFix,
        // MCP-STDIO-SANDBOXING (2026-08-05) - the staff-review gate. See
        // McpReviewStatus's javadoc; reviewRejectionReason is set only when
        // reviewStatus is REJECTED.
        McpReviewStatus reviewStatus,
        String reviewRejectionReason,
        // Decrypt-failure visibility (2026-08-05) - true when this registration's
        // stored env ciphertext exists but could not be decrypted/parsed on the
        // last connect/test attempt, so the UI can show "your stored credentials
        // could not be read - please re-enter them" instead of a generic error
        // (see McpRegistryService#decryptEnvDetailed's javadoc).
        boolean credentialsCorrupted,
        // Edge tool-access consent system (2026-08-06) - the tenant admin's own
        // layer-1 decision (see McpServerRegistration#edgeEligible's javadoc).
        // Defaults false; independent of `enabled`.
        boolean edgeEligible,
        // Transient, register()-call-only signal (never persisted, always false
        // on a plain listAll()/toggle() response): true when this POST upserted
        // an EXISTING row by name rather than creating a new one, so the
        // frontend can distinguish "added" from "overwrote your previous
        // brave-search connector" instead of silently treating both the same.
        boolean wasUpdate,
        // Transient, register()-call-only soft-validation notices (e.g. "missing
        // recommended credential FOO_API_KEY") - never blocks the registration,
        // just surfaces immediately rather than only on the next failed connect
        // attempt. Always empty on a plain listAll()/toggle() response.
        List<String> warnings) {
    private static final Logger log = LoggerFactory.getLogger(McpServerRegistrationResponse.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    public static McpServerRegistrationResponse from(McpServerRegistration entity) {
        return from(entity, false, List.of());
    }

    /**
     * {@code register()}'s own variant - carries the two transient, request-scoped signals no other
     * caller has (see their own field javadoc).
     */
    public static McpServerRegistrationResponse from(
            McpServerRegistration entity, boolean wasUpdate, List<String> warnings) {
        return new McpServerRegistrationResponse(
                entity.getId(),
                entity.getName(),
                entity.getType(),
                entity.getDescription(),
                entity.isEnabled(),
                entity.isDynamic(),
                entity.getCommand(),
                entity.getArgs(),
                entity.getUrl(),
                entity.getConnectionStatus(),
                entity.getConnectionError(),
                entity.getConnectionPhase(),
                entity.getConnectionHint(),
                entity.getUsageStatus(),
                entity.getConnectedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                suggestedFixFrom(entity),
                entity.getReviewStatus(),
                entity.getReviewRejectionReason(),
                entity.isCredentialsCorrupted(),
                entity.isEdgeEligible(),
                wasUpdate,
                warnings == null ? List.of() : warnings);
    }

    private static McpConnectionTestResult.SuggestedFix suggestedFixFrom(
            McpServerRegistration entity) {
        if (entity.getSuggestedFixCommand() == null) {
            return null;
        }
        List<String> args;
        try {
            args =
                    entity.getSuggestedFixArgs() == null
                            ? List.of()
                            : MAPPER.readValue(
                                    entity.getSuggestedFixArgs(),
                                    new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn(
                    "Could not deserialize stored suggested-fix args for registration {}, using empty list",
                    entity.getId(),
                    e);
            args = List.of();
        }
        return new McpConnectionTestResult.SuggestedFix(
                entity.getSuggestedFixCommand(), args, entity.getSuggestedFixReason());
    }
}
