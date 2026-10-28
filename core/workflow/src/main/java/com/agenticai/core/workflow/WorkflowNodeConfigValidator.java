package com.agenticai.core.workflow;

import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerChannel;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The per-node half of {@link OrchestratorService#validate}: each node type's required config, checked at save,
 * activate and every run. Graph-shape rules (edges, cycles, loop/fan-out arity) stay in the orchestrator.
 */
final class WorkflowNodeConfigValidator {

    /** The deployment switches that decide whether a config is acceptable, read when validation starts. */
    record Flags(boolean whatsAppTemplatesEnabled, boolean formTriggerEnabled, boolean waitNodeEnabled,
                 long waitMaxDurationDays) {
    }

    private final AgentService agentService;
    private final ObjectMapper objectMapper;

    WorkflowNodeConfigValidator(AgentService agentService, ObjectMapper objectMapper) {
        this.agentService = agentService;
        this.objectMapper = objectMapper;
    }

    private void requireAgent(UUID agentId, Set<UUID> assumedAgentIds) {
        if (!assumedAgentIds.contains(agentId)) {
            agentService.requireExists(agentId);
        }
    }

    /** Adds one message per problem in {@code node}'s own config to {@code errors}. */
    void validate(WorkflowNodeDto node, List<String> errors, UUID ownWorkflowId, Set<UUID> assumedAgentIds,
                  Flags flags) {
        String type = node.type() == null ? "" : node.type().toLowerCase(Locale.ROOT);
        Map<String, Object> config = node.config();
        switch (type) {
            case "agent" -> {
                Object agentId = config.get("agentId");
                if (agentId == null || agentId.toString().isBlank()) {
                    errors.add("Agent node '" + node.label() + "' has no agentId configured.");
                } else if (!isValidUuid(agentId.toString())) {
                    errors.add("Agent node '" + node.label() + "' has an invalid agentId.");
                } else {
                    try {
                        requireAgent(UUID.fromString(agentId.toString()), assumedAgentIds);
                    } catch (AgentNotFoundException e) {
                        errors.add("Agent node '" + node.label() + "' references an agent that no longer exists.");
                    }
                }
            }
            case "knowledge" -> {
                Object collectionId = config.get("collectionId");
                if (collectionId != null && !collectionId.toString().isBlank() && !isValidUuid(collectionId.toString())) {
                    errors.add("Knowledge node '" + node.label() + "' has an invalid collectionId.");
                }
            }
            case "condition" -> {
                if ("expression".equalsIgnoreCase(String.valueOf(config.get("mode")))) {
                    // Expression mode never reads 'contains'; requiring it would make the mode unsavable.
                    Object expression = config.get("expression");
                    if (expression == null || expression.toString().isBlank()) {
                        errors.add("Condition node '" + node.label()
                            + "' has no condition configured (expected an 'expression' such as $json.total > 1000).");
                    }
                } else {
                    Object contains = config.get("contains");
                    if (contains == null || contains.toString().isBlank()) {
                        errors.add("Condition node '" + node.label()
                            + "' has no condition configured (expected a 'contains' value to match against).");
                    }
                }
            }
            case "output" -> {
                // Bug fix while building #392 (AI workflow generator): this
                // allow-list had never been updated past the original two
                // channels even though #152-155 shipped four more real
                // destinations (file/email/telegram/whatsapp) - meaning any
                // workflow using them failed structural validation on every
                // Validate/Activate/Run (WorkflowExecutionEngine#execute
                // calls validate() unconditionally at the top of every run),
                // regardless of how correctly the destination itself was
                // configured. "google_drive" (#156) shipped later and was
                // never added here either - same bug, same symptom: any
                // workflow with a Google Drive output node failed structural
                // validation on every Validate/Activate/Run even though
                // WorkflowNodeExecutor#executeOutputNode's "google_drive"
                // case and NodeConfigDrawer's channel dropdown both already
                // fully supported it. Now matches the full set
                // WorkflowExecutionEngine.executeOutputNode actually
                // implements, plus NodeConfigDrawer.js's channel dropdown -
                // "log" is still the safe default for a channel that was
                // never explicitly set.
                Object channel = config.get("channel");
                Set<String> supportedChannels = Set.of("log", "event", "file", "email", "telegram", "teams", "whatsapp", "google_drive");
                if (channel != null && !supportedChannels.contains(channel.toString())) {
                    errors.add("Output node '" + node.label() + "' has an unrecognized channel '" + channel + "'.");
                }
                // WhatsApp approved template (nlflow W5): optional, but a half-filled one would only
                // fail at send time - outside the 24-hour window that is the whole message lost.
                // Only checked while templates are enabled: with the flag off the config is never
                // read by a send, so a leftover half-filled template must not block the workflow.
                Object template = config.get("whatsappTemplate");
                if (flags.whatsAppTemplatesEnabled() && "whatsapp".equals(String.valueOf(channel)) && template != null) {
                    if (!(template instanceof Map<?, ?> templateMap)) {
                        errors.add("Output node '" + node.label() + "' has a WhatsApp template that is not a {name, language} object.");
                    } else if (!templateMap.isEmpty()
                        && (isBlank(templateMap.get("name")) || isBlank(templateMap.get("language")))) {
                        errors.add("Output node '" + node.label() + "' has a WhatsApp template without a name or a language.");
                    }
                }
            }
            case "tool" -> {
                Object agentId = config.get("agentId");
                Object toolName = config.get("toolName");
                if (agentId == null || agentId.toString().isBlank()) {
                    errors.add("Tool node '" + node.label() + "' has no agent selected - pick the agent whose tool catalog this call should use.");
                } else if (!isValidUuid(agentId.toString())) {
                    errors.add("Tool node '" + node.label() + "' has an invalid agentId.");
                } else {
                    try {
                        requireAgent(UUID.fromString(agentId.toString()), assumedAgentIds);
                    } catch (AgentNotFoundException e) {
                        errors.add("Tool node '" + node.label() + "' references an agent that no longer exists.");
                    }
                }
                if (toolName == null || toolName.toString().isBlank()) {
                    errors.add("Tool node '" + node.label() + "' has no tool selected.");
                }
            }
            case "code" -> {
                Object operation = config.get("operation");
                Set<String> supportedOps = Set.of(
                    "extract", "regex_replace", "template", "uppercase", "lowercase", "json_parse", "json_stringify");
                if (operation == null || operation.toString().isBlank()) {
                    errors.add("Transform node '" + node.label() + "' has no operation selected.");
                } else if (!supportedOps.contains(operation.toString())) {
                    errors.add("Transform node '" + node.label() + "' has an unrecognized operation '" + operation + "'.");
                }
            }
            case "http" -> {
                // HTTP Request node - only the structurally-required config is
                // checked here (a URL, a known method, a known body type); the
                // URL itself is templated ({{input}}) and SSRF-validated at run
                // time by HttpRequestExecutor, so it cannot be judged at save time.
                Object url = config.get("url");
                if (url == null || url.toString().isBlank()) {
                    errors.add("HTTP Request node '" + node.label() + "' has no URL configured.");
                }
                Object method = config.get("method");
                Set<String> supportedMethods = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
                if (method != null && !method.toString().isBlank()
                    && !supportedMethods.contains(method.toString().strip().toUpperCase(Locale.ROOT))) {
                    errors.add("HTTP Request node '" + node.label() + "' has an unsupported method '" + method + "'.");
                }
                Object bodyType = config.get("bodyType");
                Set<String> supportedBodyTypes = Set.of("none", "json", "text", "form");
                if (bodyType != null && !bodyType.toString().isBlank()
                    && !supportedBodyTypes.contains(bodyType.toString().strip().toLowerCase(Locale.ROOT))) {
                    errors.add("HTTP Request node '" + node.label() + "' has an unrecognized body type '" + bodyType + "'.");
                }
            }
            case "media" -> {
                // Media node - only the operation is checkable at save time.
                // The request body arrives as this node's INPUT at run time and
                // is validated by the sidecar's own Pydantic models, which name
                // the offending field; there is nothing to judge here.
                Object operation = config.get("operation");
                Set<String> supportedOperations = Set.of("tts", "captions", "render", "render_status");
                if (operation == null || operation.toString().isBlank()) {
                    errors.add("Media node '" + node.label() + "' has no operation selected.");
                } else if (!supportedOperations.contains(
                    operation.toString().strip().toLowerCase(Locale.ROOT))) {
                    errors.add("Media node '" + node.label() + "' has an unrecognized operation '"
                        + operation + "'.");
                }
            }
            case "supervisor" -> {
                // Master Roadmap 2026 item #12 (§E) - same "agentId must be a
                // real, existing agent" check as "agent"/"tool" above, plus
                // the one piece of required config unique to this node type:
                // at least one named route to choose among (structurally
                // identical in spirit to "condition"'s required 'contains'
                // value - a Supervisor node with nothing to route to can
                // never do its one job).
                Object agentId = config.get("agentId");
                if (agentId == null || agentId.toString().isBlank()) {
                    errors.add("Supervisor node '" + node.label() + "' has no agent selected to make the routing decision.");
                } else if (!isValidUuid(agentId.toString())) {
                    errors.add("Supervisor node '" + node.label() + "' has an invalid agentId.");
                } else {
                    try {
                        requireAgent(UUID.fromString(agentId.toString()), assumedAgentIds);
                    } catch (AgentNotFoundException e) {
                        errors.add("Supervisor node '" + node.label() + "' references an agent that no longer exists.");
                    }
                }
                Object routes = config.get("routes");
                // Mirrors WorkflowNodeExecutor#resolveRoutes' own parsing exactly (same
                // tolerant-of-a-raw-JSON-string-or-already-parsed-List handling, same
                // "an entry needs a non-blank 'handle' field or it doesn't count")
                // rather than only checking the raw config value isn't empty. Previously
                // a non-empty-but-wrong-shaped routes array (e.g. every entry using
                // "value" instead of "handle" - plausible output from
                // WorkflowGraphGeneratorService) passed this check, then resolved to
                // zero routes at runtime, silently skipping the node on every run with
                // no validation error ever shown to the author.
                if (countUsableRoutes(routes) == 0) {
                    errors.add("Supervisor node '" + node.label() + "' has no usable routes configured - add at least one route with a non-blank 'handle'.");
                }
            }
            case "workflow" -> {
                // #846 workflow-triggers-workflow. Structural half of this
                // feature's two-layer recursion guard - see WorkflowNodeType's
                // javadoc: this catches the simple, common case (a node
                // pointing at its own containing workflow) for free at
                // save/activate time; WorkflowNodeExecutor's runtime
                // thread-local call-stack check is what catches the harder
                // case (an indirect cycle across separate workflow
                // definitions), which no single graph's own validation can
                // see.
                Object targetWorkflowId = config.get("targetWorkflowId");
                if (targetWorkflowId == null || targetWorkflowId.toString().isBlank()) {
                    errors.add("Workflow node '" + node.label() + "' has no target workflow selected.");
                } else if (!isValidUuid(targetWorkflowId.toString())) {
                    errors.add("Workflow node '" + node.label() + "' has an invalid target workflow id.");
                } else if (ownWorkflowId != null && targetWorkflowId.toString().equalsIgnoreCase(ownWorkflowId.toString())) {
                    errors.add("Workflow node '" + node.label()
                        + "' targets this same workflow - that would trigger it forever. Point it at a different workflow.");
                }
            }
            case "human_input" -> {
                // Human Input (Tier-3) - `fields` is optional (a field-less
                // node is a legal pure-acknowledge pause) but when present
                // must be a list of {key, ...} objects: a malformed schema
                // would only ever surface as a form the console can't
                // render mid-run, the worst possible time to find out.
                Object fields = config.get("fields");
                if (fields != null && !(fields instanceof List<?>)) {
                    errors.add("Human Input node '" + node.label()
                        + "' has a 'fields' config that is not a list of field objects.");
                } else if (fields instanceof List<?> list) {
                    for (Object entry : list) {
                        if (!(entry instanceof Map<?, ?> field)
                            || field.get("key") == null || field.get("key").toString().isBlank()) {
                            errors.add("Human Input node '" + node.label()
                                + "' has a field entry with no 'key' - every field needs one to collect its value.");
                            break;
                        }
                    }
                }
            }
            case "voice_menu" -> {
                // Voice Menu (V308, call-logic/DTMF) - `prompt` is the one
                // required field (an IVR menu that says nothing has no way
                // to tell a caller what to press); `branches`, like Human
                // Input's `fields`, is optional structurally (a menu can
                // legitimately gather digits and rely only on the implicit
                // "no_match"/"timeout" edges) but when present must be a
                // list of {digit, label} objects, checked here rather than
                // only at the mid-call moment {@code
                // WorkflowVoiceMenuRequestService} would otherwise discover
                // a malformed one.
                Object prompt = config.get("prompt");
                if (prompt == null || prompt.toString().isBlank()) {
                    errors.add("Voice Menu node '" + node.label() + "' has no prompt to speak.");
                }
                Object branches = config.get("branches");
                if (branches != null && !(branches instanceof List<?>)) {
                    errors.add("Voice Menu node '" + node.label()
                        + "' has a 'branches' config that is not a list of {digit, label} objects.");
                } else if (branches instanceof List<?> list) {
                    for (Object entry : list) {
                        if (!(entry instanceof Map<?, ?> branch)
                            || branch.get("digit") == null || branch.get("digit").toString().isBlank()
                            || branch.get("label") == null || branch.get("label").toString().isBlank()) {
                            errors.add("Voice Menu node '" + node.label()
                                + "' has a branch entry missing a 'digit' or 'label'.");
                            break;
                        }
                    }
                }
            }
            case "bulk_email" -> {
                // Bulk Email (V335) - a subject and a body are the only hard
                // requirements; the recipient source is validated loosely
                // (an "items" source resolves at run time from upstream data).
                Object subject = config.get("subject");
                Object body = config.get("body");
                if (subject == null || subject.toString().isBlank()) {
                    errors.add("Bulk Email node '" + node.label() + "' has no subject.");
                }
                if (body == null || body.toString().isBlank()) {
                    errors.add("Bulk Email node '" + node.label() + "' has no body.");
                }
                Object source = config.get("recipientsSource");
                if (source != null && !Set.of("items", "group", "static").contains(source.toString())) {
                    errors.add("Bulk Email node '" + node.label()
                        + "' has an unknown recipientsSource (expected items, group or static).");
                }
                if ("group".equals(source)
                    && (config.get("groupId") == null || !isValidUuid(config.get("groupId").toString()))) {
                    errors.add("Bulk Email node '" + node.label() + "' needs a valid contact groupId.");
                }
                if ("static".equals(source)
                    && (config.get("staticRecipients") == null || config.get("staticRecipients").toString().isBlank())) {
                    errors.add("Bulk Email node '" + node.label() + "' has no static recipients.");
                }
            }
            case "question_classifier", "parameter_extractor", "variable_aggregator", "document_extractor" ->
                errors.addAll(WorkflowAiNodeHandler.validateConfig(node, objectMapper));
            case "trigger" -> {
                // Per-node workflow triggers - previously trigger was a
                // default-case no-op (a workflow had exactly one, implicitly
                // WEBHOOK unless WorkflowService#setTriggerChannel said
                // otherwise, off-graph). Now that each trigger node carries
                // its OWN channel binding in-graph, that binding has to be
                // validated the same way any other node's required config is.
                // The cross-node "no two non-WEBHOOK trigger nodes share a
                // channel" check lives in OrchestratorService#validate itself
                // (needs the full node list, not just this one node) - see the
                // call site right after its node loop.
                Object channel = config.get("channel");
                if (channel == null || channel.toString().isBlank()) {
                    // 2026-08-09 deadlock fix: this used to hard-require a
                    // channel to activate, but WHATSAPP/SMS/CALL/etc. can only
                    // be bound via setTriggerChannel AFTER the workflow is
                    // ACTIVE (see that method's javadoc) - so a trigger node
                    // with no channel yet could never be activated, and could
                    // never get a channel without activating first. A missing
                    // channel is the same implicit-WEBHOOK default this class
                    // used before per-node trigger channels existed (see this
                    // switch case's own comment above) - accept it here too,
                    // exactly like WEBHOOK itself needs no pre-activation
                    // setup. The user can freely rebind to any other channel
                    // once ACTIVE, same as before this fix.
                } else {
                    try {
                        WorkflowTriggerChannel.valueOf(channel.toString().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        errors.add("Trigger node '" + node.label() + "' has an invalid channel value.");
                    }
                }
                // Hosted-form trigger (triggerType "form"): the form schema is validated here so a bad one fails
                // at save, not when a stranger opens the public page.
                if (WorkflowFormSchema.isFormTrigger(config)) {
                    if (!flags.formTriggerEnabled()) {
                        errors.add("Trigger node '" + node.label() + "' is a hosted form, but form triggers are not "
                            + "enabled on this deployment (app.workflow.form-trigger.enabled).");
                    } else {
                        WorkflowFormSchema.validateConfig(config, node.label(), errors);
                    }
                }
            }
            case "wait" -> {
                if (!flags.waitNodeEnabled()) {
                    errors.add("Wait node '" + node.label() + "' is not available: Wait nodes are not enabled on "
                        + "this deployment (app.workflow.wait-node.enabled).");
                } else {
                    WorkflowWaitService.validateConfig(
                        config, node.label(), Duration.ofDays(Math.max(1, flags.waitMaxDurationDays())), errors);
                }
            }
            case "sub_flow" -> {
                // Sub-Flow (Tier-3): the only required config is a `body`
                // graph carrying at least one node - every deeper check
                // (dangling body edges, ambiguous/no entry node) is a
                // run-time WorkflowValidationException from the engine's
                // readSubFlowBody/resolveSubFlowEntry, which this cheap
                // shape check front-runs so a malformed body fails at save,
                // not mid-run. Unlike Loop/Fan-out there is no "exactly one
                // outgoing edge" rule: the body lives in config, so the
                // node's outgoing edges are ordinary continuations (0 = the
                // run ends with the merged array as its output).
                Object body = config.get("body");
                if (!(body instanceof Map<?, ?> bodyMap)
                    || !(bodyMap.get("nodes") instanceof List<?> nodes) || nodes.isEmpty()) {
                    errors.add("Sub-Flow node '" + node.label()
                        + "' has no 'body' graph with at least one node - sub-flows iterate an embedded sub-graph, not a single downstream node.");
                }
            }
            case "set", "filter", "sort", "aggregate", "limit", "dedupe", "switch", "merge" ->
                WorkflowDataNodeValidator.validate(node, errors);
            default -> {
                // approval/loop/parallel/fan_out: no required node-level
                // config today (loop's and fan_out's real structural
                // requirement - exactly one outgoing edge - is checked above
                // against the graph's adjacency, not here, since it isn't a
                // config field). fan_out's numeric knobs (maxItems/
                // maxConcurrency) deliberately get no case either: both
                // engines clamp them into range at run time exactly like
                // loop's maxIterations, so a wrong value degrades safely
                // instead of blocking a save - same reasoning as loop.
            }
        }
    }

    private static boolean isBlank(Object value) {
        return value == null || value.toString().isBlank();
    }

    private boolean isValidUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Counts routes that {@link WorkflowNodeExecutor#resolveRoutes} would actually resolve
     * at runtime, so save-time validation rejects the same malformed shapes runtime would
     * otherwise silently drop. Deliberately duplicates that method's tolerant parsing
     * (raw JSON string OR already-deserialized List, entries missing a non-blank
     * {@code handle} don't count) rather than sharing code across modules/packages for a
     * ~15-line parse - if the two ever drift, that's a real bug worth a visible test
     * failure, not a shared-utility refactor for its own sake.
     */
    private int countUsableRoutes(Object routes) {
        if (routes == null) {
            return 0;
        }
        try {
            JsonNode arrayNode = routes instanceof String s
                ? objectMapper.readTree(s.isBlank() ? "[]" : s)
                : objectMapper.valueToTree(routes);
            if (!arrayNode.isArray()) {
                return 0;
            }
            int usable = 0;
            for (JsonNode routeNode : arrayNode) {
                String handle = routeNode.path("handle").isMissingNode() ? null : routeNode.path("handle").asText(null);
                if (handle != null && !handle.isBlank()) {
                    usable++;
                }
            }
            return usable;
        } catch (Exception e) {
            // Same fail-closed-to-zero posture as a missing/empty routes value above -
            // an unparseable routes config is exactly as unusable as no routes at all.
            return 0;
        }
    }

}
