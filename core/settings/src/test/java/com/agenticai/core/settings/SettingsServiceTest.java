package com.agenticai.core.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class SettingsServiceTest {

    SettingsFixture f;

    @BeforeEach
    void setUp() {
        f = new SettingsFixture();
        SettingsFixture.asAdmin();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private SettingValue effective(String key, java.util.UUID agent) {
        f.resolver.evictCurrentTenant();
        return f.resolver.get(key, SettingContext.forAgent(agent));
    }

    // ------------------------------------------------------------------------------------ precedence

    @Test
    void platformIsTheDefaultAndReadsTheSamePropertyObjectLive() {
        SettingValue v = effective("chat.retry.maxAttempts", null);
        assertThat(v.value()).isEqualTo(2L);
        assertThat(v.source()).isEqualTo(SettingSource.DEFAULT);
        f.retries.set(4);
        assertThat(effective("chat.retry.maxAttempts", null).value()).isEqualTo(4L);
    }

    @Test
    void sourceIsPlatformWhenTheOperatorSetTheProperty() {
        f.environment.setProperty("app.guardrails.max-tool-calls-per-turn", "15");
        assertThat(effective("agent.limits.maxToolSteps", null).source()).isEqualTo(SettingSource.PLATFORM);
    }

    @Test
    void agentBeatsTenantBeatsPlatformAndInheritedIsReported() {
        f.service.set(SettingScope.TENANT, null, "agent.limits.maxToolSteps", 30, null);
        SettingValue tenant = effective("agent.limits.maxToolSteps", SettingsFixture.AGENT);
        assertThat(tenant.value()).isEqualTo(30L);
        assertThat(tenant.source()).isEqualTo(SettingSource.TENANT);
        assertThat(tenant.inherited().value()).isEqualTo(15L);

        f.service.set(SettingScope.AGENT, SettingsFixture.AGENT, "agent.limits.maxToolSteps", 40, null);
        SettingValue agent = effective("agent.limits.maxToolSteps", SettingsFixture.AGENT);
        assertThat(agent.value()).isEqualTo(40L);
        assertThat(agent.source()).isEqualTo(SettingSource.AGENT);
        assertThat(agent.inherited().value()).isEqualTo(30L);
        assertThat(agent.inherited().source()).isEqualTo(SettingSource.TENANT);

        // another agent (or tenant level) still sees the tenant value
        assertThat(effective("agent.limits.maxToolSteps", null).value()).isEqualTo(30L);

        f.service.reset(SettingScope.AGENT, SettingsFixture.AGENT, "agent.limits.maxToolSteps", null);
        assertThat(effective("agent.limits.maxToolSteps", SettingsFixture.AGENT).source()).isEqualTo(SettingSource.TENANT);
        f.service.reset(SettingScope.TENANT, null, "agent.limits.maxToolSteps", null);
        assertThat(effective("agent.limits.maxToolSteps", SettingsFixture.AGENT).source()).isNotIn(SettingSource.TENANT, SettingSource.AGENT);
    }

    @Test
    void aSettingThatIsNotAgentScopedIgnoresAnAgentRow() {
        f.store.saveCurrent(new SettingOverride(SettingScope.AGENT, SettingsFixture.AGENT.toString(), "chat.retry.maxAttempts", 5, 1, "x", java.time.Instant.now()));
        assertThat(effective("chat.retry.maxAttempts", SettingsFixture.AGENT).value()).isEqualTo(2L);
    }

    @Test
    void aStoredValueIsClampedIntoTheCurrentBounds() {
        f.store.saveCurrent(new SettingOverride(SettingScope.TENANT, "", "chat.retry.maxAttempts", 99, 1, "x", java.time.Instant.now()));
        assertThat(effective("chat.retry.maxAttempts", null).value()).isEqualTo(6L);
    }

    // ------------------------------------------------------------------------------------ locked

    @Test
    void aLockedKeyIsThePlatformValueAndCannotBeWritten() {
        f.service.set(SettingScope.TENANT, null, "chat.retry.maxAttempts", 4, null);
        f.properties.getLocked().put("chat.retry.max-attempts", true);
        f.properties.getLockedReasons().put("chat.retry.maxAttempts", "Pinned by SRE until the provider incident closes");
        SettingValue v = effective("chat.retry.maxAttempts", null);
        assertThat(v.value()).isEqualTo(2L);
        assertThat(v.locked()).isTrue();
        assertThat(v.lockedReason()).contains("provider incident");
        assertThatThrownBy(() -> f.service.set(SettingScope.TENANT, null, "chat.retry.maxAttempts", 3, null))
                .isInstanceOf(SettingLockedException.class);
        assertThatThrownBy(() -> f.service.reset(SettingScope.TENANT, null, "chat.retry.maxAttempts", null))
                .isInstanceOf(SettingLockedException.class);
    }

    // ------------------------------------------------------------------------------------ validation

    @Test
    void validationErrorsNameTheKeyAndTheAllowedRange() {
        assertThatThrownBy(() -> f.service.set(SettingScope.TENANT, null, "chat.retry.maxAttempts", 7, null))
                .isInstanceOfSatisfying(SettingInvalidException.class, e -> {
                    assertThat(e.key()).isEqualTo("chat.retry.maxAttempts");
                    assertThat(e.min()).isEqualTo(1L);
                    assertThat(e.max()).isEqualTo(6L);
                    assertThat(e.getMessage()).contains("between 1 and 6");
                });
        assertThatThrownBy(() -> f.service.set(SettingScope.TENANT, null, "chat.retry.maxAttempts", 2.5, null))
                .isInstanceOf(SettingInvalidException.class).hasMessageContaining("whole number");
        assertThatThrownBy(() -> f.service.set(SettingScope.TENANT, null, "chat.retry.maxAttempts", "3", null))
                .isInstanceOf(SettingInvalidException.class);
        assertThatThrownBy(() -> f.service.set(SettingScope.TENANT, null, "chat.retry.maxAttempts", null, null))
                .isInstanceOf(SettingInvalidException.class);
        assertThatThrownBy(() -> f.service.set(SettingScope.TENANT, null, "chat.cache.eligibleWithTools", "true", null))
                .isInstanceOf(SettingInvalidException.class);
        assertThatThrownBy(() -> f.service.set(SettingScope.TENANT, null, "guardrails.failMode", "ajar", "because"))
                .isInstanceOfSatisfying(SettingInvalidException.class, e -> assertThat(e.enumValues()).containsExactly("open", "closed"));
        assertThatThrownBy(() -> f.service.set(SettingScope.TENANT, null, "quality.judge.model", "x".repeat(101), null))
                .isInstanceOf(SettingInvalidException.class);
        assertThatThrownBy(() -> f.service.set(SettingScope.TENANT, null, "agent.limits.retrievalMinScore", Double.NaN, null))
                .isInstanceOf(SettingInvalidException.class);
        assertThatThrownBy(() -> f.service.set(SettingScope.AGENT, SettingsFixture.AGENT, "chat.retry.maxAttempts", 3, null))
                .isInstanceOf(SettingInvalidException.class).hasMessageContaining("agent level");
    }

    @Test
    void unknownKeyIsNotFound() {
        assertThatThrownBy(() -> f.service.set(SettingScope.TENANT, null, "no.such", 1, null)).isInstanceOf(SettingNotFoundException.class);
    }

    @Test
    void acceptsCaseInsensitiveEnumAndStoresTheCanonicalSpelling() {
        f.service.set(SettingScope.TENANT, null, "guardrails.failMode", "CLOSED", "keep closed");
        assertThat(f.store.tenantValues().get("guardrails.failMode")).isEqualTo("closed");
    }

    @Test
    void riskyKeysNeedATypedReason() {
        assertThatThrownBy(() -> f.service.set(SettingScope.TENANT, null, "approvals.expiry.ttl", 3600, "  "))
                .isInstanceOfSatisfying(SettingInvalidException.class, e -> assertThat(e.getMessage()).contains("reason is required"));
        f.service.set(SettingScope.TENANT, null, "approvals.expiry.ttl", 3600, "stale requests pile up");
        assertThat(effective("approvals.expiry.ttl", null).value()).isEqualTo(3600L);
    }

    // ------------------------------------------------------------------------------------ security

    @Test
    void aSecurityKeyCanOnlyBeMadeStricterThanThePlatform() {
        f.failMode.set("open");
        // open -> closed is stricter: allowed
        f.service.set(SettingScope.TENANT, null, "guardrails.failMode", "closed", "we handle regulated data");
        assertThat(effective("guardrails.failMode", null).value()).isEqualTo("closed");

        // numeric, lower is stricter: platform 60, 50 is stricter, 70 is weaker
        f.service.set(SettingScope.TENANT, null, "guardrails.toxicity.blockScore", 50, "kids product");
        assertThatThrownBy(() -> f.service.set(SettingScope.TENANT, null, "guardrails.toxicity.blockScore", 70, "fewer false positives"))
                .isInstanceOfSatisfying(SettingInvalidException.class, e -> assertThat(e.getMessage()).contains("only be made stricter"));
        assertThat(effective("guardrails.toxicity.blockScore", null).value()).isEqualTo(50L);

        // the rejected attempt is audited
        verify(f.audit).record(eq("alice"), eq(SettingsService.ACTION_REJECTED), eq("setting"), eq("guardrails.toxicity.blockScore"), eq(AuditOutcome.DENIED), any());
    }

    @Test
    void anAgentOverrideOfASecurityKeyMustBeStricterThanTheTenantValue() {
        f.service.set(SettingScope.TENANT, null, "guardrails.failMode", "closed", "tenant is strict");
        assertThatThrownBy(() -> f.service.set(SettingScope.AGENT, SettingsFixture.AGENT, "guardrails.failMode", "open", "agent is chatty"))
                .isInstanceOf(SettingInvalidException.class);
    }

    @Test
    void whenThePlatformTightensLaterAWeakerStoredOverrideStopsApplying() {
        f.failMode.set("open");
        f.store.saveCurrent(new SettingOverride(SettingScope.TENANT, "", "guardrails.failMode", "open", 1, "alice", java.time.Instant.now()));
        assertThat(effective("guardrails.failMode", null).value()).isEqualTo("open");
        f.failMode.set("closed");
        SettingValue v = effective("guardrails.failMode", null);
        assertThat(v.value()).isEqualTo("closed");
        assertThat(v.source()).isNotEqualTo(SettingSource.TENANT);
    }

    @Test
    void securityKeysNeedATenantAdminEvenOnAnAgent() {
        SettingsFixture.asEditor();
        assertThatThrownBy(() -> f.service.set(SettingScope.AGENT, SettingsFixture.AGENT, "guardrails.failMode", "closed", "strict"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        SettingsFixture.asAdmin();
        f.failMode.set("open");
        f.service.set(SettingScope.AGENT, SettingsFixture.AGENT, "guardrails.failMode", "closed", "strict");
        assertThat(effective("guardrails.failMode", SettingsFixture.AGENT).source()).isEqualTo(SettingSource.AGENT);
    }

    // ------------------------------------------------------------------------------------ roles

    @Test
    void tenantScopeNeedsATenantAdmin() {
        SettingsFixture.asEditor();
        assertThatThrownBy(() -> f.service.set(SettingScope.TENANT, null, "chat.retry.maxAttempts", 3, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        assertThatThrownBy(() -> f.service.reset(SettingScope.TENANT, null, "chat.retry.maxAttempts", null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> f.service.history(SettingScope.TENANT, null, "chat.retry.maxAttempts", null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void agentScopeNeedsEditorOnThatAgent() {
        SettingsFixture.asEditor();
        f.service.set(SettingScope.AGENT, SettingsFixture.AGENT, "agent.limits.maxToolSteps", 25, null);
        f.editorOn.clear();
        assertThatThrownBy(() -> f.service.set(SettingScope.AGENT, SettingsFixture.AGENT, "agent.limits.maxToolSteps", 26, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void anEditLockedAgentCannotBeChangedButItsHistoryCanStillBeRead() {
        SettingsFixture.asEditor();
        f.service.set(SettingScope.AGENT, SettingsFixture.AGENT, "chat.cache.eligibleWithTools", true, null);
        f.lockedAgents.add(SettingsFixture.AGENT);
        assertThatThrownBy(() -> f.service.set(SettingScope.AGENT, SettingsFixture.AGENT, "chat.cache.eligibleWithTools", false, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        assertThatThrownBy(() -> f.service.reset(SettingScope.AGENT, SettingsFixture.AGENT, "chat.cache.eligibleWithTools", null))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(f.service.history(SettingScope.AGENT, SettingsFixture.AGENT, "chat.cache.eligibleWithTools", null)).hasSize(1);
        assertThat(effective("chat.cache.eligibleWithTools", SettingsFixture.AGENT).value()).isEqualTo(true);
    }

    @Test
    void theRefusedWeakeningAuditRowIsNotRolledBackWithTheRefusedRequest() throws Exception {
        // set() writes the rejection audit row inside its transaction and then throws; without noRollbackFor the
        // row would be rolled back together with the request. The real-database proof is SettingsStoreIT.
        var tx = SettingsService.class.getMethod("set", SettingScope.class, java.util.UUID.class, String.class, Object.class, String.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(tx.noRollbackFor()).contains(SettingInvalidException.class);
    }

    @Test
    void noWorkspaceNoWrite() {
        TenantContext.clear();
        assertThatThrownBy(() -> f.service.set(SettingScope.TENANT, null, "chat.retry.maxAttempts", 3, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    // ------------------------------------------------------------------------------------ tenant isolation

    @Test
    void tenantsNeverSeeEachOthersOverridesOrHistory() {
        f.service.set(SettingScope.TENANT, null, "chat.retry.maxAttempts", 5, null);
        f.service.set(SettingScope.TENANT, null, "agent.limits.maxToolSteps", 99, null);

        SettingsFixture.as("tenant-b", "bob", "ROLE_tenant-admin");
        f.resolver.evictCurrentTenant();
        assertThat(effective("chat.retry.maxAttempts", null).value()).isEqualTo(2L);
        assertThat(effective("agent.limits.maxToolSteps", null).value()).isEqualTo(15L);
        assertThat(f.service.history(SettingScope.TENANT, null, "chat.retry.maxAttempts", null)).isEmpty();

        // tenant B cannot touch tenant A's agent: it simply does not exist for it
        assertThatThrownBy(() -> f.service.set(SettingScope.AGENT, SettingsFixture.AGENT, "agent.limits.maxToolSteps", 12, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(() -> f.service.effective(SettingsFixture.AGENT)).isInstanceOf(ResponseStatusException.class);

        f.service.set(SettingScope.TENANT, null, "chat.retry.maxAttempts", 3, null);
        SettingsFixture.asAdmin();
        f.resolver.evictCurrentTenant();
        assertThat(effective("chat.retry.maxAttempts", null).value()).isEqualTo(5L);
    }

    @Test
    void aContextNamingAnotherTenantGetsOnlyThePlatformValue() {
        f.service.set(SettingScope.TENANT, null, "chat.retry.maxAttempts", 5, null);
        SettingValue v = f.resolver.get("chat.retry.maxAttempts", SettingContext.of("tenant-b", null));
        assertThat(v.value()).isEqualTo(2L);
        assertThat(f.resolver.get("chat.retry.maxAttempts", SettingContext.of("tenant-a", null)).value()).isEqualTo(5L);
    }

    @Test
    void noTenantContextMeansPlatformOnly() {
        f.service.set(SettingScope.TENANT, null, "chat.retry.maxAttempts", 5, null);
        TenantContext.clear();
        assertThat(f.resolver.get("chat.retry.maxAttempts", SettingContext.tenant()).value()).isEqualTo(2L);
    }

    // ------------------------------------------------------------------------------------ history and audit

    @Test
    void everyChangeIsVersionedAndKeptInTheHistoryEvenAfterAReset() {
        f.service.set(SettingScope.TENANT, null, "chat.retry.maxAttempts", 3, "first");
        f.service.set(SettingScope.TENANT, null, "chat.retry.maxAttempts", 4, "second");
        f.service.reset(SettingScope.TENANT, null, "chat.retry.maxAttempts", "back to default");
        f.service.set(SettingScope.TENANT, null, "chat.retry.maxAttempts", 5, "third");

        List<SettingHistoryEntry> h = f.service.history(SettingScope.TENANT, null, "chat.retry.maxAttempts", null);
        assertThat(h).extracting(SettingHistoryEntry::version).containsExactly(4, 3, 2, 1);
        assertThat(h).extracting(SettingHistoryEntry::action).containsExactly("SET", "RESET", "SET", "SET");
        assertThat(h.get(0).value()).isEqualTo(5L);
        assertThat(h.get(0).previousValue()).isNull();
        assertThat(h.get(1).value()).isNull();
        assertThat(h.get(1).previousValue()).isEqualTo(4L);
        assertThat(h.get(2).previousValue()).isEqualTo(3L);
        assertThat(h).extracting(SettingHistoryEntry::actor).containsOnly("alice");
        assertThat(h.get(0).reason()).isEqualTo("third");
        assertThat(h.get(0).at()).isNotNull();
    }

    @Test
    void theStoreInterfaceOffersNoWayToChangeHistory() {
        assertThat(java.util.Arrays.stream(SettingsStore.class.getMethods()).map(java.lang.reflect.Method::getName))
                .noneMatch(n -> n.startsWith("update") || n.startsWith("deleteHistory") || n.startsWith("removeHistory") || n.equals("clearHistory"));
    }

    @Test
    void everyWriteIsAuditedWithActorScopeOldNewAndReason() {
        f.service.set(SettingScope.TENANT, null, "approvals.expiry.ttl", 3600, "stale requests");
        f.service.set(SettingScope.TENANT, null, "approvals.expiry.ttl", 7200, "longer please");
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(f.audit, org.mockito.Mockito.times(2))
                .record(eq("alice"), eq(SettingsService.ACTION_UPDATED), eq("setting"), eq("approvals.expiry.ttl"), eq(AuditOutcome.SUCCESS), detail.capture());
        assertThat(detail.getAllValues().get(1))
                .contains("scope=tenant", "old=3600", "new=7200", "reason=longer please", "version=2");
    }

    @Test
    void anAuditFailureDoesNotUndoTheChange() {
        org.mockito.Mockito.when(f.audit.record(any(), any(), any(), any(), any(), any())).thenThrow(new RuntimeException("audit down"));
        f.service.set(SettingScope.TENANT, null, "chat.retry.maxAttempts", 3, null);
        assertThat(effective("chat.retry.maxAttempts", null).value()).isEqualTo(3L);
    }

    @Test
    void settingTheSameValueAgainIsANoOp() {
        f.service.set(SettingScope.TENANT, null, "chat.retry.maxAttempts", 3, null);
        SettingsService.Applied again = f.service.set(SettingScope.TENANT, null, "chat.retry.maxAttempts", 3, null);
        assertThat(again.unchanged()).isTrue();
        assertThat(again.version()).isNull();
        assertThat(f.service.history(SettingScope.TENANT, null, "chat.retry.maxAttempts", null)).hasSize(1);
        assertThat(f.service.reset(SettingScope.TENANT, null, "quality.judge.model", null).unchanged()).isTrue();
    }

    // ------------------------------------------------------------------------------------ backend adapter

    @Test
    void keysOfABackendAreReadAndWrittenThroughIt() {
        f.service.set(SettingScope.TENANT, null, "agent.limits.maxToolSteps", 30, null);
        f.service.set(SettingScope.AGENT, SettingsFixture.AGENT, "agent.limits.maxToolSteps", 40, null);
        assertThat(f.backend.tenantValues()).containsEntry("agent.limits.maxToolSteps", 30L);
        assertThat(f.backend.agentValues(SettingsFixture.AGENT)).containsEntry("agent.limits.maxToolSteps", 40L);
        assertThat(f.store.tenantValues()).doesNotContainKey("agent.limits.maxToolSteps");

        // a value written behind the catalog's back (the older endpoint) is seen at once, no cache in between
        f.backend.writeAgent(SettingsFixture.AGENT, "agent.limits.maxToolSteps", 77);
        assertThat(f.resolver.get("agent.limits.maxToolSteps", SettingContext.forAgent(SettingsFixture.AGENT)).value()).isEqualTo(77L);

        f.service.reset(SettingScope.AGENT, SettingsFixture.AGENT, "agent.limits.maxToolSteps", null);
        assertThat(f.backend.agentValues(SettingsFixture.AGENT)).isEmpty();
        assertThat(f.service.history(SettingScope.AGENT, SettingsFixture.AGENT, "agent.limits.maxToolSteps", null)).hasSize(2);
    }

    // ------------------------------------------------------------------------------------ resolver safety and cache

    @Test
    void aStoreErrorFallsBackToThePlatformValue() {
        SettingsStore broken = (SettingsStore) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {SettingsStore.class}, (p, m, a) -> { throw new IllegalStateException("db down"); });
        DefaultSettingsResolver r = new DefaultSettingsResolver(f.registry, broken, List.of(), f.properties, f.environment);
        SettingValue v = r.get("chat.retry.maxAttempts", SettingContext.tenant());
        assertThat(v.value()).isEqualTo(2L);
        assertThat(v.source()).isEqualTo(SettingSource.DEFAULT);
    }

    @Test
    void storedValuesAreCachedUntilTheTtlOrAWriteOnThisReplica() {
        f.store.saveCurrent(new SettingOverride(SettingScope.TENANT, "", "chat.retry.maxAttempts", 3, 1, "x", java.time.Instant.now()));
        assertThat(f.resolver.get("chat.retry.maxAttempts", SettingContext.tenant()).value()).isEqualTo(3L);
        // another replica changes the row: this one still serves the cached value...
        f.store.saveCurrent(new SettingOverride(SettingScope.TENANT, "", "chat.retry.maxAttempts", 4, 2, "x", java.time.Instant.now()));
        assertThat(f.resolver.get("chat.retry.maxAttempts", SettingContext.tenant()).value()).isEqualTo(3L);
        // ...until the TTL passes
        f.now += 11_000;
        assertThat(f.resolver.get("chat.retry.maxAttempts", SettingContext.tenant()).value()).isEqualTo(4L);
        // a write through this replica is visible at once
        f.service.set(SettingScope.TENANT, null, "chat.retry.maxAttempts", 5, null);
        assertThat(f.resolver.get("chat.retry.maxAttempts", SettingContext.tenant()).value()).isEqualTo(5L);
    }

    @Test
    void typedAccessorsConvert() {
        f.service.set(SettingScope.TENANT, null, "approvals.expiry.ttl", 90, "short");
        assertThat(f.resolver.getDuration("approvals.expiry.ttl", SettingContext.tenant())).isEqualTo(java.time.Duration.ofSeconds(90));
        assertThat(f.resolver.getInt("chat.retry.maxAttempts", SettingContext.tenant())).isEqualTo(2);
        assertThat(f.resolver.getBool("chat.cache.eligibleWithTools", SettingContext.tenant())).isFalse();
        assertThat(f.resolver.getEnum("guardrails.failMode", SettingContext.tenant(), Mode.class)).isEqualTo(Mode.CLOSED);
        assertThat(f.resolver.getDecimal("agent.limits.retrievalMinScore", SettingContext.tenant())).isEqualTo(0.0);
        assertThat(f.resolver.getString("quality.judge.model", SettingContext.tenant())).isEmpty();
        assertThatThrownBy(() -> f.resolver.get("nope.nope", SettingContext.tenant())).isInstanceOf(IllegalArgumentException.class);
    }

    enum Mode { OPEN, CLOSED }

    @Test
    void platformOnlyResolverNeedsNoStore() {
        SettingsResolver r = SettingsResolver.platformOnly(f.registry);
        assertThat(r.getInt("chat.retry.maxAttempts", SettingContext.tenant())).isEqualTo(2);
    }

    @Test
    void effectiveListsEveryKey() {
        assertThat(f.service.effective(null)).containsKeys(f.registry.all().stream().map(SettingDefinition::key).toArray(String[]::new));
    }
}
