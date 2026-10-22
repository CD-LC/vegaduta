package com.agenticai.core.brief;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.brief.BriefValidationResult.Issue;
import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.brief.model.Brief.AgentSpec;
import com.agenticai.core.brief.model.Brief.WorkflowSpec;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.workflow.OrchestratorService;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.WorkflowValidationException;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Validates a {@link Brief} against {@code brief-schema.v1.json} plus the rules a JSON Schema
 * cannot express: every ref resolves, secret names match the vault pattern, every artifact has a
 * {@code narrative.perNode} entry, {@code trust.irreversible} paths point at real workflow nodes,
 * each workflow graph passes the SAME {@link OrchestratorService#validate} gate every save/run goes
 * through (refs mapped to placeholder ids, see {@link BriefRefs#placeholder}), and the plan's
 * agent/workflow caps are pre-checked (existing + new) so apply cannot half-succeed on a limit. The
 * lookup-free section rules (tools, MCP, knowledge, secrets, channels, eval set, rehearsal) are in
 * {@link BriefSectionRules}.
 *
 * <p>Pure with respect to persistence: it reads counts and the caller's authorities but writes
 * nothing. A brief whose {@code clarifications[]} is non-empty is the planner asking, not proposing
 * - only its header and the questions are checked (schema: "everything else may be partial").
 */
@Service
public class BriefValidator {

    static final Set<String> RUNGS = Set.of("UNDER_WATCH", "TRUSTED", "AUTONOMOUS");
    static final Set<String> DEPTHS = Set.of("FAST", "CAREFUL", "RIGOROUS", "PLANNED");
    private static final Set<String> TUNING = Set.of("SIMPLE", "MANUAL");
    private static final Set<String> TRIGGER_KINDS =
            Set.of("MANUAL", "SCHEDULE", "WEBHOOK", "CHANNEL");
    private static final Set<String> PROCEDURE_SOURCES = Set.of("USER_GOAL", "PLANNER");
    private static final Pattern BUILTIN_TOOL_KEY = Pattern.compile("^[A-Za-z0-9_.:-]{1,120}$");

    public static final String PERM_MANAGE_INTEGRATIONS = "PERM_MANAGE_INTEGRATIONS";

    /**
     * Authoring a code or OpenAPI tool is gated on this over HTTP (SecurityConfig); the brief path
     * must honour it.
     */
    public static final String PERM_MANAGE_TOOLS = "PERM_MANAGE_TOOLS";

    /**
     * Routing an inbound channel to an agent is gated on this over HTTP; the brief path must honour
     * it.
     */
    public static final String PERM_MANAGE_CHANNELS = "PERM_MANAGE_CHANNELS";

    private final OrchestratorService orchestratorService;
    private final TenantLimits tenantLimits;
    private final AgentService agentService;
    private final WorkflowService workflowService;
    private final ObjectMapper objectMapper;

    public BriefValidator(
            OrchestratorService orchestratorService,
            TenantLimits tenantLimits,
            AgentService agentService,
            WorkflowService workflowService,
            ObjectMapper objectMapper) {
        this.orchestratorService = orchestratorService;
        this.tenantLimits = tenantLimits;
        this.agentService = agentService;
        this.workflowService = workflowService;
        this.objectMapper = objectMapper;
    }

    /**
     * Full validation including the plan-cap pre-check (use for a brief that is NOT applied yet).
     */
    public BriefValidationResult validate(Brief brief) {
        return validate(brief, true);
    }

    /**
     * @param checkCaps false for a brief whose artifacts already exist (its own agents would be
     *     double-counted against the plan cap).
     */
    public BriefValidationResult validate(Brief brief, boolean checkCaps) {
        List<Issue> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (brief == null) {
            errors.add(new Issue("brief", "The brief is empty."));
            return new BriefValidationResult(errors, warnings, List.of());
        }

        validateHeader(brief, errors);
        validateClarifications(brief, errors);
        if (brief.isClarificationOnly()) {
            return new BriefValidationResult(errors, warnings, List.of());
        }

        Map<String, Character> refs = new java.util.LinkedHashMap<>();
        validateRefsUnique(brief, refs, errors);
        validateTrustHeader(brief, errors);
        validateAgents(brief, refs, errors);
        validateProcedures(brief, errors);
        Map<String, Set<String>> nodeIdsByWorkflow = validateWorkflows(brief, refs, errors);
        BriefSectionRules.validateTools(brief, refs, errors);
        BriefSectionRules.warnCredentialDestinations(brief, warnings);
        BriefSectionRules.validateMcp(brief, refs, errors);
        BriefSectionRules.validateKnowledge(brief, errors);
        BriefSectionRules.validateSecrets(brief, errors);
        BriefSectionRules.validateChannels(brief, refs, errors);
        BriefSectionRules.validateEvalSet(brief, refs, errors, warnings);
        BriefSectionRules.validateRehearsal(brief, errors);
        validateTrustPaths(brief, nodeIdsByWorkflow, errors);
        validateNarrative(brief, refs, nodeIdsByWorkflow, errors);
        if (checkCaps) {
            checkCaps(brief, errors);
        }
        return new BriefValidationResult(errors, warnings, missingPermissions(brief));
    }

    // ------------------------------------------------------------------ header / trust

    private void validateHeader(Brief brief, List<Issue> errors) {
        if (brief.briefVersion() != Brief.CURRENT_VERSION) {
            errors.add(new Issue("briefVersion", "Only briefVersion 1 is supported."));
        }
        if (blank(brief.title())
                || brief.title().strip().length() < 3
                || brief.title().length() > 120) {
            errors.add(new Issue("title", "The title must be 3 to 120 characters."));
        }
        if (blank(brief.goal())
                || brief.goal().strip().length() < 10
                || brief.goal().length() > 4000) {
            errors.add(
                    new Issue(
                            "goal",
                            "The goal must be 10 to 4000 characters (the user's own words)."));
        }
        if (brief.locale() == null || !BriefRefs.LOCALE.matcher(brief.locale()).matches()) {
            errors.add(new Issue("locale", "The locale must look like 'en' or 'pt-BR'."));
        }
    }

    private void validateClarifications(Brief brief, List<Issue> errors) {
        if (brief.clarifications().size() > 3) {
            errors.add(new Issue("clarifications", "At most 3 clarifying questions are allowed."));
        }
        for (int i = 0; i < brief.clarifications().size(); i++) {
            Brief.Clarification c = brief.clarifications().get(i);
            if (c == null || blank(c.question())) {
                errors.add(
                        new Issue("clarifications[" + i + "].question", "A question is required."));
            } else if (c.options().size() > 4) {
                errors.add(
                        new Issue(
                                "clarifications[" + i + "].options",
                                "At most 4 options per question."));
            }
        }
    }

    private void validateTrustHeader(Brief brief, List<Issue> errors) {
        if (brief.trust() == null) {
            errors.add(new Issue("trust", "The trust section is required."));
        } else if (brief.trust().rung() == null || !RUNGS.contains(brief.trust().rung())) {
            errors.add(
                    new Issue(
                            "trust.rung", "The rung must be UNDER_WATCH, TRUSTED or AUTONOMOUS."));
        }
    }

    private void validateRefsUnique(Brief brief, Map<String, Character> refs, List<Issue> errors) {
        record Decl(String section, String ref, char kind) {}
        List<Decl> decls = new ArrayList<>();
        brief.agents().forEach(a -> decls.add(new Decl("agents", a.ref(), BriefRefs.AGENT)));
        brief.workflows()
                .forEach(w -> decls.add(new Decl("workflows", w.ref(), BriefRefs.WORKFLOW)));
        brief.tools().forEach(t -> decls.add(new Decl("tools", t.ref(), BriefRefs.TOOL)));
        brief.mcpServers().forEach(m -> decls.add(new Decl("mcpServers", m.ref(), BriefRefs.MCP)));
        brief.knowledge()
                .forEach(k -> decls.add(new Decl("knowledge", k.ref(), BriefRefs.KNOWLEDGE)));
        brief.secrets().forEach(s -> decls.add(new Decl("secrets", s.ref(), BriefRefs.SECRET)));
        brief.procedures()
                .forEach(p -> decls.add(new Decl("procedures", p.ref(), BriefRefs.PROCEDURE)));
        brief.channels().forEach(c -> decls.add(new Decl("channels", c.ref(), BriefRefs.CHANNEL)));
        for (Decl d : decls) {
            if (!BriefRefs.isRef(d.ref())) {
                errors.add(
                        new Issue(
                                d.section(),
                                "'"
                                        + d.ref()
                                        + "' is not a valid ref (a letter and one or two digits, like a1)."));
            } else if (d.ref().charAt(0) != d.kind()) {
                errors.add(
                        new Issue(
                                d.section() + "[" + d.ref() + "]",
                                "Refs in '"
                                        + d.section()
                                        + "' must start with '"
                                        + d.kind()
                                        + "'."));
            } else if (refs.putIfAbsent(d.ref(), d.kind()) != null) {
                errors.add(
                        new Issue(
                                d.section() + "[" + d.ref() + "]",
                                "Duplicate ref '" + d.ref() + "'."));
            }
        }
    }

    // ------------------------------------------------------------------ agents / procedures

    private void validateAgents(Brief brief, Map<String, Character> refs, List<Issue> errors) {
        if (brief.agents().isEmpty() || brief.agents().size() > 6) {
            errors.add(new Issue("agents", "A brief needs between 1 and 6 agents."));
        }
        for (AgentSpec a : brief.agents()) {
            String at = "agents[" + a.ref() + "]";
            if (blank(a.name()) || a.name().length() > 80) {
                errors.add(new Issue(at + ".name", "A name of up to 80 characters is required."));
            }
            if (a.description() != null && a.description().length() > 400) {
                errors.add(
                        new Issue(
                                at + ".description",
                                "The description is limited to 400 characters."));
            }
            if (blank(a.systemPrompt()) || a.systemPrompt().length() > 8000) {
                errors.add(
                        new Issue(
                                at + ".systemPrompt",
                                "A system prompt of up to 8000 characters is required."));
            }
            if (a.reasoningDepth() == null || !DEPTHS.contains(a.reasoningDepth())) {
                errors.add(
                        new Issue(
                                at + ".reasoningDepth",
                                "reasoningDepth must be FAST, CAREFUL, RIGOROUS or PLANNED."));
            }
            if (a.tuningMode() != null && !TUNING.contains(a.tuningMode())) {
                errors.add(new Issue(at + ".tuningMode", "tuningMode must be SIMPLE or MANUAL."));
            }
            if (a.generation() != null
                    && a.generation().maxTokens() != null
                    && (a.generation().maxTokens() < 1 || a.generation().maxTokens() > 8192)) {
                errors.add(
                        new Issue(
                                at + ".generation.maxTokens",
                                "maxTokens must be between 1 and 8192."));
            }
            for (String entry : a.toolAllowList()) {
                validateToolAllowEntry(at, entry, refs, errors);
            }
            for (String k : a.knowledgeRefs()) {
                requireRef(at + ".knowledgeRefs", k, BriefRefs.KNOWLEDGE, refs, errors);
            }
            for (String p : a.procedureRefs()) {
                requireRef(at + ".procedureRefs", p, BriefRefs.PROCEDURE, refs, errors);
            }
            for (int i = 0; i < a.guardrails().size(); i++) {
                Brief.Guardrail g = a.guardrails().get(i);
                if (g == null || blank(g.kind())) {
                    errors.add(
                            new Issue(at + ".guardrails[" + i + "]", "A guardrail needs a kind."));
                }
            }
        }
    }

    private void validateToolAllowEntry(
            String at, String entry, Map<String, Character> refs, List<Issue> errors) {
        if (blank(entry)) {
            errors.add(new Issue(at + ".toolAllowList", "Empty tool entry."));
        } else if (entry.startsWith("mcp:")) {
            String mRef = entry.substring(4).split("/", 2)[0];
            requireRef(at + ".toolAllowList", mRef, BriefRefs.MCP, refs, errors);
        } else if (BriefRefs.isRef(entry)) {
            requireRef(at + ".toolAllowList", entry, BriefRefs.TOOL, refs, errors);
        } else if (!BUILTIN_TOOL_KEY.matcher(entry).matches()) {
            errors.add(
                    new Issue(
                            at + ".toolAllowList",
                            "'" + entry + "' is neither a ref nor a built-in tool key."));
        }
    }

    private void validateProcedures(Brief brief, List<Issue> errors) {
        if (brief.procedures().size() > 6) {
            errors.add(new Issue("procedures", "At most 6 procedures."));
        }
        for (Brief.ProcedureSpec p : brief.procedures()) {
            String at = "procedures[" + p.ref() + "]";
            if (blank(p.name())) {
                errors.add(new Issue(at + ".name", "A name is required."));
            }
            if (p.steps().isEmpty() || p.steps().stream().anyMatch(BriefValidator::blank)) {
                errors.add(new Issue(at + ".steps", "At least one non-empty step is required."));
            }
            if (p.source() != null && !PROCEDURE_SOURCES.contains(p.source())) {
                errors.add(new Issue(at + ".source", "source must be USER_GOAL or PLANNER."));
            }
        }
    }

    // ------------------------------------------------------------------ workflows

    /**
     * @return workflow ref to the set of node ids in its graph (for trust/narrative path checks).
     */
    private Map<String, Set<String>> validateWorkflows(
            Brief brief, Map<String, Character> refs, List<Issue> errors) {
        Map<String, Set<String>> nodeIds = new HashMap<>();
        if (brief.workflows().size() > 4) {
            errors.add(new Issue("workflows", "At most 4 workflows."));
        }
        Set<UUID> placeholderAgents = new HashSet<>();
        brief.agents().stream()
                .filter(a -> BriefRefs.isRef(a.ref()))
                .forEach(a -> placeholderAgents.add(BriefRefs.placeholder(a.ref())));

        for (WorkflowSpec w : brief.workflows()) {
            String at = "workflows[" + w.ref() + "]";
            if (blank(w.name()) || w.name().length() > 80) {
                errors.add(new Issue(at + ".name", "A name of up to 80 characters is required."));
            }
            if (w.sampleInput() != null && w.sampleInput().length() > 2000) {
                errors.add(
                        new Issue(
                                at + ".sampleInput", "sampleInput is limited to 2000 characters."));
            }
            for (int i = 0; i < w.triggers().size(); i++) {
                Brief.Trigger t = w.triggers().get(i);
                if (t == null || t.kind() == null || !TRIGGER_KINDS.contains(t.kind())) {
                    errors.add(
                            new Issue(
                                    at + ".triggers[" + i + "].kind",
                                    "Trigger kind must be MANUAL, SCHEDULE, WEBHOOK or CHANNEL."));
                    continue;
                }
                if ("SCHEDULE".equals(t.kind()) && blank(t.cron())) {
                    errors.add(
                            new Issue(
                                    at + ".triggers[" + i + "].cron",
                                    "A SCHEDULE trigger needs a cron expression."));
                }
                if ("CHANNEL".equals(t.kind())) {
                    requireRef(
                            at + ".triggers[" + i + "].channelRef",
                            t.channelRef(),
                            BriefRefs.CHANNEL,
                            refs,
                            errors);
                }
            }
            WorkflowGraphDto graph = w.graph();
            if (graph == null || graph.nodes().isEmpty()) {
                errors.add(new Issue(at + ".graph", "The workflow graph has no nodes."));
                continue;
            }
            Set<String> ids = new LinkedHashSet<>();
            graph.nodes().forEach(n -> ids.add(n.id()));
            if (BriefRefs.isRef(w.ref())) {
                nodeIds.put(w.ref(), ids);
            }
            validateGraph(w, graph, refs, placeholderAgents, errors);
        }
        return nodeIds;
    }

    /**
     * Ref-integrity of the ids inside node config, then the real orchestrator gate on a
     * placeholder-mapped copy.
     */
    private void validateGraph(
            WorkflowSpec w,
            WorkflowGraphDto graph,
            Map<String, Character> refs,
            Set<UUID> placeholderAgents,
            List<Issue> errors) {
        String at = "workflows[" + w.ref() + "].graph";
        WorkflowGraphDto copy;
        try {
            copy =
                    objectMapper.readValue(
                            objectMapper.writeValueAsBytes(graph), WorkflowGraphDto.class);
        } catch (Exception e) {
            errors.add(new Issue(at, "The graph could not be read as a workflow graph."));
            return;
        }
        int before = errors.size();
        for (WorkflowNodeDto node : copy.nodes()) {
            Map<String, Object> config = node.config();
            mapRef(at, node, config, "agentId", BriefRefs.AGENT, refs, errors);
            mapRef(at, node, config, "collectionId", BriefRefs.KNOWLEDGE, refs, errors);
        }
        if (errors.size() > before) {
            return; // unresolved refs would only produce noisy "invalid agentId" duplicates from
            // the gate
        }
        try {
            orchestratorService.validate(copy, null, placeholderAgents);
        } catch (WorkflowValidationException e) {
            for (String message : e.getErrors()) {
                errors.add(new Issue(at, message));
            }
        }
    }

    /**
     * If {@code config[key]} is set it must be a ref of {@code kind} that resolves; it is then
     * rewritten to its placeholder UUID in the (throwaway) copy so the real validator accepts it. A
     * raw UUID is rejected: Brief graphs reference refs, never real ids.
     */
    private void mapRef(
            String at,
            WorkflowNodeDto node,
            Map<String, Object> config,
            String key,
            char kind,
            Map<String, Character> refs,
            List<Issue> errors) {
        Object raw = config.get(key);
        if (raw == null || raw.toString().isBlank()) {
            return;
        }
        String value = raw.toString().strip();
        if (BriefRefs.looksLikeUuid(value)) {
            errors.add(
                    new Issue(
                            at,
                            "Node '"
                                    + node.label()
                                    + "' uses a real id in "
                                    + key
                                    + " - a brief references its own artifacts by ref (like a1), never by id."));
            return;
        }
        if (!BriefRefs.isRef(value, kind) || !refs.containsKey(value)) {
            errors.add(
                    new Issue(
                            at,
                            "Node '"
                                    + node.label()
                                    + "' has "
                                    + key
                                    + " '"
                                    + value
                                    + "', which is not a "
                                    + kindName(kind)
                                    + " ref declared in this brief."));
            return;
        }
        try {
            config.put(key, BriefRefs.placeholder(value).toString());
        } catch (UnsupportedOperationException e) {
            errors.add(new Issue(at, "Node '" + node.label() + "' has an unreadable config."));
        }
    }

    // ------------------------------------------------------------------ trust paths / narrative

    private void validateTrustPaths(
            Brief brief, Map<String, Set<String>> nodeIds, List<Issue> errors) {
        if (brief.trust() == null) {
            return;
        }
        checkPaths("trust.irreversible", brief.trust().irreversible(), nodeIds, errors);
        checkPaths("trust.humanMustDecide", brief.trust().humanMustDecide(), nodeIds, errors);
    }

    private void checkPaths(
            String section,
            List<String> paths,
            Map<String, Set<String>> nodeIds,
            List<Issue> errors) {
        for (String path : paths) {
            if (path == null || !BriefRefs.NODE_PATH.matcher(path).matches()) {
                errors.add(
                        new Issue(
                                section,
                                "'" + path + "' is not a workflow node path (like w1.n4)."));
                continue;
            }
            String[] parts = path.split("\\.", 2);
            Set<String> ids = nodeIds.get(parts[0]);
            if (ids == null) {
                errors.add(
                        new Issue(
                                section,
                                "'"
                                        + path
                                        + "' points at workflow "
                                        + parts[0]
                                        + ", which this brief does not declare."));
            } else if (!ids.contains(parts[1])) {
                errors.add(
                        new Issue(
                                section,
                                "'"
                                        + path
                                        + "' points at a node that does not exist in "
                                        + parts[0]
                                        + "."));
            }
        }
    }

    private void validateNarrative(
            Brief brief,
            Map<String, Character> refs,
            Map<String, Set<String>> nodeIds,
            List<Issue> errors) {
        Brief.Narrative n = brief.narrative();
        if (n == null) {
            errors.add(new Issue("narrative", "The narrative is required."));
            return;
        }
        if (blank(n.whyAgentic())) {
            errors.add(
                    new Issue(
                            "narrative.whyAgentic",
                            "Say why this needs agents rather than a form or a script."));
        }
        for (String ref : refs.keySet()) {
            if (!n.perNode().containsKey(ref)) {
                errors.add(
                        new Issue(
                                "narrative.perNode",
                                "No narrative entry for '"
                                        + ref
                                        + "' - every artifact needs one."));
            }
        }
        n.perNode()
                .forEach(
                        (key, value) -> {
                            boolean known = refs.containsKey(key) || isKnownNodePath(key, nodeIds);
                            if (!known) {
                                errors.add(
                                        new Issue(
                                                "narrative.perNode." + key,
                                                "'"
                                                        + key
                                                        + "' is neither an artifact ref nor a node of this brief."));
                            } else if (value == null
                                    || blank(value.why())
                                    || blank(value.tune())
                                    || blank(value.proof())) {
                                errors.add(
                                        new Issue(
                                                "narrative.perNode." + key,
                                                "Each entry needs why, tune and proof."));
                            }
                        });
    }

    private static boolean isKnownNodePath(String key, Map<String, Set<String>> nodeIds) {
        if (key == null || !BriefRefs.NODE_PATH.matcher(key).matches()) {
            return false;
        }
        String[] parts = key.split("\\.", 2);
        Set<String> ids = nodeIds.get(parts[0]);
        return ids != null && ids.contains(parts[1]);
    }

    // ------------------------------------------------------------------ caps / permissions

    private void checkCaps(Brief brief, List<Issue> errors) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId == null) {
            return;
        }
        Optional<Integer> maxAgents = tenantLimits.maxAgents(tenantId);
        if (maxAgents.isPresent() && !brief.agents().isEmpty()) {
            int total = agentService.listAll().size() + brief.agents().size();
            if (total > maxAgents.get()) {
                errors.add(
                        new Issue(
                                "limits",
                                "This brief adds "
                                        + brief.agents().size()
                                        + " agent(s) to your existing ones, "
                                        + "which is over your plan's limit of "
                                        + maxAgents.get()
                                        + " agents."));
            }
        }
        Optional<Integer> maxWorkflows = tenantLimits.maxWorkflows(tenantId);
        if (maxWorkflows.isPresent() && !brief.workflows().isEmpty()) {
            int total = workflowService.listAll().size() + brief.workflows().size();
            if (total > maxWorkflows.get()) {
                errors.add(
                        new Issue(
                                "limits",
                                "This brief adds "
                                        + brief.workflows().size()
                                        + " workflow(s) to your existing ones, "
                                        + "which is over your plan's limit of "
                                        + maxWorkflows.get()
                                        + " workflows."));
            }
        }
    }

    /**
     * Authorities apply needs beyond the base ones that the caller lacks; apply degrades on these.
     */
    private List<String> missingPermissions(Brief brief) {
        List<String> missing = new java.util.ArrayList<>();
        boolean needsIntegrations =
                !brief.tools().isEmpty()
                        || !brief.mcpServers().isEmpty()
                        || brief.secrets().stream().anyMatch(s -> "TENANT".equals(s.scope()));
        if (needsIntegrations && !holds(PERM_MANAGE_INTEGRATIONS)) {
            missing.add(PERM_MANAGE_INTEGRATIONS);
        }
        if (!brief.tools().isEmpty() && !holds(PERM_MANAGE_TOOLS)) {
            missing.add(PERM_MANAGE_TOOLS);
        }
        boolean routesChannel = brief.channels().stream().anyMatch(c -> !"WEB".equals(c.kind()));
        if (routesChannel && !holds(PERM_MANAGE_CHANNELS)) {
            missing.add(PERM_MANAGE_CHANNELS);
        }
        return missing;
    }

    private static boolean holds(String authority) {
        return TenantContext.current()
                .map(ctx -> ctx.hasRole(authority) || ctx.hasRole("ROLE_platform-admin"))
                .orElse(false);
    }

    // ------------------------------------------------------------------ helpers

    static void requireRef(
            String section,
            String ref,
            char kind,
            Map<String, Character> refs,
            List<Issue> errors) {
        if (!BriefRefs.isRef(ref, kind) || !refs.containsKey(ref)) {
            errors.add(
                    new Issue(
                            section,
                            "'"
                                    + ref
                                    + "' is not a "
                                    + kindName(kind)
                                    + " ref declared in this brief."));
        }
    }

    private static String kindName(char kind) {
        return switch (kind) {
            case BriefRefs.AGENT -> "agent";
            case BriefRefs.WORKFLOW -> "workflow";
            case BriefRefs.TOOL -> "tool";
            case BriefRefs.MCP -> "MCP server";
            case BriefRefs.KNOWLEDGE -> "knowledge";
            case BriefRefs.SECRET -> "secret";
            case BriefRefs.PROCEDURE -> "procedure";
            case BriefRefs.CHANNEL -> "channel";
            default -> "artifact";
        };
    }

    static boolean isHttpUrl(String url) {
        if (blank(url)) {
            return false;
        }
        try {
            URI uri = URI.create(url.strip());
            return ("http".equalsIgnoreCase(uri.getScheme())
                            || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
