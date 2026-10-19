package com.agenticai.core.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.skills.AgentSkillConfigRepository;
import com.agenticai.core.agents.skills.BuiltInSkillCatalog;
import com.agenticai.core.agents.skills.SkillBuiltInOverrideRepository;
import com.agenticai.core.agents.skills.SkillRepository;
import com.agenticai.core.agents.skills.SkillService;
import com.agenticai.core.agents.tools.code.CustomCodeTool;
import com.agenticai.core.agents.tools.code.CustomCodeToolLanguage;
import com.agenticai.core.agents.tools.code.CustomCodeToolRepository;
import com.agenticai.core.agents.tools.code.CustomCodeToolService;
import com.agenticai.core.agents.tools.code.CustomCodeToolSnapshot;
import com.agenticai.core.agents.tools.code.CustomCodeToolStatus;
import com.agenticai.core.agents.tools.code.ToolSandboxProperties;
import com.agenticai.core.agents.tools.openapi.OpenApiTool;
import com.agenticai.core.agents.tools.openapi.OpenApiToolRepository;
import com.agenticai.core.agents.tools.openapi.OpenApiToolSnapshot;
import com.agenticai.core.agents.tools.openapi.OpenApiToolSource;
import com.agenticai.core.agents.tools.openapi.OpenApiToolSourceRepository;
import com.agenticai.core.agents.tools.openapi.OpenApiToolSourceService;
import com.agenticai.core.agents.tools.script.UserToolScript;
import com.agenticai.core.agents.tools.script.UserToolScriptRepository;
import com.agenticai.core.agents.tools.script.UserToolScriptService;
import com.agenticai.core.agents.tools.script.UserToolScriptSnapshot;
import com.agenticai.core.security.HotpathCaches;
import com.agenticai.core.security.HotpathLookups;
import com.agenticai.core.security.TenantContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The tool-resolution reads that used to be one database transaction each per turn: user scripts,
 * custom code tools, OpenAPI tools and the skill snapshots. Each is served through {@link
 * HotpathLookups} as an immutable record, evicted by a write, and a TTL of zero reads the row on
 * every call exactly as before.
 */
class ToolResolutionSnapshotsTest {

    private static final String TENANT = "tenant_snapshots";
    private static final UUID AGENT = UUID.randomUUID();

    @BeforeEach
    void tenantWithCacheOn() {
        TenantContext.set(TenantContext.of(TENANT, "user", Set.of()));
        HotpathLookups.configure(Duration.ofSeconds(3));
    }

    @AfterEach
    void cacheOffAndClean() {
        HotpathLookups.configure(Duration.ZERO);
        HotpathCaches.evictTenantConfig(null);
        TenantContext.clear();
    }

    // ------------------------------------------------------------ user scripts

    @Test
    void userScriptsAreReadOnceThenServedFromTheCacheAndEvictedByAWrite() {
        UserToolScriptRepository repository = mock(UserToolScriptRepository.class);
        when(repository.findAllByEnabledTrue())
                .thenReturn(
                        List.of(
                                new UserToolScript(
                                        "double_it", "Double", "Doubles x", "#x * 2", null, true)));
        UserToolScriptService service = new UserToolScriptService(repository, null);

        List<UserToolScriptSnapshot> first = service.listEnabledSnapshots();
        List<UserToolScriptSnapshot> second = service.listEnabledSnapshots();

        assertThat(second).isEqualTo(first);
        assertThat(first)
                .containsExactly(
                        new UserToolScriptSnapshot(null, "double_it", "Doubles x", "#x * 2", null));
        verify(repository, times(1)).findAllByEnabledTrue();

        HotpathCaches.evictTenantConfig(TENANT);
        service.listEnabledSnapshots();
        verify(repository, times(2)).findAllByEnabledTrue();
    }

    @Test
    void userScriptsAreReadEveryTimeWhenTheTtlIsZero() {
        HotpathLookups.configure(Duration.ZERO);
        UserToolScriptRepository repository = mock(UserToolScriptRepository.class);
        when(repository.findAllByEnabledTrue()).thenReturn(List.of());
        UserToolScriptService service = new UserToolScriptService(repository, null);

        service.listEnabledSnapshots();
        service.listEnabledSnapshots();

        verify(repository, times(2)).findAllByEnabledTrue();
    }

    // -------------------------------------------------------- custom code tools

    private static CustomCodeTool runnableCodeTool() {
        return new CustomCodeTool(
                "sum_tool",
                "Sum",
                "Adds numbers.",
                CustomCodeToolLanguage.PYTHON,
                "print(1)",
                null,
                "the sum",
                "sha",
                5000,
                128,
                "tester");
    }

    private static CustomCodeToolService codeService(CustomCodeToolRepository repository) {
        @SuppressWarnings("unchecked")
        ObjectProvider<com.agenticai.core.agents.tools.code.ToolSandboxClient> none =
                mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.agenticai.core.audit.AuditLogService> audit = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.agenticai.core.secrets.SecretVaultService> vault =
                mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.agenticai.core.secrets.SecretRedactor> redactor =
                mock(ObjectProvider.class);
        return new CustomCodeToolService(
                repository, new ToolSandboxProperties(), none, audit, vault, redactor);
    }

