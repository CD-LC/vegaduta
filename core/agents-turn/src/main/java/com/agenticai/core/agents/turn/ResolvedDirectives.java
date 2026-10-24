package com.agenticai.core.agents.turn;

import com.agenticai.core.prompts.PromptContext;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.ResolvedPrompt;
import com.agenticai.core.security.TenantScope;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * The standing directives as one turn sees them: a {@link PromptResolver} bound to the turn's
 * context (current tenant, this agent, today's date). The assembler decides WHETHER a directive
 * applies this turn (the tool it is about is on the list, its {@code app.chat.directives.*} switch)
 * and which key to send (full or compact wording); this class answers WHAT the text is: the
 * agent's override, the tenant's, the platform's or the shipped default, or nothing when an
 * override switched the directive off. No key, no text: an override never resurrects a directive
 * the platform does not send this turn.
 *
 * <p>Immutable apart from the optional {@link #trace()}, which a preview (or a test) switches on
 * with {@link #recording()} to learn which key, scope and version produced each block.
 */
public final class ResolvedDirectives {

    /** No overrides: the shipped default text for every key, no custom instructions. */
    public static final ResolvedDirectives DEFAULTS =
            new ResolvedDirectives(
                    PromptResolver.defaultsOnly(),
                    PromptContext.none(),
                    null,
                    TenantScope.of((com.agenticai.core.security.TenantContext) null));

    /**
     * The default of a turn path that has no prompt settings (hand-built fixtures, tests): every agent
     * gets the shipped text.
     */
    public static final Function<UUID, ResolvedDirectives> DEFAULTS_FOR_AGENT = agentId -> DEFAULTS;

    private final PromptResolver resolver;
    private final PromptContext context;
    private final List<ResolvedPrompt> trace;
    private final TenantScope scope;

    ResolvedDirectives(PromptResolver resolver, PromptContext context) {
        this(resolver, context, null, TenantScope.capture());
    }

    private ResolvedDirectives(
            PromptResolver resolver, PromptContext context, List<ResolvedPrompt> trace, TenantScope scope) {
        this.resolver = resolver;
        this.context = context;
        this.trace = trace;
        this.scope = scope;
    }

    /** A copy that remembers every {@link ResolvedPrompt} it hands out, in order. */
    public ResolvedDirectives recording() {
        return new ResolvedDirectives(resolver, context, Collections.synchronizedList(new ArrayList<>()), scope);
    }

    /** A view of the same settings that resolves in {@code locale} (a nudge in the language that matched). */
    public ResolvedDirectives withLocale(String locale) {
        return new ResolvedDirectives(resolver, context.withLocale(locale), trace, scope);
    }

    /** What {@link #resolved} handed out so far; empty unless {@link #recording()}. */
    public List<ResolvedPrompt> trace() {
        return trace == null ? List.of() : List.copyOf(trace);
    }

    /** The resolution of {@code promptKey} (a {@code directive.*} registry key); null for a null key. */
    public ResolvedPrompt resolved(String promptKey) {
        if (promptKey == null) {
            return null;
        }
        return resolved(promptKey, context);
    }

    /**
     * Resolves inside the tenant that was current when this object was made: the stored overrides
     * live in that tenant's schema, and a reply callback (stream completion, error handler) runs on
     * a thread that carries none.
     */
    private ResolvedPrompt resolved(String promptKey, PromptContext ctx) {
        ResolvedPrompt r = scope.get(() -> resolver.resolve(promptKey, ctx));
        if (trace != null) {
            trace.add(r);
        }
        return r;
    }

    /**
     * As {@link #text(String)}, with values for the key's declared placeholders ({@code {name}}); a
     * value is substituted as plain text and never re-scanned.
     */
    public String text(String promptKey, Map<String, String> variables) {
        if (promptKey == null) {
            return null;
        }
        ResolvedPrompt r = resolved(promptKey, context.withVariables(variables));
        return r.textOrNull();
    }

    /**
     * As {@link #text(String, Map)}, but never null for a known key: when an override switched the
     * text off (or the lookup gave nothing) the shipped text is returned. For text the person must
     * read in place of an answer, where "nothing" is not an option.
     */
    public String textOrShipped(String promptKey, Map<String, String> variables) {
        String text = text(promptKey, variables);
        if (text != null) {
            return text;
        }
        return DEFAULTS == this ? null : DEFAULTS.text(promptKey, variables);
    }

    /** As {@link #textOrShipped(String, Map)} for a key with no placeholders. */
    public String textOrShipped(String promptKey) {
        return textOrShipped(promptKey, Map.of());
    }

    /**
     * The text of {@code promptKey} in every locale the catalog ships it for, each resolved for this
     * context (an override, else the shipped file of that locale), without duplicates and without
     * switched-off or blank entries. For word lists that must recognise several languages at once
     * (a detector), where one locale is not enough.
     */
    public List<String> textsInAllLocales(String promptKey) {
        if (promptKey == null) {
            return List.of();
        }
        java.util.Set<String> locales = new java.util.TreeSet<>(resolver.catalog().locales(promptKey));
        if (locales.isEmpty()) {
            locales.add(resolver.catalog().defaultLocale());
        }
        java.util.LinkedHashSet<String> texts = new java.util.LinkedHashSet<>();
        for (String locale : locales) {
            String text = resolved(promptKey, context.withLocale(locale)).textOrNull();
            if (text != null && !text.isBlank()) {
                texts.add(text);
            }
        }
        return List.copyOf(texts);
    }

    /**
     * The block to send for {@code promptKey}: {@code null} for a null key or when an override
     * switched the directive off; the override's text when one applies; otherwise the shipped text.
     */
    public String text(String promptKey) {
        ResolvedPrompt r = resolved(promptKey);
        return r == null ? null : r.textOrNull();
    }

    /** The owner's enabled free-text block appended after the built-ins, or {@code null}. */
    public String customInstructionsBlock() {
        String text = text(PromptDirectiveRegistry.Keys.CUSTOM_INSTRUCTIONS);
        return text == null || text.isBlank() ? null : text.strip();
    }
}
