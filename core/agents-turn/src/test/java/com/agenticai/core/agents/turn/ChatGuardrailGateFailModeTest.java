package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.guardrail.AgentGuardrailService;
import com.agenticai.core.guardrails.ContentGuardrailEngine;
import com.agenticai.core.guardrails.GuardrailFailMode;
import com.agenticai.core.guardrails.GuardrailSettingDefinitions;
import com.agenticai.core.guardrails.GuardrailSettings;
import com.agenticai.core.guardrails.GuardrailTuningProperties;
import com.agenticai.core.guardrails.GuardrailViolationLogger;
import com.agenticai.core.guardrails.moderation.ModerationService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.settings.DefaultSettingsResolver;
import com.agenticai.core.settings.InMemorySettingsStore;
import com.agenticai.core.settings.SettingOverride;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingsContributor;
import com.agenticai.core.settings.SettingsProperties;
import com.agenticai.core.settings.SettingsRegistry;
import com.agenticai.core.sharing.ResourceAccessService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;

/**
 * {@code guardrails.failMode} on the three chat-turn paths of {@link ChatGuardrailGate} (inbound moderation, inbound
 * content guardrails, outbound content guardrails): the gate asks the settings catalog, not the static platform mode, so
 * a workspace or an agent can close a check the platform leaves open; the key is a security key, so a stored "open" never
 * loosens a closed platform, and a key an operator pinned ignores every override. The checks fail for real (the moderation
 * service and the engine throw); only the settings resolver, store and catalog entries are the production classes.
 */
class ChatGuardrailGateFailModeTest {

    private static final String TENANT = "tenant-a";
    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final String KEY = "guardrails.failMode";

    private InMemorySettingsStore store;
    private SettingsProperties settingsProperties;

    @BeforeEach
    void setUp() {
        store = new InMemorySettingsStore();
        settingsProperties = new SettingsProperties();
        settingsProperties.setCacheTtl(Duration.ZERO);
        TenantContext.set(TenantContext.of(TENANT, "tester", Set.of()));
    }

    @AfterEach
    void tearDown() {
        GuardrailSettings.clear();
        GuardrailFailMode.set(GuardrailFailMode.CLOSED);
        TenantContext.clear();
    }

    private void install() {
        GuardrailTuningProperties tuning = new GuardrailTuningProperties();
        SettingsContributor contributor = () -> GuardrailSettingDefinitions.all(tuning);
        GuardrailSettings.use(new DefaultSettingsResolver(
                new SettingsRegistry(List.of(contributor)), store, List.of(), settingsProperties, new MockEnvironment()));
    }

    private void tenantOverride(String value) {
        store.saveCurrent(new SettingOverride(SettingScope.TENANT, "", KEY, value, 1, "tester", Instant.now()));
    }

