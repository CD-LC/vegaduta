package com.agenticai.core.agents.tools.search;

import com.agenticai.core.guardrails.GuardrailPromptScope;
import com.agenticai.core.guardrails.GuardrailService;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Screens the authored text a deferred tool contributes to the {@code search_tools} catalog - its
 * name, its description and the schema text a result can show - once, when the catalog entry is
 * built, with the same {@link GuardrailService#sanitizeToolOutput} the per-call output screen uses,
 * instead of re-running that screen over the same static text on every search result (and
 * withholding the WHOLE result when a single description trips it).
 *
 * <p>A flagged description or schema is replaced with a fixed placeholder while the tool stays
 * searchable - and callable - by name; a flagged NAME cannot be shown at all, so the tool leaves the
 * catalog for that turn. Because every piece of outside-authored text a {@link
 * SearchToolsToolCallback} result can embed passed through this screen first, a catalog built with
 * it lets the search result itself be marked the platform's answer ({@code ToolResultMarker}): the
 * per-call screen then has nothing left to check and the journals know a catalog read is not an
 * executed effect.
 *
 * <p>Screen verdicts are cached per agent by text: descriptions are static per tool, so a turn that
 * re-resolves the same catalog pays the screen only the first time. The cache is bounded - a server
 * minting unique descriptions cannot grow it without limit.
 */
public final class SearchCatalogScreen {

    /** What a flagged description is replaced with. */
    static final String DESCRIPTION_WITHHELD = "[description withheld by content screen]";

    /** What a flagged input schema is replaced with: the name stays searchable, the parameters do not surface. */
    static final String SCHEMA_WITHHELD =
            "{\"type\":\"object\",\"description\":\"[schema withheld by content screen]\"}";

    private static final int CACHE_MAX = 4096;

    private static final Logger log = LoggerFactory.getLogger(SearchCatalogScreen.class);

    private final GuardrailService guardrails;

    private final Map<String, Optional<String>> screened =
            Collections.synchronizedMap(
                    new LinkedHashMap<>(1024, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, Optional<String>> e) {
                            return size() > CACHE_MAX;
                        }
                    });

    public SearchCatalogScreen(GuardrailService guardrails) {
        this.guardrails = guardrails;
    }

    /**
     * The catalog entry for {@code tool}: {@code tool} itself when nothing tripped the screen, a
     * decorator serving the screened {@link ToolDefinition} when a description or schema did, or
     * {@code null} when the tool's NAME is withheld (there is no safe way to list it). {@code
     * chatClient} feeds the screen's semantic layer, same as the per-call screen's; null runs the
     * pattern layer only.
     */
    public ToolCallback screened(ToolCallback tool, ChatClient chatClient, UUID agentId) {
        ToolDefinition definition = tool.getToolDefinition();
        String name = definition.name();
        String screenedName = name == null || name.isBlank() ? name : screen(name, name, chatClient, agentId);
        if (screenedName == null || !screenedName.equals(name)) {
            log.warn("Catalog screen withheld tool name '{}': dropping the tool from this turn's catalog", name);
            return null;
        }
        String description = definition.description();
        if (description != null && !description.isBlank()) {
            String screenedText = screen(name, description, chatClient, agentId);
            description =
                    screenedText == null || screenedText.isBlank()
                            ? DESCRIPTION_WITHHELD
                            : screenedText;
        }
        String schema = screenedSchema(name, definition.inputSchema(), chatClient, agentId);
        return java.util.Objects.equals(description, definition.description())
                        && schema == definition.inputSchema()
                ? tool
                : new ScreenedDefinition(tool, name, description, schema);
    }

    /**
     * Only what a search result can ever print is screened: the compact schema when it is inlined,
     * the parameter-name list standing in for it when it is not. A withheld schema becomes {@link
     * #SCHEMA_WITHHELD} - searchable by name, honest about what is missing.
     */
    private String screenedSchema(String toolName, String inputSchema, ChatClient chatClient, UUID agentId) {
        if (inputSchema == null || inputSchema.isBlank()) {
            return inputSchema;
        }
        String compact = SearchToolsToolCallback.compact(inputSchema);
        String visible =
                compact.length() <= SearchToolsToolCallback.MAX_INLINE_SCHEMA_CHARS
                        ? compact
                        : String.join(", ", ToolSearchService.parameterNames(inputSchema));
        return visible.isBlank() || screen(toolName, visible, chatClient, agentId) != null
                ? inputSchema
                : SCHEMA_WITHHELD;
    }

    /** The screened text, or {@code null} when the screen withheld it; cached per agent+text. */
    private String screen(String toolName, String text, ChatClient chatClient, UUID agentId) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String key = agentId + "\n" + text;
        Optional<String> hit = screened.get(key);
        if (hit == null) {
            hit = Optional.ofNullable(runScreen(toolName, text, chatClient, agentId));
            screened.put(key, hit);
        }
        return hit.orElse(null);
    }

    private String runScreen(String toolName, String text, ChatClient chatClient, UUID agentId) {
        try (GuardrailPromptScope scope = GuardrailPromptScope.open(agentId)) {
            return guardrails.sanitizeToolOutput(toolName, text, chatClient);
        } catch (RuntimeException e) {
            // A GuardrailViolationException means flagged; any other failure cannot tell safe from
            // hostile either - the fail-closed posture the per-call screen's default fail-mode takes.
            log.warn("Catalog screen withheld text of tool '{}': {}", toolName, e.toString());
            return null;
        }
    }

    /**
     * Serves the screened definition while every call goes to the original callback untouched - the
     * deferred entries are the full guard/log chain already, and {@code call_tool} must reach
     * exactly that.
     */
    private static final class ScreenedDefinition implements ToolCallback {

        private final ToolCallback delegate;
        private final ToolDefinition definition;

        ScreenedDefinition(ToolCallback delegate, String name, String description, String inputSchema) {
            this.delegate = delegate;
            this.definition =
                    ToolDefinition.builder()
                            .name(name)
                            .description(
                                    description == null || description.isBlank()
                                            ? "(no description)"
                                            : description)
                            .inputSchema(
                                    inputSchema == null || inputSchema.isBlank()
                                            ? "{}"
                                            : inputSchema)
                            .build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return delegate.call(toolInput);
        }

        @Override
        public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
            return delegate.call(toolInput, toolContext);
        }
    }
}
