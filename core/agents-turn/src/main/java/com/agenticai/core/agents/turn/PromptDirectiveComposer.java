package com.agenticai.core.agents.turn;

import com.agenticai.core.prompts.ResolvedPrompt;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.springframework.ai.tool.ToolCallback;

/**
 * Composite of the standing prompt sections that depend only on the turn's tool list: tool-trust
 * and untrusted-data (the retired agentic-completion section sent nothing and is gone). Each {@link
 * Section} contributes a resolved prompt or null; {@link #compose} joins the active texts in
 * declaration order with the same separator {@link ChatPromptDirectives#joinMemoryBlocks} uses
 * everywhere, so the result is byte-identical to passing the directives as adjacent arguments to
 * that method. {@link #blocks} gives the same sections with their provenance (key, scope, version),
 * which is how the prompt preview tags them.
 *
 * <p>Gates are suppliers read at compose time (not at construction) because the {@code
 * app.chat.directives.*} flags are field-injected after the owning service is constructed.
 */
final class PromptDirectiveComposer {

    /**
     * What a section may look at: the turn's offered tools and whether any are MCP tools. {@code
     * compact}: small-model mode (V501), the short form of every directive that has one.
     */
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
        ResolvedPrompt render(Context context);
    }

    private final List<Section> sections;

    PromptDirectiveComposer(List<Section> sections) {
        this.sections = List.copyOf(sections);
    }

    /** The production composition: tool-trust, then untrusted-data. */
    static PromptDirectiveComposer standard(
            BooleanSupplier toolTrustEnabled, BooleanSupplier mcpUntrustedEnabled) {
        return new PromptDirectiveComposer(
                List.of(toolTrust(toolTrustEnabled), untrustedData(mcpUntrustedEnabled)));
    }

    static Section toolTrust(BooleanSupplier enabled) {
        return ctx ->
                ctx.directives()
                        .resolved(
                                enabled.getAsBoolean()
                                        ? DirectiveKeys.toolTrustKey(ctx.tools(), ctx.compact())
                                        : null);
    }

    static Section untrustedData(BooleanSupplier enabled) {
        return ctx ->
                ctx.directives()
                        .resolved(
                                enabled.getAsBoolean()
                                        ? DirectiveKeys.mcpUntrustedKey(ctx.anyMcpToolsOffered())
                                        : null);
    }

    /** The sections that apply and have text, in order. */
    List<ResolvedPrompt> blocks(Context context) {
        List<ResolvedPrompt> out = new ArrayList<>();
        for (Section section : sections) {
            ResolvedPrompt r = section.render(context);
            if (r != null && r.textOrNull() != null) {
                out.add(r);
            }
        }
        return out;
    }

    /** The joined block, or null when no section applies. */
    String compose(Context context) {
        return ChatPromptDirectives.joinMemoryBlocks(
                blocks(context).stream().map(ResolvedPrompt::text).toArray(String[]::new));
    }
}
