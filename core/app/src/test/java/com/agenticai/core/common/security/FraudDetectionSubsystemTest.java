package com.agenticai.core.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import java.net.InetAddress;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * JAVA-005: the fraud subsystem is OFF unless {@code app.fraud-detection.enabled=true}; when on, it
 * resolves the tenant from {@link TenantContext} (not a request attribute nothing sets) and a
 * failed audit/blacklist write can no longer turn a BLOCK into an ALLOW.
 */
class FraudDetectionSubsystemTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            FraudDetectionConfig.class,
                            FraudDetectionInterceptor.class,
                            AbuseDetectionService.class,
                            AbuseEventRecorder.class,
                            FraudAnalysisController.class)
                    .withBean(ObjectMapper.class, ObjectMapper::new)
                    .withBean(AbuseEventRepository.class, () -> mock(AbuseEventRepository.class))
                    .withBean(IpBlacklistRepository.class, () -> mock(IpBlacklistRepository.class))
                    .withBean(
                            UserBlacklistRepository.class,
                            () -> mock(UserBlacklistRepository.class))
                    .withBean(
                            ChallengeNonceRepository.class,
                            () -> mock(ChallengeNonceRepository.class));

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void disabledByDefault() {
        runner.run(
                ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(FraudDetectionInterceptor.class);
                    assertThat(ctx).doesNotHaveBean(AbuseDetectionService.class);
                    assertThat(ctx).doesNotHaveBean(FraudAnalysisController.class);
                    assertThat(ctx).doesNotHaveBean(HCaptchaClient.class);
                    assertThat(ctx)
                            .doesNotHaveBean(org.springframework.web.client.RestClient.class);
                });
    }

    @Test
    void enabledWiresTheSubsystemWithoutExposingAGenericRestClientBean() {
        runner.withPropertyValues("app.fraud-detection.enabled=true")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            assertThat(ctx).hasSingleBean(FraudDetectionInterceptor.class);
                            assertThat(ctx).hasSingleBean(AbuseDetectionService.class);
                            assertThat(ctx).hasSingleBean(HCaptchaClient.class);
                            // JAVA-034: the hCaptcha RestClient is private to its client.
                            assertThat(ctx)
                                    .doesNotHaveBean(
                                            org.springframework.web.client.RestClient.class);
                        });
    }

    @Test
    void interceptorResolvesTheTenantFromTenantContextAndEnforcesBlock() throws Exception {
        AbuseDetectionService service = mock(AbuseDetectionService.class);
        when(service.analyzeRequest(any(), any())).thenReturn(FraudDecision.BLOCK);
        FraudDetectionInterceptor interceptor =
                new FraudDetectionInterceptor(
                        service, com.agenticai.core.json.JsonMappers.plain(), true);
        UUID tenant = UUID.randomUUID();
        TenantContext.set(
                TenantContext.of(tenant.toString(), UUID.randomUUID().toString(), Set.of()));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/agents");
        MockHttpServletResponse res = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(req, res, new Object())).isFalse();
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getHeader("X-Fraud-Status")).isEqualTo("BLOCKED");
    }

    @Test
    void interceptorSkipsTenantlessRequestsWithoutCallingTheService() throws Exception {
        AbuseDetectionService service = mock(AbuseDetectionService.class);
        FraudDetectionInterceptor interceptor =
                new FraudDetectionInterceptor(
                        service, com.agenticai.core.json.JsonMappers.plain(), true);
        assertThat(
                        interceptor.preHandle(
                                new MockHttpServletRequest("POST", "/api/agents"),
                                new MockHttpServletResponse(),
                                new Object()))
                .isTrue();
        verifyNoInteractions(service);
    }

    @Test
    void nonUuidTenantIdsMapToAStableUuid() {
        assertThat(FraudTenantIds.toUuid("tenant_acme"))
                .isEqualTo(FraudTenantIds.toUuid("tenant_acme"));
        UUID real = UUID.randomUUID();
        assertThat(FraudTenantIds.toUuid(real.toString())).isEqualTo(real);
    }

    @Test
    void blacklistedIpStaysBlockedWhenTheAuditWriteFails() throws Exception {
        IpBlacklistRepository ips = mock(IpBlacklistRepository.class);
        AbuseEventRecorder recorder = mock(AbuseEventRecorder.class);
        AbuseDetectionService service = service(ips, recorder);
        UUID tenant = UUID.randomUUID();
        InetAddress ip = InetAddress.getByName("203.0.113.7");
        when(ips.isBlacklisted(tenant, ip)).thenReturn(true);
        doThrow(new IllegalStateException("read-only transaction"))
                .when(recorder)
                .recordEvent(any(), any(), any(), any());

        FraudDecision decision =
                service.analyzeRequest(
                        new ClientContext(tenant, ip, "Mozilla/5.0", null, null, null),
                        new RequestContext("/api/agents", "POST", null));

        assertThat(decision).isEqualTo(FraudDecision.BLOCK);
    }

    @Test
    void bruteForceStaysBlockedWhenTheAutoBlacklistWriteFails() throws Exception {
        AbuseEventRecorder recorder = mock(AbuseEventRecorder.class);
        AbuseDetectionService service = service(mock(IpBlacklistRepository.class), recorder);
        doThrow(new IllegalStateException("db down"))
                .when(recorder)
                .blacklistIp(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
        ClientContext client =
                new ClientContext(
                        UUID.randomUUID(),
                        InetAddress.getByName("203.0.113.8"),
                        "Mozilla/5.0",
                        null,
                        null,
                        null);
        RequestContext login = new RequestContext("/api/auth/login", "POST", null);

        FraudDecision last = FraudDecision.ALLOW;
        for (int i = 0; i < 10; i++) {
            last = service.analyzeRequest(client, login);
        }

        assertThat(last).isEqualTo(FraudDecision.BLOCK);
    }

    private static AbuseDetectionService service(
            IpBlacklistRepository ips, AbuseEventRecorder recorder) {
        AbuseDetectionService service =
                new AbuseDetectionService(
                        ips,
                        mock(UserBlacklistRepository.class),
                        mock(ChallengeNonceRepository.class),
                        mock(HCaptchaClient.class),
                        recorder);
        ReflectionTestUtils.setField(service, "authMaxAttempts", 5);
        ReflectionTestUtils.setField(service, "authBlockThreshold", 10);
        ReflectionTestUtils.setField(service, "apiSoftLimit", 60);
        ReflectionTestUtils.setField(service, "apiHardLimit", 100);
        ReflectionTestUtils.setField(service, "vpnScoreThreshold", 0.7);
        ReflectionTestUtils.setField(service, "autoBlockDurationMinutes", 60);
        return service;
    }
}
