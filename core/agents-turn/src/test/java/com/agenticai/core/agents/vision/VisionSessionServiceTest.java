package com.agenticai.core.agents.vision;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link VisionSessionService#start}'s governance branches in their checked order (503 flag / 403
 * entitlement / 403 approval policy / 429 quota - the shared contract's status ladder), SNAPSHOT
 * deliberately skipping the continuous-mode gates, plus the end/attribution lifecycle. Mock style
 * per {@code ModelModalitiesServiceTest}/{@code PlanLimitsServiceTest}.
 */
class VisionSessionServiceTest {

    private static final String TENANT = "acme";
    private static final String USER = "user-1";
    private static final UUID AGENT_ID = UUID.randomUUID();

    private ValueOperations<String, String> valueOperations;
    private StringRedisTemplate redisTemplate;
    private ObjectMapper objectMapper;
    private VisionProperties properties;
    private VisionUsageService usageService;
    private TenantLimits tenantLimits;
    private ApprovalPolicyService approvalPolicyService;
    private VisionSessionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        objectMapper = com.agenticai.core.json.JsonMappers.plain();
        properties = new VisionProperties();
        usageService = mock(VisionUsageService.class);
        tenantLimits = mock(TenantLimits.class);
        approvalPolicyService = mock(ApprovalPolicyService.class);
        service =
                new VisionSessionService(
                        redisTemplate,
                        objectMapper,
                        properties,
                        usageService,
                        tenantLimits,
                        approvalPolicyService);

