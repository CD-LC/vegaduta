package com.agenticai.core.agents.tools.localshell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.ToolDescriptionResolver;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptCatalog;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.prompts.tool.ToolPromptGolden;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/**
 * local_shell and local_shell_read build their description from three pieces of run time state: the
 * runner's environment note, and whether calls need approval. The registry holds one template per
 * wording, with the note as {@code {environment}}.
 */
class LocalShellToolDescriptionTest {

    private final PromptCatalog catalog = PromptResolver.defaultsOnly().catalog();
    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver prompts = new DefaultPromptResolver(catalog, store, new PromptProperties());
    private ShellRunner runner;

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("tenant-a", "admin@a", Set.of("ROLE_tenant-admin")));
        runner = mock(ShellRunner.class, CALLS_REAL_METHODS);
        when(runner.workspace()).thenReturn("/ws/demo");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ToolCallback tool(boolean readOnly, boolean approvalRequired) {
        return new LocalShellToolCallback(runner, readOnly, 30, 300, approvalRequired);
    }

    private String idOf(boolean readOnly, boolean approvalRequired) {
        return readOnly ? "local_shell_read" : approvalRequired ? "local_shell" : "local_shell.unattended";
    }

    @Test
    void everyWordingsRegistryTemplateFilledWithTheEnvironmentNoteEqualsWhatTheCodeBuilds() {
        for (boolean readOnly : new boolean[] {true, false}) {
            for (boolean approval : new boolean[] {true, false}) {
                var def = tool(readOnly, approval).getToolDefinition();
                assertThat(ToolPromptGolden.problems(
                                catalog,
                                idOf(readOnly, approval),
                                def.description(),
                                def.inputSchema(),
                                Map.of("environment", runner.environmentNote())))
                        .as("readOnly=%s approval=%s", readOnly, approval)
                        .isEmpty();
            }
        }
    }

    private void override(PromptScope scope, String key, String text) {
        store.put(new PromptOverride(scope, "", key, "en", text, true, 1, "t", Instant.now()));
        prompts.evictAll();
    }

    @Test
    void anOverrideKeepsTheEnvironmentNoteTheRunnerReportsAndOnlyForTheMatchingWording() {
        override(PromptScope.TENANT, "tool.description.local_shell", "Shell. {environment} Ask first.");
        ToolDescriptionResolver decorator = ToolDescriptionResolver.of(prompts);

        String withApproval = decorator.apply(tool(false, true), null).getToolDefinition().description();
        String unattended = decorator.apply(tool(false, false), null).getToolDefinition().description();
        String read = decorator.apply(tool(true, true), null).getToolDefinition().description();

        assertThat(withApproval).isEqualTo("Shell. " + runner.environmentNote() + " Ask first.");
        assertThat(unattended).isEqualTo(tool(false, false).getToolDefinition().description());
        assertThat(read).isEqualTo(tool(true, true).getToolDefinition().description());
    }

    @Test
    void theUnattendedWordingHasItsOwnKeyAndSharesTheParametersOfTheShell() {
        override(PromptScope.TENANT, "tool.description.local_shell.unattended", "Unattended. {environment}");
        override(PromptScope.TENANT, "tool.param.local_shell.command", "The command to run.");
        ToolDescriptionResolver decorator = ToolDescriptionResolver.of(prompts);

        var def = decorator.apply(tool(false, false), null).getToolDefinition();

        assertThat(def.description()).startsWith("Unattended. ");
        assertThat(def.inputSchema()).contains("The command to run.");
        assertThat(def.name()).isEqualTo("local_shell");
        // The approval-required wording is a different key and is untouched.
        assertThat(decorator.apply(tool(false, true), null).getToolDefinition().description())
                .isEqualTo(tool(false, true).getToolDefinition().description());
    }
}
