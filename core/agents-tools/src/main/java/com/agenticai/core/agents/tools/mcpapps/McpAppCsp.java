package com.agenticai.core.agents.tools.mcpapps;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds the Content-Security-Policy an MCP App document is rendered under. The HTML is untrusted:
 * we do not try to sanitise it into safety, we constrain what it can do. The default is a closed
 * policy; a server may ASK for specific https origins through {@code _meta.ui.csp}, but only
 * origins the operator listed in {@code app.mcp.apps.allowed-domains} (default: none) are ever
 * granted. A request for anything else is silently dropped (never partially honoured), so a hostile
 * declaration cannot widen the policy.
 *
 * <p>Delivered to the browser as a string and applied by the host as a {@code <meta http-equiv>} in
 * the sandboxed {@code srcdoc} document ({@code buildAppDoc} in web). Meta CSPs are cumulative, so
 * app markup cannot loosen what is set first.
 */
public final class McpAppCsp {

    /** An https origin: host with optional port, no path/query/credentials/wildcards. */
    private static final Pattern HTTPS_ORIGIN =
            Pattern.compile(
                    "^https://([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*)(:[0-9]{1,5})?$");

    /** An allowlist entry: bare host or {@code *.suffix}. */
    private static final Pattern ALLOW_ENTRY =
            Pattern.compile(
                    "^(\\*\\.)?[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$");

    private McpAppCsp() {}

    /**
     * Normalises operator-supplied allowlist entries: trimmed, lower-cased, invalid ones dropped.
     */
    public static List<String> normalizeAllowlist(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String entry : raw) {
            if (entry == null) {
                continue;
            }
            String e = entry.trim().toLowerCase(Locale.ROOT);
            if (ALLOW_ENTRY.matcher(e).matches()) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * @param declared what the server asked for (untrusted)
     * @param allowedDomains operator allowlist (hosts or {@code *.suffix}); empty = no widening at
     *     all
     * @param httpsImages whether {@code img-src} includes {@code https:} (default true; see the
     *     security notes)
     */
    public static String build(
            McpAppUiMeta.DeclaredCsp declared, List<String> allowedDomains, boolean httpsImages) {
        McpAppUiMeta.DeclaredCsp d = declared == null ? McpAppUiMeta.DeclaredCsp.NONE : declared;
        List<String> allow = normalizeAllowlist(allowedDomains);
        Set<String> connect = granted(d.connectDomains(), allow);
        Set<String> resource = granted(d.resourceDomains(), allow);
        Set<String> frame = granted(d.frameDomains(), allow);
        Set<String> base = granted(d.baseUriDomains(), allow);

        List<String> directives = new ArrayList<>();
        directives.add("default-src 'none'");
        directives.add(join("script-src 'unsafe-inline'", resource));
        directives.add(join("style-src 'unsafe-inline'", resource));
        directives.add(join(httpsImages ? "img-src data: https:" : "img-src data:", resource));
        directives.add(join("font-src data:", resource));
        directives.add(join("media-src data:", resource));
        directives.add(connect.isEmpty() ? "connect-src 'none'" : join("connect-src", connect));
        directives.add(frame.isEmpty() ? "frame-src 'none'" : join("frame-src", frame));
        directives.add("object-src 'none'");
        directives.add(base.isEmpty() ? "base-uri 'none'" : join("base-uri", base));
        directives.add("form-action 'none'");
        return String.join("; ", directives);
    }

    private static String join(String head, Set<String> extra) {
        return extra.isEmpty() ? head : head + " " + String.join(" ", extra);
    }

    private static Set<String> granted(List<String> asked, List<String> allow) {
        Set<String> out = new LinkedHashSet<>();
        for (String raw : asked) {
            String origin = raw.trim().toLowerCase(Locale.ROOT);
            var m = HTTPS_ORIGIN.matcher(origin);
            if (!m.matches()) {
                continue; // not a plain https origin (wildcards, other schemes, paths, quotes, ';'
                // all land here)
            }
            String host = m.group(1);
            if (isAllowed(host, allow)) {
                out.add(origin);
            }
        }
        return out;
    }

    private static boolean isAllowed(String host, List<String> allow) {
        for (String entry : allow) {
            if (entry.startsWith("*.")) {
                String suffix = entry.substring(1); // ".example.com"
                if (host.endsWith(suffix) && host.length() > suffix.length()) {
                    return true;
                }
            } else if (host.equals(entry)) {
                return true;
            }
        }
        return false;
    }
}