        // Baseline: platform on, paid tenant with the entitlement, approval
        // loosened, plenty of quota - individual tests tighten one gate each.
        when(tenantLimits.plan(TENANT)).thenReturn(TenantPlan.PREMIUM);
        when(tenantLimits.liveVisionAllowed(TENANT)).thenReturn(true);
        when(approvalPolicyService.requiresApproval(VisionSessionService.CAMERA_CAPTURE_ACTION))
                .thenReturn(false);
        when(usageService.framesRemaining(TENANT, USER, TenantPlan.PREMIUM)).thenReturn(500L);
    }

    // --- start(): the four gates, in order ---

    @Test
    void platformFlagOffIs503ForEveryModeBeforeAnyOtherCheck() {
        properties.setEnabled(false);

        assertThrows(
                VisionDisabledException.class,
                () ->
                        service.start(
                                TENANT,
                                USER,
                                AGENT_ID,
                                VisionSessionMode.SNAPSHOT,
                                VisionSource.CAMERA));
        // Nothing else consulted - the flag is the outermost gate.
        verify(tenantLimits, never()).plan(anyString());
    }

    @Test
    void watchWithoutTheEntitlementIs403() {
        when(tenantLimits.liveVisionAllowed(TENANT)).thenReturn(false);

        assertThrows(
                VisionNotAllowedForPlanException.class,
                () ->
                        service.start(
                                TENANT,
                                USER,
                                AGENT_ID,
                                VisionSessionMode.WATCH,
                                VisionSource.CAMERA));
        verify(approvalPolicyService, never()).requiresApproval(anyString());
    }

    @Test
    void liveWithoutTheEntitlementIs403Too() {
        when(tenantLimits.liveVisionAllowed(TENANT)).thenReturn(false);

        assertThrows(
                VisionNotAllowedForPlanException.class,
                () ->
                        service.start(
                                TENANT,
                                USER,
                                AGENT_ID,
                                VisionSessionMode.LIVE,
                                VisionSource.SCREEN));
    }

    @Test
    void watchWhileApprovalPolicyStillRequiresApprovalIsASynchronousRefusal() {
        // The out-of-the-box state: camera.capture.start sits in
        // DEFAULT_SENSITIVE_PATTERNS, so a fresh tenant gets this refusal
        // until an admin loosens the override (see the service javadoc for
        // why this can't park a pending ApprovalRequest instead).
        when(approvalPolicyService.requiresApproval(VisionSessionService.CAMERA_CAPTURE_ACTION))
                .thenReturn(true);

        VisionApprovalRequiredException e =
                assertThrows(
                        VisionApprovalRequiredException.class,
                        () ->
                                service.start(
                                        TENANT,
                                        USER,
                                        AGENT_ID,
                                        VisionSessionMode.WATCH,
                                        VisionSource.CAMERA));
        // The message names the exact policy row an admin needs to change.
        assertTrue(e.getMessage().contains("camera.capture.start"));
    }

    @Test
    void watchWithExhaustedMinuteWindowPropagatesThe429() {
        org.mockito.Mockito.doThrow(
                        new VisionQuotaExceededException("Daily watch-session limit reached"))
                .when(usageService)
                .checkWatchMinutesHeadroom(TENANT, USER, TenantPlan.PREMIUM);

        assertThrows(
                VisionQuotaExceededException.class,
                () ->
                        service.start(
                                TENANT,
                                USER,
                                AGENT_ID,
                                VisionSessionMode.WATCH,
                                VisionSource.CAMERA));
    }

    @Test
    void exhaustedFrameAllowanceIs429ForAnyMode() {
        when(usageService.framesRemaining(TENANT, USER, TenantPlan.PREMIUM)).thenReturn(0L);
        when(usageService.framesDailyCapFor(TenantPlan.PREMIUM)).thenReturn(500);

        assertThrows(
                VisionQuotaExceededException.class,
                () ->
                        service.start(
                                TENANT,
                                USER,
                                AGENT_ID,
                                VisionSessionMode.SNAPSHOT,
                                VisionSource.CAMERA));
    }

    @Test
    void snapshotSkipsTheContinuousModeGatesEntirely() {
        // Both continuous gates would block if consulted - SNAPSHOT must not
        // consult them (the user's own click is the approval for one frame).
        when(tenantLimits.liveVisionAllowed(TENANT)).thenReturn(false);
        when(approvalPolicyService.requiresApproval(anyString())).thenReturn(true);

        assertDoesNotThrow(
                () ->
                        service.start(
                                TENANT,
                                USER,
                                AGENT_ID,
                                VisionSessionMode.SNAPSHOT,
                                VisionSource.CAMERA));
        verify(tenantLimits, never()).liveVisionAllowed(anyString());
        verify(approvalPolicyService, never()).requiresApproval(anyString());
        verify(usageService, never()).checkWatchMinutesHeadroom(anyString(), anyString(), any());
    }

    @Test
    void happyPathPersistsTheRecordWith24hTtlAndReturnsTheAllowance() {
        VisionSessionService.StartResult result =
                service.start(TENANT, USER, AGENT_ID, VisionSessionMode.WATCH, VisionSource.SCREEN);

        assertNotNull(result.session().sessionId());
        assertEquals(500L, result.frameAllowanceRemaining());
        assertEquals(VisionSessionMode.WATCH, result.session().mode());
        assertEquals(VisionSource.SCREEN, result.session().source());
        assertEquals(0, result.session().framesAnalyzed());
        verify(valueOperations)
                .set(
                        eq("agentic:vision-session:" + result.session().sessionId()),
                        anyString(),
                        eq(Duration.ofHours(24)));
    }

    // --- recordFrames (chat-turn attribution) ---

    @Test
    void recordFramesBumpsTheStoredCounter() throws Exception {
        VisionSessionService.VisionSessionRecord stored =
                new VisionSessionService.VisionSessionRecord(
                        "sess-1",
                        TENANT,
                        USER,
                        AGENT_ID,
                        VisionSessionMode.WATCH,
                        VisionSource.CAMERA,
                        Instant.now().toEpochMilli(),
                        3);
        when(valueOperations.get("agentic:vision-session:sess-1"))
                .thenReturn(objectMapper.writeValueAsString(stored));

        service.recordFrames("sess-1", TENANT, 2);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations)
                .set(eq("agentic:vision-session:sess-1"), json.capture(), eq(Duration.ofHours(24)));
        VisionSessionService.VisionSessionRecord updated =
                objectMapper.readValue(
                        json.getValue(), VisionSessionService.VisionSessionRecord.class);
        assertEquals(5, updated.framesAnalyzed());
    }

    @Test
    void recordFramesIgnoresUnknownAndForeignSessionsSilently() throws Exception {
        // Unknown id - no write, no exception (quota was enforced upstream).
        when(valueOperations.get("agentic:vision-session:ghost")).thenReturn(null);
        assertDoesNotThrow(() -> service.recordFrames("ghost", TENANT, 1));

        // Another tenant's session - attribution refused, still no exception.
        VisionSessionService.VisionSessionRecord foreign =
                new VisionSessionService.VisionSessionRecord(
                        "sess-2",
                        "someone-else",
                        USER,
                        AGENT_ID,
                        VisionSessionMode.WATCH,
                        VisionSource.CAMERA,
                        Instant.now().toEpochMilli(),
                        0);
        when(valueOperations.get("agentic:vision-session:sess-2"))
                .thenReturn(objectMapper.writeValueAsString(foreign));
        assertDoesNotThrow(() -> service.recordFrames("sess-2", TENANT, 1));

        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    // --- end() ---

    @Test
    void endOfAnUnknownOrForeignSessionIs404() throws Exception {
        when(valueOperations.get("agentic:vision-session:ghost")).thenReturn(null);
        assertThrows(
                VisionSessionNotFoundException.class, () -> service.end("ghost", TENANT, USER));

        VisionSessionService.VisionSessionRecord foreign =
                new VisionSessionService.VisionSessionRecord(
                        "sess-2",
                        "someone-else",
                        USER,
                        AGENT_ID,
                        VisionSessionMode.SNAPSHOT,
                        VisionSource.CAMERA,
                        Instant.now().toEpochMilli(),
                        0);
        when(valueOperations.get("agentic:vision-session:sess-2"))
                .thenReturn(objectMapper.writeValueAsString(foreign));
        assertThrows(
                VisionSessionNotFoundException.class, () -> service.end("sess-2", TENANT, USER));
    }

    @Test
    void anotherMemberCannotEndSomeoneElsesSession() throws Exception {
        VisionSessionService.VisionSessionRecord stored =
                new VisionSessionService.VisionSessionRecord(
                        "sess-1",
                        TENANT,
                        USER,
                        AGENT_ID,
                        VisionSessionMode.WATCH,
                        VisionSource.CAMERA,
                        Instant.now().minusSeconds(70).toEpochMilli(),
                        3);
        when(valueOperations.get("agentic:vision-session:sess-1"))
                .thenReturn(objectMapper.writeValueAsString(stored));

        assertThrows(
                VisionSessionNotFoundException.class,
                () -> service.end("sess-1", TENANT, "user-2"));
        verify(redisTemplate, never()).delete("agentic:vision-session:sess-1");
        verify(usageService, never())
                .recordWatchMinutes(
                        anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void endReturnsStatsDeletesTheRecordAndBooksContinuousMinutesRoundedUp() throws Exception {
        VisionSessionService.VisionSessionRecord stored =
                new VisionSessionService.VisionSessionRecord(
                        "sess-1",
                        TENANT,
                        USER,
                        AGENT_ID,
                        VisionSessionMode.WATCH,
                        VisionSource.CAMERA,
                        Instant.now().minusSeconds(70).toEpochMilli(),
                        14);
        when(valueOperations.get("agentic:vision-session:sess-1"))
                .thenReturn(objectMapper.writeValueAsString(stored));

        VisionSessionService.EndResult result = service.end("sess-1", TENANT, USER);

        assertEquals("sess-1", result.sessionId());
        assertEquals(14, result.framesAnalyzed());
        assertTrue(result.durationSeconds() >= 70 && result.durationSeconds() < 75);
        verify(redisTemplate).delete("agentic:vision-session:sess-1");
        // 70-odd seconds rounds UP to 2 minutes - a short session is never free.
        verify(usageService).recordWatchMinutes(TENANT, USER, 2);
    }

    @Test
    void endOfASnapshotSessionBooksNoWatchMinutes() throws Exception {
        VisionSessionService.VisionSessionRecord stored =
                new VisionSessionService.VisionSessionRecord(
                        "sess-1",
                        TENANT,
                        USER,
                        AGENT_ID,
                        VisionSessionMode.SNAPSHOT,
                        VisionSource.CAMERA,
                        Instant.now().minusSeconds(30).toEpochMilli(),
                        1);
        when(valueOperations.get("agentic:vision-session:sess-1"))
                .thenReturn(objectMapper.writeValueAsString(stored));

        service.end("sess-1", TENANT, USER);

        verify(usageService, never())
                .recordWatchMinutes(
                        anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }
}
