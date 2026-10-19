package com.agenticai.core.mcp.dto;

import com.agenticai.core.mcp.McpReviewStatus;
import com.agenticai.core.mcp.McpServerType;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A registration's fully-decrypted connection config, resolved by {@link
 * com.agenticai.core.mcp.McpRegistryService#dynamicConfigFor} for {@code
 * DynamicMcpConnectionManager} (core/agents) to actually connect with - kept
 * out of the entity/response objects on purpose so a decrypted {@code env}
 * map only ever exists transiently in memory on the one path that needs it,
 * never serialized to a DB column or an HTTP response.
 *
 * @param credentialsCorrupted true when the stored {@code env} ciphertext
 *     existed but failed to decrypt/parse - {@code env} is still a (possibly
 *     empty) map either way (the connect path degrades gracefully, see
 *     {@code McpRegistryService#decryptEnvDetailed}), this just flags that
 *     the emptiness/incompleteness is NOT because the tenant genuinely
 *     configured nothing.
 * @param reviewStatus the platform-staff approval gate (MCP-STDIO-SANDBOXING)
 *     - {@code DynamicMcpConnectionManager#connect}/{@code #test} refuse to
 *     spawn while this is PENDING or REJECTED, checked before any other
 *     guard. See {@link McpReviewStatus}.
 * @param reviewRejectionReason set only when {@code reviewStatus} is
 *     REJECTED - shown back to the tenant in the refusal hint.
 * @param oauthProvider null for every registration except one whose live
 *     connection headers come from a real OAuth flow (e.g. {@code
 *     "ATLASSIAN"}) rather than {@code env} - see {@code
 *     DynamicMcpConnectionManager#remoteHeaders} and {@code
 *     McpServerRegistration#oauthProvider}'s field javadoc.
 */
public record McpDynamicConfig(
    UUID registrationId,
    String name,
    McpServerType type,
    String command,
    List<String> args,
    Map<String, String> env,
    String url,
    boolean credentialsCorrupted,
    McpReviewStatus reviewStatus,
    String reviewRejectionReason,
    String oauthProvider
) {
    /**
     * Compatibility constructor for every pre-MCP-STDIO-SANDBOXING caller
     * (tests, mainly) that builds one of these directly rather than through
     * {@code McpRegistryService#dynamicConfigFor} - defaults to "nothing
     * corrupted, no review required, not OAuth-backed", i.e. exactly the
     * old behavior.
     */
    public McpDynamicConfig(
        UUID registrationId, String name, McpServerType type,
        String command, List<String> args, Map<String, String> env, String url
    ) {
        this(registrationId, name, type, command, args, env, url, false, McpReviewStatus.NOT_REQUIRED, null, null);
    }

    /**
     * Compatibility constructor for every pre-oauthProvider caller (tests,
     * mainly) that builds one of these with the full pre-2026-08-15
     * canonical shape - defaults to "not OAuth-backed", i.e. exactly the
     * old behavior.
     */
    public McpDynamicConfig(
        UUID registrationId, String name, McpServerType type, String command, List<String> args,
        Map<String, String> env, String url, boolean credentialsCorrupted,
        McpReviewStatus reviewStatus, String reviewRejectionReason
    ) {
        this(registrationId, name, type, command, args, env, url, credentialsCorrupted, reviewStatus, reviewRejectionReason, null);
    }

    /** True for a remote (SSE / streamable-HTTP) registration - {@code url} set, no local {@code command} to spawn. */
    public boolean isRemote() {
        return url != null && !url.isBlank();
    }
}
