package com.agenticai.core.common.datausage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.LlmCallLogService;
import com.agenticai.core.chat.LlmDashboardMetrics;
import com.agenticai.core.common.datausage.TenantDataUsageResponse.SystemUsage;
import com.agenticai.core.eventbus.WorkflowEventOutboxRelay;
import com.agenticai.core.eventbus.WorkflowEventOutboxRepository;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantDataErasureService;
import com.agenticai.core.tenancy.TenantRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Covers {@link TenantDataUsageService} - the {@code GET /api/tenant/data-usage}
 * response builder. Focuses on the two things worth a real test beyond
 * "it doesn't throw": (1) every field the contract promises is actually
 * populated with the right shape/id/status per system, and (2) one bad live
 * read never blanks the rest of the response (the {@code safe()} degradation
 * contract), matching how {@code TenantDataErasureServiceTest} already tests
 * this module's other raw-JDBC service.
 */
class TenantDataUsageServiceTest {

    private static final String SCHEMA_NAME = "acme";

    private TenantRepository tenantRepository;
    private TenantDataErasureService tenantDataErasureService;
    private LlmCallLogService llmCallLogService;
    private WorkflowEventOutboxRepository outboxRepository;
    private DataSource dataSource;
    private Connection connection;
    private PreparedStatement preparedStatement;
    private ResultSet resultSet;
    private TenantDataUsageService service;
    private Tenant tenant;

    @BeforeEach
    void setUp() throws SQLException {
        tenantRepository = mock(TenantRepository.class);
        tenantDataErasureService = mock(TenantDataErasureService.class);
        llmCallLogService = mock(LlmCallLogService.class);
        outboxRepository = mock(WorkflowEventOutboxRepository.class);
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        preparedStatement = mock(PreparedStatement.class);
        resultSet = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getInt(1)).thenReturn(12);

        tenant = new Tenant("Acme", TenantPlan.PREMIUM, SCHEMA_NAME, "agentic-ai", AccountType.B2C, ExperienceMode.ADVANCED);
        ReflectionTestUtils.setField(tenant, "id", java.util.UUID.randomUUID());
        when(tenantRepository.findBySchemaName(SCHEMA_NAME)).thenReturn(Optional.of(tenant));
        when(tenantDataErasureService.countObjectStorageItemsForCurrentTenant()).thenReturn(7L);
        when(llmCallLogService.dashboardMetrics()).thenReturn(
            new LlmDashboardMetrics(42L, null, null, null, null, null, null, 0L, List.of())
        );
        when(outboxRepository.countByPublishedAtIsNullAndRetryCountLessThan(WorkflowEventOutboxRelay.MAX_RETRIES))
            .thenReturn(3L);

        service = new TenantDataUsageService(tenantRepository, Optional.of(tenantDataErasureService), llmCallLogService, outboxRepository, dataSource);
        ReflectionTestUtils.setField(service, "executionEngineMode", "legacy");

        TenantContext.set(TenantContext.of(SCHEMA_NAME, "user-123", Set.of("tenant-admin")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void returnsAllSevenSystemsWithTheContractedIdsInOrder() {
        TenantDataUsageResponse response = service.currentTenantUsage();

        assertEquals(tenant.getId().toString(), response.tenantId());
        assertNotNull(response.generatedAt());
        List<String> ids = response.systems().stream().map(SystemUsage::id).toList();
        assertEquals(List.of("postgres", "minio", "redis", "langfuse", "kafka", "temporal", "keycloak"), ids);
    }

    @Test
    void everySystemHasANonBlankDescriptionAndIsolationFact() {
        for (SystemUsage system : service.currentTenantUsage().systems()) {
            assertTrue(system.description() != null && !system.description().isBlank(), system.id());
            assertTrue(system.isolationFact() != null && !system.isolationFact().isBlank(), system.id());
            assertNotNull(system.status(), system.id());
        }
    }

    @Test
    void liveStatsAreWiredToTheRealUnderlyingReads() {
        List<SystemUsage> systems = service.currentTenantUsage().systems();

        SystemUsage postgres = byId(systems, "postgres");
        assertEquals(12, postgres.liveStat().value());

        SystemUsage minio = byId(systems, "minio");
        assertEquals(7L, minio.liveStat().value());

        SystemUsage langfuse = byId(systems, "langfuse");
        assertEquals(42L, langfuse.liveStat().value());

        SystemUsage kafka = byId(systems, "kafka");
        assertEquals(3L, kafka.liveStat().value());
    }

    @Test
    void redisTemporalAndKeycloakHaveNoLiveStatByDesign() {
        List<SystemUsage> systems = service.currentTenantUsage().systems();

        assertNull(byId(systems, "redis").liveStat());
        assertNull(byId(systems, "temporal").liveStat());
        assertNull(byId(systems, "keycloak").liveStat());
    }

    @Test
    void temporalStatusIsAvailableNotEngagedWhenTheConfiguredEngineIsLegacy() {
        SystemUsage temporal = byId(service.currentTenantUsage().systems(), "temporal");

        assertEquals("available_not_engaged", temporal.status());
    }

    @Test
    void temporalStatusFlipsToActiveIfTheEngineIsEverConfiguredToTemporal() {
        ReflectionTestUtils.setField(service, "executionEngineMode", "temporal");

        SystemUsage temporal = byId(service.currentTenantUsage().systems(), "temporal");

        assertEquals("active", temporal.status());
    }

    @Test
    void aFailedPostgresTableCountDegradesToANullLiveStatWithoutFailingTheWholeResponse() throws SQLException {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("connection reset"));

        TenantDataUsageResponse response = service.currentTenantUsage();

        assertNull(byId(response.systems(), "postgres").liveStat());
        // Every other system's field is still populated - one bad reading never blanks the rest.
        assertEquals(7L, byId(response.systems(), "minio").liveStat().value());
    }

    @Test
    void aFailedObjectStorageCountDegradesToANullLiveStatWithoutFailingTheWholeResponse() {
        when(tenantDataErasureService.countObjectStorageItemsForCurrentTenant())
            .thenThrow(new RuntimeException("db unreachable"));

        TenantDataUsageResponse response = service.currentTenantUsage();

        assertNull(byId(response.systems(), "minio").liveStat());
        assertEquals(12, byId(response.systems(), "postgres").liveStat().value());
    }

    @Test
    void noTenantContextThrowsIllegalState() {
        TenantContext.clear();

        assertThrows(IllegalStateException.class, () -> service.currentTenantUsage());
    }

    private static SystemUsage byId(List<SystemUsage> systems, String id) {
        return systems.stream().filter(s -> s.id().equals(id)).findFirst()
            .orElseThrow(() -> new AssertionError("No system with id '" + id + "'"));
    }
}
