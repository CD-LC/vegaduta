package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.ToolCallGrade;
import com.agenticai.core.chat.ToolCallRecovery.Capability;
import com.agenticai.core.chat.ToolCallRecovery.Scope;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;

/**
 * Text-form tool-call recovery (and its hold-back of a reply that starts with {@code {} or {@code [}) is a
 * small-model workaround. It used to wrap every model the application builds; it now wraps a model by what is
 * known of its capability: a known weak tool caller (a local or open-weight provider, a hosted row in
 * small-model mode or graded TEXT/NONE), a model of unknown capability (an unmeasured hosted row) unless the
 * operator says otherwise, and not a known capable one.
 */
class ToolCallRecoveryScopeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final class Stub implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return null;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.empty();
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder().build();
        }
    }

    private ToolCallRecovery recovery(boolean enabled, Scope scope, String providers, String grades, boolean unmeasured) {
        return new ToolCallRecovery(
                new ResilientToolCallingManagerFactory(mapper),
                mapper,
                new StaticListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class),
                enabled,
                3,
                100,
                scope,
                providers,
                grades,
                unmeasured);
    }

    private static final String LOCAL = "OLLAMA,LOCAL_OPENAI";
    private static final String WEAK_GRADES = "TEXT,NONE";

    @Test
    void aKnownCapableModelIsNotWrappedByDefault() {
        ToolCallRecovery r = recovery(true, Scope.WEAK_TOOL_CALLERS, LOCAL, WEAK_GRADES, true);
        ChatModel frontier = new Stub();

        assertSame(frontier, r.wrap(frontier, r.capabilityOf(ProviderName.ANTHROPIC)));
        assertSame(frontier, r.wrap(frontier, r.capabilityOf(ProviderName.OPENAI)));
        assertSame(frontier, r.wrap(frontier, r.capabilityOf(ToolCallGrade.STRUCTURED, false)));
    }

    @Test
    void aKnownWeakToolCallerIsWrapped() {
        ToolCallRecovery r = recovery(true, Scope.WEAK_TOOL_CALLERS, LOCAL, WEAK_GRADES, true);

        assertInstanceOf(TextToolCallRecoveringChatModel.class, r.wrap(new Stub(), r.capabilityOf(ProviderName.OLLAMA)));
        assertInstanceOf(TextToolCallRecoveringChatModel.class, r.wrap(new Stub(), r.capabilityOf(ProviderName.LOCAL_OPENAI)));
        assertInstanceOf(TextToolCallRecoveringChatModel.class, r.wrap(new Stub(), r.capabilityOf(ToolCallGrade.TEXT, false)));
        assertInstanceOf(TextToolCallRecoveringChatModel.class, r.wrap(new Stub(), r.capabilityOf(ToolCallGrade.NONE, false)));
        assertInstanceOf(TextToolCallRecoveringChatModel.class, r.wrap(new Stub(), r.capabilityOf(null, true)), "small-model mode");
    }

    @Test
    void aSmallModelRowIsWeakEvenWhenItsMeasuredGradeIsStructured() {
        ToolCallRecovery r = recovery(true, Scope.WEAK_TOOL_CALLERS, LOCAL, WEAK_GRADES, true);

        assertEquals(Capability.WEAK, r.capabilityOf(ToolCallGrade.STRUCTURED, true));
    }

    @Test
    void anUnmeasuredHostedRowFollowsTheOperatorsChoice() {
        ToolCallRecovery wrapsUnknown = recovery(true, Scope.WEAK_TOOL_CALLERS, LOCAL, WEAK_GRADES, true);
        ToolCallRecovery skipsUnknown = recovery(true, Scope.WEAK_TOOL_CALLERS, LOCAL, WEAK_GRADES, false);
        ChatModel unmeasured = new Stub();

        assertEquals(Capability.UNKNOWN, wrapsUnknown.capabilityOf(null, false));
        assertInstanceOf(TextToolCallRecoveringChatModel.class, wrapsUnknown.wrap(unmeasured, Capability.UNKNOWN));
        assertSame(unmeasured, skipsUnknown.wrap(unmeasured, Capability.UNKNOWN));
    }

    @Test
    void allModelsScopeWrapsEverythingAsBefore() {
        ToolCallRecovery r = recovery(true, Scope.ALL_MODELS, LOCAL, WEAK_GRADES, true);

        assertInstanceOf(TextToolCallRecoveringChatModel.class, r.wrap(new Stub(), Capability.CAPABLE));
    }

    @Test
    void switchedOffWrapsNothingWhateverTheCapability() {
        ToolCallRecovery r = recovery(false, Scope.ALL_MODELS, LOCAL, WEAK_GRADES, true);
        ChatModel model = new Stub();

        assertSame(model, r.wrap(model, Capability.WEAK));
    }

    @Test
    void theLegacyOneArgumentWrapStillWrapsUnconditionally() {
        ToolCallRecovery r = recovery(true, Scope.WEAK_TOOL_CALLERS, LOCAL, WEAK_GRADES, true);

        assertInstanceOf(TextToolCallRecoveringChatModel.class, r.wrap(new Stub()));
    }

    @Test
    void theProviderAndGradeListsAreParsedFromTheirConfiguration() {
        ToolCallRecovery r = recovery(true, Scope.WEAK_TOOL_CALLERS, "groq, OPENROUTER", "NONE", true);

        assertEquals(Capability.WEAK, r.capabilityOf(ProviderName.GROQ));
        assertEquals(Capability.WEAK, r.capabilityOf(ProviderName.OPENROUTER));
        assertEquals(Capability.CAPABLE, r.capabilityOf(ProviderName.OLLAMA), "only what is listed");
        assertEquals(Capability.CAPABLE, r.capabilityOf(ToolCallGrade.TEXT, false), "a measured grade the operator did not list is taken at the operator's word");
        assertEquals(Capability.WEAK, r.capabilityOf(ToolCallGrade.NONE, false));
        assertEquals(Capability.UNKNOWN, r.capabilityOf(null, false), "never measured");
        assertNotNull(r);
    }

    @Test
    void theModelSupportSeamAppliesTheScopeToTheModelItBuildsAClientOver() {
        LlmModelSupport support = support();
        ToolCallRecovery r = recovery(true, Scope.WEAK_TOOL_CALLERS, LOCAL, WEAK_GRADES, true);
        support.setToolCallRecovery(r);
        ChatModel provider = new Stub();

        ChatModel frontier = support.prepareModel(provider, null, support.capabilityOf(ProviderName.ANTHROPIC));
        ChatModel local = support.prepareModel(provider, null, support.capabilityOf(ProviderName.OLLAMA));
        ChatModel legacy = support.prepareModel(provider, null, null);

        assertSame(provider, frontier);
        assertInstanceOf(TextToolCallRecoveringChatModel.class, local);
        assertInstanceOf(TextToolCallRecoveringChatModel.class, legacy, "a caller that does not say what it built is of unknown capability");
    }

    /** A provider whose stream is driven by the test, one chunk at a time. */
    private static final class Driven implements ChatModel {
        final reactor.core.publisher.Sinks.Many<ChatResponse> sink = reactor.core.publisher.Sinks.many().unicast().onBackpressureBuffer();

        @Override
        public ChatResponse call(Prompt prompt) {
            return null;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return sink.asFlux();
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
        }

        void emit(String text) {
            sink.tryEmitNext(new ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage(text)))));
        }
    }

    private static org.springframework.ai.tool.ToolCallback aTool() {
        return new org.springframework.ai.tool.ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.ToolDefinition.builder().name("lookup").description("d").inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return "{}";
            }
        };
    }

    private List<String> receivedAfterTheFirstChunkOfAJsonReply(ProviderName provider) {
        LlmModelSupport support = support();
        support.setToolCallRecovery(recovery(true, Scope.WEAK_TOOL_CALLERS, LOCAL, WEAK_GRADES, true));
        Driven model = new Driven();
        List<String> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        support.chatClientBuilder(model, provider).build()
                .prompt().user("give me json").toolCallbacks(aTool()).stream().content().subscribe(received::add);

        model.emit("{\"answer\":");
        // The client delivers on its own scheduler; give a chunk that is not held a moment to arrive.
        long until = System.nanoTime() + Duration.ofMillis(500).toNanos();
        while (received.isEmpty() && System.nanoTime() < until) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return List.copyOf(received);
    }

    @Test
    void aFrontierModelsJsonAnswerStreamsLiveWhileASmallModelsIsHeldBackToCheckForACall() {
        assertEquals(List.of("{\"answer\":"), receivedAfterTheFirstChunkOfAJsonReply(ProviderName.ANTHROPIC),
                "a capable model's reply that happens to start with { is not held");
        assertEquals(List.of(), receivedAfterTheFirstChunkOfAJsonReply(ProviderName.OLLAMA),
                "a weak tool caller's is: it may be a tool call written as text");
    }

    @SuppressWarnings("unchecked")
    private static LlmModelSupport support() {
        ObjectProvider<ObservationRegistry> obs = mock(ObjectProvider.class);
        when(obs.getIfAvailable(any())).thenReturn(ObservationRegistry.NOOP);
        ObjectProvider<DefaultAgentAdvisor> adv = mock(ObjectProvider.class);
        when(adv.orderedStream()).thenAnswer(i -> java.util.stream.Stream.<DefaultAgentAdvisor>empty());
        return new LlmModelSupport(obs, adv, 2, Duration.ofMillis(1), Duration.ofMillis(2), true, "MISTRAL", "low", 16, Duration.ofMinutes(30), false);
    }
}
