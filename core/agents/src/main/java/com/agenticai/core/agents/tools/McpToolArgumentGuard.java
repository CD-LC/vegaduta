package com.agenticai.core.agents.tools;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.json.JsonNodes;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.net.URI;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Baseline security check on LLM-constructed MCP tool-call ARGUMENT VALUES, applied by {@link
 * SanitizedMcpToolCallback} immediately before an argument string is handed to an MCP server's
 * {@code call()} (defensive MCP hardening, 2026-08-05 follow-up to MCP-DOCTOR).
 *
 * <p>{@link McpToolOutputSanitizer} (its sibling in this package) defends the RESULT direction - a
 * hostile/compromised MCP server flooding or smuggling injection back INTO the agent. This class
 * defends the opposite direction: nothing previously stopped an LLM from constructing an argument
 * like {@code {"path": "../../../etc/passwd"}} or {@code {"url":
 * "http://169.254.169.254/latest/meta-data/"}} and having it reach a tool's real implementation
 * unchanged - {@link ArgumentSanitizingToolCallingManager} (core/chat) only repairs malformed JSON
 * and hallucinated tool NAMES, never inspects argument VALUES.
 *
 * <h2>What this deliberately does NOT try to be</h2>
 *
 * This is a coarse, low-false-positive tripwire, not a general-purpose argument firewall. Two
 * judgment calls, both erring toward "don't break a legitimate tool call":
 *
 * <ul>
 *   <li><b>Path traversal</b> - only flags a {@code ..} that appears as its OWN path segment
 *       ({@code (^|[/\\])\.\.([/\\]|$)}, plus one URL-decoded pass for the common {@code %2e%2e}
 *       bypass) or a value that is an ABSOLUTE path into a short, well-known sensitive-location
 *       denylist ({@code /etc/}, {@code /proc/}, {@code ~/.ssh/}, {@code C:\Windows\System32},
 *       ...). A tool that legitimately takes a plain relative path within its own sandboxed
 *       workspace (no {@code ..} segment, no sensitive absolute root) is completely unaffected.
 *   <li><b>SSRF-shaped URLs</b> - only flags an {@code http(s)} URL whose host is a LITERAL
 *       loopback/private/link-local/metadata IP address or one of a tiny set of platform-internal
 *       hostnames ({@code localhost}, {@code metadata.google.internal}). Deliberately does NOT
 *       reproduce {@code McpRemoteUrlGuard}'s full alternate-IPv4-numeral-encoding bypass coverage
 *       (decimal/hex/BSD-shorthand forms) or DNS-rebinding resolution (H2) - those defend the
 *       SEPARATE, higher-stakes seam of "which server is this platform willing to run an MCP client
 *       against," checked once at registration/connect time by {@code core/mcp}'s own guard. Here
 *       the MCP server itself is already trusted-to-connect-to; this check is defense-in-depth
 *       against an LLM steering that ALREADY-CONNECTED server's own tool (e.g. a generic {@code
 *       fetch_url} tool) at an internal target via its argument, not a network-boundary guard in
 *       its own right - so the cheaper strict-literal check is an intentional, documented scope
 *       cut, not an oversight.
 * </ul>
 *
 * Not extracted as a shared utility with {@code core/mcp}'s {@code McpRemoteUrlGuard} on purpose:
 * that class is under active concurrent development on this same MCP hardening pass
 * (registration/connect paths), and the two guards protect different seams with different precision
 * requirements - duplicating the ~15 lines of literal-IPv4-range logic here avoids a cross-module
 * dependency and a merge-conflict surface for a genuinely small amount of shared code.
 *
 * <p><b>Failure mode: block, not just log.</b> A blocked call never reaches the MCP server - {@link
 * SanitizedMcpToolCallback} returns a plain-text explanation as the tool's result instead (same
 * "answer the model, don't kill the turn" shape as {@code UnavailableToolCallback}), so the model
 * can recover inside the same turn rather than the call silently going through. Fail-OPEN on
 * anything this class cannot confidently classify: arguments that aren't valid JSON ({@link
 * ArgumentSanitizingToolCallingManager} already exists to repair those before replay - duplicating
 * that concern here would be scope creep), non-textual values, and any host this class can't parse
 * as a URL at all.
 */
