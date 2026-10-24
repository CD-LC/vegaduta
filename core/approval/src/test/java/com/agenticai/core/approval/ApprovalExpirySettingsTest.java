package com.agenticai.core.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.rl.feedback.FeedbackService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.settings.DefaultSettingsResolver;
import com.agenticai.core.settings.InMemorySettingsStore;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingOverride;
import com.agenticai.core.settings.SettingRisk;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingSource;
import com.agenticai.core.settings.SettingsContributor;
import com.agenticai.core.settings.SettingsProperties;
import com.agenticai.core.settings.SettingsRegistry;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.env.MockEnvironment;

/**
 * How long a request may wait for a person is a workspace setting ({@code approvals.expiry.ttl}): the sweep reads it per
 * tenant through the real resolver and the real catalog entry, over the real gate. A workspace may turn expiry on, shorten
 * it, or turn it off; a settings failure falls back to the platform TTL; one workspace's value never reaches another.
 */
class ApprovalExpirySettingsTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");

    private ApprovalRequestRepository repository;
    private HumanApprovalGateService gate;
    private InMemorySettingsStore store;
    private final List<String> tenants = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(ApprovalRequestRepository.class);
        ApprovalActionExecutorRegistry registry = mock(ApprovalActionExecutorRegistry.class);
        when(registry.find(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(ApprovalRequest.class))).thenAnswer(call -> call.getArgument(0));
        ApprovalRequestRecorder recorder = new ApprovalRequestRecorder(
            repository, mock(AuditLogService.class), mock(ApplicationEventPublisher.class), mock(WorkflowEventPublisher.class));
        gate = new HumanApprovalGateService(
            mock(ApprovalPolicyService.class), repository, registry, recorder, mock(FeedbackService.class),
            new ObjectMapper(), 5, 30, 3600);
        store = new InMemorySettingsStore();
        tenants.clear();
        tenants.addAll(List.of("tenant_a", "tenant_b"));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private ApprovalRequest pending() throws Exception {
        ApprovalRequest request = new ApprovalRequest("mcp_tool.send", "send it", "{}", "agent:a:session:s");
        Field id = ApprovalRequest.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(request, UUID.randomUUID());
        when(repository.findById(request.getId())).thenReturn(Optional.of(request));
        when(repository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        return request;
    }

    private DefaultSettingsResolver resolver(ApprovalExpiryProperties platform, InMemorySettingsStore backing) {
        SettingsContributor contributor = () -> List.of(ApprovalSettingDefinitions.expiryTtl(platform));
        SettingsProperties props = new SettingsProperties();
        props.setCacheTtl(Duration.ZERO);
        return new DefaultSettingsResolver(new SettingsRegistry(List.of(contributor)), backing, List.of(), props, new MockEnvironment());
    }

    private ApprovalExpiryJob job(ApprovalExpiryProperties platform, DefaultSettingsResolver resolver) {
        TenantSchemaIterator iterator = mock(TenantSchemaIterator.class);
        org.mockito.Mockito.doAnswer(call -> {
            Consumer<String> action = call.getArgument(0);
            for (String tenant : tenants) {
                TenantContext.set(TenantContext.of(tenant, "system:scheduled-job", Set.of()));
                try {
                    action.accept(tenant);
                } finally {
                    TenantContext.clear();
                }
            }
            return null;
        }).when(iterator).forEachActiveTenantSchema(any());
        ApprovalExpiryJob job = new ApprovalExpiryJob(platform, iterator, repository, gate, mock(PgAdvisoryJobLock.class));
        if (resolver != null) {
            job.setSettingsResolver(resolver);
        }
        return job;
    }

    private static ApprovalExpiryProperties platformTtl(Duration ttl) {
        ApprovalExpiryProperties props = new ApprovalExpiryProperties();
        props.setTtl(ttl);
        return props;
    }

    private void tenantOverride(String tenant, long seconds) {
        TenantContext.set(TenantContext.of(tenant, "tester", Set.of()));
        store.saveCurrent(new SettingOverride(SettingScope.TENANT, "", "approvals.expiry.ttl", seconds, 1, "tester", Instant.now()));
        TenantContext.clear();
    }

    // ------------------------------------------------------------------ the sweep per tenant

    @Test
    void aWorkspaceCanTurnExpiryOnWhereThePlatformHasItOff() throws Exception {
        ApprovalExpiryProperties platform = new ApprovalExpiryProperties(); // ttl off
        tenantOverride("tenant_a", 7_200);
        ApprovalRequest stale = pending();
        when(repository.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                eq(ApprovalStatus.PENDING), eq(NOW.minus(Duration.ofHours(2))), any(Pageable.class)))
            .thenReturn(List.of(stale));

        int expired = job(platform, resolver(platform, store)).sweep(NOW);

        assertThat(expired).isEqualTo(1);
        assertThat(stale.isExpired()).isTrue();
        assertThat(stale.getRejectionReason()).as("the reason states the workspace's wait").contains("2 hours");
        verify(repository, times(1)).findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(any(), any(), any());
    }

    @Test
    void aWorkspaceWithoutAnOverrideKeepsThePlatformTtlAndAnotherWorkspaceKeepsItsOwn() throws Exception {
        ApprovalExpiryProperties platform = platformTtl(Duration.ofHours(24));
        tenantOverride("tenant_a", 3_600); // one hour
        ApprovalRequest first = pending();
        ApprovalRequest second = pending();
        when(repository.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                eq(ApprovalStatus.PENDING), eq(NOW.minus(Duration.ofHours(1))), any(Pageable.class)))
            .thenReturn(List.of(first));
        when(repository.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                eq(ApprovalStatus.PENDING), eq(NOW.minus(Duration.ofHours(24))), any(Pageable.class)))
            .thenReturn(List.of(second));

        int expired = job(platform, resolver(platform, store)).sweep(NOW);

        assertThat(expired).isEqualTo(2);
        assertThat(first.getRejectionReason()).contains("1 hour").doesNotContain("24 hours");
        assertThat(second.getRejectionReason()).contains("24 hours");
    }

    @Test
    void aWorkspaceCanTurnExpiryOffWhereThePlatformHasItOn() throws Exception {
        ApprovalExpiryProperties platform = platformTtl(Duration.ofHours(24));
        tenantOverride("tenant_a", 0);
        tenants.remove("tenant_b");

        int expired = job(platform, resolver(platform, store)).sweep(NOW);

        assertThat(expired).isZero();
        verify(repository, never()).findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(any(), any(), any());
    }

    @Test
    void aSettingsFailureFallsBackToThePlatformTtl() throws Exception {
        ApprovalExpiryProperties platform = platformTtl(Duration.ofHours(24));
        InMemorySettingsStore broken = new InMemorySettingsStore() {
            @Override
            public Map<String, Object> tenantValues() {
                throw new IllegalStateException("settings table unavailable");
            }
        };
        tenants.remove("tenant_b");
        ApprovalRequest stale = pending();
        when(repository.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                eq(ApprovalStatus.PENDING), eq(NOW.minus(Duration.ofHours(24))), any(Pageable.class)))
            .thenReturn(List.of(stale));

        int expired = job(platform, resolver(platform, broken)).sweep(NOW);

        assertThat(expired).as("the platform TTL applied: a broken store never means 'no expiry'").isEqualTo(1);
    }

    @Test
    void aSettingsFailureWithThePlatformOffExpiresNothing() {
        ApprovalExpiryProperties platform = new ApprovalExpiryProperties();
        InMemorySettingsStore broken = new InMemorySettingsStore() {
            @Override
            public Map<String, Object> tenantValues() {
                throw new IllegalStateException("settings table unavailable");
            }
        };

        assertThat(job(platform, resolver(platform, broken)).sweep(NOW)).isZero();
        verify(repository, never()).findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(any(), any(), any());
    }

    @Test
    void withoutACatalogThePlatformTtlDecidesForEveryTenant() throws Exception {
        ApprovalExpiryProperties platform = platformTtl(Duration.ofHours(2));
        tenants.remove("tenant_b");
        ApprovalRequest stale = pending();
        when(repository.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                eq(ApprovalStatus.PENDING), eq(NOW.minus(Duration.ofHours(2))), any(Pageable.class)))
            .thenReturn(List.of(stale));

        assertThat(job(platform, null).sweep(NOW)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the scheduled tick

    @Test
    void thePlatformOffAndNoCatalogMeansTheTickDoesNothingAtAll() {
        PgAdvisoryJobLock lock = mock(PgAdvisoryJobLock.class);
        ApprovalExpiryJob job = new ApprovalExpiryJob(
            new ApprovalExpiryProperties(), mock(TenantSchemaIterator.class), repository, gate, lock);

        job.expireOverdueRequests();

        verifyNoInteractions(lock);
    }

    @Test
    void withACatalogTheTickRunsEvenWhenThePlatformIsOffBecauseAWorkspaceMayHaveOptedIn() {
        PgAdvisoryJobLock lock = mock(PgAdvisoryJobLock.class);
        ApprovalExpiryProperties platform = new ApprovalExpiryProperties();
        ApprovalExpiryJob job = new ApprovalExpiryJob(platform, mock(TenantSchemaIterator.class), repository, gate, lock);
        job.setSettingsResolver(resolver(platform, store));

        job.expireOverdueRequests();

        verify(lock).tryRunExclusive(eq("approval-expiry"), any(Runnable.class));
    }

    // ------------------------------------------------------------------ the catalog entry

    @Test
    void theCatalogEntryReadsThePlatformTtlAndIsAWorkspaceSettingOfHighRisk() {
        ApprovalExpiryProperties platform = platformTtl(Duration.ofHours(24));
        SettingDefinition def = ApprovalSettingDefinitions.expiryTtl(platform);

        assertThat(def.key()).isEqualTo("approvals.expiry.ttl");
        assertThat(def.platformValue()).isEqualTo(86_400L);
        assertThat(def.risk()).isEqualTo(SettingRisk.HIGH);
        assertThat(def.scopes()).containsExactly(SettingScope.TENANT);

        TenantContext.set(TenantContext.of("tenant_a", "tester", Set.of()));
        assertThat(resolver(platform, store).get("approvals.expiry.ttl", SettingContext.tenant()).source())
            .isIn(SettingSource.DEFAULT, SettingSource.PLATFORM);
        platform.setTtl(Duration.ofHours(1));
        assertThat(resolver(platform, store).getDuration("approvals.expiry.ttl", SettingContext.tenant()))
            .as("the entry follows the property object, so it cannot drift from the yml")
            .isEqualTo(Duration.ofHours(1));
    }
}
