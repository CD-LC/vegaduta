package com.agenticai.core.chat;

import com.agenticai.core.prompts.PromptContext;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptTemplates;
import com.agenticai.core.prompts.ResolvedPrompt;
import com.agenticai.core.security.TenantScope;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How the chat module reads the model-facing text it writes (tool results of the loop, the tool-routing shims) from
 * the prompt registry ({@code docs/prompts.md}). Same contract as the guardrail module's lookup: never throws, never
 * returns nothing, and an unknown or broken registry means the shipped text.
 *
 * <p>The tool loop runs on threads that know nothing about the turn, so the agent and the tenant of the run travel with
 * the request ({@link ToolLoopTranscript#forPrompts}) and are re-applied here, around the lookup only.
 */
final class ChatPrompts {

    private static final Logger log = LoggerFactory.getLogger(ChatPrompts.class);

    static final String TOOL_BUDGET_ROUNDS = "toolresult.tool_budget.rounds";
    static final String TOOL_BUDGET_TOKENS = "toolresult.tool_budget.tokens";
    static final String HARD_STOP = "message.tool_budget.hard_stop";
    static final String UNKNOWN_TOOL = "toolresult.unavailable.unknown_tool";
    static final String INVALID_ARGUMENTS_HINT = "toolresult.invalid_arguments.hint";
    static final String INVALID_ARGUMENTS_UNKNOWN_KEYS = "toolresult.invalid_arguments.unknown_keys";
    static final String INVALID_ARGUMENTS_NOT_JSON = "toolresult.invalid_arguments.problem.not_json";
    static final String INVALID_ARGUMENTS_NOT_OBJECT = "toolresult.invalid_arguments.problem.not_object";
    static final String INVALID_ARGUMENTS_MISSING_REQUIRED = "toolresult.invalid_arguments.problem.missing_required";
    static final String ROUTER_FORMAT = "shim.router.format";
    static final String ROUTER_REQUIRED = "shim.router.required";
    static final String STRUCTURED_SCHEMA_INSTRUCTION = "shim.structured.schema_instruction";

    /** The agent and the tenant a model call belongs to; {@code tenant} may be null (the calling thread's is used). */
    record Run(UUID agentId, TenantScope tenant) {
        static final Run NONE = new Run(null, null);
    }

    private ChatPrompts() {}

    /** The text of {@code key} for {@code run}, placeholders filled from {@code nameValuePairs}. Never null. */
    static String text(PromptResolver resolver, String key, Run run, String... nameValuePairs) {
        return inRun(run, () -> {
            PromptContext context = context(run, nameValuePairs);
            String text = null;
            try {
                text = (resolver != null ? resolver : PromptResolver.defaultsOnly()).resolve(key, context).textOrNull();
            } catch (RuntimeException e) {
                log.warn("Prompt '{}' could not be resolved; using the shipped text: {}", key, e.toString());
            }
            if (text == null) {
                text = PromptResolver.defaultsOnly().resolve(key, context).textOrNull();
            }
            return text != null ? text : "[" + key + "]";
        });
    }

    /**
     * As {@link #text} for a text an operator may still change in the {@code app.chat.tools.*} property that existed
     * before the key did. Order: a registry override at any scope, else the property when it differs from the shipped
     * wording, else the shipped wording; with nothing customised the result is the shipped text byte for byte.
     */
    static String textWithConfiguredAlias(
            PromptResolver resolver, String key, Run run, String configured, String... nameValuePairs) {
        PromptResolver r = resolver != null ? resolver : PromptResolver.defaultsOnly();
        String aliased = inRun(run, () -> {
            try {
                ResolvedPrompt resolved = r.resolve(key, context(run, nameValuePairs));
                boolean overridden = resolved.known() && resolved.scope() != null;
                String shipped = r.catalog().defaultText(key, null).orElse(null);
                if (!overridden && configured != null && !configured.isBlank() && !configured.equals(shipped)) {
                    return render(configured, nameValuePairs);
                }
                return resolved.textOrNull();
            } catch (RuntimeException e) {
                log.warn("Prompt '{}' could not be resolved; using the shipped text: {}", key, e.toString());
                return null;
            }
        });
        return aliased != null ? aliased : text(null, key, run, nameValuePairs);
    }

    private static PromptContext context(Run run, String... nameValuePairs) {
        PromptContext context = PromptContext.forAgent(run == null ? null : run.agentId());
        for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
            context = context.withVariable(nameValuePairs[i], String.valueOf(nameValuePairs[i + 1]));
        }
        return context;
    }

    private static String render(String template, String... nameValuePairs) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
            values.put(nameValuePairs[i], String.valueOf(nameValuePairs[i + 1]));
        }
        return PromptTemplates.render(template, values.keySet(), values);
    }

    private static <T> T inRun(Run run, Supplier<T> work) {
        return run == null || run.tenant() == null ? work.get() : run.tenant().get(work);
    }
}
