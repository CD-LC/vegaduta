package com.agenticai.core.brief;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenticai.core.brief.dto.ApplyDtos.ApplyResult;
import com.agenticai.core.brief.dto.ApplyDtos.TuneResult;
import com.agenticai.core.brief.dto.BriefDtos.VersionSummary;
import com.agenticai.core.brief.dto.TrustDtos.TrustChange;
import com.agenticai.core.brief.dto.TrustDtos.TrustProposal;
import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.security.TenantLimits;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * HTTP mapping of apply, versions, rollback, tune and trust in {@link BriefLifecycleController}.
 */
class BriefLifecycleControllerTest {

    private static final UUID ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID JOB = UUID.fromString("99999999-bbbb-cccc-dddd-eeeeeeeeeeee");

    private BriefApplyService apply;
    private BriefVersionService versions;
    private BriefTuneService tune;
    private TrustLadderService trust;
    private BriefRateLimiter limiter;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        apply = mock(BriefApplyService.class);
        versions = mock(BriefVersionService.class);
        tune = mock(BriefTuneService.class);
        trust = mock(TrustLadderService.class);
        limiter = mock(BriefRateLimiter.class);
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new BriefLifecycleController(
                                        apply,
                                        versions,
                                        tune,
                                        trust,
                                        limiter,
                                        mock(TenantLimits.class)))
                        .defaultRequest(get("/").accept(MediaType.APPLICATION_JSON))
                        .build();
    }

    private static ApplyResult result(String status) {
        return new ApplyResult(JOB, status, 2, null, null, List.of(), List.of());
    }

    @Test
    void aForegroundApplyReturnsTheFinishedResult() throws Exception {
        when(apply.apply(eq(ID), any())).thenReturn(result("SUCCEEDED"));

        mvc.perform(post("/api/briefs/" + ID + "/apply"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void aBackgroundApplyIsAcceptedWithAJobId() throws Exception {
        when(apply.apply(eq(ID), any())).thenReturn(result("RUNNING"));

        mvc.perform(
                        post("/api/briefs/" + ID + "/apply")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"background\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(JOB.toString()))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void anApplyJobCanBePolled() throws Exception {
        when(apply.job(ID, JOB)).thenReturn(result("RUNNING"));

        mvc.perform(get("/api/briefs/" + ID + "/apply/" + JOB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void versionsAreListedAndDiffed() throws Exception {
        when(versions.list(ID))
                .thenReturn(List.of(new VersionSummary(2, 1, "n", "t", Instant.now())));
        when(versions.diff(ID, 1, 2))
                .thenReturn(
                        new com.agenticai.core.brief.dto.ApplyDtos.VersionDiff(
                                1, 2, JsonNodeFactory.instance.arrayNode()));

        mvc.perform(get("/api/briefs/" + ID + "/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(2));
        mvc.perform(get("/api/briefs/" + ID + "/versions/diff").param("from", "1").param("to", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.to").value(2));
    }

    @Test
    void rollbackIsSynchronousUnlessBackgroundIsRequested() throws Exception {
        when(versions.rollback(ID, 1, false)).thenReturn(result("SUCCEEDED"));
        when(versions.rollback(ID, 1, true)).thenReturn(result("RUNNING"));

        mvc.perform(post("/api/briefs/" + ID + "/versions/1/rollback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        mvc.perform(
                        post("/api/briefs/" + ID + "/versions/1/rollback")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"background\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(JOB.toString()));
    }

    @Test
    void tuneCountsAgainstThePlanLimitAndReturnsTheProposal() throws Exception {
        Brief brief = BriefFixtures.valid().build();
        when(tune.tune(ID, "rename it"))
                .thenReturn(new TuneResult(brief, JsonNodeFactory.instance.arrayNode(), "done", 3));

        mvc.perform(
                        post("/api/briefs/" + ID + "/tune")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"instruction\":\"rename it\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseVersion").value(3))
                .andExpect(jsonPath("$.narrative").value("done"));
        verify(limiter).assertPlanCallAllowed(any(), any());
    }

    @Test
    void tuneOverTheLimitIs429() throws Exception {
        doThrow(new BriefRateLimitException("limit"))
                .when(limiter)
                .assertPlanCallAllowed(any(), any());

        mvc.perform(
                        post("/api/briefs/" + ID + "/tune")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"instruction\":\"x\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void trustProposalAndRungChangeAreMapped() throws Exception {
        when(trust.proposal(ID))
                .thenReturn(
                        new TrustProposal(
                                true,
                                "UNDER_WATCH",
                                "TRUSTED",
                                List.of(),
                                JsonNodeFactory.instance.arrayNode(),
                                List.of()));
        when(trust.setRung(ID, TrustRung.TRUSTED))
                .thenReturn(
                        new TrustChange(
                                "PROMOTION", "UNDER_WATCH", "TRUSTED", 4, List.of(), List.of()));

        mvc.perform(post("/api/briefs/" + ID + "/trust/proposal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetRung").value("TRUSTED"));
        mvc.perform(post("/api/briefs/" + ID + "/trust/TRUSTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toRung").value("TRUSTED"));
    }

    @Test
    void anUnknownRungIs422AndMissingAndConflictingBriefsMapToTheirStatuses() throws Exception {
        mvc.perform(post("/api/briefs/" + ID + "/trust/GODMODE"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.section").value("rung"));

        when(trust.proposal(ID)).thenThrow(new BriefNotFoundException(ID));
        mvc.perform(post("/api/briefs/" + ID + "/trust/proposal")).andExpect(status().isNotFound());

        when(apply.job(ID, JOB)).thenThrow(new BriefConflictException("busy"));
        mvc.perform(get("/api/briefs/" + ID + "/apply/" + JOB))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("busy"));
    }
}
