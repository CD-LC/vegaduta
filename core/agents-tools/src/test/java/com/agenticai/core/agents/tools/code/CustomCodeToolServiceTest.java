package com.agenticai.core.agents.tools.code;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.code.dto.SandboxValidateResponse;
import com.agenticai.core.agents.tools.code.dto.SandboxValidateResponse.SandboxValidationError;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.secrets.SecretRedactor;
import com.agenticai.core.security.TenantLimitExceededException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Plain JUnit 5 + Mockito, no Spring context - same posture as the other tests in {@code
 * com.agenticai.core.agents.tools}. The repository, the sandbox client, and the audit log are all
 * mocks; what is under test is this service's own rules (naming, clamping, caps, and the "a
 * rejected tool is saved but never runnable" invariant the whole feature rests on).
 */
class CustomCodeToolServiceTest {

    private CustomCodeToolRepository repository;
    private ToolSandboxClient sandboxClient;
    private ToolSandboxProperties properties;
    private CustomCodeToolService service;

    private static final String SOURCE = "print(1)";

    @BeforeEach
    void setUp() {
        repository = mock(CustomCodeToolRepository.class);
        sandboxClient = mock(ToolSandboxClient.class);
        properties = new ToolSandboxProperties();
        service =
                new CustomCodeToolService(
                        repository,
                        properties,
                        providerOf(sandboxClient),
                        providerOf(mock(AuditLogService.class)),
                        providerOf(null),
                        providerOf(new SecretRedactor()));

        // Repository.save is identity by default - these tests assert on the
        // entity the service built, not on JPA.
        when(repository.save(any(CustomCodeTool.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.count()).thenReturn(0L);
        when(repository.existsByToolName(anyString())).thenReturn(false);
    }

    // ---------------------------------------------------------------- name

    @Test
    void acceptsWellFormedToolNames() {
        stubValid();
        for (String name : List.of("abc", "my_custom_tool", "a1_2", "tool_name_with_digits_9")) {
            CustomCodeTool created = create(name);
            assertThat(created.getToolName()).isEqualTo(name);
        }
    }

    @Test
    void rejectsMalformedToolNames() {
        stubValid();
        // Too short, leading digit, uppercase, hyphen, space, leading
        // underscore, and null - every shape the pattern is meant to exclude.
        for (String name :
                new String[] {"ab", "1abc", "MyTool", "my-tool", "my tool", "_tool", null}) {
            assertThatThrownBy(() -> create(name))
                    .as("tool name %s", name)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Tool name must be");
        }
    }

    @Test
    void rejectsDuplicateToolName() {
        stubValid();
        when(repository.existsByToolName("dup_tool")).thenReturn(true);
        assertThatThrownBy(() -> create("dup_tool"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    // ------------------------------------------------------- validation

    @Test
    void invalidSourcePersistsRejectedStatusAndErrorText() {
        when(sandboxClient.validate(any(), anyString(), anyString()))
                .thenReturn(
                        new SandboxValidateResponse(
                                false,
                                "deadbeef",
                                List.of(
                                        new SandboxValidationError(3, "cannot find symbol"),
                                        new SandboxValidationError(null, "unexpected EOF")),
                                List.of()));

        CustomCodeTool created = create("bad_tool");

        // Saved, not discarded - the tenant has to be able to see and fix this.
        verify(repository).save(created);
        assertThat(created.getStatus()).isEqualTo(CustomCodeToolStatus.REJECTED);
        assertThat(created.getValidationError())
                .contains("line 3: cannot find symbol")
                .contains("unexpected EOF");
        assertThat(created.isRunnable()).isFalse();
    }

    @Test
    void listRunnableExcludesRejectedTools() {
        // The exclusion is a repository-level filter, so what this asserts is
        // that the service asks for exactly VALIDATED+enabled and nothing else.
        when(repository.findAllByEnabledTrueAndStatus(CustomCodeToolStatus.VALIDATED))
                .thenReturn(List.of());

        assertThat(service.listRunnable()).isEmpty();

        verify(repository).findAllByEnabledTrueAndStatus(CustomCodeToolStatus.VALIDATED);
        verify(repository, never()).findAllByOrderByCreatedAtDesc();
    }

    @Test
    void validSourcePersistsValidatedStatus() {
        stubValid();
        CustomCodeTool created = create("good_tool");
        assertThat(created.getStatus()).isEqualTo(CustomCodeToolStatus.VALIDATED);
        assertThat(created.getValidationError()).isNull();
        assertThat(created.isRunnable()).isTrue();
        assertThat(created.getSourceSha256()).hasSize(64);
    }

    // ---------------------------------------------------------- enabling

    @Test
    void enablingARejectedToolThrows() {
        CustomCodeTool rejected = validatedTool("rejected_tool");
        rejected.markRejected("cannot find symbol");
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(java.util.Optional.of(rejected));

        assertThatThrownBy(() -> service.setEnabled(id, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be enabled until it passes validation");

        verify(repository, never()).save(any(CustomCodeTool.class));
    }

    @Test
    void disablingARejectedToolIsAllowed() {
        // Only ENABLING is gated - a tenant must always be able to switch
        // something off, whatever state it is in.
        CustomCodeTool rejected = validatedTool("rejected_tool");
        rejected.markRejected("boom");
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(java.util.Optional.of(rejected));

        assertThat(service.setEnabled(id, false).isEnabled()).isFalse();
    }

    // ---------------------------------------------------------- clamping

    @Test
    void clampsTimeoutAndMemoryToConfiguredMaximums() {
        stubValid();
        CustomCodeTool created =
                service.create(
                        "clamped_tool",
                        "Clamped",
                        null,
                        CustomCodeToolLanguage.PYTHON,
                        SOURCE,
                        null,
                        null,
                        999_999,
                        999_999);

        assertThat(created.getTimeoutMs()).isEqualTo(properties.getMaxTimeoutMs());
        assertThat(created.getMemoryMb()).isEqualTo(properties.getMaxMemoryMb());
    }

    @Test
    void clampsNonPositiveRequestsUpToOneAndDefaultsWhenAbsent() {
        stubValid();
        CustomCodeTool floored =
                service.create(
                        "floored_tool",
                        "Floored",
                        null,
                        CustomCodeToolLanguage.JAVA,
                        SOURCE,
                        null,
                        null,
                        0,
                        -5);
        assertThat(floored.getTimeoutMs()).isEqualTo(1);
        assertThat(floored.getMemoryMb()).isEqualTo(1);

        CustomCodeTool defaulted = create("defaulted_tool");
        assertThat(defaulted.getTimeoutMs()).isEqualTo(properties.getDefaultTimeoutMs());
        assertThat(defaulted.getMemoryMb()).isEqualTo(properties.getDefaultMemoryMb());
    }

    // -------------------------------------------------------------- caps

    @Test
    void exceedingMaxToolsPerTenantThrowsTenantLimitExceeded() {
        properties.setMaxToolsPerTenant(3);
        when(repository.count()).thenReturn(3L);

        assertThatThrownBy(() -> create("one_too_many"))
                .isInstanceOf(TenantLimitExceededException.class)
                .hasMessageContaining("maximum of 3");

        // Checked before the sandbox is bothered - no compile spent on code
        // that was never going to be accepted.
        verify(sandboxClient, never()).validate(any(), anyString(), anyString());
        verify(repository, never()).save(any(CustomCodeTool.class));
    }

    @Test
    void oversizedSourceIsRejected() {
        properties.setMaxSourceBytes(16);
        assertThatThrownBy(
                        () ->
                                service.create(
                                        "big_tool",
                                        "Big",
                                        null,
                                        CustomCodeToolLanguage.PYTHON,
                                        "x".repeat(100),
                                        null,
                                        null,
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }

    // ------------------------------------------------------ feature flag

    @Test
    void createThrowsWhenSandboxClientBeanIsAbsent() {
        CustomCodeToolService disabled =
                new CustomCodeToolService(
                        repository,
                        properties,
                        providerOf(null),
                        providerOf(null),
                        providerOf(null),
                        providerOf(null));

        assertThatThrownBy(
                        () ->
                                disabled.create(
                                        "disabled_tool",
                                        "Disabled",
                                        null,
                                        CustomCodeToolLanguage.JAVA,
                                        SOURCE,
                                        null,
                                        null,
                                        null,
                                        null))
                .isInstanceOf(CustomCodeToolsDisabledException.class)
                .hasMessageContaining("TOOL_SANDBOX_ENABLED=true");
    }

    // -------------------------------------------------------- versioning

    @Test
    void updateBumpsVersionOnlyWhenSourceChanges() {
        stubValid();
        CustomCodeTool tool = create("versioned_tool");
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(java.util.Optional.of(tool));
        String originalSha = tool.getSourceSha256();

        service.update(
                id,
                "Renamed",
                "desc",
                CustomCodeToolLanguage.PYTHON,
                SOURCE,
                null,
                null,
                null,
                null);
        assertThat(tool.getVersion()).isEqualTo(1);
        assertThat(tool.getSourceSha256()).isEqualTo(originalSha);

        service.update(
                id,
                "Renamed",
                "desc",
                CustomCodeToolLanguage.PYTHON,
                "print(2)",
                null,
                null,
                null,
                null);
        assertThat(tool.getVersion()).isEqualTo(2);
        assertThat(tool.getSourceSha256()).isNotEqualTo(originalSha);
    }

    @Test
    void auditIsRecordedOnCreate() {
        stubValid();
        AuditLogService audit = mock(AuditLogService.class);
        CustomCodeToolService audited =
                new CustomCodeToolService(
                        repository,
                        properties,
                        providerOf(sandboxClient),
                        providerOf(audit),
                        providerOf(null),
                        providerOf(new SecretRedactor()));

        audited.create(
                "audited_tool",
                "Audited",
                null,
                CustomCodeToolLanguage.JAVA,
                SOURCE,
                null,
                null,
                null,
                null);

        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        verify(audit)
                .record(
                        anyString(),
                        action.capture(),
                        eq("custom_code_tool"),
                        anyString(),
                        any(),
                        anyString());
        assertThat(action.getValue()).isEqualTo("custom_code_tool.create");
    }

    // ------------------------------------------------------------ helpers

    private void stubValid() {
        when(sandboxClient.validate(any(), anyString(), anyString()))
                .thenReturn(new SandboxValidateResponse(true, "deadbeef", null, List.of()));
    }

    private CustomCodeTool create(String toolName) {
        return service.create(
                toolName,
                "Display",
                "A tool.",
                CustomCodeToolLanguage.PYTHON,
                SOURCE,
                null,
                null,
                null,
                null);
    }

    private CustomCodeTool validatedTool(String toolName) {
        CustomCodeTool tool =
                new CustomCodeTool(
                        toolName,
                        "Display",
                        "A tool.",
                        CustomCodeToolLanguage.PYTHON,
                        SOURCE,
                        null,
                        null,
                        "deadbeef",
                        5000,
                        128,
                        "tester");
        tool.markValidated();
        return tool;
    }

    /**
     * Mocked rather than hand-implemented: {@code getIfAvailable()} is the only method the service
     * calls, and a stub keeps this test independent of which of {@link ObjectProvider}'s many
     * methods are abstract in a given Spring version. {@code providerOf(null)} models the bean
     * simply not existing.
     */
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }
}
