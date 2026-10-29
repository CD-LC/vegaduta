package com.agenticai.core.tenancy.demo;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link DemoConversionService}: the conversion seam between a demo sandbox and a real signup.
 * Covers {@code linkConversion}'s junk/already-converted/fresh paths, {@code copyDemoArtifacts}'
 * schema-resolution, missing-copier, empty-workspace, partial-failure and exception branches, and
 * the "registration bookkeeping must never fail a succeeded signup" catch-everything contract of
 * {@code recordRegistrationConversion}.
 */
class DemoConversionServiceTest {

    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID DEMO_TENANT_ID = UUID.randomUUID();
    private static final UUID REAL_TENANT_ID = UUID.randomUUID();

    private DemoSessionRepository sessionRepository;
    private DemoSessionRecorder recorder;
    private TenantRepository tenantRepository;
    private DemoArtifactCopier copier;
    private DemoConversionService service;

    private DemoSession session;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(DemoSessionRepository.class);
        recorder = mock(DemoSessionRecorder.class);
        tenantRepository = mock(TenantRepository.class);
        copier = mock(DemoArtifactCopier.class);
        service =
                new DemoConversionService(
                        sessionRepository, recorder, tenantRepository, Optional.of(copier));

        session = mock(DemoSession.class);
        when(session.getId()).thenReturn(SESSION_ID);
        when(session.getTenantId()).thenReturn(DEMO_TENANT_ID);
        when(session.getConvertedTenantId()).thenReturn(null);
        when(sessionRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.of(session));
    }

    @Test
    void linkConversionRejectsMissingJunkOrNullInputsWithoutTouchingTheStore() {
        assertFalse(service.linkConversion(null, REAL_TENANT_ID));
        assertFalse(service.linkConversion("   ", REAL_TENANT_ID));
        assertFalse(service.linkConversion("token", null));

        verify(sessionRepository, never()).findByTokenHash(anyString());
        verify(recorder, never()).recordConversion(any(), any(), any());
    }

    @Test
    void linkConversionReturnsFalseForAnUnknownToken() {
        when(sessionRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertFalse(service.linkConversion("bogus", REAL_TENANT_ID));
        verify(recorder, never()).recordConversion(any(), any(), any());
    }

    @Test
    void linkConversionReturnsFalseWhenTheSessionWasAlreadyConverted() {
        // Idempotency: a signup retry or webhook redelivery must not record -
        // or copy - twice.
        when(session.getConvertedTenantId()).thenReturn(UUID.randomUUID());

        assertFalse(service.linkConversion("token", REAL_TENANT_ID));
        verify(recorder, never()).recordConversion(any(), any(), any());
    }

    @Test
    void linkConversionRecordsAFreshConversion() {
        assertTrue(service.linkConversion("token", REAL_TENANT_ID));

        verify(recorder).recordConversion(eq(SESSION_ID), eq(REAL_TENANT_ID), any());
    }

    @Test
    void recordRegistrationConversionDoesNothingForAJunkToken() {
        when(sessionRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        service.recordRegistrationConversion("junk", REAL_TENANT_ID);

        verify(recorder, never()).recordConversion(any(), any(), any());
        verify(copier, never()).copyAgents(anyString(), anyString(), anyString());
    }

    @Test
    void recordRegistrationConversionCopiesAgentsAfterAFreshLink() {
        Tenant demoTenant = tenantWithSchema("demo_schema");
        Tenant realTenant = tenantWithSchema("real_schema");
        when(tenantRepository.findById(DEMO_TENANT_ID)).thenReturn(Optional.of(demoTenant));
        when(tenantRepository.findById(REAL_TENANT_ID)).thenReturn(Optional.of(realTenant));
        when(copier.copyAgents("demo_schema", "real_schema", "demo:" + SESSION_ID))
                .thenReturn(new DemoArtifactCopier.Outcome(1, 0, 1));

        service.recordRegistrationConversion("token", REAL_TENANT_ID);

        verify(recorder).recordConversion(eq(SESSION_ID), eq(REAL_TENANT_ID), any());
        verify(copier).copyAgents("demo_schema", "real_schema", "demo:" + SESSION_ID);
    }

    @Test
    void recordRegistrationConversionSwallowsEveryFailure() {
        when(sessionRepository.findByTokenHash(anyString()))
                .thenThrow(new RuntimeException("control plane down"));

        assertDoesNotThrow(() -> service.recordRegistrationConversion("token", REAL_TENANT_ID));
    }

    @Test
    void copyDemoArtifactsFailsQuietlyWhenASchemaCannotBeResolved() {
        when(tenantRepository.findById(any())).thenReturn(Optional.empty());

        DemoConversionService.CopyResult result =
                service.copyDemoArtifacts(DEMO_TENANT_ID, SESSION_ID, REAL_TENANT_ID);

        assertFalse(result.copied());
        assertEquals(0, result.copiedAgents());
        assertTrue(result.detail().contains("could not be resolved"));
        verify(copier, never()).copyAgents(anyString(), anyString(), anyString());
    }

    @Test
    void copyDemoArtifactsReportsANoOpWhenNoCopierIsOnTheClasspath() {
        DemoConversionService withoutCopier =
                new DemoConversionService(
                        sessionRepository, recorder, tenantRepository, Optional.empty());
        when(tenantRepository.findById(DEMO_TENANT_ID))
                .thenReturn(Optional.of(tenantWithSchema("demo_schema")));
        when(tenantRepository.findById(REAL_TENANT_ID))
                .thenReturn(Optional.of(tenantWithSchema("real_schema")));

        DemoConversionService.CopyResult result =
                withoutCopier.copyDemoArtifacts(DEMO_TENANT_ID, SESSION_ID, REAL_TENANT_ID);

        assertFalse(result.copied());
        assertTrue(result.detail().contains("not available"));
    }

    @Test
    void copyDemoArtifactsReportsAnEmptyDemoWorkspace() {
        when(tenantRepository.findById(DEMO_TENANT_ID))
                .thenReturn(Optional.of(tenantWithSchema("demo_schema")));
        when(tenantRepository.findById(REAL_TENANT_ID))
                .thenReturn(Optional.of(tenantWithSchema("real_schema")));
        when(copier.copyAgents(anyString(), anyString(), anyString()))
                .thenReturn(new DemoArtifactCopier.Outcome(0, 0, 0));

        DemoConversionService.CopyResult result =
                service.copyDemoArtifacts(DEMO_TENANT_ID, SESSION_ID, REAL_TENANT_ID);

        assertFalse(result.copied());
        assertTrue(result.detail().contains("no agents"));
    }

    @Test
    void copyDemoArtifactsReportsPartialAndFullSuccess() {
        when(tenantRepository.findById(DEMO_TENANT_ID))
                .thenReturn(Optional.of(tenantWithSchema("demo_schema")));
        when(tenantRepository.findById(REAL_TENANT_ID))
                .thenReturn(Optional.of(tenantWithSchema("real_schema")));
        when(copier.copyAgents(anyString(), anyString(), anyString()))
                .thenReturn(new DemoArtifactCopier.Outcome(1, 1, 2));

        DemoConversionService.CopyResult partial =
                service.copyDemoArtifacts(DEMO_TENANT_ID, SESSION_ID, REAL_TENANT_ID);

        assertTrue(partial.copied());
        assertEquals(1, partial.copiedAgents());
        assertTrue(partial.detail().contains("1 failed"));

        when(copier.copyAgents(anyString(), anyString(), anyString()))
                .thenReturn(new DemoArtifactCopier.Outcome(2, 0, 2));

        DemoConversionService.CopyResult full =
                service.copyDemoArtifacts(DEMO_TENANT_ID, SESSION_ID, REAL_TENANT_ID);

        assertTrue(full.copied());
        assertEquals(2, full.copiedAgents());
        assertEquals("Copied 2 agent(s).", full.detail());
    }

    @Test
    void copyDemoArtifactsTreatsACopierExceptionAsCopiedFalse() {
        when(tenantRepository.findById(DEMO_TENANT_ID))
                .thenReturn(Optional.of(tenantWithSchema("demo_schema")));
        when(tenantRepository.findById(REAL_TENANT_ID))
                .thenReturn(Optional.of(tenantWithSchema("real_schema")));
        when(copier.copyAgents(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("demo schema already purged"));

        DemoConversionService.CopyResult result =
                service.copyDemoArtifacts(DEMO_TENANT_ID, SESSION_ID, REAL_TENANT_ID);

        assertFalse(result.copied());
        assertTrue(result.detail().contains("Artifact copy failed"));
    }

    private static Tenant tenantWithSchema(String schemaName) {
        return new Tenant(schemaName, null, schemaName, "agentic-ai", null, null);
    }
}