final class McpToolArgumentGuard {

    private static final ObjectMapper OBJECT_MAPPER = JsonMappers.plain();

    /**
     * Matches a `..` path-traversal segment bounded by `/`, `\`, or string start/end - not an
     * incidental ".." inside ordinary prose.
     */
    private static final Pattern TRAVERSAL_SEGMENT =
            Pattern.compile("(^|[/\\\\])\\.\\.([/\\\\]|$)");

    /**
     * Absolute-path prefixes into locations no legitimate MCP tool argument should ever need to
     * name. Lower-cased comparison.
     */
    private static final String[] SENSITIVE_PATH_PREFIXES = {
        "/etc/",
        "/proc/",
        "/sys/",
        "/root/",
        "/var/run/secrets/",
        "/.ssh/",
        "/.aws/",
        "~/.ssh/",
        "~/.aws/",
        "c:\\windows\\system32",
        "c:\\windows\\syswow64",
        "c:/windows/system32"
    };

    /**
     * Platform-internal hostnames a tool argument's URL must never target - a small, deliberately
     * conservative subset of {@code McpRemoteUrlGuard}'s registration-time denylist (see class
     * javadoc).
     */
    private static final Set<String> BLOCKED_LITERAL_HOSTS =
            Set.of("localhost", "metadata.google.internal", "metadata");

    private static final int MAX_SCAN_DEPTH = 20;

    private McpToolArgumentGuard() {}

