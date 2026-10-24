package com.agenticai.core.prompts;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The prompt text the in-browser (edge) runtime needs, resolved for the signed-in caller: the keys of
 * the {@code edge} family (the system-prompt fallback, the reasoning nudge, the on-device guardrail
 * refusals, the codebase Q&amp;A instruction) with their effective value, so a change made on the
 * instructions console reaches turns that never touch the server.
 *
 * <p>The response may be cached by the browser for {@code app.prompts.cache.ttl}, the same freshness the server's own
 * override cache has, so no extra setting is involved.
 *
 * <p>Read-only and for any signed-in user (no admin role): the text is what the caller's own browser is
 * about to send to a model on their behalf. It is resolved in the caller's tenant (the overrides live in
 * that tenant's schema), with the agent's override applied when {@code agentId} names an agent of that
 * tenant. A caller without a session (the anonymous try surface) gets 401 and the web client keeps its
 * bundled defaults; the bundled defaults are checked against the shipped files by a web test.
 *
 * <p>Switching off a key (where the key allows it) arrives as {@code enabled=false, text=null}; the client
 * treats a missing, blank or switched-off value as "use the bundled default" for everything that is not
 * disableable, and as "send nothing" only for the keys that are.
 */
@RestController
@RequestMapping("/api/edge/prompts")
@PreAuthorize("isAuthenticated()")
public class EdgePromptBundleController {

    /** Every key of this family is part of the bundle. */
    static final String FAMILY_PREFIX = "edge.";

    /**
     * One key of the bundle. {@code text} is null when the prompt is switched off; {@code switchable} says the key
     * may be switched off at all (a client must keep its own default for a key that may not).
     */
    public record EdgePrompt(
            String text, boolean enabled, boolean switchable, PromptSource source, int version, String hash) {}

    /** {@code version} changes whenever any value changes (the join of the hashes); clients may cache on it. */
    public record EdgePromptBundle(String version, Map<String, EdgePrompt> prompts) {}

    private final PromptResolver resolver;
    private final ObjectProvider<PromptAgentDirectory> agents;
    private final PromptProperties properties;

    public EdgePromptBundleController(
            PromptResolver resolver, ObjectProvider<PromptAgentDirectory> agents, PromptProperties properties) {
        this.resolver = resolver;
        this.agents = agents;
        this.properties = properties;
    }

    @GetMapping
    public ResponseEntity<EdgePromptBundle> bundle(@RequestParam(required = false) UUID agentId) {
        PromptContext context = PromptContext.none();
        if (agentId != null) {
            PromptAgentDirectory directory = agents.getIfAvailable();
            // The agent's own text is returned only to a caller who may view that agent: an agent of the tenant the
            // caller has no access to, an agent of another tenant and an unknown id all read as "no agent", so
            // the answer is also no oracle for which ids exist.
            if (directory != null && directory.canViewInCurrentTenant(agentId)) {
                context = PromptContext.forAgent(agentId);
            }
        }
        Map<String, EdgePrompt> prompts = new LinkedHashMap<>();
        StringBuilder versionSeed = new StringBuilder();
        for (PromptDefinition definition : resolver.catalog().all()) {
            if (!definition.key().startsWith(FAMILY_PREFIX) || definition.retired()) {
                continue;
            }
            ResolvedPrompt resolved = resolver.resolve(definition.key(), context);
            if (!resolved.known()) {
                continue;
            }
            prompts.put(
                    definition.key(),
                    new EdgePrompt(
                            resolved.enabled() ? resolved.text() : null,
                            resolved.enabled(),
                            definition.disableable(),
                            resolved.source(),
                            resolved.version(),
                            resolved.hash()));
            versionSeed.append(definition.key()).append('=').append(resolved.enabled()).append(resolved.hash()).append(';');
        }
        String version = ResolvedPrompt.hashOf(versionSeed.toString());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(properties.getCache().getTtl()).cachePrivate())
                .body(new EdgePromptBundle(version, prompts));
    }
}
