package com.agenticai.core.providers;

import com.agenticai.core.security.TenantContext;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

/**
 * {@code /api/platform/tool-credentials/**} - already gated to {@code
 * ROLE_platform-admin} by {@code SecurityConfig}'s blanket {@code
 * /api/platform/**} rule, no new security wiring needed. Lets a platform
 * admin configure a platform-wide credential for a tool-capability provider
 * that previously had no admin path at all (ElevenLabs voice, E2B code
 * execution) - see V174's migration comment and {@link PlatformToolKey} for
 * the fixed set of valid {@code toolKey} values this accepts.
 */
@RestController
@RequestMapping("/api/platform/tool-credentials")
public class PlatformToolCredentialController {

    private final PlatformToolCredentialService service;

    public PlatformToolCredentialController(PlatformToolCredentialService service) {
        this.service = service;
    }

    /** The fixed list of tool keys this endpoint supports - lets the admin UI render one card per tool without hardcoding the list twice. */
    @GetMapping
    public List<PlatformToolCredentialStatusResponse> listAll() {
        return List.of(
            service.status(PlatformToolKey.ELEVENLABS),
            service.status(PlatformToolKey.E2B),
            // Brave key behind the web_search built-in. This list is hardcoded
            // rather than derived, so a new PlatformToolKey is invisible to the
            // admin UI until it is added here - the tool then exists in code and
            // can never be configured, which is the same "capability with no
            // reachable surface" trap that has bitten this codebase repeatedly.
            service.status(PlatformToolKey.BRAVE_SEARCH),
            // Alternative web_search providers (Tavily / Exa / Serper) - same hardcoded-list reasoning.
            service.status(PlatformToolKey.TAVILY_SEARCH),
            service.status(PlatformToolKey.EXA_SEARCH),
            service.status(PlatformToolKey.SERPER_SEARCH),
            // Managed-browser provider behind the web_task built-in - same
            // hardcoded-list reasoning as BRAVE_SEARCH above.
            service.status(PlatformToolKey.BROWSER_TASK),
            // TypeSafe Jev - the decision model behind JevDecisionService's
            // cost-routing and agent-selection upgrades. Same hardcoded-list
            // reasoning: without this row the key has no admin surface.
            service.status(PlatformToolKey.TYPESAFE)
        );
    }

    @GetMapping("/{toolKey}/status")
    public PlatformToolCredentialStatusResponse status(@PathVariable String toolKey) {
        return service.status(toolKey.toUpperCase(java.util.Locale.ROOT));
    }

    @PostMapping("/{toolKey}")
    public PlatformToolCredentialStatusResponse activate(
        @PathVariable String toolKey, @RequestBody PlatformToolCredentialActivationRequest request
    ) {
        String updatedBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        return service.activate(toolKey.toUpperCase(java.util.Locale.ROOT), request.apiKey(), request.extraConfig(), updatedBy);
    }

    @DeleteMapping("/{toolKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable String toolKey) {
        String updatedBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        service.deactivate(toolKey.toUpperCase(java.util.Locale.ROOT), updatedBy);
    }
}
