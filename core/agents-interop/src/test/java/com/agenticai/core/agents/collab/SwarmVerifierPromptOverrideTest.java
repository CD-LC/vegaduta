package com.agenticai.core.agents.collab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The swarm verifier's system prompt comes from the registry on the real {@code verifyOnce} path. */
class SwarmVerifierPromptOverrideTest {

    private static final String PASS =
            "{\"coverage\":[{\"subtask\":\"S1\",\"answered\":true,\"evidence\":\"e\"}],\"contradictions\":[],"
                    + "\"missingForGoal\":[],\"confidence\":0.9,\"verdict\":\"PASS\"}";

    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());
    private final List<String> systems = new CopyOnWriteArrayList<>();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private SwarmVerifier verifier() {
        return new SwarmVerifier(
                        new SwarmVerifierConfig(true, 0, 1500, 1200),
                        (system, user) -> {
                            systems.add(system);
                            return PASS;
                        },
                        null)
                .withPromptResolver(resolver);
    }

    @Test
    void theShippedPromptIsSentUntilATenantOverridesIt() {
        List<SwarmVerifier.Item> items = List.of(new SwarmVerifier.Item("researcher", "find facts", "facts", true));
        SwarmVerifier verifier = verifier();

        verifier.verifyOnce("goal", items, null);
        assertEquals(SwarmVerifier.SYSTEM_PROMPT, systems.get(0));

        TenantContext.set(TenantContext.of("a", "owner", Set.of()));
        store.put(new PromptOverride(PromptScope.TENANT, "", "swarm.verifier", "en", "Verify strictly.", true, 1, "owner", Instant.now()));
        resolver.evictAll();

        verifier.verifyOnce("goal", items, null);
        assertEquals("Verify strictly.", systems.get(1));
    }
}
