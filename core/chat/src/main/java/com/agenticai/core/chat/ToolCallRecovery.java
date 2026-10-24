package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.ToolCallGrade;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Settings and construction for {@link TextToolCallRecoveringChatModel}, the one place the
 * text-form tool-call recovery is switched on or off, and for which models it applies to.
 *
 * <p>The recovery is a small-model workaround: a model that writes its tool call as text instead of a
 * structured call, and, while streaming, a hold-back of any reply that starts with {@code {}, {@code [}, a
 * code fence or a known tag so that such a call is not shown as the answer. Applied to every model it costs
 * a frontier model that legitimately answers with JSON or code its live streaming and a parse, for a
 * problem it does not have. So it is applied by capability ({@link Capability}), set by {@code
 * app.chat.tools.text-call-recovery.scope}:
 *
 * <ul>
 *   <li>{@link Scope#WEAK_TOOL_CALLERS} (default): a model known to be a weak tool caller - a bring-your-own
 *       provider listed in {@code providers} (local and open-weight servers), a platform-hosted row in
 *       small-model mode or measured at one of {@code tool-grades} - and a model whose capability is unknown
 *       (an unmeasured hosted row) unless {@code apply-to-unmeasured-rows} is false. A known capable model
 *       (a frontier provider, a row measured STRUCTURED) is not wrapped.
 *   <li>{@link Scope#ALL_MODELS}: every model, as before this scope existed.
 * </ul>
 *
 * {@link LlmModelSupport#chatClientBuilder} wraps each provider model through {@link #wrap(ChatModel,
 * Capability)}; with {@code app.chat.tools.text-call-recovery.enabled=false} the model is returned untouched
 * and the request path is byte-for-byte what it was before this existed.
 */
@Component
public class ToolCallRecovery {

    /** Which models the recovery applies to. */
    public enum Scope {
        /** A known weak tool caller, and a model of unknown capability unless the operator excludes it. */
        WEAK_TOOL_CALLERS,
        /** Every model. */
        ALL_MODELS
    }

    /** What is known of how well the model makes structured tool calls. */
    public enum Capability {
        /** Known to write tool calls as text or not at all (a local or open-weight model, a small model). */
        WEAK,
        /** Known to make structured tool calls (a frontier provider, a row measured STRUCTURED). */
        CAPABLE,
        /** Not known (an unmeasured hosted row, a caller that did not say). */
        UNKNOWN
    }

    private final ResilientToolCallingManagerFactory toolCallingManagers;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final int maxPerInvocation;
    private final int holdBackChars;
    private final Scope scope;
    private final Set<ProviderName> weakProviders;
    private final Set<ToolCallGrade> weakGrades;
    private final boolean applyToUnmeasuredRows;

    /** Every model, as a hand-built instance has always behaved. */
    public ToolCallRecovery(
        ResilientToolCallingManagerFactory toolCallingManagers,
        ObjectMapper objectMapper,
        ObjectProvider<MeterRegistry> meterRegistry,
        boolean enabled,
        int maxPerInvocation,
        int holdBackChars
    ) {
        this(toolCallingManagers, objectMapper, meterRegistry, enabled, maxPerInvocation, holdBackChars,
            Scope.ALL_MODELS, "", "", true);
    }

    @Autowired
    public ToolCallRecovery(
        ResilientToolCallingManagerFactory toolCallingManagers,
        ObjectMapper objectMapper,
        ObjectProvider<MeterRegistry> meterRegistry,
        @Value("${app.chat.tools.text-call-recovery.enabled:true}") boolean enabled,
        @Value("${app.chat.tools.text-call-recovery.max-per-invocation:"
            + TextToolCallRecoveringChatModel.DEFAULT_MAX_RECOVERIES + "}") int maxPerInvocation,
        @Value("${app.chat.tools.text-call-recovery.hold-back-chars:"
            + TextToolCallRecoveringChatModel.DEFAULT_HOLD_BACK_CHARS + "}") int holdBackChars,
        @Value("${app.chat.tools.text-call-recovery.scope:WEAK_TOOL_CALLERS}") Scope scope,
        @Value("${app.chat.tools.text-call-recovery.providers:OLLAMA,LOCAL_OPENAI,OPENROUTER,GROQ,META,SARVAM}") String providers,
        @Value("${app.chat.tools.text-call-recovery.tool-grades:TEXT,NONE}") String toolGrades,
        @Value("${app.chat.tools.text-call-recovery.apply-to-unmeasured-rows:true}") boolean applyToUnmeasuredRows
    ) {
        this.toolCallingManagers = toolCallingManagers;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry == null ? null : meterRegistry.getIfAvailable();
        this.enabled = enabled;
        this.maxPerInvocation = maxPerInvocation;
        this.holdBackChars = holdBackChars;
        this.scope = scope == null ? Scope.WEAK_TOOL_CALLERS : scope;
        this.weakProviders = parse(ProviderName.class, providers);
        this.weakGrades = parse(ToolCallGrade.class, toolGrades);
        this.applyToUnmeasuredRows = applyToUnmeasuredRows;
    }

    private static <E extends Enum<E>> Set<E> parse(Class<E> type, String csv) {
        Set<E> out = EnumSet.noneOf(type);
        if (csv != null) {
            for (String name : csv.split(",")) {
                if (!name.isBlank()) {
                    out.add(Enum.valueOf(type, name.strip().toUpperCase(Locale.ROOT)));
                }
            }
        }
        return out;
    }

    public boolean enabled() {
        return enabled;
    }

    /** What is known of a bring-your-own provider: weak when listed in {@code providers}, else capable. */
    public Capability capabilityOf(ProviderName provider) {
        return provider != null && weakProviders.contains(provider) ? Capability.WEAK : Capability.CAPABLE;
    }

    /**
     * What is known of a platform-hosted row: weak in small-model mode or at a listed grade, capable when
     * measured at a grade that is not listed, unknown when it was never measured.
     */
    public Capability capabilityOf(ToolCallGrade grade, boolean smallModel) {
        if (smallModel || (grade != null && weakGrades.contains(grade))) {
            return Capability.WEAK;
        }
        return grade == null ? Capability.UNKNOWN : Capability.CAPABLE;
    }

    /** True when a model of {@code capability} gets the recovery under the configured scope. */
    public boolean appliesTo(Capability capability) {
        if (!enabled) {
            return false;
        }
        if (scope == Scope.ALL_MODELS) {
            return true;
        }
        Capability known = capability == null ? Capability.UNKNOWN : capability;
        return known == Capability.WEAK || (known == Capability.UNKNOWN && applyToUnmeasuredRows);
    }

    /** {@code model} wrapped with recovery regardless of its capability, or itself when recovery is off. */
    public ChatModel wrap(ChatModel model) {
        if (!enabled || model == null || model instanceof TextToolCallRecoveringChatModel) {
            return model;
        }
        return new TextToolCallRecoveringChatModel(
            model, toolCallingManagers.create(), objectMapper, maxPerInvocation, holdBackChars, meterRegistry);
    }

    /** {@code model} wrapped with recovery when its {@code capability} calls for it, else {@code model} itself. */
    public ChatModel wrap(ChatModel model, Capability capability) {
        return appliesTo(capability) ? wrap(model) : model;
    }
}
