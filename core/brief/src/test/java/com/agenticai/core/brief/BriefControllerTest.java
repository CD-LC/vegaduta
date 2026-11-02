package com.agenticai.core.brief;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenticai.core.brief.BriefValidationResult.Issue;
import com.agenticai.core.brief.dto.BriefDtos.CreateResponse;
import com.agenticai.core.brief.dto.BriefDtos.ListItem;
import com.agenticai.core.brief.dto.BriefDtos.UpdateResponse;
import com.agenticai.core.brief.dto.PlanResult;
import com.agenticai.core.brief.dto.ReadinessRow;
import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.security.TenantLimits;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** HTTP mapping, status codes and error bodies of {@link BriefController}. */
class BriefControllerTest {

    private static final UUID ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private final ObjectMapper mapper = new ObjectMapper();
    private BriefPlannerService planner;
    private BriefReadinessService readiness;
    private BriefService service;
    private BriefRateLimiter limiter;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        planner = mock(BriefPlannerService.class);
        readiness = mock(BriefReadinessService.class);
        service = mock(BriefService.class);
        limiter = mock(BriefRateLimiter.class);
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new BriefController(
                                        planner,
                                        readiness,
                                        service,
                                        limiter,
                                        mock(TenantLimits.class)))
                        .defaultRequest(get("/").accept(MediaType.APPLICATION_JSON))
                        .build();
    }

    private String body(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }

    private ListItem item(String title) {
        return new ListItem(ID, title, "UNDER_WATCH", "DRAFT", 1, null, null, null, Instant.now());
    }

    @Test
    void planReturnsTheBriefWithLiveReadinessAndWarnings() throws Exception {
        Brief brief = BriefFixtures.valid().build();
        when(planner.plan(any())).thenReturn(new PlanResult(brief, List.of("dropped a tool")));
        when(readiness.compute(brief)).thenReturn(List.of());

        mvc.perform(
                        post("/api/briefs/plan")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content("{\"prompt\":\"chase invoices\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brief.title").value("Invoice chaser"))
                .andExpect(jsonPath("$.warnings[0]").value("dropped a tool"));
        verify(limiter).assertPlanCallAllowed(any(), any());
    }

    @Test
    void aBlankPromptIsABadRequest() throws Exception {
        mvc.perform(
                        post("/api/briefs/plan")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content("{\"prompt\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exceedingThePlanLimitIsATooManyRequestsWithAnErrorBody() throws Exception {
        doThrow(new BriefRateLimitException("daily limit reached"))
                .when(limiter)
                .assertPlanCallAllowed(any(), any());

        mvc.perform(
                        post("/api/briefs/plan")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.TEXT_EVENT_STREAM)
                                .content("{\"prompt\":\"x\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("daily limit reached"))
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    void createReturns201WithTheNewVersion() throws Exception {
        when(service.create(any(), eq("first"))).thenReturn(new CreateResponse(ID, 1, "DRAFT"));

        mvc.perform(
                        post("/api/briefs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"brief\":"
                                                + body(BriefFixtures.valid().build())
                                                + ",\"note\":\"first\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void aValidationFailureIs422WithTheFirstSectionNamed() throws Exception {
        when(service.create(any(), any()))
                .thenThrow(new BriefValidationException(List.of(new Issue("agents[a1]", "bad"))));

        mvc.perform(
                        post("/api/briefs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"brief\":" + body(BriefFixtures.valid().build()) + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.section").value("agents[a1]"))
                .andExpect(jsonPath("$.errors[0].message").value("bad"));
    }

    @Test
    void listUsesTheEntityFilterWhenGivenAndThePlainListOtherwise() throws Exception {
        when(service.list()).thenReturn(List.of(item("all")));
        when(service.listBoundTo("agent-1")).thenReturn(List.of(item("bound")));

        mvc.perform(get("/api/briefs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("all"));
        mvc.perform(get("/api/briefs").param("entityId", "agent-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("bound"));
        mvc.perform(get("/api/briefs").param("entityId", "  "))
                .andExpect(jsonPath("$[0].title").value("all"));
    }

    @Test
    void getAnUnknownBriefIs404() throws Exception {
        when(service.get(ID)).thenThrow(new BriefNotFoundException(ID));

        mvc.perform(get("/api/briefs/" + ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void updateReturnsTheNewVersionAndAConflictIs409() throws Exception {
        when(service.update(eq(ID), any(), any())).thenReturn(new UpdateResponse(ID, 2, "DRAFT"));
        mvc.perform(
                        put("/api/briefs/" + ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"brief\":" + body(BriefFixtures.valid().build()) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        when(service.update(eq(ID), any(), any())).thenThrow(new BriefConflictException("applied"));
        mvc.perform(
                        put("/api/briefs/" + ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"brief\":" + body(BriefFixtures.valid().build()) + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("applied"));
    }

    @Test
    void readinessReturnsTheRowsAndTheirSummary() throws Exception {
        List<ReadinessRow> rows = List.of();
        when(service.readiness(ID)).thenReturn(rows);
        when(readiness.summarize(rows)).thenReturn(Map.of("ready", 0));

        mvc.perform(post("/api/briefs/" + ID + "/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.ready").value(0));
    }

    @Test
    void deleteHonoursThePurgeFlagFromTheQueryOrTheBody() throws Exception {
        mvc.perform(delete("/api/briefs/" + ID)).andExpect(status().isNoContent());
        verify(service).delete(ID, false);

        mvc.perform(delete("/api/briefs/" + ID).param("purgeEntities", "true"))
                .andExpect(status().isNoContent());
        verify(service).delete(ID, true);

        UUID other = UUID.randomUUID();
        mvc.perform(
                        delete("/api/briefs/" + other)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"purgeEntities\":true}"))
                .andExpect(status().isNoContent());
        verify(service).delete(other, true);
    }

    private String streamBody() throws Exception {
        org.springframework.test.web.servlet.MvcResult started =
                mvc.perform(
                                post("/api/briefs/plan")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .accept(MediaType.TEXT_EVENT_STREAM)
                                        .content("{\"prompt\":\"chase invoices\"}"))
                        .andExpect(request().asyncStarted())
                        .andReturn();
        long deadline = System.currentTimeMillis() + 5000;
        while (!started.getResponse().getContentAsString().contains("event:done")
                && !started.getResponse().getContentAsString().contains("event:error")
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        return started.getResponse().getContentAsString();
    }

    @Test
    void theStreamingPlanSendsThinkingSectionAndDoneEvents() throws Exception {
        Brief brief = BriefFixtures.valid().build();
        when(planner.plan(any(), any()))
                .thenAnswer(
                        inv -> {
                            BriefPlannerService.PlanListener l = inv.getArgument(1);
                            l.thinking("skeleton");
                            l.section("agents", List.of("a1"));
                            return new PlanResult(brief, List.of("w"));
                        });
        when(readiness.compute(brief)).thenReturn(List.of());

        String body = streamBody();

        org.assertj.core.api.Assertions.assertThat(body)
                .contains("event:thinking")
                .contains("skeleton")
                .contains("event:section")
                .contains("event:done")
                .contains("Invoice chaser");
    }

    @Test
    void aValidationFailureWhileStreamingBecomesAnErrorEvent() throws Exception {
        when(planner.plan(any(), any()))
                .thenThrow(new BriefValidationException("prompt", "too vague"));

        org.assertj.core.api.Assertions.assertThat(streamBody())
                .contains("event:error")
                .contains("too vague")
                .contains("422");
    }

    @Test
    void anUnexpectedFailureWhileStreamingIsAGenericErrorEvent() throws Exception {
        when(planner.plan(any(), any())).thenThrow(new IllegalStateException("secret detail"));

        String body = streamBody();

        org.assertj.core.api.Assertions.assertThat(body)
                .contains("event:error")
                .contains("Planning failed")
                .doesNotContain("secret detail");
    }
}
