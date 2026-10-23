package com.agenticai.core.agents.tools.desktop;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The local_desktop_* tools: the registry's text per action equals what the code builds, with and
 * without the sentence the code appends when every call needs approval.
 */
class DesktopToolDescriptionTest {

    private static final String APPROVAL_NOTE = " Each call is shown to the user for approval before it runs.";

    private final PromptCatalog catalog = PromptResolver.defaultsOnly().catalog();
    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver prompts = new DefaultPromptResolver(catalog, store, new PromptProperties());

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("tenant-a", "admin@a", Set.of("ROLE_tenant-admin")));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void everyActionsRegistryDefaultsEqualWhatTheCodeBuilds() {
        List<String> problems = new java.util.ArrayList<>();
        for (DesktopToolCallback.Action action : DesktopToolCallback.Action.values()) {
            for (boolean approval : new boolean[] {true, false}) {
                ToolPromptGolden.Probed probed =
                        ToolPromptGolden.probe(DesktopToolCallback.class, Map.of("action", action, "approvalRequired", approval));
                assertThat(probed.name()).isEqualTo(action.toolName);
                String note = approval && !action.benign ? APPROVAL_NOTE : "";
                problems.addAll(
                        ToolPromptGolden.problems(
                                catalog,
                                probed.name(),
                                probed.description(),
                                probed.inputSchema(),
                                Map.of("approval_note", note)));
            }
        }
        assertThat(problems).isEmpty();
    }

    private DesktopToolCallback tool(DesktopToolCallback.Action action, boolean approval) {
        return new DesktopToolCallback(null, action, approval);
    }

    private void override(String key, String text) {
        store.put(new PromptOverride(PromptScope.TENANT, "", key, "en", text, true, 1, "t", Instant.now()));
        prompts.evictAll();
    }

    @Test
    void anOverrideCanKeepOrDropTheApprovalSentence() {
        ToolDescriptionResolver decorator = ToolDescriptionResolver.of(prompts);
        override("tool.description.local_desktop_press", "Press a labelled control.{approval_note}");
        assertThat(decorator.apply(tool(DesktopToolCallback.Action.PRESS, true), null).getToolDefinition().description())
                .isEqualTo("Press a labelled control." + APPROVAL_NOTE);
        assertThat(decorator.apply(tool(DesktopToolCallback.Action.PRESS, false), null).getToolDefinition().description())
                .isEqualTo("Press a labelled control.");

        override("tool.description.local_desktop_press", "Press a labelled control.");
        assertThat(decorator.apply(tool(DesktopToolCallback.Action.PRESS, true), null).getToolDefinition().description())
                .isEqualTo("Press a labelled control.");
    }

    @Test
    void anActionWithoutAnOverrideIsReturnedAsTheSameObject() {
        ToolDescriptionResolver decorator = ToolDescriptionResolver.of(prompts);
        DesktopToolCallback tool = tool(DesktopToolCallback.Action.CLICK, true);
        assertThat(decorator.apply(tool, null)).isSameAs(tool);
    }
}
