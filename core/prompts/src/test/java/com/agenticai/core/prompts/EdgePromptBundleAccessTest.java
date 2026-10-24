package com.agenticai.core.prompts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The edge bundle used to return an agent's own {@code edge.*} text to any member of the tenant that named the
 * agent. It now asks whether the CALLER may view the agent. These run the real controller over the real resolver
 * and a tenant-keyed override store, with a directory that models who may view what.
 */
class EdgePromptBundleAccessTest {

    private static final UUID TEAM_AGENT = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private static final String KEY = "edge.reasoning_nudge";
    private static final String AGENT_TEXT = "Agent-owned secret nudge: always cite the Acme refund policy.";
    private static final String TENANT_TEXT = "Tenant-wide nudge.";

    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void as(String tenant, String user) {
        TenantContext.set(TenantContext.of(tenant, user, Set.of()));
    }

    private void override(PromptScope scope, String scopeId, String text) {
        store.put(new PromptOverride(scope, scopeId, KEY, "en", text, true, 1, "owner", Instant.now()));
        resolver.evictAll();
    }

    /** exists = the agent is in the calling tenant; view = the calling user may read it (owner or a grant). */
    private EdgePromptBundleController controller(java.util.function.Predicate<UUID> exists, java.util.function.Predicate<UUID> view) {
        PromptAgentDirectory directory = new PromptAgentDirectory() {
            @Override
            public boolean existsInCurrentTenant(UUID agentId) {
                return exists.test(agentId);
            }

            @Override
            public boolean canViewInCurrentTenant(UUID agentId) {
                return view.test(agentId);
            }
        };
        @SuppressWarnings("unchecked")
        ObjectProvider<PromptAgentDirectory> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(directory);
        return new EdgePromptBundleController(resolver, provider, new PromptProperties());
    }

    private String nudge(EdgePromptBundleController controller, UUID agent) {
        return controller.bundle(agent).getBody().prompts().get(KEY).text();
    }

    @Test
    void aViewerOfTheAgentReadsItsOwnText() {
        as("tenant-a", "owner@a");
        override(PromptScope.TENANT, "", TENANT_TEXT);
        override(PromptScope.AGENT, TEAM_AGENT.toString(), AGENT_TEXT);

        assertThat(nudge(controller(id -> true, id -> true), TEAM_AGENT)).isEqualTo(AGENT_TEXT);
    }

    @Test
    void aTenantMemberWithoutAccessToTheAgentGetsTheTenantTextNotTheAgents() {
        as("tenant-a", "owner@a");
        override(PromptScope.TENANT, "", TENANT_TEXT);
        override(PromptScope.AGENT, TEAM_AGENT.toString(), AGENT_TEXT);

        as("tenant-a", "colleague-without-grant@a");
        EdgePromptBundleController asColleague = controller(id -> true, id -> false);

        assertThat(nudge(asColleague, TEAM_AGENT)).isEqualTo(TENANT_TEXT).doesNotContain("Acme");
        // Indistinguishable from an agent that does not exist: no oracle for which ids are real.
        assertThat(asColleague.bundle(TEAM_AGENT).getBody().version())
                .isEqualTo(asColleague.bundle(UUID.randomUUID()).getBody().version());
    }

    @Test
    void anotherTenantsAgentIdNeverReachesTheAgentScope() {
        as("tenant-a", "owner@a");
        override(PromptScope.TENANT, "", TENANT_TEXT);
        override(PromptScope.AGENT, TEAM_AGENT.toString(), AGENT_TEXT);

        as("tenant-b", "someone@b");
        // The agent table is per tenant schema: from tenant B the id is simply absent.
        EdgePromptBundleController asTenantB = controller(id -> false, id -> false);
        String text = nudge(asTenantB, TEAM_AGENT);

        assertThat(text).isNotEqualTo(AGENT_TEXT).isNotEqualTo(TENANT_TEXT);
        assertThat(text).isEqualTo(PromptResolver.defaultsOnly().text(KEY));
    }

    @Test
    void theViewCheckIsWhatDecidesNotMereExistence() {
        as("tenant-a", "owner@a");
        override(PromptScope.AGENT, TEAM_AGENT.toString(), AGENT_TEXT);

        EdgePromptBundleController existsButNotViewable = controller(id -> true, id -> false);

        assertThat(nudge(existsButNotViewable, TEAM_AGENT)).isNotEqualTo(AGENT_TEXT);
    }
}
