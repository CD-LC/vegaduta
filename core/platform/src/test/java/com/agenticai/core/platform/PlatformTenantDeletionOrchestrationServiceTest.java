package com.agenticai.core.platform;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.codeintel.CodeIntelClient;
import com.agenticai.core.tenancy.TenantAlreadyErasedException;
import com.agenticai.core.tenancy.TenantDataErasureService;
import com.agenticai.core.tenancy.TenantErasureResult;
import com.agenticai.core.tenancy.TenantNotFoundByIdException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Failure semantics of the tenant-deletion sequence - deliberately ASYMMETRIC and previously
 * unverified. Step 1 ({@link TenantDataErasureService#eraseTenantById}) throwing must propagate AND
 * stop the best-effort step from ever running: core/tenancy has already recorded the FAILED outcome
 * at that point, so deleting code-intel directories would be acting on a deletion that did not
 * happen. Conversely, step 2 (code-intel directory delete) runs AFTER the tenant's core data is
 * already gone - a failure there has nothing left to protect by failing loudly, so it must be
 * swallowed (the caller still sees success). A regression in either direction is invisible in
 * normal operation, which is exactly why it is pinned here.
 *
 * <p>Also pins the argument contract that {@link TenantErasureResult}'s javadoc warns about:
 * code-intel is keyed by SCHEMA NAME (its on-disk {@code data/tenants/<tenant_id>/} directories
 * match what every other call site sends as {@code X-Tenant-Id}), so passing the tenant UUID would
 * silently orphan the tenant's indexed source code forever.
 *
 * <p>Plain JUnit 5 + Mockito, service constructed with {@code new} - the established convention
 * across core/platform and core/tenancy tests. No Spring context, no network.
 */
class PlatformTenantDeletionOrchestrationServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String SCHEMA = "acme";

    private TenantDataErasureService erasureService;
    private CodeIntelClient codeIntelClient;
    private PlatformTenantDeletionOrchestrationService service;

    @BeforeEach
    void setUp() {
        erasureService = mock(TenantDataErasureService.class);
        codeIntelClient = mock(CodeIntelClient.class);
        service =
                new PlatformTenantDeletionOrchestrationService(
                        erasureService, Optional.of(codeIntelClient));
    }

    /** Real record, not a mock - the happy path exercises the actual accessor contract. */
    private static TenantErasureResult result() {
        return new TenantErasureResult(SCHEMA);
    }

    // --- happy path + ordering -----------------------------------------------------------------

    @Test
    void happyPathErasesFirstThenDeletesCodeIntelData() {
        when(erasureService.eraseTenantById(TENANT_ID, "Acme", "admin-1")).thenReturn(result());

        service.deleteTenant(TENANT_ID, "Acme", "admin-1");

        // Erase-before-cleanup ordering is load-bearing: the schema name only
        // EXISTS in the erasure result, so any reordering would be working
        // from data that is no longer obtainable.
        InOrder order = inOrder(erasureService, codeIntelClient);
        order.verify(erasureService).eraseTenantById(TENANT_ID, "Acme", "admin-1");
        order.verify(codeIntelClient).deleteTenantData(SCHEMA);
    }

    @Test
    void confirmNameAndActorAreForwardedVerbatimToTheErasureService() {
        when(erasureService.eraseTenantById(TENANT_ID, "Exact Name Inc.", "admin-2"))
                .thenReturn(result());

        service.deleteTenant(TENANT_ID, "Exact Name Inc.", "admin-2");

        // The name-confirmation check and the erased_by tombstone both live in
        // core/tenancy - this layer must pass both through untouched, or the
        // safety check compares against the wrong string.
        verify(erasureService).eraseTenantById(TENANT_ID, "Exact Name Inc.", "admin-2");
    }

    // --- step 1 failures propagate and stop everything -----------------------------------------

    @Test
    void everyEraseFailureModePropagatesAndNeitherBestEffortCleanupStepEverRuns() {
        // One representative per documented eraseTenantById failure mode:
        // not-found, already-erased, confirm-name mismatch, schema-drop
        // failure. All must behave identically at this layer - rethrow, no
        // cleanup - so they are exercised uniformly against fresh mocks.
        List<RuntimeException> failures =
                List.of(
                        new TenantNotFoundByIdException(TENANT_ID),
                        new TenantAlreadyErasedException(TENANT_ID),
                        new IllegalArgumentException("Confirmation name does not match."),
                        new IllegalStateException("could not drop schema - open connections"));

        for (RuntimeException failure : failures) {
            TenantDataErasureService failingErasure = mock(TenantDataErasureService.class);
            CodeIntelClient codeIntel = mock(CodeIntelClient.class);
            PlatformTenantDeletionOrchestrationService failingService =
                    new PlatformTenantDeletionOrchestrationService(
                            failingErasure, Optional.of(codeIntel));
            when(failingErasure.eraseTenantById(TENANT_ID, "Acme", "admin-1")).thenThrow(failure);

            RuntimeException thrown =
                    assertThrows(
                            RuntimeException.class,
                            () -> failingService.deleteTenant(TENANT_ID, "Acme", "admin-1"));

            // The exact exception must surface (it drives the HTTP status
            // mapping), and the cleanup client must not have been touched -
            // core/tenancy already recorded FAILED; "cleaning up" here would
            // be acting on a deletion that did not happen.
            org.junit.jupiter.api.Assertions.assertSame(failure, thrown);
            verifyNoInteractions(codeIntel);
        }
    }

    // --- step 2 is best-effort -------------------------------------------------------------

    @Test
    void aFailingCodeIntelDeleteIsSwallowedSoTheCallerStillSeesSuccess() {
        when(erasureService.eraseTenantById(TENANT_ID, "Acme", "admin-1")).thenReturn(result());
        when(codeIntelClient.deleteTenantData(SCHEMA))
                .thenThrow(new RuntimeException("code-intel unreachable"));

        assertDoesNotThrow(() -> service.deleteTenant(TENANT_ID, "Acme", "admin-1"));
    }

    // --- optional/unconfigured dependencies are skipped cleanly --------------------------------

    @Test
    void anEmptyCodeIntelOptionalIsSkippedCleanlyOnADeploymentWithoutCodeIntel() {
        // code-intel.enabled=false means the bean simply does not exist -
        // Optional.empty() is the real wiring shape on such a deployment, not
        // a test-only contrivance.
        PlatformTenantDeletionOrchestrationService noCodeIntelService =
                new PlatformTenantDeletionOrchestrationService(erasureService, Optional.empty());
        when(erasureService.eraseTenantById(TENANT_ID, "Acme", "admin-1")).thenReturn(result());

        assertDoesNotThrow(() -> noCodeIntelService.deleteTenant(TENANT_ID, "Acme", "admin-1"));
    }

    @Test
    void codeIntelIsCalledWithTheSchemaNameNeverTheTenantUuid() {
        when(erasureService.eraseTenantById(TENANT_ID, "Acme", "admin-1")).thenReturn(result());

        service.deleteTenant(TENANT_ID, "Acme", "admin-1");

        // TenantErasureResult's javadoc contract: code-intel's on-disk
        // data/tenants/<tenant_id>/ directories are named by SCHEMA (what
        // every other call site sends as X-Tenant-Id), so passing the UUID
        // would delete nothing and silently orphan the tenant's indexed
        // source code forever.
        verify(codeIntelClient).deleteTenantData(SCHEMA);
        verify(codeIntelClient, never()).deleteTenantData(TENANT_ID.toString());
    }

    // --- demo-sandbox reclaim (2026-10-09) -----------------------------------------------------

    @Test
    void deleteDemoTenantUsesTheDemoErasurePathThenTheSameFollowUps() {
        when(erasureService.eraseDemoTenantById(TENANT_ID, "demo_shared", "admin-1"))
                .thenReturn(result());

        service.deleteDemoTenant(TENANT_ID, "demo_shared", "admin-1");

        verify(erasureService, never())
                .eraseTenantById(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        verify(codeIntelClient).deleteTenantData(SCHEMA);
    }

    @Test
    void deleteDemoTenantPropagatesARefusalAndRunsNoFollowUps() {
        when(erasureService.eraseDemoTenantById(TENANT_ID, "demo_shared", "admin-1"))
                .thenThrow(new IllegalArgumentException("not a demo sandbox"));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.deleteDemoTenant(TENANT_ID, "demo_shared", "admin-1"));

        verifyNoInteractions(codeIntelClient);
    }
}
