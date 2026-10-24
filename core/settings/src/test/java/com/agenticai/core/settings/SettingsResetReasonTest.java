package com.agenticai.core.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** A reset of a high or security key needs a typed reason, exactly like a PUT; the reason is kept and audited. */
class SettingsResetReasonTest {

    SettingsFixture f;
    MockMvc mvc;

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
    void resettingAHighRiskKeyWithoutAReasonIsRefusedAndKeepsTheOverride() {
        f.service.set(SettingScope.TENANT, null, "approvals.expiry.ttl", 3600, "stale requests pile up");
        assertThatThrownBy(() -> f.service.reset(SettingScope.TENANT, null, "approvals.expiry.ttl", null))
                .isInstanceOfSatisfying(SettingInvalidException.class, e -> {
                    assertThat(e.key()).isEqualTo("approvals.expiry.ttl");
                    assertThat(e.getMessage()).contains("reason is required").contains("reset");
                });
        assertThatThrownBy(() -> f.service.reset(SettingScope.TENANT, null, "approvals.expiry.ttl", "   "))
                .isInstanceOf(SettingInvalidException.class);
        assertThat(f.store.find(SettingScope.TENANT, "", "approvals.expiry.ttl")).isPresent();
        assertThat(f.service.history(SettingScope.TENANT, null, "approvals.expiry.ttl", null)).hasSize(1);
    }

    @Test
    void resettingAHighRiskKeyWithAReasonWorksAndTheReasonIsInTheHistoryAndTheAuditEntry() {
        f.service.set(SettingScope.TENANT, null, "approvals.expiry.ttl", 3600, "stale requests pile up");
        SettingsService.Applied applied = f.service.reset(SettingScope.TENANT, null, "approvals.expiry.ttl", "  back to the platform value  ");
        assertThat(applied.unchanged()).isFalse();
        assertThat(applied.version()).isEqualTo(2);

        SettingHistoryEntry last = f.service.history(SettingScope.TENANT, null, "approvals.expiry.ttl", null).get(0);
        assertThat(last.action()).isEqualTo(SettingHistoryEntry.ACTION_RESET);
        assertThat(last.reason()).isEqualTo("back to the platform value");
        assertThat(last.actor()).isEqualTo("alice");

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(f.audit).record(eq("alice"), eq(SettingsService.ACTION_RESET), eq("setting"), eq("approvals.expiry.ttl"), eq(AuditOutcome.SUCCESS), detail.capture());
        assertThat(detail.getValue()).contains("action=RESET", "old=3600", "reason=back to the platform value");
    }

    @Test
    void resettingASecurityKeyOnAnAgentNeedsAReasonToo() {
        f.failMode.set("open");
        f.service.set(SettingScope.AGENT, SettingsFixture.AGENT, "guardrails.failMode", "closed", "regulated data");
        assertThatThrownBy(() -> f.service.reset(SettingScope.AGENT, SettingsFixture.AGENT, "guardrails.failMode", null))
                .isInstanceOf(SettingInvalidException.class).hasMessageContaining("reason is required");
        f.service.reset(SettingScope.AGENT, SettingsFixture.AGENT, "guardrails.failMode", "agent no longer handles that data");
        assertThat(f.store.find(SettingScope.AGENT, SettingsFixture.AGENT.toString(), "guardrails.failMode")).isEmpty();
    }

    @Test
    void lowAndMediumKeysStillResetWithoutAReason() {
        f.service.set(SettingScope.TENANT, null, "chat.retry.maxAttempts", 3, null);
        f.service.set(SettingScope.TENANT, null, "agent.limits.maxToolSteps", 40, null);
        assertThat(f.service.reset(SettingScope.TENANT, null, "chat.retry.maxAttempts", null).unchanged()).isFalse();
        assertThat(f.service.reset(SettingScope.TENANT, null, "agent.limits.maxToolSteps", null).unchanged()).isFalse();
    }

    @Test
    void theHttpDeleteAnswers400WithTheContractBodyUntilAReasonIsGiven() throws Exception {
        f.service.set(SettingScope.TENANT, null, "approvals.expiry.ttl", 3600, "stale requests pile up");
        mvc.perform(delete("/api/settings/tenant/approvals.expiry.ttl"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTING_INVALID"))
                .andExpect(jsonPath("$.key").value("approvals.expiry.ttl"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("reason")));
        mvc.perform(delete("/api/settings/tenant/approvals.expiry.ttl").param("reason", "platform value is right"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        // and for a security key on an agent through the agent route
        f.failMode.set("open");
        mvc.perform(put("/api/agents/" + SettingsFixture.AGENT + "/settings/guardrails.failMode")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"closed\",\"reason\":\"regulated data\"}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/agents/" + SettingsFixture.AGENT + "/settings/guardrails.failMode"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTING_INVALID"));
        mvc.perform(delete("/api/agents/" + SettingsFixture.AGENT + "/settings/guardrails.failMode").param("reason", "agent is internal only"))
                .andExpect(status().isOk());
    }

    @Test
    void aMissingReasonIsCheckedBeforeAnythingElseSoItNeverLeaksWhetherAnOverrideExists() {
        // nothing was overridden: the reset would be a no-op, but the contract is the same as for PUT
        assertThatThrownBy(() -> f.service.reset(SettingScope.TENANT, null, "approvals.expiry.ttl", null))
                .isInstanceOf(SettingInvalidException.class);
        verify(f.audit, org.mockito.Mockito.never()).record(any(), eq(SettingsService.ACTION_RESET), any(), any(), any(), any());
    }
}
