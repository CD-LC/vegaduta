package com.agenticai.core.agents.turn;

import java.util.List;
import java.util.function.BooleanSupplier;
import org.springframework.ai.tool.ToolCallback;

/**
 * Composite of the standing prompt sections that depend only on the turn's tool list: tool-trust,
 * agentic-completion and untrusted-data. Each {@link Section} contributes a block or null; {@link
 * #compose} joins the non-null blocks in declaration order with the same separator {@link
 * ChatPromptDirectives#joinMemoryBlocks} uses everywhere, so the result is byte-identical to
 * passing the three directives as adjacent arguments to that method.
 *
 * <p>Gates are suppliers read at compose time (not at construction) because the {@code
 * app.chat.directives.*} flags are field-injected after the owning service is constructed.
 */
final class PromptDirectiveComposer {

    /** What a section may look at: the turn's offered tools and whether any are MCP tools. */
    /** {@code compact}: small-model mode (V501), the short form of every directive that has one. */
    record Context(
            List<ToolCallback> tools,
            boolean anyMcpToolsOffered,
            boolean compact,
            ResolvedDirectives directives) {
        Context(List<ToolCallback> tools, boolean anyMcpToolsOffered) {
            this(tools, anyMcpToolsOffered, false);
        }

        Context(List<ToolCallback> tools, boolean anyMcpToolsOffered, boolean compact) {
            this(tools, anyMcpToolsOffered, compact, ResolvedDirectives.DEFAULTS);
        }
    }

    /** One independently-gated block of the standing system text. */
    @FunctionalInterface
    interface Section {
        /** The block for this turn, or null when it does not apply. */
        String render(Context context);
    }

    private final List<Section> sections;

    PromptDirectiveComposer(List<Section> sections) {
        this.sections = List.copyOf(sections);
    }

    /** The production composition: tool-trust, agentic-completion, untrusted-data, in that order. */
    static PromptDirectiveComposer standard(
            BooleanSupplier toolTrustEnabled, BooleanSupplier mcpUntrustedEnabled) {
        return new PromptDirectiveComposer(
                List.of(
                        toolTrust(toolTrustEnabled),
                        agenticCompletion(),
                        untrustedData(mcpUntrustedEnabled)));
    }

    static Section toolTrust(BooleanSupplier enabled) {
        return ctx ->
                ctx.directives()
                        .resolve(
                                PromptDirectiveRegistry.TOOL_TRUST,
                                enabled.getAsBoolean()
                                        ? ChatPromptDirectives.toolTrustDirective(
                                                ctx.tools(), ctx.compact())
                                        : null);
    }

    static Section agenticCompletion() {
        return ctx ->
                ctx.directives()
                        .resolve(
                                PromptDirectiveRegistry.AGENTIC_COMPLETION,
                                ChatPromptDirectives.agenticCompletionDirective(ctx.tools()));
    }

    static Section untrustedData(BooleanSupplier enabled) {
        return ctx ->
                ctx.directives()
                        .resolve(
                                PromptDirectiveRegistry.MCP_UNTRUSTED,
                                enabled.getAsBoolean()
                                        ? ChatPromptDirectives.mcpUntrustedDataDirective(
                                                ctx.anyMcpToolsOffered())
                                        : null);
    }

    /** The joined block, or null when no section applies. */
    String compose(Context context) {
        String[] blocks = new String[sections.size()];
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = sections.get(i).render(context);
        }
        return ChatPromptDirectives.joinMemoryBlocks(blocks);
    }
}
