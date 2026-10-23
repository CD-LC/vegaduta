package com.agenticai.core.agents.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.agenticai.core.security.TenantContext;
import static org.mockito.Mockito.when;

/**
 * The line that opens the replayed rolling summary ({@code memory.summary_preamble}) is the tenant's
 * or the platform's wording when one is stored, and the shipped wording otherwise.
 */
class SessionSummaryPreambleOverrideTest extends SessionMemoryServiceTestBase {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void theReplayedSummaryOpensWithTheTenantsWording() {
        withTenantContext();
        InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
        DefaultPromptResolver resolver =
                new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());
        store.put(
                new PromptOverride(
                        PromptScope.TENANT,
                        "",
                        "memory.summary_preamble",
                        "en",
                        "[Earlier chat, shortened:]",
                        true,
                        1,
                        "owner",
                        Instant.now()));
        resolver.evictAll();
        SessionMemoryService service = newService(3, 32000, 0);
        service.setPromptResolver(resolver);
        stubTailRange(3, List.of(user("u2"), assistant("a2"), user("u3")));
        when(valueOps.getAndExpire(SUMMARY_KEY, TTL)).thenReturn("The user is migrating Postgres.");

        List<ChatTurn> window = service.getHistory(SESSION_ID);

        assertEquals("[Earlier chat, shortened:]\nThe user is migrating Postgres.\n\nu2", window.get(0).content());
    }

    @Test
    void withNoOverrideTheShippedPreambleIsUsed() {
        withTenantContext();
        SessionMemoryService service = newService(3, 32000, 0);
        service.setPromptResolver(
                new DefaultPromptResolver(
                        PromptResolver.defaultsOnly().catalog(), new InMemoryPromptOverrideStore(), new PromptProperties()));
        stubTailRange(3, List.of(user("u2"), assistant("a2"), user("u3")));
        when(valueOps.getAndExpire(SUMMARY_KEY, TTL)).thenReturn("S");

        assertTrue(service.getHistory(SESSION_ID).get(0).content().startsWith(SessionMemoryService.SUMMARY_PREAMBLE));
    }
}
