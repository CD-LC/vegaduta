package com.agenticai.core.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.LlmCallLogService;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.quality.dataset.EvalDataset;
import com.agenticai.core.quality.dataset.EvalDatasetCase;
import com.agenticai.core.quality.impl.JudgeChatClientFactory;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.settings.DefaultSettingsResolver;
import com.agenticai.core.settings.InMemorySettingsStore;
import com.agenticai.core.settings.SettingOverride;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingsProperties;
import com.agenticai.core.settings.SettingsRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The three {@code quality.*} catalog keys on their real use sites ({@link JudgeChatClientFactory} and the eval run
 * trigger): the real {@link QualitySettings} entries over the real resolver and a store. A workspace override changes what
 * the code does, an operator pin ignores it, another workspace never sees it, and an agent-level row is ignored because
 * these keys are workspace-level. The deployment's property stays the platform value.
 */
class QualitySettingsWiringTest {

    private static final String TENANT = "tenant-a";
    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private InMemorySettingsStore store;
    private SettingsProperties settingsProperties;
    private MockEnvironment env;
    private DefaultSettingsResolver resolver;

    @BeforeEach
    void setUp() {
        store = new InMemorySettingsStore();
        settingsProperties = new SettingsProperties();
        settingsProperties.setCacheTtl(Duration.ZERO);
        env = new MockEnvironment();
        TenantContext.set(TenantContext.of(TENANT, "tester", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void build() {
        resolver = new DefaultSettingsResolver(
                new SettingsRegistry(List.of(new QualitySettings(env))), store, List.of(), settingsProperties, env);
    }

    private void tenantOverride(String key, Object value) {
        store.saveCurrent(new SettingOverride(SettingScope.TENANT, "", key, value, 1, "tester", Instant.now()));
    }

    private JudgeChatClientFactory judgeFactory() {
        ProviderCredentialResolver credentials = mock(ProviderCredentialResolver.class);
        when(credentials.resolveAnyActiveKey())
                .thenReturn(new ProviderCredentialResolver.ActiveProvider(ProviderName.OPENROUTER, "key"));
        ProviderModelCatalogService catalog = mock(ProviderModelCatalogService.class);
        when(catalog.defaultModelFor(ProviderName.OPENROUTER)).thenReturn(Optional.of("catalog/default"));
        JudgeChatClientFactory factory =
                new JudgeChatClientFactory(mock(LlmGatewayService.class), credentials, catalog);
        factory.setSettingsResolver(resolver);
        return factory;
    }

    /** A factory whose @Value fields carry the deployment's properties as Spring binds them (the catalog reads the same environment). */
    private JudgeChatClientFactory judgeFactoryWithPlatform(String model, long timeoutSeconds) {
        JudgeChatClientFactory factory = judgeFactory();
        org.springframework.test.util.ReflectionTestUtils.setField(factory, "configuredJudgeModel", model);
        org.springframework.test.util.ReflectionTestUtils.setField(factory, "callTimeoutSeconds", timeoutSeconds);
        return factory;
    }

    // ---------------------------------------------------------------- quality.judge.model

    @Test
    void aWorkspaceJudgeModelChangesWhichModelJudges() {
        env.setProperty("app.quality.eval.judge-model", "platform/judge");
        build();
        JudgeChatClientFactory factory = judgeFactoryWithPlatform("platform/judge", 20);
        assertEquals("OPENROUTER/platform/judge", factory.resolveJudge().id());

        tenantOverride(QualitySettings.JUDGE_MODEL, "workspace/judge");

        JudgeChatClientFactory.JudgeChoice choice = factory.resolveJudge();
        assertEquals("OPENROUTER/workspace/judge", choice.id());
        assertTrue(choice.why().contains("configured judge model"), choice.why());
    }

    @Test
    void theWorkspaceJudgeModelIsPerWorkspace() {
        build();
        JudgeChatClientFactory factory = judgeFactoryWithPlatform("", 20);
        tenantOverride(QualitySettings.JUDGE_MODEL, "workspace/judge");
        TenantContext.set(TenantContext.of("tenant-b", "tester", Set.of()));

        assertEquals("OPENROUTER/catalog/default", factory.resolveJudge().id());
    }

    @Test
    void aPinnedJudgeModelIgnoresTheWorkspaceOverride() {
        settingsProperties.getLocked().put(QualitySettings.JUDGE_MODEL, true);
        env.setProperty("app.quality.eval.judge-model", "platform/judge");
        build();
        JudgeChatClientFactory factory = judgeFactoryWithPlatform("platform/judge", 20);
        tenantOverride(QualitySettings.JUDGE_MODEL, "workspace/judge");

        assertEquals("OPENROUTER/platform/judge", factory.resolveJudge().id());
    }

    @Test
    void anAgentLevelRowOfAWorkspaceLevelKeyIsIgnored() {
        env.setProperty("app.quality.eval.judge-model", "platform/judge");
        build();
        JudgeChatClientFactory factory = judgeFactoryWithPlatform("platform/judge", 20);
        store.saveCurrent(new SettingOverride(
                SettingScope.AGENT, AGENT.toString(), QualitySettings.JUDGE_MODEL, "agent/judge", 1, "tester", Instant.now()));

        assertEquals("OPENROUTER/platform/judge", factory.resolveJudge().id());
    }

    @Test
    void aRunScopedModelStillBeatsTheWorkspaceSetting() throws Exception {
        build();
        JudgeChatClientFactory factory = judgeFactoryWithPlatform("", 20);
        tenantOverride(QualitySettings.JUDGE_MODEL, "workspace/judge");

        try (AutoCloseable scope = factory.judgeModelScope("run/judge")) {
            assertEquals("OPENROUTER/run/judge", factory.resolveJudge().id());
        }
    }

    // ---------------------------------------------------------------- quality.judge.callTimeoutSeconds

    @Test
    void aWorkspaceCallTimeoutChangesTheLimitOnJudgeCalls() {
        build();
        JudgeChatClientFactory factory = judgeFactoryWithPlatform("", 20);
        assertEquals(Duration.ofSeconds(20), factory.callTimeout());

        tenantOverride(QualitySettings.JUDGE_CALL_TIMEOUT_SECONDS, 90L);

        assertEquals(Duration.ofSeconds(90), factory.callTimeout());
        TenantContext.set(TenantContext.of("tenant-b", "tester", Set.of()));
        assertEquals(Duration.ofSeconds(20), factory.callTimeout(), "another workspace keeps the platform value");
    }

    @Test
    void aPinnedCallTimeoutIgnoresTheWorkspaceOverride() {
        settingsProperties.getLocked().put(QualitySettings.JUDGE_CALL_TIMEOUT_SECONDS, true);
        build();
        JudgeChatClientFactory factory = judgeFactoryWithPlatform("", 20);
        tenantOverride(QualitySettings.JUDGE_CALL_TIMEOUT_SECONDS, 90L);

        assertEquals(Duration.ofSeconds(20), factory.callTimeout());
    }

    @Test
    void theDeploymentPropertyIsThePlatformValueOfTheCatalogEntry() {
        env.setProperty("app.quality.eval.judge-call-timeout-seconds", "45");
        env.setProperty("app.quality.eval.judge-model", "platform/judge");
        env.setProperty("app.quality.eval.default-repeats", "2");
        build();

        assertEquals(45L, resolver.getLong(QualitySettings.JUDGE_CALL_TIMEOUT_SECONDS, com.agenticai.core.settings.SettingContext.tenant()));
        assertEquals("platform/judge", resolver.getString(QualitySettings.JUDGE_MODEL, com.agenticai.core.settings.SettingContext.tenant()));
        assertEquals(2, resolver.getInt(QualitySettings.EVAL_DEFAULT_REPEATS, com.agenticai.core.settings.SettingContext.tenant()));
    }
}
