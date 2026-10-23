package com.agenticai.core.agents.memory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.settings.ProductControlsFixture;
import com.agenticai.core.agents.settings.SettingsRig;
import com.agenticai.core.knowledge.SidecarClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code memory.tenant.maxMemories}: how many shared memories a workspace keeps follows the tenant's setting,
 * {@code app.memory.limits.tenant-max-memories} when there is none. JdbcTemplate is mocked, as in {@link MemoryLimitsTest}.
 */
class MemoryCapSettingsTest {

    private final SidecarClient sidecar = mock(SidecarClient.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ProductControlsFixture f = new ProductControlsFixture();

    @AfterEach
    void clear() {
        SettingsRig.clear();
    }

    @BeforeEach
    void embeddings() {
        when(sidecar.embedQuery(anyString())).thenReturn(new float[] {0.1f, 0.2f});
    }

    private TenantMemoryService service(boolean withCatalog) {
        TenantMemoryService service = new TenantMemoryService(sidecar, jdbc, true, 1200, 20, 4000, true);
        service.setLimits(f.memory);
        if (withCatalog) {
            service.setSettingsResolver(f.rig().resolver());
        }
        return service;
    }

    private void storedCountIs(int count) {
        when(jdbc.queryForObject(contains("SELECT COUNT(*)"), eq(Integer.class))).thenReturn(count);
        when(jdbc.queryForObject(contains("SELECT COUNT(*)"), eq(Integer.class), any(Object[].class))).thenReturn(count);
    }

    @Test
    void aTenantPrunesDownToTheCapItSetAndAnotherTenantKeepsThePlatformCap() {
        f.memory.setTenantMaxMemories(300);
        f.rig().tenant("tenant-a", "memory.tenant.maxMemories", 100);
        storedCountIs(130);
        TenantMemoryService service = service(true);

        SettingsRig.inTenant("tenant-a", () -> service.remember("alice", "likes tea", null));
        verify(jdbc).update(contains("DELETE FROM"), eq(30));

        SettingsRig.inTenant("tenant-b", () -> service.remember("alice", "likes tea", null));
        verify(jdbc, times(1)).update(contains("DELETE FROM"), any(Object[].class));
    }

    @Test
    void aTenantMayRaiseTheCapSoNothingIsPrunedThatThePlatformWould() {
        f.memory.setTenantMaxMemories(100);
        f.rig().tenant("tenant-a", "memory.tenant.maxMemories", 500);
        storedCountIs(130);

        SettingsRig.inTenant("tenant-a", () -> service(true).remember("alice", "likes tea", null));

        verify(jdbc, never()).update(contains("DELETE FROM"), any(Object[].class));
    }

    @Test
    void aServiceBuiltWithoutACatalogKeepsThePropertyCap() {
        f.memory.setTenantMaxMemories(100);
        f.rig().tenant("tenant-a", "memory.tenant.maxMemories", 500);
        storedCountIs(130);

        SettingsRig.inTenant("tenant-a", () -> service(false).remember("alice", "likes tea", null));

        verify(jdbc).update(contains("DELETE FROM"), eq(30));
    }
}
