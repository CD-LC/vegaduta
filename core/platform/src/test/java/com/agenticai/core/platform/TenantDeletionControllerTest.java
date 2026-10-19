package com.agenticai.core.platform;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.MasterKeyInvalidException;
import com.agenticai.core.tenancy.MasterKeyMissingException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code X-Master-Key} gate on {@code DELETE /api/platform/tenants/{id}} -
 * the platform's ONLY irreversible destructive endpoint. The role check
 * ({@code SecurityConfig}'s blanket {@code /api/platform/**} rule) is the
 * first gate; this controller's own master-key check is the second,
 * independent one, and it had zero coverage before this class. The two
 * regressions this suite exists to catch: (1) a deployment that forgot to set
 * {@code PLATFORM_MASTER_KEY} silently leaving tenant deletion wide open
 * behind the role check alone - the blank-secret path must fail CLOSED, and
 * (2) any rejection path leaking through to {@link
 * PlatformTenantDeletionOrchestrationService}, which would erase a tenant on
 * a request that failed authentication. Every rejection test therefore ends
 * in {@code verifyNoInteractions(orchestrationService)}.
 *
 * <p>Controller constructed with {@code new} and its method called directly -
 * the same plain-unit-test convention {@code CompGrantControllerTest} /
 * {@code AccountControllerTest} (core/tenancy) established; no MockMvc or
 * Spring context anywhere in these modules' tests. The real (not mocked)
 * {@link TenantContext} static ThreadLocal is used, with an
 * {@code @AfterEach} clear so nothing leaks across tests in the same fork.
 */
class TenantDeletionControllerTest {

    private static final String MASTER_KEY = "correct-master-key-123";
    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private PlatformTenantDeletionOrchestrationService orchestrationService;
    private TenantDeletionController controller;

    @BeforeEach
    void setUp() {
        orchestrationService = mock(PlatformTenantDeletionOrchestrationService.class);
        controller = new TenantDeletionController(orchestrationService, MASTER_KEY);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private TenantDeletionController.DeleteTenantRequest confirm(String name) {
        return new TenantDeletionController.DeleteTenantRequest(name);
    }

    // --- fail-closed: unconfigured secret ------------------------------------------------------

    @Test
    void blankConfiguredMasterKeyFailsClosedEvenWhenTheCallerPresentsThatSameBlankValue() {
        // A deployment that forgot PLATFORM_MASTER_KEY yields "" via the
        // @Value default. If this path fell through to the comparison, a
        // caller presenting an empty/blank header would MATCH the empty
        // configured key - deletion silently wide open behind the role check
        // alone. Must throw the missing-key (401-mapped) exception instead,
        // before any comparison happens.
        TenantDeletionController noSecretController =
            new TenantDeletionController(orchestrationService, "");

        assertThrows(MasterKeyMissingException.class,
            () -> noSecretController.deleteTenant(TENANT_ID, "", confirm("Acme")));
        assertThrows(MasterKeyMissingException.class,
            () -> noSecretController.deleteTenant(TENANT_ID, "any-guess", confirm("Acme")));
        verifyNoInteractions(orchestrationService);
    }

    @Test
    void nullConfiguredMasterKeyFailsClosedTheSameWay() {
        // Defensive twin of the blank case - @Value's ":}" default makes null
        // unlikely, but the fail-closed contract must not depend on which
        // "unset" shape the property system produces.
        TenantDeletionController noSecretController =
            new TenantDeletionController(orchestrationService, null);

        assertThrows(MasterKeyMissingException.class,
            () -> noSecretController.deleteTenant(TENANT_ID, MASTER_KEY, confirm("Acme")));
        verifyNoInteractions(orchestrationService);
    }

    // --- missing / blank header ----------------------------------------------------------------

    @Test
    void missingHeaderRejectsAsMissingKeyAndNeverReachesTheOrchestrator() {
        // required=false on the @RequestHeader means Spring hands the method
        // null rather than 400ing - so the null branch here IS the gate.
        assertThrows(MasterKeyMissingException.class,
            () -> controller.deleteTenant(TENANT_ID, null, confirm("Acme")));
        verifyNoInteractions(orchestrationService);
    }

    @Test
    void blankHeaderRejectsAsMissingKeyAndNeverReachesTheOrchestrator() {
        assertThrows(MasterKeyMissingException.class,
            () -> controller.deleteTenant(TENANT_ID, "   ", confirm("Acme")));
        verifyNoInteractions(orchestrationService);
    }

    // --- wrong key -----------------------------------------------------------------------------

    @Test
    void wrongKeyRejectsAsInvalidKeyAndNeverReachesTheOrchestrator() {
        assertThrows(MasterKeyInvalidException.class,
            () -> controller.deleteTenant(TENANT_ID, "wrong-guess", confirm("Acme")));
        verifyNoInteractions(orchestrationService);
    }

    @Test
    void aKeyThatIsAPrefixOfTheRealOneIsRejected() {
        // Guards MessageDigest.isEqual's whole-value semantics against a
        // refactor to a length-limited/startsWith-style comparison: a prefix
        // of the real secret must never authenticate.
        assertThrows(MasterKeyInvalidException.class,
            () -> controller.deleteTenant(
                TENANT_ID, MASTER_KEY.substring(0, MASTER_KEY.length() - 1), confirm("Acme")));
        verifyNoInteractions(orchestrationService);
    }

    @Test
    void aKeyDifferingOnlyByAnExtraTrailingCharacterIsRejected() {
        // The other length-mismatch direction - same refactor-guard as the
        // prefix case above (MessageDigest.isEqual rejects on differing
        // lengths before comparing content; so must any replacement).
        assertThrows(MasterKeyInvalidException.class,
            () -> controller.deleteTenant(TENANT_ID, MASTER_KEY + "x", confirm("Acme")));
        verifyNoInteractions(orchestrationService);
    }

    // --- success path --------------------------------------------------------------------------

    @Test
    void correctKeyForwardsTenantIdConfirmNameVerbatimAndActorFromTenantContextSubject() {
        // actorId feeds the durable erased_by tombstone column (see
        // Tenant#markErased) - it must be the authenticated caller's subject,
        // not anything the request body could claim.
        TenantContext.set(TenantContext.of("control", "admin-user-42", Set.of("platform-admin")));

        controller.deleteTenant(TENANT_ID, MASTER_KEY, confirm("Acme Corp"));

        verify(orchestrationService).deleteTenant(TENANT_ID, "Acme Corp", "admin-user-42");
    }

    @Test
    void correctKeyWithNoTenantContextForwardsANullActorRatherThanFailing() {
        // No TenantContext on this thread (possible for internal/service
        // callers) must degrade to a null actor, not block an otherwise
        // fully-authenticated deletion - the master key is the gate here,
        // not the context.
        TenantContext.clear();

        controller.deleteTenant(TENANT_ID, MASTER_KEY, confirm("Acme Corp"));

        verify(orchestrationService).deleteTenant(TENANT_ID, "Acme Corp", null);
    }
}
