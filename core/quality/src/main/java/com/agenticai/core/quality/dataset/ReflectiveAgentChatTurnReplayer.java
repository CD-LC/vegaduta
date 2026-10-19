package com.agenticai.core.quality.dataset;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Interim {@link AgentTurnReplayer} that bridges to {@code
 * com.agenticai.core.agents.turn.AgentChatService} through the merged {@code
 * core/app} Spring context via reflection.
 *
 * <h2>Why reflection, of all things</h2>
 * Read {@link AgentTurnReplayer}'s javadoc first - the short version: the
 * module dependency {@code quality -> agents} is explicitly refused by this
 * module's own pom (cycle avoidance), the reverse edge plus an agents-side
 * implementation would require files owned by a concurrent workstream
 * ({@code core/agents/pom.xml}, plus a class in a package this workstream
 * may not create files in), and shipping the runner with NO wiring at all
 * is exactly the "built but never wired" failure mode this repo's history
 * warns about. Runtime lookup against the merged application context - the
 * same trick {@code DynamicMcpConnectionManager} already uses with {@code
 * Class.forName} for its optional Jackson adapter - is the one option that
 * is simultaneously (a) inside this workstream's file ownership, (b) free
 * of a compile-time cycle, and (c) actually functional in the deployed app,
 * where both modules always coexist on the classpath.
 *
 * <p>The cost is honest and bounded: if the other workstream changes the
 * signatures of {@code chatTextOnly} / {@code chatWithParams} /
 * {@code StructuredAgentInput}, this bridge fails at RUN time, not compile
 * time - as a clear per-run FAILED with this class's name in the message,
 * never silently. The two methods bound here are the platform's most
 * load-bearing entry points ({@code chatWithParams} is the workflow
 * engine's agent-node contract), so their shapes are about as stable as
 * anything in {@code core/agents} gets. Replacing this bridge with a typed
 * agents-side {@code AgentTurnReplayer} bean is the recorded follow-up; the
 * injected-list selection in {@code EvalRunService#selectReplayer} already
 * prefers any OTHER implementation over this one so the swap needs no
 * change here.
 *
 * <h2>Which entry points, and why these two</h2>
 * <ul>
 *   <li>Tool-less (default): {@code chatTextOnly(UUID, String, String,
 *       String)} - the method built for exactly the "run the model, never a
 *       side-effecting tool" case (its javadoc documents the repair-turn
 *       incident that motivated it). Same budget metering, moderation,
 *       kill-switch as a real turn.</li>
 *   <li>Tool-enabled (opt-in): {@code chatWithParams(UUID, String, String,
 *       StructuredAgentInput, String, Duration)} - the full pipeline, whose
 *       {@code StructuredAgentOutput} already carries the turn's tool-call
 *       summaries (read from {@code tool_call_log}), which is precisely
 *       what expected-tools assertions need.</li>
 * </ul>
 *
 * <p>Reactor types are also handled reflectively ({@code Flux#collectList}
 * / {@code Mono#block}) so this class compiles against zero transitive
 * surface it does not declare.
 */
@Component
public class ReflectiveAgentChatTurnReplayer implements AgentTurnReplayer {

    private static final Logger log = LoggerFactory.getLogger(ReflectiveAgentChatTurnReplayer.class);

    private static final String CHAT_SERVICE_CLASS = "com.agenticai.core.agents.turn.AgentChatService";
    private static final String STRUCTURED_INPUT_CLASS =
        "com.agenticai.core.agents.turn.AgentChatService$StructuredAgentInput";

    private final ApplicationContext applicationContext;

    /** Lazily-resolved and cached - see {@link #binding()}. */
    private volatile Binding binding;

    public ReflectiveAgentChatTurnReplayer(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public ReplayedTurn replay(
        UUID agentId, String sessionId, String subjectKey, String userMessage,
        boolean executeTools, Duration timeout
    ) throws Exception {
        Binding b = binding();
        try {
            if (executeTools) {
                return replayWithTools(b, agentId, sessionId, subjectKey, userMessage, timeout);
            }
            return replayToolless(b, agentId, sessionId, subjectKey, userMessage, timeout);
        } catch (InvocationTargetException e) {
            // Unwrap so the case result records the REAL failure
            // (TenantUnavailableException, provider error, ...) rather than
            // the reflection wrapper.
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw cause instanceof Exception ex ? ex : new IllegalStateException(cause);
        }
    }

    private ReplayedTurn replayToolless(
        Binding b, UUID agentId, String sessionId, String subjectKey, String userMessage, Duration timeout
    ) throws Exception {
        Object flux = b.chatTextOnly.invoke(b.chatService, agentId, sessionId, subjectKey, userMessage);
        // Flux<String> -> Mono<List<String>> -> List<String>, reflectively.
        // Look the methods up on the PUBLIC Flux/Mono types: the runtime classes (MonoCallable ...) are
        // package-private, so invoking a Method taken from getClass() throws IllegalAccessException.
        Object mono = Class.forName("reactor.core.publisher.Flux").getMethod("collectList").invoke(flux);
        Object chunks = Class.forName("reactor.core.publisher.Mono")
            .getMethod("block", Duration.class).invoke(mono, timeout);
        StringBuilder reply = new StringBuilder();
        if (chunks instanceof List<?> parts) {
            for (Object part : parts) {
                reply.append(part);
            }
        }
        return new ReplayedTurn(reply.toString(), List.of());
    }

    private ReplayedTurn replayWithTools(
        Binding b, UUID agentId, String sessionId, String subjectKey, String userMessage, Duration timeout
    ) throws Exception {
        // new StructuredAgentInput(query, contextData=null, knowledgeCollectionIds=null, approvalRequired=false)
        Object input = b.structuredInputCtor.newInstance(userMessage, null, null, false);
        Object output = b.chatWithParams.invoke(
            b.chatService, agentId, sessionId, subjectKey, input, null, timeout);

        String resultText = (String) output.getClass().getMethod("resultText").invoke(output);
        Object rawToolCalls = output.getClass().getMethod("toolCalls").invoke(output);

        List<ReplayedToolCall> toolCalls = new ArrayList<>();
        if (rawToolCalls instanceof List<?> summaries) {
            for (Object summary : summaries) {
                toolCalls.add(new ReplayedToolCall(
                    (String) summary.getClass().getMethod("toolName").invoke(summary),
                    (String) summary.getClass().getMethod("input").invoke(summary),
                    (String) summary.getClass().getMethod("output").invoke(summary)));
            }
        }
        return new ReplayedTurn(resultText != null ? resultText : "", toolCalls);
    }

    /**
     * Resolve-once binding of the bean + methods. Double-checked volatile
     * rather than eager {@code @PostConstruct} resolution on purpose: this
     * bean must construct cleanly even in a context where {@code
     * core/agents} is absent (module-scoped test slices), failing only if a
     * replay is actually attempted there.
     */
    private Binding binding() {
        Binding local = binding;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (binding == null) {
                binding = resolveBinding();
            }
            return binding;
        }
    }

    private Binding resolveBinding() {
        try {
            Class<?> serviceClass = Class.forName(CHAT_SERVICE_CLASS);
            Class<?> inputClass = Class.forName(STRUCTURED_INPUT_CLASS);
            Object chatService = applicationContext.getBean(serviceClass);
            Method chatTextOnly = serviceClass.getMethod(
                "chatTextOnly", UUID.class, String.class, String.class, String.class);
            Method chatWithParams = serviceClass.getMethod(
                "chatWithParams", UUID.class, String.class, String.class, inputClass, String.class, Duration.class);
            Constructor<?> structuredInputCtor = inputClass.getDeclaredConstructor(
                String.class, Map.class, List.class, boolean.class);
            log.info("Eval-dataset replay bridge bound to {} (tool-less + structured entry points)",
                CHAT_SERVICE_CLASS);
            return new Binding(chatService, chatTextOnly, chatWithParams, structuredInputCtor);
        } catch (Exception e) {
            throw new IllegalStateException(
                "Eval-dataset replay is unavailable: could not bind to " + CHAT_SERVICE_CLASS
                    + " through the application context. Either core/agents is not on the classpath"
                    + " (module-scoped test?) or its chat entry-point signatures changed - in which"
                    + " case ReflectiveAgentChatTurnReplayer must be updated, or better, replaced by"
                    + " a typed agents-side AgentTurnReplayer bean (the recorded follow-up).", e);
        }
    }

    private record Binding(
        Object chatService, Method chatTextOnly, Method chatWithParams, Constructor<?> structuredInputCtor
    ) {
    }
}
