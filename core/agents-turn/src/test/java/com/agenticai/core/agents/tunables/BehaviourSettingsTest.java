package com.agenticai.core.agents.tunables;

import com.agenticai.core.agents.settings.BehaviourCatalogFixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingValue;
import com.agenticai.core.settings.SettingsRegistry;
import com.agenticai.core.settings.SettingsResolver;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What the turn path reads: the platform value unless a tenant or an agent of the scope's tenant overrode it, whatever
 * thread asks, and the platform value whenever the catalog is absent or fails.
 */
class BehaviourSettingsTest {

    private static final String TENANT = "tenant-a";
    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final String MAX_CONT = "chat.finish.maxContinuations";

    private final BehaviourCatalogFixture fixture = new BehaviourCatalogFixture();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static TenantScope scopeOf(String tenant) {
        return TenantScope.of(TenantContext.of(tenant, "u", Set.of()));
    }

    @Test
    void withoutACatalogEveryAnswerIsThePlatformValue() {
        BehaviourSettings none = BehaviourSettings.platformOnly();

        assertFalse(none.hasCatalog());
        assertEquals(7, none.integer(MAX_CONT, scopeOf(TENANT), AGENT, () -> 7));
        assertTrue(none.bool("chat.finish.enabled", null, AGENT, () -> true));
        assertEquals(Duration.ofSeconds(90), none.seconds("chat.followUp.stickyToolsWindow", null, AGENT, () -> Duration.ofSeconds(90)));
        assertNull(none.boolOrNull("chat.finish.enabled", null, AGENT));
        assertNull(none.integerOrNull(MAX_CONT, null, AGENT));
    }

    @Test
    void withACatalogAndNoOverrideTheAnswerIsThePlatformValueTheCatalogReads() {
        fixture.finish.setMaxContinuations(7);
        BehaviourSettings behaviour = BehaviourSettings.of(fixture.resolver());

        assertEquals(7, behaviour.integer(MAX_CONT, scopeOf(TENANT), AGENT, () -> 99), "the property object, not the fallback");
        assertEquals(Integer.valueOf(7), behaviour.integerOrNull(MAX_CONT, scopeOf(TENANT), AGENT));
    }

    @Test
    void aTenantOverrideAppliesToEveryAgentOfThatTenantAndAnAgentOverrideBeatsIt() {
        fixture.tenant(TENANT, MAX_CONT, 2).agent(TENANT, AGENT, MAX_CONT, 5);
        BehaviourSettings behaviour = BehaviourSettings.of(fixture.resolver());

        assertEquals(5, behaviour.integer(MAX_CONT, scopeOf(TENANT), AGENT, () -> 0));
        assertEquals(2, behaviour.integer(MAX_CONT, scopeOf(TENANT), OTHER_AGENT, () -> 0));
        assertEquals(2, behaviour.integer(MAX_CONT, scopeOf(TENANT), null, () -> 0));
    }

    @Test
    void anotherTenantsOverrideNeverReachesThisOne() {
        fixture.tenant("tenant-b", MAX_CONT, 9);
        BehaviourSettings behaviour = BehaviourSettings.of(fixture.resolver());

        assertEquals(fixture.finish.getMaxContinuations(), behaviour.integer(MAX_CONT, scopeOf(TENANT), AGENT, () -> 0));
        assertEquals(9, behaviour.integer(MAX_CONT, scopeOf("tenant-b"), AGENT, () -> 0));
    }

    @Test
    void theOverrideIsAppliedOnAThreadThatHoldsNoTenantBecauseTheScopeCarriesIt() throws Exception {
        fixture.tenant(TENANT, MAX_CONT, 4);
        BehaviourSettings behaviour = BehaviourSettings.of(fixture.resolver());
        TenantScope scope = scopeOf(TENANT);
        AtomicReference<Integer> seen = new AtomicReference<>();

        Thread scheduler = new Thread(() -> seen.set(behaviour.integer(MAX_CONT, scope, AGENT, () -> -1)));
        scheduler.start();
        scheduler.join();

        assertEquals(4, seen.get());
    }

    @Test
    void aFlagAndADurationAreReadThroughTheirOwnTypes() {
        fixture.agent(TENANT, AGENT, "chat.finish.enabled", false).tenant(TENANT, "chat.followUp.stickyToolsWindow", 600);
        BehaviourSettings behaviour = BehaviourSettings.of(fixture.resolver());

        assertFalse(behaviour.bool("chat.finish.enabled", scopeOf(TENANT), AGENT, () -> true));
        assertTrue(behaviour.bool("chat.finish.enabled", scopeOf(TENANT), OTHER_AGENT, () -> false));
        assertEquals(Duration.ofSeconds(600), behaviour.seconds("chat.followUp.stickyToolsWindow", scopeOf(TENANT), AGENT, () -> Duration.ZERO));
    }

    @Test
    void aCatalogThatFailsGivesThePlatformValueAndNeverFailsTheTurn() {
        SettingsResolver broken =
                new SettingsResolver() {
                    @Override
                    public SettingValue get(String key, SettingContext ctx) {
                        throw new IllegalStateException("store is down");
                    }

                    @Override
                    public Map<String, SettingValue> resolveAll(SettingContext ctx) {
                        throw new IllegalStateException("store is down");
                    }

                    @Override
                    public SettingsRegistry registry() {
                        return SettingsRegistry.of();
                    }
                };
        BehaviourSettings behaviour = BehaviourSettings.of(broken);

        assertEquals(3, behaviour.integer(MAX_CONT, scopeOf(TENANT), AGENT, () -> 3));
        assertNull(behaviour.integerOrNull(MAX_CONT, scopeOf(TENANT), AGENT));
    }

    @Test
    void aLazyCatalogIsAskedAtReadTimeSoAResolverThatAppearsLaterIsUsed() {
        fixture.tenant(TENANT, MAX_CONT, 6);
        AtomicReference<SettingsResolver> late = new AtomicReference<>();
        BehaviourSettings behaviour = BehaviourSettings.lazy(late::get);

        assertEquals(1, behaviour.integer(MAX_CONT, scopeOf(TENANT), AGENT, () -> 1), "not there yet: the platform value");

        late.set(fixture.resolver());

        assertEquals(6, behaviour.integer(MAX_CONT, scopeOf(TENANT), AGENT, () -> 1));
    }
}
