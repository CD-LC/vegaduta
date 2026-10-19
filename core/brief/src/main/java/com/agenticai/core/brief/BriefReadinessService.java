package com.agenticai.core.brief;

import static com.agenticai.core.brief.dto.ReadinessRow.GATED;
import static com.agenticai.core.brief.dto.ReadinessRow.MISSING;
import static com.agenticai.core.brief.dto.ReadinessRow.READY;
import static com.agenticai.core.brief.dto.ReadinessRow.WAITING_ON_ADMIN;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.brief.dto.ReadinessRow;
import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.knowledge.KnowledgeCollectionService;
import com.agenticai.core.knowledge.KnowledgeDocumentService;
import com.agenticai.core.mcp.McpCatalogService;
import com.agenticai.core.mcp.registry.OfficialMcpRegistryClient;
import com.agenticai.core.mcp.registry.RegistryEnvVarSpec;
import com.agenticai.core.mcp.registry.RegistrySearchResponse;
import com.agenticai.core.mcp.registry.RegistrySearchResult;
import com.agenticai.core.omnichannel.session.ChannelDefaultAgent;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.secrets.SecretScope;
import com.agenticai.core.secrets.SecretVaultService;
import com.agenticai.core.secrets.TenantSecret;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.workflow.WorkflowService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * "What is missing before this can work" - the rows behind the readiness rail. Everything here
 * is read from real state at call time (vault list, MCP registry, knowledge counts, channel
 * routing, plan limits, the caller's authorities); nothing is cached or invented, and a row whose
 * truth could not be determined says so instead of guessing. Secrets are reported by NAME only -
 * the vault's plaintext exit ({@code resolveForRun}) is never called and no value preview is
 * read.
 *
 * <p>Plans are the real {@link TenantPlan} values (FREE / PREMIUM / UNLIMITED); {@code unlocksAt}
 * therefore says PREMIUM or UNLIMITED. No price is ever stated.
 */
@Service
public class BriefReadinessService {

    private static final Logger log = LoggerFactory.getLogger(BriefReadinessService.class);

    static final String PERM_MANAGE_AGENTS = "PERM_MANAGE_AGENTS";
    static final String PERM_MANAGE_WORKFLOWS_ADMIN = "PERM_MANAGE_WORKFLOWS_ADMIN";

    private final SecretVaultService secretVaultService;
    private final OfficialMcpRegistryClient registryClient;
    private final McpCatalogService catalogService;
    private final KnowledgeCollectionService knowledgeCollectionService;
    private final KnowledgeDocumentService knowledgeDocumentService;
    private final ChannelSessionService channelSessionService;
    private final TenantLimits tenantLimits;
    private final AgentService agentService;
    private final WorkflowService workflowService;

    public BriefReadinessService(
        SecretVaultService secretVaultService,
        OfficialMcpRegistryClient registryClient,
        McpCatalogService catalogService,
        KnowledgeCollectionService knowledgeCollectionService,
        KnowledgeDocumentService knowledgeDocumentService,
        ChannelSessionService channelSessionService,
        TenantLimits tenantLimits,
        AgentService agentService,
        WorkflowService workflowService
    ) {
        this.secretVaultService = secretVaultService;
        this.registryClient = registryClient;
        this.catalogService = catalogService;
        this.knowledgeCollectionService = knowledgeCollectionService;
        this.knowledgeDocumentService = knowledgeDocumentService;
        this.channelSessionService = channelSessionService;
        this.tenantLimits = tenantLimits;
        this.agentService = agentService;
        this.workflowService = workflowService;
    }

    /** Readiness for a brief that has not been applied (no bindings). */
    public List<ReadinessRow> compute(Brief brief) {
        return compute(brief, Map.of());
    }

    /**
     * @param bindings ref to real entity id (from {@code brief_binding}) once applied; lets the
     *     knowledge rows report the REAL collection's document count instead of "not created yet".
     */
    public List<ReadinessRow> compute(Brief brief, Map<String, String> bindings) {
        List<ReadinessRow> rows = new ArrayList<>();
        if (brief == null || brief.isClarificationOnly()) {
            return rows;
        }
        Optional<TenantContext> ctx = TenantContext.current();
        String tenantId = ctx.map(TenantContext::getTenantId).orElse(null);
        String subject = ctx.map(TenantContext::getSubject).orElse(null);
        TenantPlan plan = tenantId == null ? TenantPlan.UNLIMITED : tenantLimits.plan(tenantId);
        boolean canIntegrate = hasAuthority(ctx, BriefValidator.PERM_MANAGE_INTEGRATIONS);

        List<TenantSecret> vault = subject == null ? List.of() : secretVaultService.listVisibleTo(subject);
        Map<String, Brief.SecretSpec> secretsByRef = new LinkedHashMap<>();
        brief.secrets().forEach(s -> secretsByRef.put(s.ref(), s));

        secretRows(brief, vault, subject, canIntegrate, rows);
        connectorRows(brief, secretsByRef, vault, subject, plan, canIntegrate, rows);
        toolRows(brief, secretsByRef, vault, subject, canIntegrate && hasAuthority(ctx, BriefValidator.PERM_MANAGE_TOOLS), rows);
        knowledgeRows(brief, bindings, rows);
        channelRows(brief, rows);
        planLimitRows(brief, bindings, tenantId, plan, rows);
        permissionRows(ctx, canIntegrate, brief, rows);
        return rows;
    }

    /** State to count, in a fixed order the list view can print as "3 ready, 2 missing". */
    public Map<String, Integer> summarize(List<ReadinessRow> rows) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String state : List.of(READY, MISSING, WAITING_ON_ADMIN, GATED, ReadinessRow.SIMULATED)) {
            out.put(state, 0);
        }
        rows.forEach(r -> out.merge(r.state(), 1, Integer::sum));
        return out;
    }

    // ------------------------------------------------------------------ secrets

    private void secretRows(
        Brief brief, List<TenantSecret> vault, String subject, boolean canIntegrate, List<ReadinessRow> rows
    ) {
        for (Brief.SecretSpec s : brief.secrets()) {
            String href = "/secrets?tour=field:secret-add&name=" + urlEncode(s.name());
            Optional<TenantSecret> found = findSecret(vault, s, subject);
            if (found.isPresent() && found.get().isResolvable()) {
                rows.add(new ReadinessRow("SECRET", s.ref(), s.name(), READY,
                    "Stored in the vault. The value is never shown here.", null, null));
            } else if (found.isPresent()) {
                rows.add(new ReadinessRow("SECRET", s.ref(), s.name(), MISSING,
                    "A secret with this name exists but is revoked or expired - rotate it in the vault.", href, null));
            } else if ("TENANT".equals(s.scope()) && !canIntegrate) {
                rows.add(new ReadinessRow("SECRET", s.ref(), s.name(), WAITING_ON_ADMIN,
                    "Not in the vault yet. Shared secrets can only be added by someone with the Manage integrations "
                        + "permission - ask an admin. " + safe(s.why()), null, null));
            } else {
                String scopeNote = "USER".equals(s.scope())
                    ? "Each person who runs this needs their own value in the vault. "
                    : "";
                rows.add(new ReadinessRow("SECRET", s.ref(), s.name(), MISSING,
                    "Not in the vault yet. " + scopeNote + safe(s.why()), href, null));
            }
        }
    }

    private static Optional<TenantSecret> findSecret(List<TenantSecret> vault, Brief.SecretSpec spec, String subject) {
        SecretScope scope = "USER".equals(spec.scope()) ? SecretScope.USER : SecretScope.TENANT;
        return vault.stream()
            .filter(v -> v.getName().equals(spec.name()) && v.getScope() == scope)
            .filter(v -> scope == SecretScope.TENANT || (subject != null && subject.equals(v.getOwnerUserId())))
            .findFirst();
    }

    /** True when the brief's secret {@code ref} is resolvable in the vault for this caller. */
    private static boolean secretPresent(
        String ref, Map<String, Brief.SecretSpec> byRef, List<TenantSecret> vault, String subject
    ) {
        Brief.SecretSpec spec = byRef.get(ref);
        return spec != null && findSecret(vault, spec, subject).filter(TenantSecret::isResolvable).isPresent();
    }

    // ------------------------------------------------------------------ MCP + tools

    private void connectorRows(
        Brief brief, Map<String, Brief.SecretSpec> secretsByRef, List<TenantSecret> vault, String subject,
        TenantPlan plan, boolean canIntegrate, List<ReadinessRow> rows
    ) {
        for (Brief.McpServerSpec m : brief.mcpServers()) {
            String label = m.name() != null && !m.name().isBlank() ? m.name() : m.registryId();
            boolean catalog = catalogService.isCatalogEntry(m.registryId()) || catalogService.isCatalogEntry(m.name());
            if (plan == TenantPlan.FREE && !catalog) {
                rows.add(new ReadinessRow("CONNECTOR", m.ref(), label, GATED,
                    "Your plan can only connect servers from the built-in MCP catalog; this one is a custom registry server.",
                    "/billing", "PREMIUM"));
                continue;
            }
            Optional<List<String>> requiredEnv = requiredEnvVars(m, catalog);
            if (requiredEnv.isEmpty()) {
                rows.add(new ReadinessRow("CONNECTOR", m.ref(), label, MISSING,
                    "The MCP registry could not be reached or does not list '" + m.registryId()
                        + "', so its required variables could not be checked.",
                    "/tools?tour=field:mcp-connect", null));
                continue;
            }
            List<String> missing = new ArrayList<>();
            for (String envName : requiredEnv.get()) {
                Brief.EnvBinding binding = m.env().get(envName);
                if (binding == null || !secretPresent(binding.secretRef(), secretsByRef, vault, subject)) {
                    missing.add(envName);
                }
            }
            if (!missing.isEmpty()) {
                rows.add(new ReadinessRow("CONNECTOR", m.ref(), label, MISSING,
                    "Needs " + String.join(", ", missing) + " stored in the vault before it can connect.",
                    "/secrets?tour=field:secret-add", null));
            } else if (!canIntegrate) {
                rows.add(new ReadinessRow("CONNECTOR", m.ref(), label, WAITING_ON_ADMIN,
                    "Its variables are in the vault, but connecting an MCP server needs the Manage integrations permission.",
                    null, null));
            } else {
                rows.add(new ReadinessRow("CONNECTOR", m.ref(), label, READY,
                    "Its required variables are stored. It is connected when the brief is applied.", null, null));
            }
        }
    }

    /**
     * Required + secret env var names for a server, from the curated catalog when it is one,
     * otherwise from the live registry (exact name match). Empty when the answer is unknowable
     * (registry unreachable or no such entry) - the caller reports that honestly.
     */
    private Optional<List<String>> requiredEnvVars(Brief.McpServerSpec m, boolean catalog) {
        if (catalog) {
            return catalogService.find(m.registryId()).or(() -> catalogService.find(m.name()))
                .map(e -> e.requiredEnvVars() == null ? List.<String>of() : e.requiredEnvVars());
        }
        try {
            RegistrySearchResponse response = registryClient.search(m.registryId(), null);
            if (response == null || response.unavailable() || response.results() == null) {
                return Optional.empty();
            }
            for (RegistrySearchResult r : response.results()) {
                if (m.registryId().equals(r.name())) {
                    List<String> names = new ArrayList<>();
                    if (r.envVars() != null) {
                        for (RegistryEnvVarSpec v : r.envVars()) {
                            if (v.required() && v.secret()) {
                                names.add(v.name());
                            }
                        }
                    }
                    return Optional.of(names);
                }
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("MCP registry lookup failed while computing brief readiness", e);
            return Optional.empty();
        }
    }

    private void toolRows(
        Brief brief, Map<String, Brief.SecretSpec> secretsByRef, List<TenantSecret> vault, String subject,
        boolean canIntegrate, List<ReadinessRow> rows
    ) {
        for (Brief.ToolSpec t : brief.tools()) {
            String label = t.name() != null && !t.name().isBlank() ? t.name() : t.ref();
            boolean needsSecret = t.auth() != null && t.auth().type() != null && !"NONE".equals(t.auth().type());
            if (needsSecret && !secretPresent(t.auth().secretRef(), secretsByRef, vault, subject)) {
                Brief.SecretSpec spec = secretsByRef.get(t.auth().secretRef());
                rows.add(new ReadinessRow("CONNECTOR", t.ref(), label, MISSING,
                    "Its credential" + (spec != null ? " (" + spec.name() + ")" : "") + " is not in the vault yet.",
                    "/secrets?tour=field:secret-add", null));
            } else if (!canIntegrate) {
                rows.add(new ReadinessRow("CONNECTOR", t.ref(), label, WAITING_ON_ADMIN,
                    "Creating tools needs the Manage integrations and Manage tools permissions - an admin can apply this part.", null, null));
            } else {
                rows.add(new ReadinessRow("CONNECTOR", t.ref(), label, READY,
                    "Ready to be created when the brief is applied.", null, null));
            }
        }
    }

    // ------------------------------------------------------------------ knowledge

    private void knowledgeRows(Brief brief, Map<String, String> bindings, List<ReadinessRow> rows) {
        for (Brief.KnowledgeSpec k : brief.knowledge()) {
            String hint = k.sources().stream().map(Brief.KnowledgeSource::hint)
                .filter(h -> h != null && !h.isBlank()).findFirst().orElse("Upload the documents this collection should answer from.");
            String bound = bindings.get(k.ref());
            if (bound == null) {
                rows.add(new ReadinessRow("KNOWLEDGE", k.ref(), k.name(), MISSING,
                    "The collection is created when the brief is applied and starts empty. " + hint,
                    "/knowledge", null));
                continue;
            }
            long files;
            try {
                files = knowledgeDocumentService.fileCount(UUID.fromString(bound));
            } catch (RuntimeException e) {
                rows.add(new ReadinessRow("KNOWLEDGE", k.ref(), k.name(), MISSING,
                    "The collection could not be read, so its contents are unknown.", "/knowledge", null));
                continue;
            }
            if (files > 0) {
                rows.add(new ReadinessRow("KNOWLEDGE", k.ref(), k.name(), READY,
                    files + " file" + (files == 1 ? "" : "s") + " in the collection.", null, null));
            } else {
                rows.add(new ReadinessRow("KNOWLEDGE", k.ref(), k.name(), MISSING,
                    "The collection is empty. " + hint, "/knowledge", null));
            }
        }
    }

    // ------------------------------------------------------------------ channels

    private void channelRows(Brief brief, List<ReadinessRow> rows) {
        for (Brief.ChannelSpec c : brief.channels()) {
            String label = c.kind();
            if ("WEB".equals(c.kind())) {
                rows.add(new ReadinessRow("CHANNEL", c.ref(), label, READY,
                    "Web chat needs no channel setup.", null, null));
                continue;
            }
            Optional<ChannelType> type = channelType(c.kind());
            if (type.isEmpty()) {
                rows.add(new ReadinessRow("CHANNEL", c.ref(), label, MISSING,
                    c.kind() + " routing cannot be set up from a brief yet - connect it in Integrations.",
                    "/integrations", null));
                continue;
            }
            Optional<ChannelDefaultAgent> routing;
            try {
                routing = channelSessionService.routingFor(type.get());
            } catch (RuntimeException e) {
                rows.add(new ReadinessRow("CHANNEL", c.ref(), label, MISSING,
                    "Channel routing could not be read, so its state is unknown.", "/integrations", null));
                continue;
            }
            if (routing.isPresent() && routing.get().getAgentId() != null) {
                rows.add(new ReadinessRow("CHANNEL", c.ref(), label, MISSING,
                    c.kind() + " is already routed to another agent. Applying will not take it over - change the routing "
                        + "first if you want this brief to answer there.", "/integrations", null));
            } else {
                rows.add(new ReadinessRow("CHANNEL", c.ref(), label, READY,
                    c.kind() + " has no default agent yet, so the brief can take it. Whether the channel's own credentials "
                        + "are connected is checked in Integrations.", "/integrations", null));
            }
        }
    }

    static Optional<ChannelType> channelType(String kind) {
        if (kind == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(ChannelType.valueOf(kind));
        } catch (IllegalArgumentException e) {
            return Optional.empty(); // WEB and unknown kinds: no ChannelType
        }
    }

    // ------------------------------------------------------------------ plan limits

    /**
     * Only artifacts not yet bound count against the caps: an applied brief's own agents are already in the
     * tenant-wide totals, and adding them again would flag a solution that is fine.
     */
    private void planLimitRows(
        Brief brief, Map<String, String> bindings, String tenantId, TenantPlan plan, List<ReadinessRow> rows
    ) {
        if (tenantId == null) {
            return;
        }
        String next = nextPlan(plan);
        tenantLimits.maxAgents(tenantId).ifPresent(max -> {
            long adding = brief.agents().stream().filter(a -> !bound(bindings, a.ref())).count();
            if (brief.agents().isEmpty()) {
                return;
            }
            int total = agentService.listAll().size() + (int) adding;
            rows.add(capRow("agents", total, max, next));
        });
        tenantLimits.maxWorkflows(tenantId).ifPresent(max -> {
            long adding = brief.workflows().stream().filter(w -> !bound(bindings, w.ref())).count();
            if (brief.workflows().isEmpty()) {
                return;
            }
            int total = workflowService.listAll().size() + (int) adding;
            rows.add(capRow("workflows", total, max, next));
        });
        tenantLimits.maxKbCollections(tenantId).ifPresent(max -> {
            if (brief.knowledge().isEmpty()) {
                return;
            }
            long adding = brief.knowledge().stream().filter(k -> !bound(bindings, k.ref())).count();
            long total = knowledgeCollectionService.count() + adding;
            rows.add(capRow("knowledge collections", (int) total, max, next));
        });
        if (brief.evalSet() != null && plan == TenantPlan.FREE) {
            rows.add(new ReadinessRow("PLAN_LIMIT", null, "Eval sets and quality gate", GATED,
                "Eval sets and regression runs are not available on your plan, so this brief stays Under watch with no quality gate.",
                "/billing", "PREMIUM"));
        }
    }

    private static boolean bound(Map<String, String> bindings, String ref) {
        return bindings != null && ref != null && bindings.containsKey(ref);
    }

    private static ReadinessRow capRow(String what, int total, int max, String next) {
        if (total > max) {
            return new ReadinessRow("PLAN_LIMIT", null, "Plan limit: " + what, GATED,
                "Applying would bring you to " + total + " " + what + ", over your plan's limit of " + max
                    + ". Remove some or move to a higher plan.", "/billing", next);
        }
        return new ReadinessRow("PLAN_LIMIT", null, "Plan limit: " + what, READY,
            "Within your plan's limit of " + max + " " + what + " (" + total + " after applying).", null, null);
    }

    private static String nextPlan(TenantPlan plan) {
        return switch (plan) {
            case FREE -> "PREMIUM";
            case PREMIUM -> "UNLIMITED";
            case UNLIMITED -> null;
        };
    }

    // ------------------------------------------------------------------ permissions

    private void permissionRows(Optional<TenantContext> ctx, boolean canIntegrate, Brief brief, List<ReadinessRow> rows) {
        boolean needsIntegrations = !brief.tools().isEmpty() || !brief.mcpServers().isEmpty()
            || brief.secrets().stream().anyMatch(s -> "TENANT".equals(s.scope()));
        if (needsIntegrations && !canIntegrate) {
            rows.add(new ReadinessRow("PERMISSION", null, BriefValidator.PERM_MANAGE_INTEGRATIONS, WAITING_ON_ADMIN,
                "Tools, MCP servers and shared secrets need the Manage integrations permission. Applying will skip those "
                    + "parts and mark them waiting on an admin.", null, null));
        }
        if (!brief.tools().isEmpty() && !hasAuthority(ctx, BriefValidator.PERM_MANAGE_TOOLS)) {
            rows.add(new ReadinessRow("PERMISSION", null, BriefValidator.PERM_MANAGE_TOOLS, WAITING_ON_ADMIN,
                "Creating tools, including generated code tools, needs the Manage tools permission. Applying will skip "
                    + "those parts and mark them waiting on an admin.", null, null));
        }
        if (brief.channels().stream().anyMatch(c -> !"WEB".equals(c.kind()))
            && !hasAuthority(ctx, BriefValidator.PERM_MANAGE_CHANNELS)) {
            rows.add(new ReadinessRow("PERMISSION", null, BriefValidator.PERM_MANAGE_CHANNELS, WAITING_ON_ADMIN,
                "Routing a channel to an agent needs the Manage channels permission. Applying will skip that part and "
                    + "mark it waiting on an admin.", null, null));
        }
        if (!hasAuthority(ctx, PERM_MANAGE_AGENTS)) {
            rows.add(new ReadinessRow("PERMISSION", null, PERM_MANAGE_AGENTS, WAITING_ON_ADMIN,
                "Applying a brief creates agents, which needs the Manage agents permission.", null, null));
        }
        if (!brief.workflows().isEmpty() && !hasAuthority(ctx, PERM_MANAGE_WORKFLOWS_ADMIN)) {
            rows.add(new ReadinessRow("PERMISSION", null, PERM_MANAGE_WORKFLOWS_ADMIN, WAITING_ON_ADMIN,
                "Applying a brief creates workflows, which needs the workflow admin permission.", null, null));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static boolean hasAuthority(Optional<TenantContext> ctx, String authority) {
        return ctx.map(c -> c.hasRole(authority) || c.hasRole("ROLE_platform-admin")).orElse(false);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
