package com.agenticai.core;

import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import org.springframework.ai.mcp.server.common.autoconfigure.StatelessToolCallbackConverterAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Agentic AI Platform orchestration core.
 *
 * <p>AnthropicChatAutoConfiguration is intentionally excluded: Spring AI's default
 * autoconfiguration wires a single, static ChatModel from one application.yml api-key. This
 * platform is BYOK and multi-provider - each tenant/agent can activate its own Anthropic (or other)
 * key at runtime (see {@code providers} package), so ChatModel instances are constructed
 * dynamically per request from the decrypted, stored credential instead (wired in Stage 3).
 *
 * <p>The OpenAI and Google GenAI exclusions below are the same fix, needed when OpenRouter/Grok
 * ({@code OpenAiChatModel}-backed, see {@code OpenRouterAgentChatClientFactory}/{@code
 * GrokAgentChatClientFactory}) and Gemini ({@code GoogleGenAiChatModel}-backed, see {@code
 * GeminiAgentChatClientFactory}) were added. First pass only excluded the two *Chat*
 * autoconfiguration classes, which missed a real startup crash: {@code
 * spring-ai-starter-model-openai} registers SIX autoconfiguration classes total (chat, embedding,
 * image, audio-speech, audio-transcription, moderation - see that module's {@code
 * AutoConfiguration.imports}), and {@code spring-ai-starter-model-google-genai} registers three
 * (chat, embedding-connection, text-embedding). Every one of them eagerly tries to build its own
 * static, single-key model bean at context-refresh time - {@code
 * OpenAiAudioSpeechAutoConfiguration} was the one that actually crashed startup ("OpenAI API key
 * must be set") since this platform never configures {@code spring.ai.openai.api-key} at all (BYOK,
 * decrypted per tenant at request time). All non-chat model types
 * (embedding/image/audio/moderation) are unused by this platform for these two providers regardless
 * - only chat is wired.
 *
 * <p>{@code @EnableScheduling} added for {@code AbandonedSignupCleanupJob} - the first
 * {@code @Scheduled} job in this codebase, so this annotation was never needed here before.
 *
 * <p>{@code @EnableAsync} added for {@code core.documentgen}'s {@code
 * DocumentGenerationEventListener} - the first {@code @Async} + {@code @TransactionalEventListener}
 * consumer in this codebase (confirmed by grep before adding this), so this annotation was never
 * needed here before either. No explicit {@code TaskExecutor} bean is configured, so Spring Boot's
 * auto-configured {@code ThreadPoolTaskExecutor} (event-driven document rendering; not a hot
 * per-request path) backs it.
 *
 * <p>The JVM-default-timezone fix that used to live here (a line in {@code main()}, then briefly a
 * static initializer on this class) has moved to {@link TimeZoneEnvironmentPostProcessor} - see
 * that class's javadoc for why both earlier attempts were unreliable and why an {@code
 * EnvironmentPostProcessor} is the hook that's actually guaranteed to run before any bean exists,
 * under every entry point including {@code @SpringBootTest}.
 *
 * <p><b>2026-08-15 - {@code ToolCallbackConverterAutoConfiguration} exclusion RE-CONFIRMED, for a
 * DIFFERENT and more serious reason than 2026-07-21's original crash theory.</b> That theory (its
 * {@code syncTools} bean eagerly calling {@code McpToolUtils.getToolCallbacksFromSyncClients()} on
 * every outbound {@code McpSyncClient}, crashing startup) does not hold up - decompiling the actual
 * pinned dependency ({@code spring-ai-autoconfigure-mcp-server-common-1.1.7.jar}) found no
 * reference anywhere in that jar to {@code McpSyncClient} or {@code
 * getToolCallbacksFromSyncClients}; that symbol does not exist in this pinned version. This
 * exclusion was briefly removed on that basis and verified LIVE against staging (2026-08-15) -
 * startup did not crash, but {@code tools/list} on the inbound MCP server returned all ~60 tools
 * from EVERY {@code ToolCallback}/{@code ToolCallbackProvider} bean in the whole context, not just
 * {@code McpServerToolConfig}'s 5 curated ones: {@code aggregateToolCallbacks} aggregates
 * indiscriminately, and the "mcp" profile's own outbound-client tool-use wiring (needed so agents
 * can call filesystem/puppeteer/browser/memory tools during chat) registers exactly such a bean.
 * That means every filesystem read/write/delete tool, {@code browser_run_code_unsafe}, and full
 * remote browser control was reachable by any external caller holding a valid {@code
 * core/mcpserver} grant key - a real capability leak, confirmed live, not hypothetical. Re-excluded
 * immediately. The 5 curated tools ARE only reachable through this same bean ({@code
 * McpServerToolConfig#mcpServerToolCallbackProvider} has no other wiring path in 1.1.7 - see that
 * class's javadoc), so with this exclusion in place {@code tools/list} again returns nothing until
 * {@code core/mcpserver} gets its own, narrow conversion that does NOT go through {@code
 * aggregateToolCallbacks}' whole-context aggregation - real follow-up work, not a config toggle. Do
 * not remove this exclusion again without that replacement built first.
 *
 * <p><b>2026-09-28 - {@code ChatClientAutoConfiguration} and {@code ChatMemoryAutoConfiguration}
 * excluded.</b> Both arrive transitively via {@code spring-ai-starter-model-*} and neither
 * contributes a bean this platform uses: no class under {@code core/} injects {@code
 * ChatClient.Builder}, {@code ChatClientCustomizer}, {@code ChatMemory} or {@code
 * ChatMemoryRepository} (grep-verified; the only {@code ChatClient.Builder} reference is a Mockito
 * mock in {@code FreeTierChatClientServiceRefundTest}). Every {@code ChatClient} is built per
 * request by a provider factory from a BYOK/pool {@code ChatModel}, and conversation memory is our
 * own Redis/DB {@code SessionMemoryService}, not Spring AI's {@code ChatMemory}. The
 * single-static-model builder those auto-configs offer is the same pattern the model exclusions
 * above reject. NOT excluded on purpose: the chat/tool observation auto-configs, tool-calling,
 * retry and MCP auto-configs - those are still live.
 *
 * <p><b>2026-09-30 (ADR-0012 P1a) - starter swap.</b> {@code core/chat} and {@code core/quality}
 * now depend on the plain {@code spring-ai-anthropic|openai|google-genai} model modules instead of
 * the {@code spring-ai-starter-model-*} starters, so the provider/embedding/image/audio/moderation,
 * ChatClient and ChatMemory auto-configurations are no longer on the classpath and their exclusions
 * were deleted. Only the MCP {@code ToolCallbackConverterAutoConfiguration} exclusion remains. The
 * tool-calling, retry and observation auto-configs are kept as explicit {@code
 * spring-ai-autoconfigure-*} deps in {@code core/chat}.
 */
@SpringBootApplication(
        exclude = {
            ToolCallbackConverterAutoConfiguration.class,
            // Spring AI 2.0 (ADR-0012 P3) added a stateless twin of the converter above with the
            // same context-wide
            // ToolCallback aggregation; inactive under protocol STREAMABLE but excluded so a
            // protocol flip cannot leak tools.
            StatelessToolCallbackConverterAutoConfiguration.class
        })
@EnableScheduling
@EnableAsync
public class CoreApplication {

    /**
     * Pins the name {@code applicationTaskExecutor} to a real bean. Boot's auto-configured executor
     * is {@code @ConditionalOnMissingBean(Executor.class)} - ANY {@link
     * java.util.concurrent.Executor}-typed bean makes it back off, and {@code
     * TemporalConfig#temporalActivityExecutor} (an {@code ExecutorService}) started doing exactly
     * that, so {@code @Qualifier("applicationTaskExecutor")} injection points (e.g. {@code
     * McpConnectDispatcher}) found no bean and the app failed to boot on the 2026-09-23 prod
     * deploy. Declared here explicitly so the qualified name can never silently disappear again.
     * Virtual threads to match {@code spring.threads.virtual.enabled} in application.yml - same
     * executor Boot was supplying before it backed off.
     */
    @Bean(name = "applicationTaskExecutor")
    public TaskExecutor applicationTaskExecutor() {
        // Tenant-aware (ADR-0024): an @Async method runs with the caller's tenant, restored after.
        return TenantAwareExecutors.newTaskExecutor("app-task-", true);
    }

    public static void main(String[] args) {
        SpringApplication.run(CoreApplication.class, args);
    }
}
