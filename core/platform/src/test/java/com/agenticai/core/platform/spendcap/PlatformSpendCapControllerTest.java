package com.agenticai.core.platform.spendcap;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.spendcap.SpendCapService;
import com.agenticai.core.agents.spendcap.SpendCapService.CapStatus;
import com.agenticai.core.platform.PlatformTenantNotFoundException;
import com.agenticai.core.platform.ops.PlatformTenantAuditor;
import com.agenticai.core.tenancy.MasterKeyInvalidException;
import com.agenticai.core.tenancy.MasterKeyMissingException;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlatformSpendCapControllerTest {

    private final SpendCapService caps = mock(SpendCapService.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final PlatformTenantAuditor auditor = mock(PlatformTenantAuditor.class);
    private final PlatformSpendCapController controller = new PlatformSpendCapController(caps, tenants, auditor, "mk");
    private final UUID id = UUID.randomUUID();

    private CapStatus status(String cap) {
        return new CapStatus(id, cap == null ? null : new BigDecimal(cap), new BigDecimal("12.0000"), null, 0, false,
            LocalDate.of(2026, 10, 1));
    }

    @Test
    void putRequiresTheMasterKey() {
        var body = new PlatformSpendCapController.SpendCapUpdate(new BigDecimal("10"));
        assertThrows(MasterKeyMissingException.class, () -> controller.put(id, null, body));
        assertThrows(MasterKeyInvalidException.class, () -> controller.put(id, "wrong", body));
        verify(caps, never()).updateCap(any(), any());
    }

    @Test
    void putUpdatesAndAuditsToTheTenantLog() {
        Tenant tenant = mock(Tenant.class);
        when(tenant.getSchemaName()).thenReturn("tenant_acme");
        when(tenants.findById(id)).thenReturn(Optional.of(tenant));
        when(caps.status(id)).thenReturn(Optional.of(status(null)));
        CapStatus updated = status("25.00");
        when(caps.updateCap(id, new BigDecimal("25"))).thenReturn(Optional.of(updated));

        assertSame(updated, controller.put(id, "mk", new PlatformSpendCapController.SpendCapUpdate(new BigDecimal("25"))));
        verify(auditor).record(eq("tenant_acme"), any(), eq("platform.tenant.spend_cap"), eq("tenant"), eq(id.toString()),
            contains("from none to $25.00"));
    }

    @Test
    void unknownTenantIs404() {
        when(caps.status(id)).thenReturn(Optional.empty());
        assertThrows(PlatformTenantNotFoundException.class, () -> controller.get(id));
        when(tenants.findById(id)).thenReturn(Optional.empty());
        assertThrows(PlatformTenantNotFoundException.class,
            () -> controller.put(id, "mk", new PlatformSpendCapController.SpendCapUpdate(null)));
        verify(auditor, never()).record(anyString(), any(), any(), any(), any(), any());
    }
}
