package com.agenticai.core.agents.tools.platformguide;

import com.agenticai.core.agentspi.SetupStatusProvider;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.mcp.McpCatalogService;
import com.agenticai.core.mcp.McpConnectionStatus;
import com.agenticai.core.mcp.McpRegistryService;
import com.agenticai.core.mcp.dto.McpCatalogEntry;
import com.agenticai.core.mcp.dto.McpServerRegistrationResponse;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The MCP half of the setup guide: this tenant's registered servers (for the {@code mcp-servers}
 * status) and the catalogue servers that fit a request ("jira", "postgres", "github"), each marked
 * with whether it is already registered and connected. That is what lets the Assistant say "this
 * needs the Atlassian server - it is registered but failing, fix its credentials" instead of a
 * generic answer.
 */
@Component
public class McpSetupAdvisor implements SetupStatusProvider, SetupGuideToolCallback.McpAdvisor {

    private static final Logger log = LoggerFactory.getLogger(McpSetupAdvisor.class);
    private static final int MAX_CANDIDATES = 4;

    private final ObjectProvider<McpRegistryService> registry;
    private final ObjectProvider<McpCatalogService> catalog;

    public McpSetupAdvisor(
            ObjectProvider<McpRegistryService> registry,
            ObjectProvider<McpCatalogService> catalog) {
        this.registry = registry;
        this.catalog = catalog;
    }

    @Override
    public Map<String, Status> statuses() {
        McpRegistryService svc = registry.getIfAvailable();
        if (svc == null) {
            return Map.of();
        }
        try {
            List<McpServerRegistrationResponse> all = svc.listAll();
            if (all.isEmpty()) {
                return Map.of(
                        "mcp-servers",
                        new Status(State.NOT_SET_UP, "No MCP server is registered yet."));
            }
            List<String> connected =
                    names(
                            all,
                            r ->
                                    r.enabled()
                                            && r.connectionStatus()
                                                    == McpConnectionStatus.CONNECTED);
            List<String> failing =
                    names(all, r -> r.connectionStatus() == McpConnectionStatus.ERROR);
            List<String> off = names(all, r -> !r.enabled());
            StringBuilder detail = new StringBuilder();
            if (!connected.isEmpty()) {
                detail.append("Connected: ").append(String.join(", ", connected)).append(". ");
            }
            if (!failing.isEmpty()) {
                detail.append("Failing: ").append(String.join(", ", failing)).append(". ");
            }
            if (!off.isEmpty()) {
                detail.append("Turned off: ").append(String.join(", ", off)).append(". ");
            }
            State state =
                    !failing.isEmpty() || connected.isEmpty() ? State.NEEDS_ATTENTION : State.READY;
            return Map.of("mcp-servers", new Status(state, detail.toString().strip()));
        } catch (RuntimeException e) {
            log.debug("MCP status unavailable", e);
            return Map.of();
        }
    }

    @Override
    public List<ObjectNode> candidates(String query) {
        McpCatalogService cat = catalog.getIfAvailable();
        if (cat == null) {
            return List.of();
        }
        List<String> terms = PlatformGuide.terms(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        Map<String, McpServerRegistrationResponse> registered = registered();
        record Scored(McpCatalogEntry entry, int score) {}
        List<Scored> scored = new ArrayList<>();
        try {
            for (McpCatalogEntry e : cat.list()) {
                String name = lower(e.name());
                String hay = name + " " + lower(e.description()) + " " + lower(e.key());
                int score = 0;
                for (String t : terms) {
                    if (name.contains(t)) {
                        score += 5;
                    } else if (hay.contains(t)) {
                        score += 1;
                    }
                }
                if (score >= 5) {
                    scored.add(new Scored(e, score));
                }
            }
        } catch (RuntimeException e) {
            log.debug("MCP catalogue unavailable", e);
            return List.of();
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed());
        List<ObjectNode> out = new ArrayList<>();
        for (Scored s : scored.subList(0, Math.min(MAX_CANDIDATES, scored.size()))) {
            McpCatalogEntry e = s.entry();
            ObjectNode n = JsonMappers.plain().createObjectNode();
            n.put("name", e.name()).put("what_it_does", e.description());
            McpServerRegistrationResponse r = registered.get(lower(e.name()));
            if (r == null) {
                n.put("state", "NOT_REGISTERED");
                n.put(
                        "to_register",
                        e.requiresOAuth()
                                ? "Open Tools, pick "
                                        + e.name()
                                        + " and sign in through the browser prompt."
                                : e.ready()
                                        ? "Open Tools and register "
                                                + e.name()
                                                + "; it is already wired on this install."
                                        : "Open Tools, pick "
                                                + e.name()
                                                + " and enter: "
                                                + (e.requiredEnvVars() == null
                                                                || e.requiredEnvVars().isEmpty()
                                                        ? "no credentials needed"
                                                        : String.join(", ", e.requiredEnvVars()))
                                                + ".");
            } else {
                n.put("state", !r.enabled() ? "TURNED_OFF" : r.connectionStatus().name());
                if (r.connectionError() != null && !r.connectionError().isBlank()) {
                    n.put("last_error", r.connectionError());
                }
                if (r.connectionHint() != null && !r.connectionHint().isBlank()) {
                    n.put("hint", r.connectionHint());
                }
            }
            out.add(n);
        }
        return out;
    }

    private Map<String, McpServerRegistrationResponse> registered() {
        McpRegistryService svc = registry.getIfAvailable();
        if (svc == null) {
            return Map.of();
        }
        try {
            return svc.listAll().stream()
                    .collect(Collectors.toMap(r -> lower(r.name()), r -> r, (a, b) -> a));
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static List<String> names(
            List<McpServerRegistrationResponse> all,
            java.util.function.Predicate<McpServerRegistrationResponse> p) {
        return all.stream().filter(p).map(McpServerRegistrationResponse::name).toList();
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
