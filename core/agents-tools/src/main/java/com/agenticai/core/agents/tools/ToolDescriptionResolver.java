package com.agenticai.core.agents.tools;

import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.tool.ToolPromptTexts;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Applies the prompt registry's overrides of tool descriptions to the tool list of one turn: the
 * single place where {@code tool.description.<id>} and {@code tool.param.<id>.<path>} (see {@link
 * ToolPromptKeys}) reach the model, for every built-in tool at once, so no tool class has to look
 * a text up itself.
 *
 * <p><b>When.</b> {@link ToolResolver} calls it while it builds a turn's list, on the request
 * thread, with the agent the list is for. That covers every path that resolves tools: streaming
 * and buffered turns, delegation and specialist hand-offs, agent jobs, the continuation after an
 * approval and the prompt preview. The texts are resolved there and then (not when the model call
 * later asks for the definition), so a tool that is invoked on another thread keeps the wording
 * its turn started with.
 *
 * <p><b>Default is untouched.</b> A tool whose texts resolve to their shipped defaults is returned
 * as it is: the same object, the same string the code built. Only a tool with an override at the
 * agent, tenant or platform scope is wrapped, so with no overrides the model sees byte-identical
 * tool lists and nothing downstream sees a different class.
 *
 * <p><b>Only the words change.</b> The decorator rewrites the {@code description} text of the tool
 * and of schema properties that already have one. The tool name is never touched, and the result is
 * checked to be the same schema apart from those texts ({@link ToolPromptSchemas#sameShape}); if it
 * is not, the override is dropped and the original definition is used.
 *
 * <p><b>Which tools.</b> A tool takes part when the catalog has a {@code tool.description.<name>}
 * key. MCP tools, user-defined tools and any other tool without a key pass through unchanged. When
 * two implementations share a name, the catalog has the second as {@code <name>.<variant>}; the
 * variant whose shipped text equals the description the code just built is the one that applies.
 * A description key with placeholders is a template the code fills in at run time (the shell's
 * environment note, a roster); the values are recovered from the text the code built, so an
 * override can use the same placeholders. A tool that renders its own override while it is built
 * (the delegation tools, through {@link ToolPromptTexts#current()}) no longer matches the shipped
 * template and is left as it is.
 */
@Component
public final class ToolDescriptionResolver {

    private static final Logger log = LoggerFactory.getLogger(ToolDescriptionResolver.class);

    private final ObjectProvider<PromptResolver> prompts;

    @Autowired
    public ToolDescriptionResolver(ObjectProvider<PromptResolver> prompts) {
        this.prompts = prompts;
    }

    private ToolDescriptionResolver(PromptResolver fixed) {
        this.prompts =
                new ObjectProvider<>() {
                    @Override
                    public PromptResolver getObject() {
                        return fixed;
                    }

                    @Override
                    public PromptResolver getObject(Object... args) {
                        return fixed;
                    }

                    @Override
                    public PromptResolver getIfAvailable() {
                        return fixed;
                    }

                    @Override
                    public PromptResolver getIfUnique() {
                        return fixed;
                    }
                };
    }

    /** For code that builds tools by hand (tests, static helpers). */
    public static ToolDescriptionResolver of(PromptResolver resolver) {
        return new ToolDescriptionResolver(resolver);
    }

    /** The shipped texts only: every tool comes back unchanged. */
    public static ToolDescriptionResolver defaultsOnly() {
        return new ToolDescriptionResolver(PromptResolver.defaultsOnly());
    }

    /** Each tool with the registry's current wording for {@code agentId} (null: no agent-scope override). */
    public List<ToolCallback> apply(List<ToolCallback> tools, UUID agentId) {
        PromptResolver resolver = prompts.getIfAvailable();
        if (resolver == null || tools.isEmpty()) {
            return tools;
        }
        ToolPromptTexts texts = ToolPromptTexts.of(resolver, agentId);
        List<ToolCallback> out = new ArrayList<>(tools.size());
        for (ToolCallback tool : tools) {
            out.add(applyOne(texts, tool));
        }
        return out;
    }

    public ToolCallback apply(ToolCallback tool, UUID agentId) {
        PromptResolver resolver = prompts.getIfAvailable();
        return resolver == null ? tool : applyOne(ToolPromptTexts.of(resolver, agentId), tool);
    }

    /**
     * Runs {@code body} with this registry bound for {@code agentId}, so a tool that renders its own
     * template at construction ({@link ToolPromptTexts#current()}) answers for that agent. The
     * tool resolver wraps the whole building of a turn's tool list in it.
     */
    public <T> T within(UUID agentId, Supplier<T> body) {
        PromptResolver resolver = prompts.getIfAvailable();
        if (resolver == null) {
            return body.get();
        }
        return ToolPromptTexts.within(ToolPromptTexts.of(resolver, agentId), body);
    }

    private ToolCallback applyOne(ToolPromptTexts texts, ToolCallback tool) {
        try {
            ToolDefinition definition = tool.getToolDefinition();
            if (definition == null || definition.name() == null) {
                return tool;
            }
            String id = texts.idFor(definition.name(), definition.description());
            if (id == null) {
                return tool;
            }
            String overridden = texts.overriddenDescription(id, definition.description());
            String description = overridden != null ? overridden : definition.description();
            String schema = texts.schema(id, definition.inputSchema());
            if (Objects.equals(description, definition.description())
                    && Objects.equals(schema, definition.inputSchema())) {
                return tool;
            }
            return new Described(
                    tool,
                    ToolDefinition.builder().name(definition.name()).description(description).inputSchema(schema).build());
        } catch (RuntimeException e) {
            // A prompt problem must never break a turn: the tool keeps the wording its code built.
            log.warn("Tool text overrides could not be applied; using the built-in wording: {}", e.toString());
            return tool;
        }
    }

    /** The tool with a registry-resolved definition; everything else is the wrapped tool's. */
    private static final class Described implements ToolCallback {
        private final ToolCallback delegate;
        private final ToolDefinition definition;

        Described(ToolCallback delegate, ToolDefinition definition) {
            this.delegate = delegate;
            this.definition = definition;
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
        public String call(String toolInput, ToolContext toolContext) {
            return delegate.call(toolInput, toolContext);
        }
    }
}
