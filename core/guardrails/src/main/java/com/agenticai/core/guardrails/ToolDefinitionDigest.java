package com.agenticai.core.guardrails;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * A fingerprint of what a tool <i>is</i> (name, description, input schema), recorded when a human
 * is asked to approve a call and re-checked when the approved call is replayed.
 *
 * <p>An approval names a tool, but a tool's behaviour can change behind its name (an MCP server
 * swapped or updated between request and approval). Without this, "approved {@code send_report}"
 * would run whatever {@code send_report} has become. It does not prove the tool's code is
 * unchanged (a server can change behaviour without touching its description), only that the
 * contract the approver could see is.
 */
public final class ToolDefinitionDigest {

    private ToolDefinitionDigest() {}

    /** Lower-case hex SHA-256, or {@code null} when there is no definition to fingerprint. */
    public static String of(ToolDefinition definition) {
        if (definition == null) {
            return null;
        }
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every JVM", e);
        }
        update(sha, definition.name());
        update(sha, definition.description());
        update(sha, definition.inputSchema());
        return HexFormat.of().formatHex(sha.digest());
    }

    private static void update(MessageDigest sha, String part) {
        sha.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
        sha.update((byte) 0);
    }
}