    private void agentOverride(UUID agent, String value) {
        store.saveCurrent(new SettingOverride(SettingScope.AGENT, agent.toString(), KEY, value, 1, "tester", Instant.now()));
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }
        };
    }

    private static Agent agent() {
        Agent agent = mock(Agent.class);
        when(agent.getEnabledGuardrails()).thenReturn(Set.of("jailbreak"));
        return agent;
    }

    private static ChatGuardrailGate gate() {
        ModerationService moderation = mock(ModerationService.class);
        when(moderation.moderate(any(), any())).thenThrow(new IllegalStateException("moderation down"));
        ContentGuardrailEngine engine = mock(ContentGuardrailEngine.class);
        when(engine.evaluate(any(), any(), any(), any())).thenThrow(new IllegalStateException("engine down"));
        AgentGuardrailService agentGuardrails = new AgentGuardrailService(
                mock(TenantLimits.class), mock(AgentRepository.class), mock(ResourceAccessService.class));
        return new ChatGuardrailGate(
                provider(moderation), provider(engine), provider(mock(GuardrailViolationLogger.class)), agentGuardrails);
    }

    /** The three paths, each reduced to "was the work refused because the check could not run". */
    private static List<Supplier<Boolean>> refusedOnEachPath(ChatGuardrailGate gate, UUID agentId) {
        return List.of(
                () -> refused(gate.moderateInbound(agentId, "s1", "u", "hello", TENANT, null)),
                () -> refused(gate.enforceInboundGuardrails(agent(), agentId, "s1", "hello", TENANT)),
                () -> ChatGuardrailGate.CHECK_UNAVAILABLE_NOTICE.equals(
                        gate.enforceOutboundGuardrails(agent(), agentId, "s1", TENANT, null, "the answer")));
    }

    private static boolean refused(Flux<String> result) {
        return result != null
                && ChatGuardrailGate.CHECK_UNAVAILABLE_NOTICE.equals(result.collectList().block().get(0));
    }

    private static void assertAll(List<Supplier<Boolean>> paths, boolean expectedRefused, String why) {
        for (int i = 0; i < paths.size(); i++) {
            assertThat(paths.get(i).get()).as("path %d: %s", i, why).isEqualTo(expectedRefused);
        }
    }

    @Test
    void withNoOverrideTheGateFollowsThePlatformMode() {
        install();
        GuardrailFailMode.set(GuardrailFailMode.OPEN);
        assertAll(refusedOnEachPath(gate(), AGENT), false, "platform open");

        GuardrailFailMode.set(GuardrailFailMode.CLOSED);
        assertAll(refusedOnEachPath(gate(), AGENT), true, "platform closed");
    }

    @Test
    void aWorkspaceCanCloseTheChecksTheOpenPlatformLeavesOpen() {
        install();
        GuardrailFailMode.set(GuardrailFailMode.OPEN);
        ChatGuardrailGate gate = gate();
        assertAll(refusedOnEachPath(gate, AGENT), false, "before the override");

        tenantOverride("closed");

        assertAll(refusedOnEachPath(gate, AGENT), true, "workspace closed it");
        assertAll(refusedOnEachPath(gate, OTHER_AGENT), true, "every agent of the workspace");
    }

    @Test
    void anAgentOverrideClosesTheChecksForThatAgentOnly() {
        install();
        GuardrailFailMode.set(GuardrailFailMode.OPEN);
        agentOverride(AGENT, "closed");
        ChatGuardrailGate gate = gate();

        assertAll(refusedOnEachPath(gate, AGENT), true, "the agent closed it");
        assertAll(refusedOnEachPath(gate, OTHER_AGENT), false, "another agent follows the platform");
    }

    @Test
    void anAgentOverrideBeatsTheWorkspaceWhenItIsTheStricterOne() {
        install();
        GuardrailFailMode.set(GuardrailFailMode.OPEN);
        tenantOverride("open");
        agentOverride(AGENT, "closed");
        ChatGuardrailGate gate = gate();

        assertAll(refusedOnEachPath(gate, AGENT), true, "agent closed over a workspace open");
        assertAll(refusedOnEachPath(gate, OTHER_AGENT), false, "the workspace's open still applies to the rest");
    }

    @Test
    void aStoredOpenModeNeverLoosensAClosedPlatform() {
        install();
        GuardrailFailMode.set(GuardrailFailMode.CLOSED);
        tenantOverride("open");
        agentOverride(AGENT, "open");
        ChatGuardrailGate gate = gate();

        assertAll(refusedOnEachPath(gate, AGENT), true, "security key: only stricter overrides apply");
        assertAll(refusedOnEachPath(gate, OTHER_AGENT), true, "security key, workspace level");
    }

    @Test
    void aKeyTheOperatorPinnedIgnoresEveryOverride() {
        settingsProperties.getLocked().put(KEY, true);
        install();
        GuardrailFailMode.set(GuardrailFailMode.OPEN);
        tenantOverride("closed");
        agentOverride(AGENT, "closed");
        ChatGuardrailGate gate = gate();

        assertAll(refusedOnEachPath(gate, AGENT), false, "pinned to the platform's open");
    }

    @Test
    void anotherWorkspacesOverrideNeverReachesThisOne() {
        install();
        GuardrailFailMode.set(GuardrailFailMode.OPEN);
        TenantContext.set(TenantContext.of("tenant-b", "tester", Set.of()));
        tenantOverride("closed");
        TenantContext.set(TenantContext.of(TENANT, "tester", Set.of()));

        assertAll(refusedOnEachPath(gate(), AGENT), false, "tenant-b's override is not tenant-a's");
    }
}
