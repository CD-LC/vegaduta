package com.agenticai.core.characterisation;

import com.agenticai.core.chat.DefaultAgentAdvisor;
import com.agenticai.core.chat.LlmModelSupport;
import com.agenticai.core.chat.ResilientToolCallingManagerFactory;
import com.agenticai.core.chat.ToolResultClearing;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * P0 characterisation harness (ADR-0012): builds a {@link ChatClient} the way the provider
 * factories do, through the PUBLIC seams {@link LlmModelSupport#chatClientBuilder} and {@link
 * ResilientToolCallingManagerFactory#create()}, over a {@link ScriptedChatModel}.
 *
 * <p><b>P3 touch point.</b> Today the resilient tool-calling manager is handed to the model ({@code
 * ChatModel.Builder#toolCallingManager}); after the Spring AI 2 move it is handed to the
 * ChatClient's {@code ToolCallingAdvisor}. {@link #client} is the single place that changes.
 */
public final class ToolLoopHarness {

    private ToolLoopHarness() {}

    /** The production tool-calling manager (argument sanitising, redaction, round caps). */
    public static ToolCallingManager resilientManager(int maxRounds, int maxUnknownCalls) {
        return new ResilientToolCallingManagerFactory(
                        new ObjectMapper(),
                        null,
                        null,
                        maxRounds,
                        maxUnknownCalls,
                        true,
                        ToolResultClearing.DEFAULT_THRESHOLD_CHARS,
                        ToolResultClearing.DEFAULT_KEEP_LAST)
                .create();
    }

    public static ToolCallingManager resilientManager() {
        return new ResilientToolCallingManagerFactory(new ObjectMapper()).create();
    }

    public static LlmModelSupport modelSupport() {
        StaticListableBeanFactory empty = new StaticListableBeanFactory();
        return new LlmModelSupport(
                empty.getBeanProvider(ObservationRegistry.class),
                empty.getBeanProvider(DefaultAgentAdvisor.class),
                2,
                Duration.ofMillis(1),
                Duration.ofMillis(2),
                true,
                "MISTRAL",
                "low",
                4,
                Duration.ofMinutes(1),
                false);
    }

    /**
     * A ChatClient over {@code model} with {@code advisors} as defaults, built like the factories
     * do.
     */
    public static ChatClient client(ScriptedChatModel model, Advisor... advisors) {
        LlmModelSupport support = modelSupport();
        return support.chatClientBuilder(model).defaultAdvisors(support.advisors(advisors)).build();
    }

    /** A prompt/completion usage with the total derived, no native (provider) payload. */
    public static Usage usage(int prompt, int completion) {
        return new DefaultUsage(prompt, completion, prompt + completion);
    }
}
