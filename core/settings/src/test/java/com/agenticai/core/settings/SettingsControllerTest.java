package com.agenticai.core.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenticai.core.security.TenantContext;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The HTTP contract: paths, bodies and error shapes the web packages code against. */
class SettingsControllerTest {

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

    @Test
    void catalogListsEntriesInTheContractShape() throws Exception {
        mvc.perform(get("/api/settings/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(8))
                .andExpect(jsonPath("$.entries[0].key").value("agent.limits.maxToolSteps"))
                .andExpect(jsonPath("$.entries[0].group").value("agent-limits"))
                .andExpect(jsonPath("$.entries[0].labelKey").value("settings.agent.limits.maxToolSteps.label"))
                .andExpect(jsonPath("$.entries[0].descriptionKey").value("settings.agent.limits.maxToolSteps.description"))
                .andExpect(jsonPath("$.entries[0].helpKey").value("settings.agent.limits.maxToolSteps.help"))
                .andExpect(jsonPath("$.entries[0].type").value("int"))
                .andExpect(jsonPath("$.entries[0].unit").value("count"))
                .andExpect(jsonPath("$.entries[0].min").value(1))
                .andExpect(jsonPath("$.entries[0].max").value(500))
                .andExpect(jsonPath("$.entries[0].risk").value("medium"))
                .andExpect(jsonPath("$.entries[0].scopes[0]").value("tenant"))
                .andExpect(jsonPath("$.entries[0].scopes[1]").value("agent"))
                .andExpect(jsonPath("$.entries[0].default").value(15))
                .andExpect(jsonPath("$.entries[0].minRole").value("EDITOR"))
                .andExpect(jsonPath("$.entries[0].restartRequired").value(false))
                .andExpect(jsonPath("$.entries[2].key").value("guardrails.failMode"))
                .andExpect(jsonPath("$.entries[2].type").value("enum"))
                .andExpect(jsonPath("$.entries[2].enumValues[1]").value("closed"))
                .andExpect(jsonPath("$.entries[2].risk").value("security"))
                .andExpect(jsonPath("$.entries[2].minRole").value("TENANT_ADMIN"))
                .andExpect(jsonPath("$.entries[2].strictness").value("enum_order"));
    }

    @Test
    void effectiveReturnsValueSourceLockedAndReason() throws Exception {
        f.properties.getLocked().put("chat.retry.maxAttempts", true);
        mvc.perform(get("/api/settings/effective"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values['agent.limits.maxToolSteps'].value").value(15))
                .andExpect(jsonPath("$.values['agent.limits.maxToolSteps'].source").value("default"))
                .andExpect(jsonPath("$.values['agent.limits.maxToolSteps'].locked").value(false))
                .andExpect(jsonPath("$.values['chat.retry.maxAttempts'].locked").value(true))
                .andExpect(jsonPath("$.values['chat.retry.maxAttempts'].lockedReason").isNotEmpty());
    }

    @Test
    void putAndDeleteTenantThenAgentRoundTrip() throws Exception {
        mvc.perform(put("/api/settings/tenant/agent.limits.maxToolSteps").contentType(MediaType.APPLICATION_JSON).content("{\"value\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("agent.limits.maxToolSteps"))
                .andExpect(jsonPath("$.effective.value").value(30))
                .andExpect(jsonPath("$.effective.source").value("tenant"))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(put("/api/agents/" + agent + "/settings/agent.limits.maxToolSteps").contentType(MediaType.APPLICATION_JSON).content("{\"value\":45,\"reason\":\"big job\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective.value").value(45))
                .andExpect(jsonPath("$.effective.source").value("agent"));
        mvc.perform(get("/api/settings/effective").param("agentId", agent))
                .andExpect(jsonPath("$.values['agent.limits.maxToolSteps'].value").value(45))
                .andExpect(jsonPath("$.values['agent.limits.maxToolSteps'].inherited.value").value(30))
                .andExpect(jsonPath("$.values['agent.limits.maxToolSteps'].inherited.source").value("tenant"));
        mvc.perform(delete("/api/agents/" + agent + "/settings/agent.limits.maxToolSteps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective.source").value("tenant"));
        mvc.perform(delete("/api/settings/tenant/agent.limits.maxToolSteps")).andExpect(status().isOk());
    }

    @Test
    void validationFailureIs400WithTheContractBody() throws Exception {
        mvc.perform(put("/api/settings/tenant/chat.retry.maxAttempts").contentType(MediaType.APPLICATION_JSON).content("{\"value\":9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTING_INVALID"))
                .andExpect(jsonPath("$.key").value("chat.retry.maxAttempts"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.min").value(1))
                .andExpect(jsonPath("$.max").value(6));
        mvc.perform(put("/api/settings/tenant/guardrails.failMode").contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"ajar\",\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.enumValues[0]").value("open"));
        mvc.perform(put("/api/settings/tenant/approvals.expiry.ttl").contentType(MediaType.APPLICATION_JSON).content("{\"value\":60}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTING_INVALID"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("reason")));
    }

    @Test
    void lockedIs409AndUnknownIs404() throws Exception {
        f.properties.getLocked().put("chat.retry.maxAttempts", true);
        mvc.perform(put("/api/settings/tenant/chat.retry.maxAttempts").contentType(MediaType.APPLICATION_JSON).content("{\"value\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SETTING_LOCKED"))
                .andExpect(jsonPath("$.key").value("chat.retry.maxAttempts"));
        mvc.perform(put("/api/settings/tenant/no.such.key").contentType(MediaType.APPLICATION_JSON).content("{\"value\":3}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void historyHasTheContractFields() throws Exception {
        mvc.perform(put("/api/settings/tenant/chat.retry.maxAttempts").contentType(MediaType.APPLICATION_JSON).content("{\"value\":3,\"reason\":\"flaky provider\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/settings/chat.retry.maxAttempts/history").param("scope", "tenant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].value").value(3))
                .andExpect(jsonPath("$[0].actor").value("alice"))
                .andExpect(jsonPath("$[0].reason").value("flaky provider"))
                .andExpect(jsonPath("$[0].at").isNotEmpty())
                .andExpect(jsonPath("$[0].id").doesNotExist());
        mvc.perform(get("/api/settings/chat.retry.maxAttempts/history").param("scope", "galaxy")).andExpect(status().isBadRequest());
    }

    @Test
    void agentOfAnotherTenantIs404() throws Exception {
        mvc.perform(put("/api/agents/" + SettingsFixture.OTHER_TENANT_AGENT + "/settings/agent.limits.maxToolSteps")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":10}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void tenantWritesAreGatedToTenantAdminsOnTheRoute() throws Exception {
        for (String name : new String[] {"putTenant", "deleteTenant"}) {
            Method m = java.util.Arrays.stream(SettingsController.class.getDeclaredMethods()).filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
            PreAuthorize pre = m.getAnnotation(PreAuthorize.class);
            assertThat(pre).as(name).isNotNull();
            assertThat(pre.value()).contains("ROLE_tenant-admin").doesNotContain("agent-builder");
        }
    }
}
