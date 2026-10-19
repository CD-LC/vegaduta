package com.agenticai.core.workflow.portability;

import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.importer.AgentImportJson;
import com.agenticai.core.agents.importer.AgentImportText;
import com.agenticai.core.devices.MobileDeviceRepository;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.knowledge.KnowledgeCollectionNotFoundException;
import com.agenticai.core.knowledge.KnowledgeCollectionService;
import com.agenticai.core.omnichannel.contact.ChannelContactService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import com.agenticai.core.workflow.OrchestratorService;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowNotFoundException;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.WorkflowValidationException;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.expression.Diagnostic;
import com.agenticai.core.workflow.expression.ExpressionEngine;
import com.agenticai.core.workflow.expression.LintContext;
import com.agenticai.core.workflow.portability.PortabilityDtos.FidelityView;
import com.agenticai.core.workflow.portability.PortabilityDtos.ImportResponse;
import com.agenticai.core.workflow.portability.PortabilityDtos.PlaceholderView;
import com.agenticai.core.workflow.portability.PortabilityDtos.PreviewResponse;
import com.agenticai.core.workflow.portability.PortabilityDtos.UseView;
import com.agenticai.core.workflow.portability.PortabilityDtos.WorkflowView;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Workflow portability: export a saved workflow as the VegaDuta workflow DSL (JSON), and import a
 * workflow from a DSL file, an n8n export (JSON) or a Dify app DSL (YAML) as a DRAFT, with an
 * honest report of what carried over.
 *
 * <p>Same shape as the agent importer (preview first, then create): {@link #preview} converts and
 * validates and creates nothing; {@link #importWorkflow} does the same and then creates a DRAFT
 * through {@link WorkflowService#create}, the path {@code POST /api/workflows} uses, so the plan's
 * workflow cap, ownership stamping and version history all apply unchanged. The import never
 * activates the workflow, never enables a webhook, schedule or channel binding, and never runs
 * anything. Credentials are never imported: see {@link N8nImportMapper} and {@link
 * DifyImportMapper}.
 *
 * <p>The file is DATA only: nothing here fetches a URL, evaluates code or follows a reference
 * outside the payload, and imported text is never interpreted. Full payloads are never logged (a
 * source tag and counts only).
 *
 * <p>Throws only types {@code GlobalExceptionHandler} already maps: {@link
 * IllegalArgumentException} (400), {@link ResponseStatusException} (404 when the feature is off,
 * 413 when too large), and whatever {@code WorkflowService} throws (403 plan cap, 404 no access).
 */
@Service
public class WorkflowPortabilityService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowPortabilityService.class);

    /**
     * Room for the {@code {source, payload, overrides, mappings}} wrapper around a payload of
     * exactly the cap.
     */
    static final int ENVELOPE_SLACK_BYTES = 16 * 1024;

    private static final int MAX_MAPPINGS = 200;
    private static final Pattern SECRET_REF =
            Pattern.compile("\\{\\{\\s*secret\\s*[.:]\\s*[A-Za-z][A-Za-z0-9_]{0,63}\\s*}}");

    /**
     * A downloaded export: the suggested file name and the file's text (UTF-8 JSON, "\n" line
     * endings).
     */
    public record ExportedFile(String fileName, String json) {}

    private final WorkflowService workflowService;
    private final OrchestratorService orchestratorService;
    private final AgentService agentService;
    // Optional lookups for mapped ids; setter-injected so the constructor signature (and tests)
    // stay unchanged.
    private ObjectProvider<KnowledgeCollectionService> knowledge;
    private ObjectProvider<ChannelContactService> contacts;
    private ObjectProvider<MobileDeviceRepository> devices;
    private ObjectProvider<TenantLimits> tenantLimits;
    private final boolean enabled;
    private final long maxPayloadBytes;
    private final ObjectMapper mapper = JsonMappers.plain();
    private final ObjectMapper envelopeMapper;
    private final PortabilityDocumentParser parser;
    private final ExpressionEngine expressionEngine = new ExpressionEngine();

    public WorkflowPortabilityService(
            WorkflowService workflowService,
            OrchestratorService orchestratorService,
            AgentService agentService,
            @Value("${app.workflow.portability.enabled:false}") boolean enabled,
            @Value("${app.workflow.portability.max-payload-bytes:2097152}") long maxPayloadBytes) {
        this.workflowService = workflowService;
        this.orchestratorService = orchestratorService;
        this.agentService = agentService;
        this.enabled = enabled;
        this.maxPayloadBytes = Math.max(1024L, maxPayloadBytes);
        this.parser = new PortabilityDocumentParser(this.maxPayloadBytes);
        JsonFactory factory =
                JsonFactory.builder()
                        .streamReadConstraints(
                                StreamReadConstraints.builder()
                                        .maxNestingDepth(
                                                PortabilityDocumentParser.MAX_NESTING_DEPTH)
                                        .maxStringLength(
                                                (int)
                                                        Math.min(
                                                                Integer.MAX_VALUE - 8L,
                                                                this.maxPayloadBytes
                                                                        + ENVELOPE_SLACK_BYTES))
                                        .maxNumberLength(64)
                                        .build())
                        .build();
        this.envelopeMapper = JsonMappers.withFactory(factory);
    }

    @Autowired(required = false)
    void setMappingLookups(
            ObjectProvider<KnowledgeCollectionService> knowledge,
            ObjectProvider<ChannelContactService> contacts,
            ObjectProvider<MobileDeviceRepository> devices) {
        this.knowledge = knowledge;
        this.contacts = contacts;
        this.devices = devices;
    }

    /**
     * Optional for the same reason as the mapping lookups: keeps the constructor (and its tests)
     * unchanged.
     */
    @Autowired(required = false)
    void setTenantLimits(ObjectProvider<TenantLimits> tenantLimits) {
        this.tenantLimits = tenantLimits;
    }

    // ---- export
    // ------------------------------------------------------------------------------------------

    /** The DSL file for a saved workflow. VIEWER access (the same bar as reading the workflow). */
    public ExportedFile export(UUID workflowId) {
        requireEnabled();
        // Plan gate - export is the data-portability surface (a tenant can
        // walk its workflows out), a paid-tier entitlement
        // (plan_limits.workflow_export_allowed) enforced here at the service
        // boundary so the controller is not the only check. Import stays
        // free: it only creates a DRAFT under the normal workflow cap.
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TenantLimits limits = tenantLimits == null ? null : tenantLimits.getIfAvailable();
        if (tenantId != null && limits != null && !limits.workflowExportAllowed(tenantId)) {
            throw new TenantLimitExceededException(
                    "Your plan does not include workflow export - upgrade your plan to export workflows.");
        }
        WorkflowDefinition definition = workflowService.requireViewerAccess(workflowId);
        WorkflowDslExporter.Settings settings =
                new WorkflowDslExporter.Settings(
                        definition.getMaxStepsPerRun(),
                        definition.getStepTimeoutSeconds(),
                        definition.getMaxRetryAttempts(),
                        definition.getReasoningDepth() == null
                                ? null
                                : definition.getReasoningDepth().name(),
                        definition.getTuningMode() == null
                                ? null
                                : definition.getTuningMode().name(),
                        definition.isStepCachingEnabled());
        ObjectNode doc =
                WorkflowDslExporter.export(
                        mapper,
                        definition.getName(),
                        definition.getDescription(),
                        definition.getGraphJson(),
                        settings);
        return new ExportedFile(
                WorkflowDslExporter.fileName(definition.getName()),
                WorkflowDslExporter.toJson(mapper, doc));
    }

    // ---- import
    // ------------------------------------------------------------------------------------------

    /** Converts and validates; creates nothing for the tenant. */
    public PreviewResponse preview(String body) {
        Prepared p = prepare(body);
        log.info(
                "Workflow import preview source={} nodes={} placeholders={}",
                p.format.wire(),
                p.nodes.size(),
                p.wf.placeholders.entries().size());
        return new PreviewResponse(
                AgentImportText.inline(p.requestedSource, 40),
                p.format.wire(),
                new WorkflowView(
                        p.wf.name, p.wf.description == null ? "" : p.wf.description, p.graph),
                placeholderViews(p),
                p.issues,
                fidelity(p.wf.fidelity),
                p.wf.report.toReport());
    }

    /**
     * Converts, validates and creates a DRAFT workflow through the normal create path. Never
     * activates it.
     */
    public ImportResponse importWorkflow(String body) {
        Prepared p = prepare(body);
        WorkflowDefinition created = workflowService.create(p.wf.name, p.wf.description, p.graph);
        applySettings(created.getId(), p);
        p.wf.report.converted(
                "Draft workflow",
                "Created as a DRAFT owned by you. It is not active, has no webhook, "
                        + "schedule or channel binding, and has not run.");
        log.info(
                "Workflow import created workflow={} source={} nodes={} unresolved={}",
                created.getId(),
                p.format.wire(),
                p.nodes.size(),
                p.unresolved.size());
        return new ImportResponse(
                created.getId(),
                "DRAFT",
                p.issues,
                placeholderViews(p),
                fidelity(p.wf.fidelity),
                p.wf.report.toReport());
    }

    /**
     * Reads a request body without ever buffering more than the cap (plus the envelope allowance):
     * 413 as soon as the limit is crossed, so an oversized upload cannot fill memory before the
     * size check runs.
     */
    public String readBounded(InputStream in) throws IOException {
        long limit = maxEnvelopeBytes();
        byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8L, limit + 1));
        if (bytes.length > limit) {
            throw tooLarge();
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private long maxEnvelopeBytes() {
        // A text payload is JSON-escaped inside the envelope (newlines and quotes grow), so the
        // wire body may be
        // larger than the payload; the payload's own byte count is checked exactly in prepare().
        return maxPayloadBytes * 2 + ENVELOPE_SLACK_BYTES;
    }

    // ---- preparation
    // -------------------------------------------------------------------------------------

    private record Prepared(
            PortabilityFormat format,
            String requestedSource,
            PortableWorkflow wf,
            List<WorkflowNodeDto> nodes,
            Map<String, String> mappings,
            Set<String> unresolved,
            List<String> issues,
            WorkflowGraphDto graph) {}

    private Prepared prepare(String body) {
        requireEnabled();
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException(
                    "A JSON body with \"source\" and \"payload\" is required.");
        }
        if (utf8Length(body) > maxEnvelopeBytes()) {
            throw tooLarge();
        }
        JsonNode envelope;
        try {
            envelope = envelopeMapper.readTree(body);
        } catch (JsonProcessingException e) {
            // Not echoing the parser message: it can quote a fragment of the payload.
            throw new IllegalArgumentException(
                    "The request body is not valid JSON, or is nested more than "
                            + PortabilityDocumentParser.MAX_NESTING_DEPTH
                            + " levels deep.");
        }
        if (envelope == null || !envelope.isObject()) {
            throw new IllegalArgumentException("The request body must be a JSON object.");
        }
        JsonNode sourceNode = envelope.get("source");
        if (sourceNode != null && !sourceNode.isNull() && !sourceNode.isTextual()) {
            throw new IllegalArgumentException("\"source\" must be a string.");
        }
        String requestedSource =
                sourceNode == null || sourceNode.isNull() || sourceNode.asText().isBlank()
                        ? PortabilityFormat.AUTO
                        : sourceNode.asText();

        PortabilityDocumentParser.Parsed parsed =
                parser.parse(payloadText(envelope.get("payload")));
        PortabilityFormat format = resolveFormat(requestedSource, parsed.root());
        PortableWorkflow wf = convert(format, parsed.root());
        if (parsed.dropped() > 0) {
            wf.report.warn(
                    "The file contained "
                            + (parsed.dropped() + 1)
                            + " workflows; only the first was imported.");
        }
        JsonNode overrides = envelope.get("overrides");
        if (overrides != null && overrides.isObject()) {
            String overrideName = AgentImportJson.text(overrides, "name");
            if (overrideName != null) {
                wf.name = AgentImportText.sanitizeName(overrideName, wf.name);
            }
        }
        Map<String, String> mappings = readMappings(envelope.get("mappings"), wf);
        PlaceholderResolver.Result resolved =
                PlaceholderResolver.apply(wf.nodes, mappings, wf.placeholders);
        finishReport(wf, resolved, mappings);
        reviewExpressions(wf, resolved.nodes());
        WorkflowGraphDto graph = new WorkflowGraphDto(resolved.nodes(), wf.edges);
        List<String> issues = validate(graph, resolved);
        return new Prepared(
                format,
                requestedSource,
                wf,
                resolved.nodes(),
                mappings,
                resolved.unresolved(),
                issues,
                graph);
    }

    private String payloadText(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            throw new IllegalArgumentException("\"payload\" is required.");
        }
        String text;
        if (payload.isTextual()) {
            text = payload.asText();
        } else if (payload.isObject() || payload.isArray()) {
            text = payload.toString();
        } else {
            throw new IllegalArgumentException(
                    "\"payload\" must be the workflow file's text or a JSON object.");
        }
        if (utf8Length(text) > maxPayloadBytes) {
            throw tooLarge();
        }
        return text;
    }

    private PortabilityFormat resolveFormat(String requested, JsonNode root) {
        Optional<PortabilityFormat> detected = PortabilityFormat.detect(root);
        if (PortabilityFormat.isAuto(requested)) {
            return detected.orElseThrow(
                    () ->
                            new IllegalArgumentException(
                                    "Couldn't tell which format this file is. Choose a source: vegaduta, n8n or dify."));
        }
        PortabilityFormat chosen =
                PortabilityFormat.parse(requested)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Unknown source. Use one of: vegaduta, n8n, dify, auto."));
        if (detected.isPresent() && detected.get() != chosen) {
            throw new IllegalArgumentException(
                    "The file looks like a "
                            + detected.get().wire()
                            + " export but source was \""
                            + chosen.wire()
                            + "\". Use source \"auto\" or \""
                            + detected.get().wire()
                            + "\".");
        }
        return chosen;
    }

    private PortableWorkflow convert(PortabilityFormat format, JsonNode root) {
        try {
            return switch (format) {
                case VEGADUTA -> WorkflowDslImporter.map(mapper, root);
                case N8N -> N8nImportMapper.map(mapper, root);
                case DIFY -> DifyImportMapper.map(mapper, root);
            };
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            // A shape the mapper did not anticipate. Log the type and the source tag, never the
            // payload.
            log.warn(
                    "Workflow import could not convert a {} file: {}",
                    format.wire(),
                    e.getClass().getSimpleName());
            throw new IllegalArgumentException(
                    "The file could not be converted: it may be malformed or use a shape "
                            + "this importer does not understand.");
        }
    }

    // ---- mappings
    // ----------------------------------------------------------------------------------------

    private Map<String, String> readMappings(JsonNode node, PortableWorkflow wf) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException(
                    "\"mappings\" must be an object of placeholder key to id.");
        }
        if (node.size() > MAX_MAPPINGS) {
            throw new IllegalArgumentException("Too many mappings (limit " + MAX_MAPPINGS + ").");
        }
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String key = entry.getKey();
            PlaceholderRegistry.Entry placeholder = wf.placeholders.get(key);
            if (placeholder == null || !PlaceholderRegistry.isMappable(placeholder.kind())) {
                throw new IllegalArgumentException(
                        "'"
                                + AgentImportText.inline(key, 40)
                                + "' is not a placeholder that can be mapped in this file.");
            }
            String value = AgentImportJson.textOf(entry.getValue());
            if (value == null) {
                continue;
            }
            UUID id;
            try {
                id = UUID.fromString(value);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "The id mapped to '"
                                + AgentImportText.inline(key, 40)
                                + "' is not a valid id.");
            }
            if (!mappedTargetExists(placeholder.kind(), id)) {
                throw new IllegalArgumentException(
                        "The "
                                + kindName(placeholder.kind())
                                + " mapped to '"
                                + AgentImportText.inline(key, 40)
                                + "' does not exist in this workspace.");
            }
            out.put(key, id.toString());
        }
        return out;
    }

    /**
     * Existence (and, for a workflow, viewer access) of a mapped id, through the funnel each kind
     * already has. A kind whose service is not present in this deployment (the knowledge profile is
     * off) is not checked: the id could not be used there anyway.
     */
    private boolean mappedTargetExists(String kind, UUID id) {
        try {
            switch (kind) {
                case PlaceholderRegistry.AGENT -> agentService.requireExists(id);
                case PlaceholderRegistry.WORKFLOW -> workflowService.requireViewerAccess(id);
                case PlaceholderRegistry.KNOWLEDGE_BASE -> {
                    KnowledgeCollectionService kb =
                            knowledge == null ? null : knowledge.getIfAvailable();
                    if (kb != null) {
                        kb.requireExists(id);
                    }
                }
                case PlaceholderRegistry.CONTACT_GROUP -> {
                    ChannelContactService contacts =
                            this.contacts == null ? null : this.contacts.getIfAvailable();
                    if (contacts != null) {
                        contacts.requireGroup(id);
                    }
                }
                case PlaceholderRegistry.DEVICE -> {
                    MobileDeviceRepository devices =
                            this.devices == null ? null : this.devices.getIfAvailable();
                    if (devices != null
                            && devices.findById(id).filter(d -> !d.isRevoked()).isEmpty()) {
                        return false;
                    }
                }
                default -> {
                    // not a kind with an id to look up
                }
            }
            return true;
        } catch (AgentNotFoundException
                | WorkflowNotFoundException
                | ResourceAccessDeniedException
                | KnowledgeCollectionNotFoundException e) {
            return false;
        } catch (ResponseStatusException e) {
            if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return false;
            }
            throw e;
        }
    }

    // ---- report
    // ------------------------------------------------------------------------------------------

    private void finishReport(
            PortableWorkflow wf,
            PlaceholderResolver.Result resolved,
            Map<String, String> mappings) {
        for (PlaceholderRegistry.Entry entry : wf.placeholders.entries()) {
            String label = AgentImportText.inline(entry.label(), 80);
            String kind = entry.kind();
            if (PlaceholderRegistry.isMappable(kind)) {
                if (mappings.containsKey(entry.key())) {
                    wf.report.converted(
                            "Mapped " + kindName(kind) + " '" + label + "'",
                            "Pointed at the "
                                    + kindName(kind)
                                    + " you chose (placeholder "
                                    + entry.key()
                                    + ").");
                } else if (resolved.unresolved().contains(entry.key())) {
                    wf.report.needsManual(
                            "Choose a " + kindName(kind) + " for '" + label + "'",
                            "The "
                                    + kindName(kind)
                                    + " this step used lives in another workspace or platform and cannot "
                                    + "be carried over, so the field was left empty.",
                            "Pick one on the node (placeholder " + entry.key() + ").");
                }
            } else if (PlaceholderRegistry.DESTINATION.equals(kind)
                    && resolved.unresolved().contains(entry.key())) {
                wf.report.needsManual(
                        "Set the destination for '" + label + "'",
                        "Email addresses, chat ids and folders are not carried in a workflow file.",
                        "Fill in the destination on the node (placeholder " + entry.key() + ").");
            } else if (entry.key().startsWith("CREDENTIAL_")) {
                wf.report.needsManual(
                        "Credential marker " + entry.key(),
                        "A credential-looking value was replaced by this marker; it is never kept in a file.",
                        "Replace the marker with a {{secret.NAME}} vault reference (or the real value in the node, "
                                + "if you accept that) before activating.");
            } else if (PlaceholderRegistry.SECRET.equals(kind) && hasNodeUse(entry)) {
                wf.report.needsManual(
                        "Vault secret '" + label + "'",
                        "The workflow refers to {{secret."
                                + label
                                + "}}; the secret's value is not part of the file.",
                        "Create a vault secret with this name before running the workflow.");
            }
        }
    }

    private static boolean hasNodeUse(PlaceholderRegistry.Entry entry) {
        for (PlaceholderRegistry.Use use : entry.usedBy()) {
            if (!use.nodeId().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static String kindName(String kind) {
        return switch (kind) {
            case PlaceholderRegistry.AGENT -> "agent";
            case PlaceholderRegistry.KNOWLEDGE_BASE -> "knowledge base";
            case PlaceholderRegistry.WORKFLOW -> "workflow";
            case PlaceholderRegistry.CONTACT_GROUP -> "contact group";
            case PlaceholderRegistry.DEVICE -> "device";
            default -> "resource";
        };
    }

    /**
     * Runs the engine's own expression lint over every template in the imported nodes and reports
     * what will not parse.
     */
    private void reviewExpressions(PortableWorkflow wf, List<WorkflowNodeDto> nodes) {
        for (WorkflowNodeDto node : nodes) {
            List<String> problems = new ArrayList<>();
            lintValue(node.config(), problems, 0);
            if (!problems.isEmpty()) {
                wf.report.needsManual(
                        "Expression in node '" + AgentImportText.inline(node.label(), 80) + "'",
                        String.join(" ", problems.subList(0, Math.min(3, problems.size()))),
                        "Open the node and rewrite the expression in VegaDuta's expression language.");
            }
        }
    }

    private void lintValue(Object value, List<String> problems, int depth) {
        if (value == null || depth > 20 || problems.size() >= 3) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            boolean bare = "expression".equals(String.valueOf(map.get("mode")));
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (key.startsWith("imported") || "placeholder".equals(key)) {
                    continue;
                }
                if (bare
                        && "expression".equals(key)
                        && entry.getValue() instanceof String expression) {
                    lintTemplate("{{ " + expression + " }}", problems);
                } else {
                    lintValue(entry.getValue(), problems, depth + 1);
                }
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                lintValue(item, problems, depth + 1);
            }
        } else if (value instanceof String text && text.contains("{{")) {
            lintTemplate(text, problems);
        }
    }

    private void lintTemplate(String template, List<String> problems) {
        try {
            String cleaned = SECRET_REF.matcher(template).replaceAll("");
            for (Diagnostic d : expressionEngine.lint(cleaned, LintContext.empty())) {
                if (d.severity() == Diagnostic.Severity.ERROR && problems.size() < 3) {
                    problems.add(AgentImportText.inline(d.message(), 160));
                }
            }
        } catch (RuntimeException e) {
            problems.add("An expression could not be checked.");
        }
    }

    // ---- validation
    // --------------------------------------------------------------------------------------

    /**
     * The orchestrator's own structural validation (the gate Activate and Run use). A step whose
     * agent has not been mapped yet is validated against a stand-in agent id, so the issues listed
     * are the real structural ones (no trigger, a cycle, a loop without a body, a bad config) and
     * not one "no agent" line per unmapped step - the unmapped steps are listed as placeholders
     * instead.
     */
    private List<String> validate(WorkflowGraphDto graph, PlaceholderResolver.Result resolved) {
        Set<UUID> assumed = new HashSet<>();
        List<WorkflowNodeDto> checked = new ArrayList<>();
        for (WorkflowNodeDto node : graph.nodes()) {
            List<String> missing = resolved.missing().get(node.id());
            if (missing != null && missing.contains("agentId")) {
                UUID standIn =
                        UUID.nameUUIDFromBytes(
                                ("placeholder:" + node.id()).getBytes(StandardCharsets.UTF_8));
                assumed.add(standIn);
                Map<String, Object> data = new LinkedHashMap<>(node.data());
                Map<String, Object> config = new LinkedHashMap<>(node.config());
                config.put("agentId", standIn.toString());
                data.put("config", config);
                checked.add(new WorkflowNodeDto(node.id(), node.type(), node.position(), data));
            } else {
                checked.add(node);
            }
        }
        List<String> issues = new ArrayList<>();
        try {
            orchestratorService.validate(
                    new WorkflowGraphDto(checked, graph.edges()), null, assumed);
        } catch (WorkflowValidationException e) {
            issues.addAll(e.getErrors());
        } catch (RuntimeException e) {
            log.warn(
                    "Workflow import validation could not complete: {}",
                    e.getClass().getSimpleName());
            issues.add(
                    "Validation could not be completed just now; validate the draft from the canvas.");
        }
        return issues;
    }

    private void applySettings(UUID workflowId, Prepared p) {
        if (p.wf.settings.isEmpty() || workflowId == null) {
            return;
        }
        try {
            WorkflowDefinition current = workflowService.requireViewerAccess(workflowId);
            int steps = intSetting(p.wf.settings, "maxStepsPerRun", current.getMaxStepsPerRun());
            int timeout =
                    intSetting(
                            p.wf.settings, "stepTimeoutSeconds", current.getStepTimeoutSeconds());
            int retries =
                    intSetting(p.wf.settings, "maxRetryAttempts", current.getMaxRetryAttempts());
            workflowService.setExecutionLimits(workflowId, steps, timeout, retries);
            p.wf.report.converted(
                    "Execution limits",
                    "Step limit "
                            + steps
                            + ", step timeout "
                            + timeout
                            + "s and "
                            + retries
                            + " retries were applied from the file.");
        } catch (RuntimeException e) {
            log.warn(
                    "Workflow import could not apply execution limits to {}: {}",
                    workflowId,
                    e.getClass().getSimpleName());
            p.wf.report.warn(
                    "The execution limits in the file could not be applied; the workflow keeps the defaults.");
        }
    }

    private static int intSetting(Map<String, Object> settings, String key, int fallback) {
        Object value = settings.get(key);
        return value instanceof Number n ? n.intValue() : fallback;
    }

    // ---- views
    // -------------------------------------------------------------------------------------------

    private List<PlaceholderView> placeholderViews(Prepared p) {
        List<PlaceholderView> out = new ArrayList<>();
        for (PlaceholderRegistry.Entry entry : p.wf.placeholders.entries()) {
            boolean mappable = PlaceholderRegistry.isMappable(entry.kind());
            String mappedTo = p.mappings.get(entry.key());
            List<UseView> uses = new ArrayList<>();
            for (PlaceholderRegistry.Use use : entry.usedBy()) {
                uses.add(new UseView(use.nodeId(), use.field()));
            }
            out.add(
                    new PlaceholderView(
                            entry.key(),
                            entry.kind(),
                            AgentImportText.inline(entry.label(), 120),
                            mappable,
                            mappedTo != null,
                            mappedTo == null ? "" : mappedTo,
                            uses));
        }
        return out;
    }

    private static FidelityView fidelity(PortableWorkflow.Fidelity f) {
        int denominator = Math.max(1, f.sourceNodes - f.skipped);
        String level;
        if ((f.exact + f.approximate) * 2 < denominator) {
            level = "low";
        } else if (f.placeholders == 0
                && f.connectionsDropped == 0
                && f.approximate * 2 <= denominator) {
            level = "high";
        } else {
            level = "partial";
        }
        return new FidelityView(
                level,
                f.sourceNodes,
                f.exact,
                f.approximate,
                f.placeholders,
                f.skipped,
                f.sourceConnections,
                f.connectionsKept,
                f.connectionsDropped);
    }

    // ---- misc
    // --------------------------------------------------------------------------------------------

    /** 404 when the feature flag is off; the controller calls it before reading a request body. */
    public void requireEnabled() {
        if (!enabled) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Workflow import and export is not enabled.");
        }
    }

    private ResponseStatusException tooLarge() {
        return new ResponseStatusException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "The file is larger than the import limit of " + maxPayloadBytes + " bytes.");
    }

    private static long utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }
}
