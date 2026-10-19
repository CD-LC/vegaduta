package com.agenticai.core.agents.tools.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code GET /api/platform/web-search/providers} - read-only: which web-search providers exist,
 * which are configured, which is primary / fallback (and in what order), and which would answer a
 * search right now. Already gated to {@code ROLE_platform-admin} by SecurityConfig's blanket {@code
 * /api/platform/**} rule.
 *
 * <p>Honest by construction: "configured" means a key / base URL is present, never that it works
 * (nothing is called), and no secret or URL is returned. Which provider is primary and the fallback
 * order are operator configuration ({@code app.web-search.*}), so this view cannot change them -
 * the keys themselves are set on the existing Tool Credentials page.
 */
@RestController
@RequestMapping("/api/platform/web-search")
public class WebSearchProvidersController {

    private final ObjectProvider<WebSearchProviderRegistry> registry;

    public WebSearchProvidersController(ObjectProvider<WebSearchProviderRegistry> registry) {
        this.registry = registry;
    }

    @GetMapping("/providers")
    public WebSearchProviderRegistry.Status providers() {
        WebSearchProviderRegistry r = registry.getIfAvailable();
        if (r == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Web search providers are not available.");
        }
        return r.status();
    }
}
