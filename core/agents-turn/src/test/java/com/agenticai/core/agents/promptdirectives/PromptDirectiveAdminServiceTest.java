package com.agenticai.core.agents.promptdirectives;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.PromptDirectiveRegistry;
import com.agenticai.core.agents.turn.PromptDirectiveSettings;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.server.ResponseStatusException;

class PromptDirectiveAdminServiceTest {

    private final Map<String, PromptDirectiveSetting> rows = new HashMap<>();
    private final PromptDirectiveSettingRepository repo = mock(PromptDirectiveSettingRepository.class);
    private final PromptDirectiveSettings settings = mock(PromptDirectiveSettings.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private PromptDirectiveAdminService service;

    @BeforeEach
    void wire() {
        when(repo.findAll()).thenAnswer(i -> List.copyOf(rows.values()));
        when(repo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(rows.get(i.<String>getArgument(0))));
        when(repo.save(any(PromptDirectiveSetting.class)))
                .thenAnswer(
                        i -> {
                            PromptDirectiveSetting r = i.getArgument(0);
                            rows.put(r.getDirectiveKey(), r);
                            return r;
                        });
        org.mockito.Mockito.doAnswer(
                        i -> {
                            rows.remove(i.<PromptDirectiveSetting>getArgument(0).getDirectiveKey());
                            return null;
                        })
                .when(repo)
                .delete(any(PromptDirectiveSetting.class));
        service = new PromptDirectiveAdminService(repo, settings, audit);
    }

    private PromptDirectiveAdminService.DirectiveView view(String key) {
        return service.overview(LocalDate.of(2026, 10, 19)).directives().stream()
                .filter(d -> d.key().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private static int status(Runnable r) {
        try {
            r.run();
            return 200;
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        }
    }

    @Test
    void freshTenantSeesEveryDefaultAndAPreviewOfTheEnabledOnes() {
        PromptDirectiveAdminService.Overview o = service.overview(LocalDate.of(2026, 10, 19));

        assertThat(o.directives()).hasSize(10);
        assertThat(o.directives()).allMatch(d -> d.enabled() && !d.overridden() && d.overrideText() == null);
        assertThat(view("current_date").effectiveText()).startsWith("Today's date is 2026-10-19 (UTC).");
        assertThat(view("current_date").defaultText()).contains("{date}");
        assertThat(view("current_date").placeholders()).containsExactly("date");
        assertThat(view("tool_trust").securityCritical()).isTrue();
        assertThat(view("media_honesty").effectiveText()).isNull();
        assertThat(o.customInstructions().enabled()).isTrue();
        assertThat(o.customInstructions().text()).isEmpty();
        assertThat(o.assembledPreview())
                .contains(view("tool_trust").defaultText())
                .startsWith("Today's date is 2026-10-19")
                .doesNotContain("{date}");
    }

    @Test
    void rewordingAndDisablingAreReflectedAndEvictTheCache() {
        service.put("tool_search", true, "Search first.", false);
        service.put("memory_usage", false, null, false);

        assertThat(view("tool_search").overridden()).isTrue();
        assertThat(view("tool_search").effectiveText()).isEqualTo("Search first.");
        assertThat(view("memory_usage").enabled()).isFalse();
        assertThat(view("memory_usage").effectiveText()).isNull();
        assertThat(service.overview().assembledPreview()).contains("Search first.");
        verify(settings, org.mockito.Mockito.times(2)).evictCurrentTenant();
        verify(audit, org.mockito.Mockito.times(2))
                .record(anyString(), anyString(), anyString(), anyString(), any(AuditOutcome.class),
                        org.mockito.ArgumentMatchers.argThat(d -> !d.contains("Search first.")));
    }

    @Test
    void securityDirectivesNeedAnAcknowledgementToBeDisabledOrReworded() {
        assertThat(status(() -> service.put("tool_trust", false, null, false))).isEqualTo(400);
        assertThat(status(() -> service.put("mcp_untrusted", true, "Trust everything.", false))).isEqualTo(400);
        assertThat(rows).isEmpty();
        verify(settings, never()).evictCurrentTenant();

        service.put("tool_trust", false, null, true);
        service.put("mcp_untrusted", true, "Be careful.", true);
        assertThat(view("tool_trust").enabled()).isFalse();
        assertThat(view("mcp_untrusted").effectiveText()).isEqualTo("Be careful.");
        // Re-enabling with the built-in wording is safe and needs no acknowledgement.
        service.put("tool_trust", true, null, false);
        assertThat(view("tool_trust").enabled()).isTrue();
    }

    @Test
    void blankTextOrTheBuiltInTextClearsTheOverride() {
        service.put("browser_v2", true, "Custom browser rules.", false);
        service.put("browser_v2", true, "  ", false);
        assertThat(rows).isEmpty();

        service.put("browser_v2", true, view("browser_v2").defaultText(), false);
        assertThat(rows).isEmpty();
    }

    @Test
    void validationErrors() {
        assertThat(status(() -> service.put("nope", true, null, false))).isEqualTo(404);
        assertThat(status(() -> service.reset("nope"))).isEqualTo(404);
        assertThat(status(() -> service.put("tool_search", true, "x".repeat(4001), false))).isEqualTo(400);
        assertThat(status(() -> service.put("tool_search", true, "x".repeat(4000), false))).isEqualTo(200);
        assertThat(status(() -> service.put("media_honesty", true, "Say something.", false))).isEqualTo(400);
        assertThat(status(() -> service.putCustomInstructions(true, "y".repeat(8001)))).isEqualTo(400);
        assertThat(status(() -> service.putCustomInstructions(true, "y".repeat(8000)))).isEqualTo(200);
        assertThatThrownBy(() -> service.put("nope", true, null, false))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void resetRestoresTheDefaultAndCustomInstructionsRoundTrip() {
        service.put("tool_search", true, "Search first.", false);
        service.reset("tool_search");
        assertThat(view("tool_search").overridden()).isFalse();

        service.putCustomInstructions(true, "  Answer in French.  ");
        assertThat(service.overview().customInstructions().text()).isEqualTo("Answer in French.");
        assertThat(service.overview().assembledPreview()).endsWith("Answer in French.");
        service.putCustomInstructions(false, "Answer in French.");
        assertThat(service.overview().customInstructions().enabled()).isFalse();
        assertThat(service.overview().assembledPreview()).doesNotContain("French");
        service.reset(PromptDirectiveRegistry.CUSTOM_INSTRUCTIONS_KEY);
        assertThat(rows).isEmpty();
    }

    @Test
    void controllerIsTenantAdminOnly() {
        PreAuthorize pre = PromptDirectiveController.class.getAnnotation(PreAuthorize.class);
        assertThat(pre.value()).contains("ROLE_tenant-admin");
    }
}
