package com.agenticai.core.guardrails;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.ai.tool.ToolCallback;
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
 *
 * <p>A callback that knows <i>what executes it</i> (an MCP server) can say so by implementing
 * {@link Provenance}; {@link #of(ToolCallback)} then folds that identity in, so the same
 * name/description/schema served by a different server no longer matches. A callback without it
 * digests exactly as before, so digests recorded earlier stay valid.
 */
public final class ToolDefinitionDigest {

    private ToolDefinitionDigest() {}

    /**
     * Optional: who executes this tool. Implemented by MCP wrappers (and any wrapper that
     * delegates to one). Must never contain credentials; build the value with {@link
     * #provenance(String, String)}.
     */
    public interface Provenance {
        /** A stable identity of the executing server, or {@code null} when unknown. */
        String toolProvenance();
    }

    /**
     * {@code serverId} plus the normalised endpoint or command: URLs lose user-info, query and
     * fragment and get a lower-case scheme and host; a command line keeps only its executable
     * (arguments and environment can carry secrets). Returns {@code null} when both are blank.
     */
    public static String provenance(String serverId, String endpointOrCommand) {
        String id = serverId == null ? "" : serverId.strip();
        String where = normaliseEndpoint(endpointOrCommand);
        if (id.isEmpty() && where.isEmpty()) {
            return null;
        }
        return id + "@" + where;
    }

    static String normaliseEndpoint(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = raw.strip();
        if (value.contains("://")) {
            try {
                URI uri = URI.create(value);
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
                if (!host.isEmpty()) {
                    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                    String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
                    String path = uri.getPath() == null ? "" : uri.getPath();
                    return scheme + "://" + host + port + path;
                }
            } catch (IllegalArgumentException malformed) {
                // fall through to the textual cut below
            }
            int cut = value.indexOf('?');
            String noQuery = cut < 0 ? value : value.substring(0, cut);
            return noQuery.replaceAll("//[^/@]*@", "//");
        }
        return value.split("\\s+", 2)[0];
    }

    /** Digest of the callback's definition plus, when it exposes one, its {@link Provenance}. */
    public static String of(ToolCallback callback) {
        if (callback == null) {
            return null;
        }
        String provenance = callback instanceof Provenance p ? p.toolProvenance() : null;
        return of(callback.getToolDefinition(), provenance);
    }

    /** Lower-case hex SHA-256, or {@code null} when there is no definition to fingerprint. */
    public static String of(ToolDefinition definition) {
        return of(definition, null);
    }

    /** As {@link #of(ToolDefinition)}; a non-blank {@code provenance} is folded in (null/blank: same as before). */
    public static String of(ToolDefinition definition, String provenance) {
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
        if (provenance != null && !provenance.isBlank()) {
            update(sha, "provenance");
            update(sha, provenance);
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static void update(MessageDigest sha, String part) {
        sha.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
        sha.update((byte) 0);
    }
}
