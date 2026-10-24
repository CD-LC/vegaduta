package com.agenticai.core.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

/** The all-keys history: paging, the since filter, scope handling, authorization and tenant isolation. */
class SettingsBulkHistoryTest {

    SettingsFixture f;
    MockMvc mvc;
    final String agent = SettingsFixture.AGENT.toString();

    @BeforeEach
    void setUp() {
        f = new SettingsFixture();
        SettingsFixture.asAdmin();
        mvc = MockMvcBuilders.standaloneSetup(new SettingsController(f.service), new AgentSettingsController(f.service))
                .setControllerAdvice(new SettingsExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** tenant: retry v1, v2 and maxToolSteps v1; agent: maxToolSteps v1, chat.cache v1. Written one after another. */
    private void seed() {
        f.service.set(SettingScope.TENANT, null, "chat.retry.maxAttempts", 3, "first");
        f.service.set(SettingScope.TENANT, null, "chat.retry.maxAttempts", 4, "second");
        f.service.set(SettingScope.TENANT, null, "agent.limits.maxToolSteps", 30, null);
        f.service.set(SettingScope.AGENT, SettingsFixture.AGENT, "agent.limits.maxToolSteps", 45, "big job");
        f.service.set(SettingScope.AGENT, SettingsFixture.AGENT, "chat.cache.eligibleWithTools", true, null);
    }

    @Test
    void theWorkspaceViewMergesTenantAndAgentRowsNewestFirstWithTheirScope() {
        seed();
        SettingsService.HistoryPage page = f.service.historyPage(null, null, null, null, null);
        assertThat(page.total()).isEqualTo(5);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.entries()).extracting(SettingHistoryEntry::key)
                .containsExactly("chat.cache.eligibleWithTools", "agent.limits.maxToolSteps", "agent.limits.maxToolSteps", "chat.retry.maxAttempts", "chat.retry.maxAttempts");
        assertThat(page.entries()).extracting(SettingHistoryEntry::scope)
                .containsExactly(SettingScope.AGENT, SettingScope.AGENT, SettingScope.TENANT, SettingScope.TENANT, SettingScope.TENANT);
        assertThat(page.entries().get(1).scopeId()).isEqualTo(agent);
    }

    @Test
    void scopeFiltersNarrowTheRows() {
        seed();
        assertThat(f.service.historyPage(SettingScope.TENANT, null, null, null, null).entries())
                .hasSize(3).allMatch(e -> e.scope() == SettingScope.TENANT);
        SettingsService.HistoryPage agentRows = f.service.historyPage(SettingScope.AGENT, SettingsFixture.AGENT, null, null, null);
        assertThat(agentRows.total()).isEqualTo(2);
        assertThat(agentRows.entries()).allMatch(e -> e.scope() == SettingScope.AGENT && e.scopeId().equals(agent));
    }

    @Test
    void agentScopeWithoutAnAgentIsEveryAgentsRowsForATenantAdminOnly() {
        seed();
        SettingsService.HistoryPage page = f.service.historyPage(SettingScope.AGENT, null, null, null, null);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.entries()).allMatch(e -> e.scope() == SettingScope.AGENT);
        SettingsFixture.asEditor();
        assertThatThrownBy(() -> f.service.historyPage(SettingScope.AGENT, null, null, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void pagingReportsTotalOffsetAndHasMoreAndNeverRepeatsARow() {
        seed();
        SettingsService.HistoryPage first = f.service.historyPage(null, null, null, 2, 0);
        SettingsService.HistoryPage second = f.service.historyPage(null, null, null, 2, 2);
        SettingsService.HistoryPage third = f.service.historyPage(null, null, null, 2, 4);
        assertThat(first.entries()).hasSize(2);
        assertThat(first.total()).isEqualTo(5);
        assertThat(first.hasMore()).isTrue();
        assertThat(second.hasMore()).isTrue();
        assertThat(third.entries()).hasSize(1);
        assertThat(third.hasMore()).isFalse();
        List<String> seen = new java.util.ArrayList<>();
        for (SettingsService.HistoryPage p : List.of(first, second, third)) {
            p.entries().forEach(e -> seen.add(e.scope() + "/" + e.scopeId() + "/" + e.key() + "/" + e.version()));
        }
        assertThat(seen).doesNotHaveDuplicates().hasSize(5);
        assertThat(f.service.historyPage(null, null, null, 2, 99).entries()).isEmpty();
        // out-of-range inputs are clamped, never a 500
        assertThat(f.service.historyPage(null, null, null, 0, -5).entries()).hasSize(1);
        assertThat(f.service.historyPage(null, null, null, 5_000_000, 2_000_000_000).entries()).isEmpty();
    }

    @Test
    void theLimitIsCappedAndTheDefaultComesFromTheProperties() {
        f.properties.setBulkHistoryMaxLimit(3);
        f.properties.setBulkHistoryDefaultLimit(2);
        seed();
        assertThat(f.service.historyPage(null, null, null, null, null).entries()).hasSize(2);
        assertThat(f.service.historyPage(null, null, null, 100, null).entries()).hasSize(3);
        f.properties.setBulkHistoryDefaultLimit(50);
        assertThat(f.service.historyPage(null, null, null, null, null).entries()).hasSize(3); // default is capped by the max
    }

    @Test
    void sinceKeepsOnlyRowsAtOrAfterIt() throws Exception {
        f.service.set(SettingScope.TENANT, null, "chat.retry.maxAttempts", 3, null);
        Thread.sleep(15);
        Instant cut = Instant.now();
        Thread.sleep(15);
        f.service.set(SettingScope.TENANT, null, "chat.retry.maxAttempts", 4, null);
        SettingsService.HistoryPage page = f.service.historyPage(null, null, cut, null, null);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.entries().get(0).version()).isEqualTo(2);
        assertThat(f.service.historyPage(null, null, Instant.now().plusSeconds(60), null, null).entries()).isEmpty();
        assertThat(f.service.historyPage(null, null, Instant.EPOCH, null, null).total()).isEqualTo(2);
    }

    @Test
    void theWorkspaceAndTenantViewsNeedATenantAdmin() {
        seed();
        SettingsFixture.asEditor();
        for (SettingScope scope : new SettingScope[] {null, SettingScope.TENANT}) {
            assertThatThrownBy(() -> f.service.historyPage(scope, null, null, null, null))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        }
    }

    @Test
    void theAgentViewNeedsEditorOnThatAgentAndAnAgentOfAnotherTenantIsNotFound() {
        seed();
        SettingsFixture.asEditor();
        assertThat(f.service.historyPage(SettingScope.AGENT, SettingsFixture.AGENT, null, null, null).entries()).hasSize(2);
        f.editorOn.clear();
        assertThatThrownBy(() -> f.service.historyPage(SettingScope.AGENT, SettingsFixture.AGENT, null, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        SettingsFixture.asAdmin();
        assertThatThrownBy(() -> f.service.historyPage(SettingScope.AGENT, SettingsFixture.OTHER_TENANT_AGENT, null, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(() -> f.service.historyPage(SettingScope.TENANT, UUID.randomUUID(), null, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void noWorkspaceNoHistory() {
        TenantContext.clear();
        assertThatThrownBy(() -> f.service.historyPage(null, null, null, null, null)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void anotherTenantNeverSeesTheRowsOfThisOne() {
        seed();
        SettingsFixture.as("tenant-b", "bob", "ROLE_tenant-admin");
        assertThat(f.service.historyPage(null, null, null, null, null).total()).isZero();
        assertThat(f.service.historyPage(SettingScope.TENANT, null, null, null, null).entries()).isEmpty();
        // its own rows are its own
        f.service.set(SettingScope.TENANT, null, "chat.retry.maxAttempts", 5, null);
        assertThat(f.service.historyPage(null, null, null, null, null).entries()).hasSize(1)
                .allMatch(e -> e.actor().equals("bob"));
        SettingsFixture.asAdmin();
        assertThat(f.service.historyPage(null, null, null, null, null).total()).isEqualTo(5);
        // and an agent of tenant A is a 404 for tenant B on the agent view
        SettingsFixture.as("tenant-b", "bob", "ROLE_tenant-admin");
        assertThatThrownBy(() -> f.service.historyPage(SettingScope.AGENT, SettingsFixture.AGENT, null, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }

    // ------------------------------------------------------------------------------------ HTTP

    @Test
    void theWorkspaceRouteHasTheContractShape() throws Exception {
        seed();
        mvc.perform(get("/api/settings/history").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].key").value("chat.cache.eligibleWithTools"))
                .andExpect(jsonPath("$.entries[0].scope").value("agent"))
                .andExpect(jsonPath("$.entries[0].agentId").value(agent))
                .andExpect(jsonPath("$.entries[0].version").value(1))
                .andExpect(jsonPath("$.entries[0].action").value("SET"))
                .andExpect(jsonPath("$.entries[0].value").value(true))
                .andExpect(jsonPath("$.entries[0].actor").value("alice"))
                .andExpect(jsonPath("$.entries[0].at").isNotEmpty())
                .andExpect(jsonPath("$.entries[1].reason").value("big job"))
                .andExpect(jsonPath("$.entries[0].id").doesNotExist());
        mvc.perform(get("/api/settings/history").param("scope", "tenant").param("offset", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].scope").value("tenant"))
                .andExpect(jsonPath("$.entries[0].agentId").doesNotExist())
                .andExpect(jsonPath("$.hasMore").value(false));
        mvc.perform(get("/api/settings/history").param("since", Instant.now().plusSeconds(60).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.entries.length()").value(0));
    }

    @Test
    void theAgentRouteAndItsErrors() throws Exception {
        seed();
        mvc.perform(get("/api/agents/" + agent + "/settings/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.entries[0].scope").value("agent"))
                .andExpect(jsonPath("$.entries[0].agentId").value(agent));
        mvc.perform(get("/api/agents/" + SettingsFixture.OTHER_TENANT_AGENT + "/settings/history")).andExpect(status().isNotFound());
        SettingsFixture.asEditor();
        mvc.perform(get("/api/settings/history")).andExpect(status().isForbidden());
        mvc.perform(get("/api/agents/" + agent + "/settings/history")).andExpect(status().isOk());
        mvc.perform(get("/api/settings/history").param("scope", "galaxy")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/settings/history").param("scope", "agent")).andExpect(status().isForbidden()); // every agent: admins only
        mvc.perform(get("/api/settings/history").param("scope", "agent").param("agentId", agent)).andExpect(status().isOk());
        mvc.perform(get("/api/settings/history").param("scope", "all").param("agentId", agent)).andExpect(status().isBadRequest());
        SettingsFixture.asAdmin();
        mvc.perform(get("/api/settings/history").param("scope", "agent")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/settings/history").param("scope", "tenant").param("agentId", agent)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/settings/history").param("since", "yesterday")).andExpect(status().isBadRequest());
    }
}
