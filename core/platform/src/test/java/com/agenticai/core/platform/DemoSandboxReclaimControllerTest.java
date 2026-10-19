package com.agenticai.core.platform;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.MasterKeyInvalidException;
import com.agenticai.core.tenancy.MasterKeyMissingException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DemoSandboxReclaimControllerTest {

    private static final String MASTER_KEY = "master-key-1";

    private final DemoSandboxReclaimService service = mock(DemoSandboxReclaimService.class);
    private final DemoSandboxReclaimController controller =
            new DemoSandboxReclaimController(service, MASTER_KEY);

    @BeforeEach
    void operator() {
        TenantContext.set(TenantContext.of(null, "ops-1", Set.of("ROLE_platform-admin")));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void reclaimRequiresTheSameMasterKeyAsTenantDeletion() {
        var body = new DemoSandboxReclaimController.ReclaimRequest(null, "RECLAIM");
        assertThrows(MasterKeyMissingException.class, () -> controller.reclaim(null, body));
        assertThrows(MasterKeyInvalidException.class, () -> controller.reclaim("wrong", body));
        assertThrows(
                MasterKeyMissingException.class,
                () -> new DemoSandboxReclaimController(service, "").reclaim(MASTER_KEY, body));
        verify(service, never()).reclaim(any(), any(), any());
    }

    @Test
    void reclaimPassesIdsConfirmationAndActorThrough() {
        UUID id = UUID.randomUUID();
        var expected = new DemoSandboxReclaimService.ReclaimResult(List.of(id), List.of(), 0);
        when(service.reclaim(List.of(id), "RECLAIM", "ops-1")).thenReturn(expected);

        var result =
                controller.reclaim(
                        MASTER_KEY,
                        new DemoSandboxReclaimController.ReclaimRequest(List.of(id), "RECLAIM"));

        assertSame(expected, result);
    }

    @Test
    void listNeedsNoMasterKey() {
        var listing = new DemoSandboxReclaimService.SandboxListing(null, List.of(), 0);
        when(service.list()).thenReturn(listing);
        assertSame(listing, controller.list());
    }
}
