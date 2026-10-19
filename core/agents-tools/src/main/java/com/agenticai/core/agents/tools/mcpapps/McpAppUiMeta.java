package com.agenticai.core.agents.tools.mcpapps;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads the MCP Apps declaration off a tool descriptor's {@code _meta}: {@code
 * _meta.ui.resourceUri} (or the older flat key {@code _meta["ui/resourceUri"]}) and the optional
 * {@code _meta.ui.csp}.
 *
 * <p>Pure and total: anything malformed yields {@link Optional#empty()} or an empty domain list,
 * never an exception. The URI check is deliberately the same shape as the web host's {@code
 * isUiResourceUri} ({@code web/app/lib/mcpApps/mcpApps.ts}): the {@code ui://} scheme and nothing
 * else - {@code http(s)://}, {@code file://}, {@code data:}, {@code javascript:} and every other
 * scheme is refused here, so no tool descriptor can steer the platform into fetching an arbitrary
 * URL.
 */
public final class McpAppUiMeta {

    /** Same regex as web/app/lib/mcpApps/mcpApps.ts UI_URI - keep the two in step. */
    private static final Pattern UI_URI =
            Pattern.compile("^ui://[A-Za-z0-9][A-Za-z0-9._-]{0,127}(/[A-Za-z0-9._~\\-/]{0,255})?$");

    private McpAppUiMeta() {}

    /**
     * What a server asked for beyond the locked-down default CSP. Every list may be empty; none of
     * it is trusted.
     */
    public record DeclaredCsp(
            List<String> connectDomains,
            List<String> resourceDomains,
            List<String> frameDomains,
            List<String> baseUriDomains) {

        public static final DeclaredCsp NONE =
                new DeclaredCsp(List.of(), List.of(), List.of(), List.of());

        public DeclaredCsp {
            connectDomains = connectDomains == null ? List.of() : List.copyOf(connectDomains);
            resourceDomains = resourceDomains == null ? List.of() : List.copyOf(resourceDomains);
            frameDomains = frameDomains == null ? List.of() : List.copyOf(frameDomains);
            baseUriDomains = baseUriDomains == null ? List.of() : List.copyOf(baseUriDomains);
        }
    }

    /**
     * A tool's app declaration: the (validated) {@code ui://} URI plus whatever CSP widening it
     * requested.
     */
    public record Declaration(String resourceUri, DeclaredCsp csp) {}

    /** True only for a syntactically valid {@code ui://} URI. */
    public static boolean isUiUri(Object uri) {
        return uri instanceof String s && s.length() <= 400 && UI_URI.matcher(s).matches();
    }

    /**
     * The tool's app declaration, or empty when it has none or the URI is not a valid {@code ui://}
     * URI.
     */
    public static Optional<Declaration> declarationOf(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) {
            return Optional.empty();
        }
        Object ui = meta.get("ui");
        Object uri = null;
        Object csp = null;
        if (ui instanceof Map<?, ?> uiMap) {
            uri = uiMap.get("resourceUri");
            csp = uiMap.get("csp");
        }
        if (uri == null) {
            uri = meta.get("ui/resourceUri");
        }
        if (!isUiUri(uri)) {
            return Optional.empty();
        }
        return Optional.of(new Declaration((String) uri, parseCsp(csp)));
    }

    private static DeclaredCsp parseCsp(Object csp) {
        if (!(csp instanceof Map<?, ?> m)) {
            return DeclaredCsp.NONE;
        }
        return new DeclaredCsp(
                strings(m.get("connectDomains")),
                strings(m.get("resourceDomains")),
                strings(m.get("frameDomains")),
                strings(m.get("baseUriDomains")));
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof String s && !s.isBlank() && s.length() <= 253 && out.size() < 32) {
                out.add(s.trim());
            }
        }
        return out;
    }
}
