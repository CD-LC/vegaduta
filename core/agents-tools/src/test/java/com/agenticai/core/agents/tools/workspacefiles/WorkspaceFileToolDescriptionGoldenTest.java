package com.agenticai.core.agents.tools.workspacefiles;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.tools.ToolDescriptionResolver;
import com.agenticai.core.agents.tools.workspacefiles.WorkspaceFileToolCallback.Kind;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptCatalog;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.prompts.tool.ToolPromptGolden;
import com.agenticai.core.prompts.tool.ToolPromptKeys;
import com.agenticai.core.prompts.tool.ToolPromptSchemas;
import com.agenticai.core.security.TenantContext;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

/**
 * Golden test (package 4e-2) for the five workspace file tools. Their descriptions end with the
 * label of the projects folder, which the code fills in at run time; the registry default carries it
 * as the plain text {@code {root}}. The default must still produce, for a real folder label, exactly
 * the text the code built before the keys existed (kept below as literals), and an override must
 * reach the tool through the same decorator as every other built-in tool.
 */
class WorkspaceFileToolDescriptionGoldenTest {

    private static final String LABEL = "/home/me/projects";
    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    /** The descriptions as they were hard-coded before the registry, for {@link #LABEL}. */
    private static final Map<Kind, String> BEFORE =
        Map.of(
            Kind.LIST, "Lists the files and folders in a workspace folder (dependency and build output folders such as node_modules, target and .git are skipped). Paths are relative to the person's projects folder (/home/me/projects); start them with the folder the person attached. Nothing outside it can be reached.",
            Kind.READ, "Reads a text file from the workspace, with line numbers. Use offset/limit for long files. Paths are relative to the person's projects folder (/home/me/projects); start them with the folder the person attached. Nothing outside it can be reached.",
            Kind.SEARCH, "Searches the workspace like a find-in-files: file names that contain the query, then lines of text files that contain every word of it (case-insensitive). Use it to find a document or a passage the person attached or mentioned, then workspace_read the file. PDFs the person attached have their text in a sibling '<name>.pdf.txt' file. Paths are relative to the person's projects folder (/home/me/projects); start them with the folder the person attached. Nothing outside it can be reached.",
            Kind.WRITE, "Creates or replaces a whole text file in the workspace (missing folders are created). Prefer workspace_edit for a small change to an existing file. Paths are relative to the person's projects folder (/home/me/projects); start them with the folder the person attached. Nothing outside it can be reached.",
            Kind.EDIT, "Replaces one exact snippet in a workspace file. old_text must appear exactly once (include enough surrounding lines); read the file first. Paths are relative to the person's projects folder (/home/me/projects); start them with the folder the person attached. Nothing outside it can be reached.");

    private final PromptCatalog catalog = PromptResolver.defaultsOnly().catalog();
    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver = new DefaultPromptResolver(catalog, store, new PromptProperties());
    private final ToolDescriptionResolver decorator = ToolDescriptionResolver.of(resolver);

    @TempDir Path dir;

    @BeforeEach
    void tenant() {
        TenantContext.set(TenantContext.of("tenant-a", "admin@a", Set.of("ROLE_tenant-admin")));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private WorkspaceFileToolCallback tool(Kind kind) {
        return new WorkspaceFileToolCallback(new WorkspaceFiles(dir), kind, LABEL);
    }

    @Test
    void theRegistryDefaultsEqualWhatTheCodeBuilds() {
        List<String> problems = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            // the registry default is a template with the label as {root}; the code fills it in
            ToolPromptGolden.Probed probed =
                ToolPromptGolden.probe(WorkspaceFileToolCallback.class, Map.of("kind", kind, "rootLabel", LABEL));
            assertThat(probed.name()).isEqualTo(kind.toolName);
            problems.addAll(
                ToolPromptGolden.problems(
                    catalog, kind.toolName, probed.description(), probed.inputSchema(), Map.of("root", LABEL)));
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void theDefaultTextForARealLabelIsByteIdenticalToTheOldLiteral() {
        for (Kind kind : Kind.values()) {
            String shipped = catalog.defaultText("tool.description." + kind.toolName, catalog.defaultLocale()).orElseThrow();
            assertThat(shipped.replace("{root}", LABEL)).as(kind.toolName + " registry default").isEqualTo(BEFORE.get(kind));
            assertThat(tool(kind).getToolDefinition().description()).as(kind.toolName + " code").isEqualTo(BEFORE.get(kind));
        }
    }

    @Test
    void theFileToolsAreDeclaredAsTemplatesAndSecurityCritical() {
        for (Kind kind : Kind.values()) {
            var def = catalog.find("tool.description." + kind.toolName).orElseThrow();
            assertThat(def.placeholderNames()).as(kind.toolName).containsExactly("root");
            assertThat(def.requiredPlaceholderNames()).as(kind.toolName).containsExactly("root");
            assertThat(def.securityCritical()).as(kind.toolName).isTrue();
            assertThat(def.disableable()).as(kind.toolName).isFalse();
        }
    }

    @Test
    void withoutAnOverrideTheToolIsReturnedUnchanged() {
        for (Kind kind : Kind.values()) {
            ToolCallback tool = tool(kind);
            assertThat(decorator.apply(tool, AGENT)).isSameAs(tool);
        }
    }

    @Test
    void anOverrideReachesTheToolPerAgentAndKeepsNameAndShape() {
        ToolCallback read = tool(Kind.READ);
        String schema = read.getToolDefinition().inputSchema();
        put(PromptScope.TENANT, "", "tool.description.workspace_read", "Read a file under {root}.");
        put(PromptScope.AGENT, AGENT.toString(), ToolPromptKeys.param("workspace_read", "limit"), "How many lines.");

        var def = decorator.apply(read, AGENT).getToolDefinition();
        assertThat(def.name()).isEqualTo("workspace_read");
        assertThat(def.description()).isEqualTo("Read a file under " + LABEL + ".");
        assertThat(ToolPromptSchemas.byPath(def.inputSchema())).containsEntry("limit", "How many lines.");
        assertThat(ToolPromptSchemas.sameShape(schema, def.inputSchema())).isTrue();

        // another agent: the tenant wording but the shipped parameter text
        var other = decorator.apply(read, UUID.fromString("00000000-0000-0000-0000-0000000000b9")).getToolDefinition();
        assertThat(other.description()).isEqualTo("Read a file under " + LABEL + ".");
        assertThat(ToolPromptSchemas.byPath(other.inputSchema())).isEqualTo(ToolPromptSchemas.byPath(schema));
    }

    private void put(PromptScope scope, String scopeId, String key, String text) {
        store.put(new PromptOverride(scope, scopeId, key, "en", text, true, 1, "tester", Instant.now()));
        resolver.evictAll();
    }
}
