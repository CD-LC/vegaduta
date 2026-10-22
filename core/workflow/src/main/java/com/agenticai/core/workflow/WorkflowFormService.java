package com.agenticai.core.workflow;

import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNode;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.share.WorkflowShareService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The PUBLIC hosted form of a Trigger node whose config says {@code triggerType: "form"} (see
 * {@link WorkflowFormSchema}). Deliberately NO new auth mechanism: the form URL carries the SAME
 * per-node opaque webhook token the shared-secret webhook trigger uses, resolved the same way
 * ({@link WebhookTokenHasher} SHA-256 -> {@code TenantResolutionService} -> {@code
 * WorkflowTriggerNode}), and honours the same switches (the node's webhook must be ENABLED, in
 * {@link WebhookAuthMode#SHARED_SECRET} mode, on an ACTIVE workflow). A submission is validated
 * server-side against the schema in the graph that actually runs (the PRODUCTION snapshot), then
 * starts the run through {@link WorkflowTriggerPort#triggerAsync} - fire-and-forget, so a slow
 * workflow never holds a stranger's browser open - with the validated values as the trigger input
 * (a JSON object keyed by field key).
 *
 * <p>Abuse controls: a hidden honeypot field (a bot that fills it gets a normal-looking success and
 * no run), a per-token and a per-IP fixed-window limit ({@link WorkflowPublicRateLimiter}), a
 * serialized-size cap, field count/length caps from the schema, and a uniform 404 for every "this
 * form does not exist" cause (unknown token, disabled webhook, inactive workflow, not a form node)
 * so the endpoint reveals nothing. The whole surface is off unless {@code
 * app.workflow.form-trigger.enabled}.
 *
 * <p>Known limitation, accepted on purpose: a submission is NOT de-duplicated. Two identical POSTs
 * (a client retry, a second tab) start two runs. The page disables its button while sending, and
 * the per-token / per-IP limits cap the damage. A server-side dedup on token + values ({@code
 * InboundMessageDedupService}) was left out because its window is a fixed hour and would silently
 * swallow a legitimate repeat of the same answers (a visitor filing the same issue twice) while
 * still answering "success"; make the downstream workflow idempotent where that matters.
 */
@Service
public class WorkflowFormService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowFormService.class);

    private static final Pattern TOKEN_SHAPE = Pattern.compile("[A-Za-z0-9_-]{16,128}");
    private static final String TOKEN_RATE_PREFIX = "agentic:workflow-form-token-rate:";
    private static final String IP_RATE_PREFIX = "agentic:workflow-form-ip-rate:";
    private static final String VIEW_RATE_PREFIX = "agentic:workflow-form-view-rate:";
    private static final String REQUESTED_BY_PREFIX = "workflow-form:";
    private static final String CONTEXT_PREFIX = "system:workflow-form:";
    private static final String BODY_VALUES_KEY = "values";

    /** How a submission ended. {@code OK} also covers a honeypot hit (the bot is told nothing). */
    public enum Status {
        OK,
        NOT_FOUND,
        INVALID,
        RATE_LIMITED,
        TOO_LARGE
    }

    /** One field as the public page needs it - nothing internal. */
    public record FieldView(
            String key,
            String label,
            String type,
            boolean required,
            String placeholder,
            List<String> options,
            Integer maxLength,
            Double min,
            Double max) {}

    /**
     * The public form definition; {@code honeypotField} is the name of the hidden field the page
     * must render empty.
     */
    public record FormView(
            String title,
            String description,
            String submitLabel,
            List<FieldView> fields,
            String honeypotField) {}

    public record Submission(
            Status status, Map<String, String> errors, String message, String redirectUrl) {
        static Submission of(Status status) {
            return new Submission(status, Map.of(), null, null);
        }
    }

    private record Resolved(
            WorkflowDefinition definition,
            WorkflowTriggerNode triggerNode,
            WorkflowFormSchema.Form form) {}

    private final TenantResolutionService tenantResolutionService;
    private final WorkflowTriggerNodeService triggerNodeService;
    private final WorkflowDefinitionRepository definitionRepository;
    private final WorkflowService workflowService;
    private final WorkflowTriggerPort triggerPort;
    private final WorkflowPublicRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final long maxPerMinutePerToken;
    private final long maxPerMinutePerIp;
    private final int maxPayloadChars;

    public WorkflowFormService(
            TenantResolutionService tenantResolutionService,
            WorkflowTriggerNodeService triggerNodeService,
            WorkflowDefinitionRepository definitionRepository,
            WorkflowService workflowService,
            WorkflowTriggerPort triggerPort,
            WorkflowPublicRateLimiter rateLimiter,
            ObjectMapper objectMapper,
            @Value("${app.workflow.form-trigger.enabled:false}") boolean enabled,
            @Value("${app.workflow.form-trigger.max-submissions-per-minute-per-token:30}")
                    long maxPerMinutePerToken,
            @Value("${app.workflow.form-trigger.max-submissions-per-minute-per-ip:8}")
                    long maxPerMinutePerIp,
            @Value("${app.workflow.form-trigger.max-payload-chars:20000}") int maxPayloadChars) {
        this.tenantResolutionService = tenantResolutionService;
        this.triggerNodeService = triggerNodeService;
        this.definitionRepository = definitionRepository;
        this.workflowService = workflowService;
        this.triggerPort = triggerPort;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.maxPerMinutePerToken = maxPerMinutePerToken;
        this.maxPerMinutePerIp = maxPerMinutePerIp;
        this.maxPayloadChars = maxPayloadChars;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * True when {@code nodeId} of {@code definition}'s PRODUCTION graph is a hosted-form trigger -
     * the webhook controller uses it to refuse the raw webhook URL for such a node (the form is its
     * only front door, so schema validation cannot be bypassed). Always false while the feature is
     * off.
     */
    public boolean isFormTriggerNode(WorkflowDefinition definition, String nodeId) {
        if (!enabled || definition == null || nodeId == null) {
            return false;
        }
        try {
            return findTriggerNode(workflowService.resolveRunGraph(definition), nodeId)
                    .map(n -> WorkflowFormSchema.isFormTrigger(n.config()))
                    .orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Per-IP limit on loading forms (generous: a page load is cheap, but it still costs a tenant
     * lookup).
     */
    public boolean allowView(String clientIp) {
        return rateLimiter.tryAcquire(
                VIEW_RATE_PREFIX, WorkflowShareService.ipHash(clientIp), maxPerMinutePerIp * 8);
    }

    /**
     * The public form definition for {@code token}, or empty for ANY reason it is not available.
     */
    public Optional<FormView> view(String token) {
        if (!enabled || !shapeOk(token)) {
            return Optional.empty();
        }
        Optional<Resolved> resolved = resolve(token);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(toView(resolved.get().form()));
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Validates and accepts one submission. {@code body} is the posted JSON: {@code {"values":
     * {...}, "_hp": ""}}. {@code clientIp} only feeds the per-IP limiter (never stored or logged).
     */
    public Submission submit(String token, Map<String, Object> body, String clientIp) {
        if (!enabled || !shapeOk(token)) {
            return Submission.of(Status.NOT_FOUND);
        }
        String tokenKey = WebhookTokenHasher.sha256Hex(token).substring(0, 24);
        if (!rateLimiter.tryAcquire(TOKEN_RATE_PREFIX, tokenKey, maxPerMinutePerToken)
                || !rateLimiter.tryAcquire(
                        IP_RATE_PREFIX, WorkflowShareService.ipHash(clientIp), maxPerMinutePerIp)) {
            return Submission.of(Status.RATE_LIMITED);
        }
        if (serializedLength(body) > maxPayloadChars) {
            return Submission.of(Status.TOO_LARGE);
        }
        // Honeypot: a real browser never fills the hidden field. The bot is told the submission
        // worked.
        if (body != null && !str(body.get(WorkflowFormSchema.HONEYPOT_FIELD)).isEmpty()) {
            return Submission.of(Status.OK);
        }
        Optional<Resolved> resolved = resolve(token);
        if (resolved.isEmpty()) {
            return Submission.of(Status.NOT_FOUND);
        }
        try {
            Resolved r = resolved.get();
            Object rawValues = body == null ? null : body.get(BODY_VALUES_KEY);
            if (rawValues != null && !(rawValues instanceof Map<?, ?>)) {
                return new Submission(Status.INVALID, Map.of("_form", "invalid_body"), null, null);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> values = (Map<String, Object>) rawValues;
            WorkflowFormSchema.Result result = WorkflowFormSchema.validate(r.form(), values);
            if (!result.ok()) {
                return new Submission(Status.INVALID, result.errors(), null, null);
            }
            String input;
            try {
                input = objectMapper.writeValueAsString(result.values());
            } catch (Exception e) {
                log.warn(
                        "Workflow form submission for workflow {} could not be serialised: {}",
                        r.definition().getId(),
                        e.getMessage());
                return new Submission(Status.INVALID, Map.of("_form", "invalid_body"), null, null);
            }
            // TenantContext is set (resolve) - triggerAsync snapshots it for the background thread.
            triggerPort.triggerAsync(
                    r.definition().getId(),
                    r.triggerNode().getNodeId(),
                    input,
                    REQUESTED_BY_PREFIX + lastFour(token));
            String redirect = r.form().redirectUrl().isEmpty() ? null : r.form().redirectUrl();
            String message = r.form().successMessage().isEmpty() ? null : r.form().successMessage();
            return new Submission(Status.OK, Map.of(), message, redirect);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Resolves {@code token} to its form. On a PRESENT result {@link TenantContext} is set to the
     * owning tenant and the caller must clear it; on empty it is already cleared.
     */
    private Optional<Resolved> resolve(String token) {
        String hash = WebhookTokenHasher.sha256Hex(token);
        Optional<String> schema =
                tenantResolutionService.resolveTenantSchema(
                        ExternalAccountProvider.WORKFLOW_WEBHOOK, hash);
        if (schema.isEmpty()) {
            return Optional.empty();
        }
        TenantContext.set(
                TenantContext.of(schema.get(), CONTEXT_PREFIX + lastFour(token), Set.of()));
        try {
            WorkflowTriggerNode triggerNode =
                    triggerNodeService.findByWebhookTokenHash(hash).orElse(null);
            if (triggerNode == null
                    || !triggerNode.isWebhookEnabled()
                    || WebhookAuthMode.fromNullableName(triggerNode.getWebhookAuthMode())
                            != WebhookAuthMode.SHARED_SECRET) {
                TenantContext.clear();
                return Optional.empty();
            }
            WorkflowDefinition definition =
                    definitionRepository.findById(triggerNode.getWorkflowId()).orElse(null);
            if (definition == null || definition.getStatus() != WorkflowStatus.ACTIVE) {
                TenantContext.clear();
                return Optional.empty();
            }
            Optional<WorkflowNodeDto> node =
                    findTriggerNode(
                            workflowService.resolveRunGraph(definition), triggerNode.getNodeId());
            if (node.isEmpty() || !WorkflowFormSchema.isFormTrigger(node.get().config())) {
                TenantContext.clear();
                return Optional.empty();
            }
            WorkflowFormSchema.Form form;
            try {
                form = WorkflowFormSchema.parse(node.get().config());
            } catch (IllegalArgumentException badSchema) {
                log.warn(
                        "Workflow {} trigger node {} has an unusable form schema: {}",
                        definition.getId(),
                        triggerNode.getNodeId(),
                        badSchema.getMessage());
                TenantContext.clear();
                return Optional.empty();
            }
            return Optional.of(new Resolved(definition, triggerNode, form));
        } catch (RuntimeException e) {
            TenantContext.clear();
            throw e;
        }
    }

    private static Optional<WorkflowNodeDto> findTriggerNode(
            WorkflowGraphDto graph, String nodeId) {
        return graph.nodes().stream()
                .filter(n -> nodeId.equals(n.id()) && "trigger".equalsIgnoreCase(n.type()))
                .findFirst();
    }

    private static FormView toView(WorkflowFormSchema.Form form) {
        List<FieldView> fields = new ArrayList<>();
        for (WorkflowFormSchema.Field f : form.fields()) {
            boolean text = f.type().equals("text") || f.type().equals("long_text");
            fields.add(
                    new FieldView(
                            f.key(),
                            f.label(),
                            f.type(),
                            f.required(),
                            f.placeholder(),
                            f.options(),
                            text ? f.maxLength() : null,
                            f.min() == null ? null : f.min().doubleValue(),
                            f.max() == null ? null : f.max().doubleValue()));
        }
        return new FormView(
                form.title(),
                form.description(),
                form.submitLabel(),
                List.copyOf(fields),
                WorkflowFormSchema.HONEYPOT_FIELD);
    }

    private int serializedLength(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return 0;
        }
        try {
            return objectMapper.writeValueAsString(body).length();
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private static boolean shapeOk(String token) {
        return token != null && TOKEN_SHAPE.matcher(token).matches();
    }

    private static String lastFour(String value) {
        return value == null || value.length() < 4 ? "****" : value.substring(value.length() - 4);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }
}
