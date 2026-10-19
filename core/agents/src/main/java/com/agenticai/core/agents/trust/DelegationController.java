package com.agenticai.core.agents.trust;

import com.agenticai.core.agents.a2a.A2aProperties;
import com.agenticai.core.agents.a2a.A2aPublicBaseUrl;
import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.trust.DelegationDtos.CreateGrantRequest;
import com.agenticai.core.agents.trust.DelegationDtos.GrantResponse;
import com.agenticai.core.agents.trust.DelegationDtos.IntrospectRequest;
import com.agenticai.core.agents.trust.DelegationDtos.MintTokenRequest;
import com.agenticai.core.agents.trust.DelegationDtos.MintedTokenResponse;
import com.agenticai.core.agents.trust.DelegationDtos.RevokeRequest;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.tenancy.ListPaging;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tenant-facing REST for agent delegation (agent identity acting on behalf of a user). Creating a
 * grant or minting a token requires EDITOR access on the agent - only someone who can modify the
 * agent may authorize it to act for them. Introspection is open to any authenticated caller of the
 * platform (same posture as an OAuth introspection endpoint inside one trust domain).
 *
 * <p>{@code grantedBy} is NEVER caller-supplied: the delegator is the authenticated JWT subject
 * from {@link TenantContext} - a client cannot mint a delegation "granted by" someone else.
 */
@RestController
@RequestMapping("/api/agents")
public class DelegationController {

    private final DelegationTokenService delegationService;
    private final AgentService agentService;
    private final CapabilityManifestService manifestService;
    private final A2aProperties a2aProperties;
    private final ObjectMapper objectMapper = JsonMappers.plain();

    public DelegationController(
            DelegationTokenService delegationService,
            AgentService agentService,
            CapabilityManifestService manifestService,
            A2aProperties a2aProperties) {
        this.delegationService = delegationService;
        this.agentService = agentService;
        this.manifestService = manifestService;
        this.a2aProperties = a2aProperties;
    }

    /**
     * Create a grant AND mint its first token in one call - the common case ("let this agent call
     * that service for me") is one round-trip.
     */
    @PostMapping("/{agentId}/delegations")
    public ResponseEntity<Map<String, Object>> createGrant(
            @PathVariable UUID agentId, @Valid @RequestBody CreateGrantRequest body) {
        agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        try {
            DelegationGrant grant =
                    delegationService.createGrant(
                            agentId,
                            currentSubject(),
                            body.audience(),
                            body.scopes(),
                            body.maxTokenTtlSeconds() == null ? 0 : body.maxTokenTtlSeconds(),
                            body.expiresAt(),
                            body.walletId(),
                            body.spendCapMinor());
            DelegationTokenService.MintedToken minted =
                    delegationService.mintToken(grant.getId(), null);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(
                            Map.of(
                                    "grant",
                                    toResponse(grant),
                                    "token",
                                    new MintedTokenResponse(
                                            minted.token(),
                                            minted.jti(),
                                            minted.expiresAt(),
                                            grant.getId())));
        } catch (DelegationException e) {
            throw delegationError(e);
        }
    }

    /** Mint an additional token under an existing grant. */
    @PostMapping("/{agentId}/delegations/{grantId}/tokens")
    public ResponseEntity<MintedTokenResponse> mint(
            @PathVariable UUID agentId,
            @PathVariable UUID grantId,
            @Valid @RequestBody(required = false) MintTokenRequest body) {
        agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        try {
            DelegationGrant grant = requireGrantForAgent(grantId, agentId);
            DelegationTokenService.MintedToken minted =
                    delegationService.mintToken(
                            grant.getId(), body == null ? null : body.ttlSeconds());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(
                            new MintedTokenResponse(
                                    minted.token(),
                                    minted.jti(),
                                    minted.expiresAt(),
                                    grant.getId()));
        } catch (DelegationException e) {
            throw delegationError(e);
        }
    }

    @GetMapping("/{agentId}/delegations")
    public ResponseEntity<List<GrantResponse>> list(@PathVariable UUID agentId, @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        return ListPaging.window(
            delegationService.listGrants(agentId).stream().map(this::toResponse).toList(),
            limit, offset, 50, 500);
    }

    @PostMapping("/{agentId}/delegations/{grantId}/revoke")
    public GrantResponse revoke(
            @PathVariable UUID agentId,
            @PathVariable UUID grantId,
            @RequestBody(required = false) RevokeRequest body) {
        agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        requireGrantForAgent(grantId, agentId);
        DelegationGrant grant =
                delegationService.revokeGrant(grantId, body == null ? null : body.reason());
        return toResponse(grant);
    }

    /**
     * RFC 7662-style introspection for callers inside this deployment. Any authenticated principal
     * may introspect; active responses include the full claim set, inactive ones only a stable
     * reason code.
     */
    @PostMapping("/delegations/introspect")
    public Map<String, Object> introspect(@Valid @RequestBody IntrospectRequest body) {
        return delegationService.introspect(body.token());
    }

    /**
     * The signed capability manifest - the Know-Your-Agent document. Behind the same RUNNER access
     * the A2A card uses: a manifest is handed only to a caller who could already talk to the agent.
     */
    @GetMapping("/{agentId}/manifest")
    public Map<String, Object> manifest(@PathVariable UUID agentId, HttpServletRequest request) {
        Agent agent = agentService.requireAccess(agentId, PermissionLevel.RUNNER);
        return manifestService.manifest(agent, A2aPublicBaseUrl.resolve(a2aProperties, request));
    }

    // ---------------------------------------------------------------- internals

    private DelegationGrant requireGrantForAgent(UUID grantId, UUID agentId) {
        return delegationService
                .findGrant(grantId)
                .filter(g -> g.getAgentId().equals(agentId))
                // A grant belonging to a different agent 404s like a missing one -
                // same anti-enumeration posture as requireAccess.
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such grant"));
    }

    private String currentSubject() {
        String subject = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        if (subject == null || subject.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "no authenticated subject to delegate as");
        }
        return subject;
    }

    private GrantResponse toResponse(DelegationGrant g) {
        List<String> scopes;
        try {
            scopes = objectMapper.readValue(g.getScopesJson(), List.class);
        } catch (Exception e) {
            scopes = List.of();
        }
        return DelegationDtos.toGrantResponse(g, scopes);
    }

    private ResponseStatusException delegationError(DelegationException e) {
        // Grant-state failures are conflicts; everything else is a bad request.
        HttpStatus status =
                switch (e.getCode()) {
                    case "GRANT_REVOKED", "GRANT_EXPIRED", "GRANT_INACTIVE" -> HttpStatus.CONFLICT;
                    case "GRANT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
                    case "SIGNING_DISABLED", "SIGNING_FAILED" -> HttpStatus.SERVICE_UNAVAILABLE;
                    default -> HttpStatus.BAD_REQUEST;
                };
        return new ResponseStatusException(status, e.getCode() + ": " + e.getMessage());
    }
}