    @Test
    void customCodeToolsAreSnapshotsWithoutTheSourceAndCachedUntilAWrite() {
        CustomCodeToolRepository repository = mock(CustomCodeToolRepository.class);
        when(repository.findAllByEnabledTrueAndStatus(CustomCodeToolStatus.VALIDATED))
                .thenReturn(List.of(runnableCodeTool()));
        CustomCodeToolService service = codeService(repository);

        List<CustomCodeToolSnapshot> first = service.listRunnableSnapshots();
        List<CustomCodeToolSnapshot> second = service.listRunnableSnapshots();

        assertThat(first).hasSize(1).isEqualTo(second);
        assertThat(first.get(0).toolName()).isEqualTo("sum_tool");
        assertThat(first.get(0).outputDescription()).isEqualTo("the sum");
        assertThat(first.get(0).toString()).doesNotContain("print(1)");
        verify(repository, times(1)).findAllByEnabledTrueAndStatus(CustomCodeToolStatus.VALIDATED);

        HotpathCaches.evictTenantConfig(TENANT);
        service.listRunnableSnapshots();
        verify(repository, times(2)).findAllByEnabledTrueAndStatus(CustomCodeToolStatus.VALIDATED);
    }

    @Test
    void aCallToAToolThatIsNoLongerRunnableGetsErrorJsonNotAnException() {
        CustomCodeToolRepository repository = mock(CustomCodeToolRepository.class);
        UUID gone = UUID.randomUUID();
        when(repository.findById(gone)).thenReturn(Optional.empty());
        CustomCodeToolService service = codeService(repository);

        assertThat(service.executeById(gone, "{}"))
                .contains("\"error\"")
                .contains("no longer available");

        CustomCodeTool disabled = runnableCodeTool();
        disabled.setEnabled(false);
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(disabled));
        assertThat(service.executeById(id, "{}")).contains("no longer available");
    }

    // ------------------------------------------------------------ OpenAPI tools

    @Test
    void openApiToolsAreSnapshotsCachedUntilAWriteAndTheSourceNameIsCopied() {
        OpenApiToolRepository tools = mock(OpenApiToolRepository.class);
        OpenApiTool tool = mock(OpenApiTool.class);
        OpenApiToolSource source = mock(OpenApiToolSource.class);
        UUID id = UUID.randomUUID();
        when(source.getName()).thenReturn("Petstore");
        when(tool.getId()).thenReturn(id);
        when(tool.getToolName()).thenReturn("petstore_get_pet");
        when(tool.getHttpMethod()).thenReturn("GET");
        when(tool.getPathTemplate()).thenReturn("/pet/{id}");
        when(tool.getSource()).thenReturn(source);
        when(tools.findRunnable()).thenReturn(List.of(tool));
        OpenApiToolSourceService service =
                new OpenApiToolSourceService(
                        mock(OpenApiToolSourceRepository.class), tools, null, null, null, null);

        List<OpenApiToolSnapshot> first = service.listRunnableSnapshots();
        List<OpenApiToolSnapshot> second = service.listRunnableSnapshots();

        assertThat(second).isEqualTo(first);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).id()).isEqualTo(id);
        assertThat(first.get(0).sourceName()).isEqualTo("Petstore");
        verify(tools, times(1)).findRunnable();

        HotpathCaches.evictTenantConfig(TENANT);
        service.listRunnableSnapshots();
        verify(tools, times(2)).findRunnable();
    }

    // ------------------------------------------------------------------ skills

    private static SkillService skills(SkillRepository skills, AgentSkillConfigRepository configs) {
        SkillBuiltInOverrideRepository overrides = mock(SkillBuiltInOverrideRepository.class);
        when(overrides.findAll()).thenReturn(List.of());
        return new SkillService(mock(BuiltInSkillCatalog.class), skills, overrides, configs);
    }

    @Test
    void skillMetadataAndSettingsAreCachedPerAgentUntilAWrite() {
        SkillRepository skillRepository = mock(SkillRepository.class);
        when(skillRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of());
        AgentSkillConfigRepository configs = mock(AgentSkillConfigRepository.class);
        when(configs.findById(any())).thenReturn(Optional.empty());
        SkillService service = skills(skillRepository, configs);

        assertThat(service.metadataForTurn(AGENT)).isEmpty();
        assertThat(service.metadataForTurn(AGENT)).isEmpty();
        assertThat(service.settingsForTurn(AGENT).skillsEnabled()).isTrue();
        assertThat(service.settingsForTurn(AGENT).allowedSkillNames()).isEmpty();

        verify(skillRepository, times(1)).findAllByEnabledTrueOrderByNameAsc();
        // metadata reads the settings itself once; settingsForTurn is its own lookup, read once.
        verify(configs, times(2)).findById(AGENT);

        HotpathCaches.evictTenantConfig(TENANT);
        service.metadataForTurn(AGENT);
        verify(skillRepository, times(2)).findAllByEnabledTrueOrderByNameAsc();
    }

    @Test
    void skillSnapshotsReadEveryTimeWhenTheTtlIsZero() {
        HotpathLookups.configure(Duration.ZERO);
        SkillRepository skillRepository = mock(SkillRepository.class);
        when(skillRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of());
        AgentSkillConfigRepository configs = mock(AgentSkillConfigRepository.class);
        when(configs.findById(any())).thenReturn(Optional.empty());
        SkillService service = skills(skillRepository, configs);

        service.metadataForTurn(AGENT);
        service.metadataForTurn(AGENT);

        verify(skillRepository, times(2)).findAllByEnabledTrueOrderByNameAsc();
    }

    @Test
    void cachedAnswersAreNeverSharedAcrossTenants() {
        UserToolScriptRepository repository = mock(UserToolScriptRepository.class);
        when(repository.findAllByEnabledTrue()).thenReturn(List.of());
        UserToolScriptService service = new UserToolScriptService(repository, null);

        service.listEnabledSnapshots();
        TenantContext.set(TenantContext.of("tenant_other", "user", Set.of()));
        service.listEnabledSnapshots();

        verify(repository, times(2)).findAllByEnabledTrue();
    }
}
