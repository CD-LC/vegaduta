package com.agenticai.core.prompts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.server.ResponseStatusException;

class PromptAdminControllerTest {

    private final PromptOverrideService service = mock(PromptOverrideService.class);
    private final PromptAdminController controller = new PromptAdminController(service, new PromptProperties());

    @Test
    void theApiIsTenantAdminOnly() {
        PreAuthorize pre = PromptAdminController.class.getAnnotation(PreAuthorize.class);
        assertThat(pre.value()).contains("ROLE_tenant-admin").contains("ROLE_platform-admin");
    }

    @Test
    void aPutBecomesAWriteAtTheScopeInThePath() {
        UUID agent = UUID.randomUUID();
        controller.put("a.b", "agent", agent, "de", new PromptAdminController.OverrideRequest("text", null, true, "why", 4));
        ArgumentCaptor<PromptOverrideService.Write> w = ArgumentCaptor.forClass(PromptOverrideService.Write.class);
        verify(service).put(w.capture());
        assertThat(w.getValue())
                .isEqualTo(new PromptOverrideService.Write(PromptScope.AGENT, agent, "a.b", "de", "text", true, true, "why", 4));
        controller.put("a.b", "tenant", null, null, new PromptAdminController.OverrideRequest(null, false, false, null, null));
        verify(service, org.mockito.Mockito.times(2)).put(any());
    }

    @Test
    void aKeyWithDotsIsOneSegmentOfThePathAndTheScopeAndAgentAreRead() throws Exception {
        org.springframework.test.web.servlet.MockMvc mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        UUID agent = UUID.randomUUID();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/prompts/directive.tool_trust.compact"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        verify(service).get("directive.tool_trust.compact", null, null);

        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                        "/api/prompts/directive.tool_search/overrides/agent?agentId=" + agent + "&locale=de")
                                .contentType("application/json")
                                .content("{\"text\":\"Search first.\",\"acknowledgeRisk\":false}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        ArgumentCaptor<PromptOverrideService.Write> w = ArgumentCaptor.forClass(PromptOverrideService.Write.class);
        verify(service).put(w.capture());
        assertThat(w.getValue().key()).isEqualTo("directive.tool_search");
        assertThat(w.getValue().scope()).isEqualTo(PromptScope.AGENT);
        assertThat(w.getValue().agentId()).isEqualTo(agent);
        assertThat(w.getValue().locale()).isEqualTo("de");
        assertThat(w.getValue().text()).isEqualTo("Search first.");
        assertThat(w.getValue().enabled()).isTrue();
    }

    @Test
    void anUnknownScopeIsA400() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> controller.revert("a.b", "galaxy", null, null, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("scope must be one of");
    }

    private static java.util.List<PromptOverrideService.PromptView> views(int n) {
        java.util.List<PromptOverrideService.PromptView> out = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new PromptOverrideService.PromptView(null, "default " + i, null, java.util.List.of()));
        }
        return out;
    }

    @Test
    void theListIsWindowedByLimitAndOffsetAndSaysHowManyThereAre() {
        org.mockito.Mockito.when(service.list(null, null, null, null)).thenReturn(views(5));

        var page = controller.list(null, null, null, null, 2, 1L);

        assertThat(page.getBody()).extracting(PromptOverrideService.PromptView::defaultText).containsExactly("default 1", "default 2");
        assertThat(page.getHeaders().getFirst("X-Total-Count")).isEqualTo("5");
        assertThat(page.getHeaders().getFirst("X-Has-More")).isEqualTo("true");
    }

    @Test
    void aListWithoutALimitIsTheWholeRegistryAsBefore() {
        org.mockito.Mockito.when(service.list(null, null, null, null)).thenReturn(views(5));

        var page = controller.list(null, null, null, null, null, null);

        assertThat(page.getBody()).hasSize(5);
        assertThat(page.getHeaders().getFirst("X-Total-Count")).isEqualTo("5");
        assertThat(page.getHeaders().getFirst("X-Has-More")).isEqualTo("false");
    }

    @Test
    void aLimitIsClampedToTheConfiguredMaximumAndAnOffsetPastTheEndIsAnEmptyPage() {
        PromptProperties properties = new PromptProperties();
        properties.setListMaxLimit(3);
        PromptAdminController capped = new PromptAdminController(service, properties);
        org.mockito.Mockito.when(service.list(null, null, null, null)).thenReturn(views(5));

        assertThat(capped.list(null, null, null, null, 100, 0L).getBody()).hasSize(3);
        assertThat(capped.list(null, null, null, null, 0, 0L).getBody()).hasSize(1);
        var past = capped.list(null, null, null, null, 2, 9_999_999_999L);
        assertThat(past.getBody()).isEmpty();
        assertThat(past.getHeaders().getFirst("X-Total-Count")).isEqualTo("5");
        assertThat(capped.list(null, null, null, null, 2, -4L).getBody()).hasSize(2);
    }

    @Test
    void theListLimitCeilingDefaultsToTheDocumentedOne() {
        PromptProperties properties = new PromptProperties();
        assertThat(properties.getListMaxLimit()).isEqualTo(500);
        properties.setListMaxLimit(0);
        assertThat(properties.getListMaxLimit()).isEqualTo(500);
    }
}
