package com.agenticai.core.workflow;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerChannel;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNode;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.security.JwtClaimPaths;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workflow webhook trigger (#163), extended by #422 to support two auth modes selected per-workflow
 * (see {@link WebhookAuthMode}):
 *
 * <p><b>{@code POST /{token}}</b> - unchanged SHARED_SECRET behavior: authenticated by possession
 * of the opaque token in the URL path, exactly {@link
 * com.agenticai.core.omnichannel.twilio.TwilioWebhookController}'s posture, for external callers
 * (Zapier, cron-job.org, a curl one-liner) that can't present a bearer token at all. Per-node
 * workflow triggers: the token now resolves a specific {@code WorkflowTriggerNode} (one per Trigger
 * node in the graph, each with its own independent token), not the workflow as a whole - this
 * additionally checks that node's own {@code webhookAuthMode} - a node switched to OAUTH2 mode
 * stops responding on this URL, since "how do I trigger THIS node" now has exactly one active
 * answer per node.
 *
 * <p><b>{@code POST /oidc/{workflowId}} - new, #422's "secured...using the secured token oauth and
 * oidc" ask.</b> Requires an {@code Authorization: Bearer <jwt>} header carrying a real
 * Keycloak-issued token for this tenant. {@code /webhook/**} is {@code permitAll()} in {@code
 * SecurityConfig} (no third-party webhook caller can present a JWT through the normal OAuth2
 * Resource Server filter chain - see that class's javadoc), so this endpoint decodes the bearer
 * token itself, manually autowiring the app's single auto-configured {@link JwtDecoder} bean (one
 * shared Keycloak realm/issuer means one decoder validates every JWT app-wide - see {@code
 * KeycloakRoleConverter}'s javadoc for the same assumption). {@code JwtDecoder} is only
 * auto-configured when the {@code security} Spring profile is active (see {@code
 * application-security.yml}'s header comment) - injected here via {@link ObjectProvider} rather
 * than a hard constructor dependency so this controller (and the whole app context) still loads
 * cleanly with that profile off; the OIDC endpoint just returns 503 in that case, same "gracefully
 * degrade when optional infra isn't active" posture {@code WorkflowEventPublisher} already uses for
 * Kafka. On success: the tenant claim (default {@code tenant_id}, {@code
 * app.security.oidc.tenant-claim}) -&gt; {@link TenantContext} (mirrors {@code TenantContextFilter}
 * exactly, just invoked by hand since that filter never runs on this path), and the roles claim
 * (default {@code realm_access.roles}, {@code app.security.oidc.roles-claim-path}) must contain
 * {@code workflow-trigger} - a realm role that, like {@code platform-admin}, is NOT
 * auto-provisioned by signup and must be hand- assigned to a service account in Keycloak's own
 * console (documented in {@code deploy/keycloak/agentic-ai-realm.json}'s companion setup notes). An
 * invalid/expired JWT, a JWT for the wrong tenant's workflow, or a valid JWT missing the role, all
 * return 401/403 - never a 404 (unlike the shared-secret path, this endpoint's existence isn't a
 * secret worth hiding behind an identical "not found" for every failure mode, since the caller
 * already had to authenticate to reach this far).
 *
 * <p><b>Redelivery idempotency (audit item #21).</b> Webhook senders are at-least-once by contract
 * and will redeliver on a timeout even after this endpoint's run actually started - without a
 * check, a redelivery started a second full workflow run for the exact same event. The {@code POST
 * /{token}} path now claims a dedup key via the same {@link InboundMessageDedupService} ({@code
 * SETNX}-with-TTL over Redis) already used for inbound channel messages, keyed on whichever of
 * {@code X-Delivery-Id}/{@code X-Webhook-Id}/{@code X-Request-Id} the sender provides, falling back
 * to a SHA-256 hash of the request body when none is present. A dedup hit returns 409 without
 * starting a run. Scope intentionally limited to this trigger path - the OIDC path ({@code POST
 * /oidc/{workflowId}}) is a different, first-party-authenticated caller shape and is out of scope
 * for this fix.
 */
@RestController
@RequestMapping("/webhook/workflows")
public class WorkflowWebhookController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowWebhookController.class);
    private static final String REQUESTED_BY_PREFIX = "system:workflow-webhook:";
    private static final String OIDC_REQUESTED_BY_PREFIX = "system:workflow-webhook-oidc:";
    private static final String WORKFLOW_TRIGGER_ROLE = "workflow-trigger";
    // Channel scope for InboundMessageDedupService (see that class's javadoc)
    // - this surface has no ChannelType of its own, same reasoning as the
    // Twilio SMS webhook's "SMS" scope.
    private static final String DEDUP_CHANNEL_SCOPE = "WORKFLOW_WEBHOOK";

    private final TenantResolutionService tenantResolutionService;
    private final WorkflowDefinitionRepository definitionRepository;
    // Per-node workflow triggers - the webhook token/enabled/auth-mode lookup
    // that used to be a plain WorkflowDefinitionRepository#findByWebhookTokenHash
    // query now goes through this service instead (see its javadoc): the
    // token/hash belong to a WorkflowTriggerNode row, one per Trigger node,
    // not to WorkflowDefinition anymore. WorkflowDefinitionRepository is
    // still needed alongside it, to load the WorkflowDefinition by the
    // resolved workflowId for the status==ACTIVE check below (that field
    // stays on WorkflowDefinition).
    private final WorkflowTriggerNodeService workflowTriggerNodeService;
    private final WorkflowService workflowService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<JwtDecoder> jwtDecoderProvider;
    private final InboundMessageDedupService dedupService;

    // OIDC claim mapping (app.security.oidc.*, same properties SecurityConfig wires into the
    // converters/filters): field-injected so hand-built test controllers keep their constructor
    // arity; the initializers reproduce the Keycloak defaults.
    @Value("${app.security.oidc.tenant-claim:" + JwtClaimPaths.DEFAULT_TENANT_CLAIM + "}")
    private String tenantClaim = JwtClaimPaths.DEFAULT_TENANT_CLAIM;

    @Value("${app.security.oidc.roles-claim-path:" + JwtClaimPaths.DEFAULT_ROLES_CLAIM_PATH + "}")
    private String rolesClaimPath = JwtClaimPaths.DEFAULT_ROLES_CLAIM_PATH;

    // Hosted-form trigger (wfcontrol): setter-injected (not a constructor argument) so hand-built
    // test controllers keep
    // their arity; null / feature off = this controller behaves exactly as before.
    private WorkflowFormService formService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setFormService(WorkflowFormService formService) {
        this.formService = formService;
    }

    // Webhook signature secrets (V482) are AES-256-GCM ciphertext; decryption
    // needs EncryptionService (core-crypto, present whenever the app key is
    // configured). Setter-injected for the same test-arity reason as
    // formService; a signature-enabled node with no EncryptionService fails
    // CLOSED (503), never silently verifies-nothing.
    private EncryptionService encryptionService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEncryptionService(EncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    public WorkflowWebhookController(
            TenantResolutionService tenantResolutionService,
            WorkflowDefinitionRepository definitionRepository,
            WorkflowTriggerNodeService workflowTriggerNodeService,
            WorkflowService workflowService,
            ObjectMapper objectMapper,
            ObjectProvider<JwtDecoder> jwtDecoderProvider,
            InboundMessageDedupService dedupService) {
        this.tenantResolutionService = tenantResolutionService;
        this.definitionRepository = definitionRepository;
        this.workflowTriggerNodeService = workflowTriggerNodeService;
        this.workflowService = workflowService;
        this.objectMapper = objectMapper;
        this.jwtDecoderProvider = jwtDecoderProvider;
        this.dedupService = dedupService;
    }

    @PostMapping("/{token}")
    public ResponseEntity<?> trigger(
            @PathVariable String token,
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) byte[] rawBody) {
        // The cross-tenant lookup table stores only the SHA-256 hash of the token (see
        // WorkflowService#enableWebhook), never the plaintext - hash the inbound raw path token the
        // same way before resolving so the round-trip stays symmetric. The raw token is still
        // needed
        // below (trigger-node lookup and lastFour logging), so only this lookup key is hashed.
        Optional<String> tenantSchema =
                tenantResolutionService.resolveTenantSchema(
                        ExternalAccountProvider.WORKFLOW_WEBHOOK,
                        WebhookTokenHasher.sha256Hex(token));
        if (tenantSchema.isEmpty()) {
            log.warn("Workflow webhook called with an unknown token (***{})", lastFour(token));
            return ResponseEntity.notFound().build();
        }

        // Manually populated (and cleared, in the finally block below) -
        // TenantContextFilter never runs on /webhook/** since there is no
        // JWT here, mirroring TwilioWebhookController exactly.
        TenantContext.set(
                TenantContext.of(
                        tenantSchema.get(), REQUESTED_BY_PREFIX + lastFour(token), Set.of()));
        try {
            // #815 Governance follow-up (roadmap §10.3 item 1) - the stored token is now
            // ciphertext, so lookup goes by the SHA-256 hash of the caller's raw token instead of
            // an equality match against the raw value itself. Per-node workflow triggers: this now
            // resolves a WorkflowTriggerNode (workflowId + nodeId), not a WorkflowDefinition
            // directly - the definition is loaded separately below for the status==ACTIVE check,
            // which still lives on WorkflowDefinition.
            WorkflowTriggerNode triggerNode =
                    workflowTriggerNodeService
                            .findByWebhookTokenHash(WebhookTokenHasher.sha256Hex(token))
                            .orElse(null);
            if (triggerNode == null || !triggerNode.isWebhookEnabled()) {
                // Distinct from "unknown token" above only in the log message -
                // both return the same 404 to an external caller, since
                // "disabled" and "never existed" should look identical from
                // outside a tenant's own account.
                log.warn(
                        "Workflow webhook token resolved a tenant but no enabled trigger node (***{})",
                        lastFour(token));
                return ResponseEntity.notFound().build();
            }
            WebhookAuthMode authMode =
                    WebhookAuthMode.fromNullableName(triggerNode.getWebhookAuthMode());
            if (authMode != WebhookAuthMode.SHARED_SECRET) {
                // #422: this trigger node's active auth mode is now OAUTH2 -
                // the shared-secret URL intentionally stops firing rather
                // than silently running alongside whatever the tenant
                // actually configured.
                log.warn(
                        "Workflow webhook token used but trigger node {} on workflow {} is now configured for {} - rejecting",
                        triggerNode.getNodeId(),
                        triggerNode.getWorkflowId(),
                        authMode);
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(
                                Map.of(
                                        "error",
                                        "This trigger node is no longer the shared-secret webhook URL - check its "
                                                + "trigger channel setting."));
            }
            WorkflowDefinition definition =
                    definitionRepository.findById(triggerNode.getWorkflowId()).orElse(null);
            if (definition == null) {
                return ResponseEntity.notFound().build();
            }
            if (definition.getStatus() != WorkflowStatus.ACTIVE) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(
                                Map.of(
                                        "error",
                                        "This workflow is enabled for webhooks but is not Activated - activate it from the canvas first."));
            }
            // A hosted-form trigger node is reached through its form (schema validation, honeypot,
            // limits) -
            // its token must not double as a raw webhook that skips all of that.
            if (formService != null
                    && formService.isFormTriggerNode(definition, triggerNode.getNodeId())) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(
                                Map.of(
                                        "error",
                                        "This trigger is a hosted form - submit it through its form page, not the raw webhook URL."));
            }
            // V482 - optional sender-signature verification. Runs AFTER the
            // auth-mode gate (it only applies to the shared-secret URL path)
            // and BEFORE the dedup claim, so a forged/unsigned delivery can
            // never consume the legitimate delivery's dedup key.
            WebhookSignatureMode signatureMode =
                    WebhookSignatureMode.fromNullableName(triggerNode.getWebhookSignatureMode());
            if (signatureMode != WebhookSignatureMode.NONE) {
                if (encryptionService == null) {
                    log.error(
                            "Trigger node {} on workflow {} requires signature verification but no EncryptionService "
                                    + "bean is available - refusing rather than skipping the check.",
                            triggerNode.getNodeId(),
                            triggerNode.getWorkflowId());
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(
                                    Map.of(
                                            "error",
                                            "Signature verification is unavailable on this deployment."));
                }
                String signatureSecret =
                        Optional.ofNullable(triggerNode.getWebhookSignatureSecretEncrypted())
                                .map(encryptionService::decrypt)
                                .orElse(null);
                if (!WebhookSignatureVerifier.verify(
                        signatureMode, signatureSecret, rawBody, headers)) {
                    log.warn(
                            "Workflow webhook signature verification failed for trigger node {} on workflow {} (***{})",
                            triggerNode.getNodeId(),
                            triggerNode.getWorkflowId(),
                            lastFour(token));
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body(Map.of("error", "Webhook signature verification failed."));
                }
            }
            Map<String, Object> body = parseBody(rawBody);
            if (body == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Request body must be a JSON object."));
            }
            // Webhook senders are at-least-once by contract - a redelivery on
            // a slow/timed-out response must not start a second full run.
            // Scoped by trigger node (not just token) so two nodes can never
            // collide on the same dedup id.
            String dedupId =
                    triggerNode.getNodeId() + ":" + deliveryIdOrContentHash(headers, rawBody);
            if (!dedupService.tryClaim(DEDUP_CHANNEL_SCOPE, dedupId)) {
                log.info(
                        "Workflow webhook redelivery deduped for trigger node {} on workflow {} (***{})",
                        triggerNode.getNodeId(),
                        triggerNode.getWorkflowId(),
                        lastFour(token));
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(
                                Map.of(
                                        "error",
                                        "Duplicate webhook delivery - this delivery has already been processed."));
            }
            WorkflowRunResponse result =
                    workflowService.run(
                            definition.getId(),
                            triggerNode.getNodeId(),
                            extractInput(body),
                            REQUESTED_BY_PREFIX + lastFour(token));
            return ResponseEntity.ok(result);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * OAuth2/OIDC-secured trigger (#422). See the class javadoc for the full auth flow; the short
     * version: valid bearer JWT for this tenant, with the {@code workflow-trigger} realm role,
     * required.
     */
    @PostMapping("/oidc/{workflowId}")
    public ResponseEntity<?> triggerOidc(
            @PathVariable java.util.UUID workflowId,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestBody(required = false) Map<String, Object> body) {
        JwtDecoder jwtDecoder = jwtDecoderProvider.getIfAvailable();
        if (jwtDecoder == null) {
            log.error(
                    "Workflow OIDC webhook called but no JwtDecoder bean is available - the 'security' Spring profile "
                            + "isn't active on this deployment (see application-security.yml).");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(
                            Map.of(
                                    "error",
                                    "OAuth2/OIDC webhook triggers aren't configured on this deployment - the 'security' profile isn't active."));
        }
        if (authorizationHeader == null
                || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(
                            Map.of(
                                    "error",
                                    "Missing bearer token - this workflow requires an OAuth2/OIDC Authorization header."));
        }
        String rawToken = authorizationHeader.substring(7).trim();
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(rawToken);
        } catch (JwtException e) {
            log.warn(
                    "Workflow OIDC webhook rejected an invalid/expired bearer token: {}",
                    e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid or expired bearer token."));
        }

        String tenantId = JwtClaimPaths.valueAsString(jwt, tenantClaim);
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(
                            Map.of(
                                    "error",
                                    "This token has no "
                                            + tenantClaim
                                            + " claim - it can't be used against a tenant-scoped workflow."));
        }
        if (!hasWorkflowTriggerRole(jwt)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(
                            Map.of(
                                    "error",
                                    "This token's account is missing the '"
                                            + WORKFLOW_TRIGGER_ROLE
                                            + "' realm role required to "
                                            + "call OAuth2/OIDC workflow triggers - a tenant admin must assign it in Keycloak."));
        }

        // tenant_id claim IS the schema identifier - identical assumption
        // TenantContextFilter makes for every normal authenticated request;
        // this endpoint just has to do it by hand since /webhook/** never
        // runs that filter.
        TenantContext.set(
                TenantContext.of(tenantId, OIDC_REQUESTED_BY_PREFIX + jwt.getSubject(), Set.of()));
        try {
            WorkflowDefinition definition = definitionRepository.findById(workflowId).orElse(null);
            if (definition == null) {
                return ResponseEntity.notFound().build();
            }
            if (definition.getStatus() != WorkflowStatus.ACTIVE) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(
                                Map.of(
                                        "error",
                                        "This workflow is enabled for webhooks but is not Activated - activate it from the canvas first."));
            }
            // Per-node workflow triggers: the workflow-trigger role is a
            // coarse, tenant-wide grant handed to service accounts, so the
            // role alone must not unlock EVERY ACTIVE workflow - the target
            // workflow must have at least one WEBHOOK-channel trigger node
            // explicitly configured for OAUTH2 auth before it answers here.
            // This endpoint still takes only a workflowId (no node) and
            // starts from the graph's default trigger (startNodeId=null) -
            // resolving "which specific OAUTH2 node" would need a per-node
            // OIDC URL, which remains follow-up scope.
            boolean oidcTriggerConfigured =
                    workflowTriggerNodeService.triggerNodesFor(definition.getId()).stream()
                            .anyMatch(
                                    node ->
                                            node.getChannel() == WorkflowTriggerChannel.WEBHOOK
                                                    && WebhookAuthMode.fromNullableName(
                                                                    node.getWebhookAuthMode())
                                                            == WebhookAuthMode.OAUTH2);
            if (!oidcTriggerConfigured) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(
                                Map.of(
                                        "error",
                                        "This workflow has no webhook trigger node configured for OAuth2/OIDC auth - "
                                                + "configure one on the canvas before calling this endpoint."));
            }
            WorkflowRunResponse result =
                    workflowService.run(
                            definition.getId(),
                            null,
                            extractInput(body),
                            OIDC_REQUESTED_BY_PREFIX + jwt.getSubject());
            return ResponseEntity.ok(result);
        } finally {
            TenantContext.clear();
        }
    }

    private boolean hasWorkflowTriggerRole(Jwt jwt) {
        Object rolesClaim = JwtClaimPaths.value(jwt, rolesClaimPath);
        if (!(rolesClaim instanceof List<?> roles)) {
            return false;
        }
        return roles.contains(WORKFLOW_TRIGGER_ROLE);
    }

    /**
     * A caller can send {@code {"input": "..."}} to control exactly what the Trigger node's output
     * is; anything else in the body (or no body at all) still runs the workflow, just with a
     * generic default - a webhook caller that only wants "fire this" (a cron ping, a no-payload
     * webhook relay) shouldn't be forced to shape a payload it has no use for.
     */
    /**
     * Parses the raw request bytes into a JSON object. Null means "not a JSON object" - the caller
     * turns that into a 400, matching the old {@code @RequestBody Map} binding behavior (a JSON
     * array or scalar never bound to a Map then either). The body is only parsed AFTER signature
     * verification, on purpose: the HMAC covers the exact wire bytes, so parsing first and
     * re-serializing would be both wasteful and wrong.
     */
    private Map<String, Object> parseBody(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(rawBody, Map.class);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractInput(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return "Triggered via webhook.";
        }
        Object explicit = body.get("input");
        if (explicit != null && !explicit.toString().isBlank()) {
            return explicit.toString();
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            return "Triggered via webhook.";
        }
    }

    /**
     * Prefers whichever delivery-id header the sender actually provides (checked in this order
     * since no single header name is standard across webhook senders); falls back to a SHA-256 hash
     * of the raw body when none is present, so even a sender with no delivery-id concept at all
     * still gets redelivery protection as long as the retried body is byte-identical to the
     * original (true for every at-least-once webhook sender this platform has seen - a redelivery
     * is a resend, not a regeneration).
     */
    private String deliveryIdOrContentHash(HttpHeaders headers, byte[] rawBody) {
        for (String header : List.of("X-Delivery-Id", "X-Webhook-Id", "X-Request-Id")) {
            String value = headers.getFirst(header);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        // Hash the exact wire bytes (not a re-serialized map) - a redelivery
        // is byte-identical by definition, and this skips the parse round-trip.
        return "sha256:" + sha256Hex(rawBody == null ? new byte[0] : rawBody);
    }

    private static String sha256Hex(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed available on every JDK - unreachable in
            // practice, but a checked exception still has to go somewhere.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String lastFour(String value) {
        if (value == null || value.length() < 4) {
            return "****";
        }
        return value.substring(value.length() - 4);
    }
}