    /**
     * Empty when {@code rawJsonArguments} looks safe to send to the MCP server (including "not
     * JSON" / "not parseable" - fail-open, see class javadoc); otherwise a short, model-facing
     * reason why it was blocked. Never throws.
     */
    static Optional<String> checkArguments(String rawJsonArguments) {
        if (rawJsonArguments == null || rawJsonArguments.isBlank()) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(rawJsonArguments);
        } catch (Exception e) {
            return Optional.empty();
        }
        try {
            return scanNode(root, 0);
        } catch (RuntimeException e) {
            // A guard that itself crashes the turn would be strictly worse
            // than the thing it defends against - fail open, same posture
            // as every other best-effort check in this module.
            return Optional.empty();
        }
    }

    private static Optional<String> scanNode(JsonNode node, int depth) {
        if (node == null || depth > MAX_SCAN_DEPTH) {
            return Optional.empty();
        }
        if (node.isString()) {
            return checkValue(node.stringValue());
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = JsonNodes.fields(node);
            while (fields.hasNext()) {
                Optional<String> reason = scanNode(fields.next().getValue(), depth + 1);
                if (reason.isPresent()) {
                    return reason;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                Optional<String> reason = scanNode(child, depth + 1);
                if (reason.isPresent()) {
                    return reason;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> checkValue(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        Optional<String> traversal = pathTraversalReason(value);
        if (traversal.isPresent()) {
            return traversal;
        }
        return ssrfReason(value);
    }

    private static Optional<String> pathTraversalReason(String value) {
        if (TRAVERSAL_SEGMENT.matcher(value).find()) {
            return Optional.of("contains a path-traversal sequence ('..')");
        }
        // One decode pass for the common %2e%2e URL-encoded bypass - best
        // effort, not a full recursive-decode defense (see class javadoc on
        // scope).
        String decoded = tryUrlDecode(value);
        if (decoded != null
                && !decoded.equals(value)
                && TRAVERSAL_SEGMENT.matcher(decoded).find()) {
            return Optional.of("contains a URL-encoded path-traversal sequence ('..')");
        }
        String lower = value.toLowerCase(Locale.ROOT);
        for (String prefix : SENSITIVE_PATH_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return Optional.of("is an absolute path into a sensitive system location");
            }
        }
        return Optional.empty();
    }

    private static String tryUrlDecode(String value) {
        if (!value.contains("%")) {
            return null;
        }
        try {
            return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static Optional<String> ssrfReason(String value) {
        URI uri;
        try {
            uri = new URI(value.trim());
        } catch (Exception e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return Optional.empty();
        }
        String schemeLower = scheme.toLowerCase(Locale.ROOT);
        if (!schemeLower.equals("http") && !schemeLower.equals("https")) {
            return Optional.empty();
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        String bareHost =
                host.startsWith("[") && host.endsWith("]")
                        ? host.substring(1, host.length() - 1)
                        : host;
        String lowerHost = bareHost.toLowerCase(Locale.ROOT);
        while (lowerHost.endsWith(".")) {
            lowerHost = lowerHost.substring(0, lowerHost.length() - 1);
        }
        if (BLOCKED_LITERAL_HOSTS.contains(lowerHost) || lowerHost.endsWith(".localhost")) {
            return Optional.of(
                    "is a URL pointing at a platform-internal hostname ('" + bareHost + "')");
        }
        byte[] ipv4 = parseStrictDottedQuad(lowerHost);
        if (ipv4 != null && isBlockedIpv4(ipv4)) {
            return Optional.of(
                    "is a URL pointing at a private, loopback, link-local, or cloud-metadata IP"
                        + " address ("
                            + bareHost
                            + ")");
        }
        if (lowerHost.contains(":")) {
            try {
                InetAddress addr = InetAddress.getByName(lowerHost.split("%", 2)[0]);
                if (isBlockedInetAddress(addr)) {
                    return Optional.of(
                            "is a URL pointing at a private, loopback, or link-local IPv6 address ("
                                    + bareHost
                                    + ")");
                }
            } catch (Exception ignored) {
                // Unparseable IPv6 literal - not this check's concern; the
                // URL simply won't resolve, which is a functional failure
                // for the tool, not a security one this guard should block.
            }
        }
        return Optional.empty();
    }

    /**
     * Strict 4-octet dotted-decimal parse only - see class javadoc for why the
     * alternate-numeral-encoding forms are out of scope here.
     */
    private static byte[] parseStrictDottedQuad(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3 || !p.chars().allMatch(Character::isDigit)) {
                return null;
            }
            int v = Integer.parseInt(p);
            if (v > 255) {
                return null;
            }
            out[i] = (byte) v;
        }
        return out;
    }

    /**
     * Same range set as {@code McpRemoteUrlGuard#isBlockedIpv4} - deliberately duplicated, see
     * class javadoc.
     */
    private static boolean isBlockedIpv4(byte[] ip) {
        int b0 = ip[0] & 0xFF;
        int b1 = ip[1] & 0xFF;
        return b0 == 127 // 127.0.0.0/8 loopback
                || b0 == 10 // 10.0.0.0/8
                || (b0 == 172 && b1 >= 16 && b1 <= 31) // 172.16.0.0/12
                || (b0 == 192 && b1 == 168) // 192.168.0.0/16
                || (b0 == 169 && b1 == 254) // 169.254.0.0/16 link-local + cloud metadata
                || (b0 == 100 && b1 >= 64 && b1 <= 127) // 100.64.0.0/10 CGNAT
                || b0 == 0; // 0.0.0.0/8 unspecified
    }

    private static boolean isBlockedInetAddress(InetAddress addr) {
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            return isBlockedIpv4(b);
        }
        boolean mapped = (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF;
        for (int i = 0; i < 10 && mapped; i++) {
            if (b[i] != 0) {
                mapped = false;
            }
        }
        if (mapped) {
            return isBlockedIpv4(new byte[] {b[12], b[13], b[14], b[15]});
        }
        if (addr.isLoopbackAddress()
                || addr.isAnyLocalAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()) {
            return true;
        }
        int g = ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
        return (g & 0xFE00) == 0xFC00 // fc00::/7 unique-local
                || (g & 0xFFC0) == 0xFE80; // fe80::/10 link-local
    }
}
