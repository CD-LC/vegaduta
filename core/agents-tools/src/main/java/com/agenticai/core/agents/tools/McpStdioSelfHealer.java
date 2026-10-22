package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.mcp.McpStdioCommandGuard;
import com.agenticai.core.mcp.dto.McpConnectionTestResult.SuggestedFix;
import com.agenticai.core.mcp.dto.McpDynamicConfig;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderModelResponse;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MCP-SELF-HEAL (2026-07-26): turns a failed STDIO MCP connection's captured stderr into a
 * machine-actionable {@link SuggestedFix}, instead of leaving the tenant staring at a wall of raw
 * process output and a "Try connecting again" button that will fail identically every time.
 *
 * <p>Two tiers, cheapest first:
 *
 * <ol>
 *   <li><b>Pattern match</b> ({@link #matchKnownPattern}) - a fast, free, purely-deterministic
 *       regex pass for known-shape errors. The motivating case is {@code uv}'s own fixed error
 *       format when a registration's executable name doesn't match what the package actually
 *       installs: {@code An executable named `X` is not provided by package `X`. The following
 *       executables are available: - Y. Use `uvx --from X Y` instead.} - package name, correct
 *       executable, and the full corrected command are all present verbatim in {@code uv}'s own
 *       stderr, so this never needs an LLM call.
 *   <li><b>LLM fallback</b> ({@link #askLlmForFix}) - only reached when the stderr does not match
 *       any known template. Asks whichever platform-hosted or BYOK chat client this tenant would
 *       normally get (mirrors {@code AssistService#buildChatClient}'s FREE-tier vs BYOK branch) to
 *       read the stderr and propose a corrected command/args as strict JSON. Exactly ONE LLM call
 *       per failed connection - if it errors, times out, or returns something unusable, this
 *       returns empty rather than retrying the LLM itself; the real connection failure is still
 *       surfaced normally by the caller either way.
 * </ol>
 *
 * <p><b>Both tiers are re-validated through {@link McpStdioCommandGuard} before ever being
 * returned</b> - a pattern-matched suggestion is extracted from a trusted tool's own stderr but is
 * still just parsed text, and an LLM-proposed command is untrusted model output that could name a
 * disallowed launcher or smuggle a shell metacharacter. A candidate the guard rejects is dropped
 * entirely (logged, never returned) - this class never applies anything itself and never bypasses
 * the guard.
 *
 * <p>Never applies a fix - only proposes one. The caller ({@link DynamicMcpConnectionManager#test})
 * surfaces it via {@code McpConnectionTestResult.suggestedFix()} for the registration UI to offer
 * as a one-click "Apply suggested fix?" action; the actual registration is only ever changed
 * through the normal update path, with the guard enforced again there too.
 */
@Component
public class McpStdioSelfHealer {

    private static final Logger log = LoggerFactory.getLogger(McpStdioSelfHealer.class);
    private static final ObjectMapper OBJECT_MAPPER = com.agenticai.core.json.JsonMappers.plain();

    /**
     * {@code uv}'s own fixed error shape (verified verbatim against the live repro this feature was
     * built for - {@code mcp-bing-scraper}). Group 1 is the package name (informational, used only
     * in the human-readable reason); group 2 is the FULL corrected invocation {@code uv} already
     * printed (e.g. {@code "uvx --from mcp-bing-scraper bing-search-mcp"}) - trivially tokenizable
     * into command+args, no guessing required. {@code DOTALL} so the "following executables are
     * available" block (which may list several lines) doesn't defeat the match.
     */
    static final Pattern UV_WRONG_EXECUTABLE =
            Pattern.compile(
                    "An executable named `[^`]+` is not provided by package `([^`]+)`\\."
                            + "[\\s\\S]*?"
                            + "Use `([^`]+)` instead\\.",
                    Pattern.DOTALL);

    /** Cheap, deterministic - never spends an LLM call. */
    private final McpStdioCommandGuard commandGuard;

    private final ProviderCredentialResolver credentialResolver;
    private final LlmGatewayService llmGatewayService;
    private final ProviderModelCatalogService providerModelCatalogService;
    private final TenantLimits tenantLimits;
    private final FreeTierChatClientService freeTierChatClientService;

    public McpStdioSelfHealer(
            McpStdioCommandGuard commandGuard,
            ProviderCredentialResolver credentialResolver,
            LlmGatewayService llmGatewayService,
            ProviderModelCatalogService providerModelCatalogService,
            TenantLimits tenantLimits,
            FreeTierChatClientService freeTierChatClientService) {
        this.commandGuard = commandGuard;
        this.credentialResolver = credentialResolver;
        this.llmGatewayService = llmGatewayService;
        this.providerModelCatalogService = providerModelCatalogService;
        this.tenantLimits = tenantLimits;
        this.freeTierChatClientService = freeTierChatClientService;
    }

    /**
     * Production entry point - STDIO connections only ({@code config.isRemote()} short-circuits to
     * empty), and only when there is stderr to reason about. Never throws: every failure mode (no
     * pattern match, no active LLM provider, quota exhausted, malformed LLM reply, guard rejection)
     * simply yields {@link Optional#empty()}.
     */
    public Optional<SuggestedFix> suggestFix(McpDynamicConfig config, List<String> stderrTail) {
        return suggestFix(config, stderrTail, null);
    }

    /**
     * Test seam: {@code chatClientOverride} lets a unit test exercise the LLM fallback and the
     * post-LLM guard re-check without needing a real platform-hosted pool or BYOK credential wired
     * up. Package-private - production code always goes through the two-arg overload.
     */
    Optional<SuggestedFix> suggestFix(
            McpDynamicConfig config, List<String> stderrTail, ChatClient chatClientOverride) {
        if (config == null || config.isRemote() || stderrTail == null || stderrTail.isEmpty()) {
            return Optional.empty();
        }
        String stderrBlock = String.join("\n", stderrTail);

        Optional<SuggestedFix> patternMatch = matchKnownPattern(stderrBlock);
        if (patternMatch.isPresent()) {
            return validate(patternMatch.get(), "pattern-matched");
        }

        return askLlmForFix(config, stderrBlock, chatClientOverride)
                .flatMap(fix -> validate(fix, "LLM-proposed"));
    }

    /**
     * Pure, deterministic, no dependencies - the fast path that must never cost an LLM call.
     * Package-private + static specifically so it is directly unit-testable with plain strings,
     * same posture as {@code McpConnectionDiagnoser}'s pure mapping methods.
     */
    static Optional<SuggestedFix> matchKnownPattern(String stderrBlock) {
        if (stderrBlock == null) {
            return Optional.empty();
        }
        Matcher matcher = UV_WRONG_EXECUTABLE.matcher(stderrBlock);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String packageName = matcher.group(1).trim();
        String suggestedInvocation = matcher.group(2).trim();
        List<String> tokens = tokenize(suggestedInvocation);
        if (tokens.isEmpty()) {
            return Optional.empty();
        }
        String command = tokens.get(0);
        List<String> args =
                tokens.size() > 1 ? List.copyOf(tokens.subList(1, tokens.size())) : List.of();
        String reason =
                "The package `"
                        + packageName
                        + "` does not provide an executable under the configured "
                        + "name - the server's own error names the correct command to use instead.";
        return Optional.of(new SuggestedFix(command, args, reason));
    }

    private static List<String> tokenize(String invocation) {
        String trimmed = invocation.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(trimmed.split("\\s+")).filter(s -> !s.isBlank()).toList();
    }

    /**
     * The one-LLM-call fallback. Deliberately swallows EVERY failure (Throwable, not just checked
     * provider exceptions) - a self-heal suggestion is a nice-to-have enrichment of a failure that
     * is already being reported normally, so nothing here may ever turn a clean failure report into
     * a second, confusing error.
     */
    private Optional<SuggestedFix> askLlmForFix(
            McpDynamicConfig config, String stderrBlock, ChatClient chatClientOverride) {
        try {
            ChatClient client =
                    chatClientOverride != null ? chatClientOverride : resolveChatClient();
            if (client == null) {
                return Optional.empty();
            }
            String system =
                    """
                You diagnose failed STDIO MCP (Model Context Protocol) server launches for a \
                platform that runs tenant-registered command-line MCP servers. Launchers are \
                restricted to npx, node, uvx, uv, python, python3 - never suggest anything else, \
                and never suggest inline-eval flags like -e/-c/--eval. You will be given the \
                configured command, configured args, and the child process's captured stderr.

                If - and only if - the stderr clearly implies one specific corrected command and \
                args, reply with STRICT JSON only, no markdown fences, no prose, no explanation \
                outside the JSON:
                {"command": "<launcher>", "args": ["<arg1>", "<arg2>"], "reason": "<one short sentence>"}

                If you cannot determine a confident fix from the stderr, reply with exactly:
                {"command": null}

                Never invent a package or executable name that is not evidenced in the stderr.""";
            String user =
                    "Configured command: "
                            + config.command()
                            + "\nConfigured args: "
                            + (config.args() == null ? List.of() : config.args())
                            + "\nServer stderr:\n"
                            + stderrBlock;
            String content = client.prompt().system(system).user(user).call().content();
            return parseLlmResponse(content);
        } catch (Throwable t) {
            log.debug(
                    "MCP self-heal LLM fallback produced no suggestion for '{}'", config.name(), t);
            return Optional.empty();
        }
    }

    /**
     * Parses the LLM's strict-JSON reply into a candidate fix - tolerant of a fenced code block
     * even though the prompt asked for none (models don't always comply), otherwise returns empty
     * on anything that isn't the expected shape rather than guessing. Package-private + static so
     * the parsing contract is directly unit-testable without a real ChatClient.
     */
    static Optional<SuggestedFix> parseLlmResponse(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            trimmed =
                    trimmed.replaceFirst("^```[a-zA-Z]*\\n?", "")
                            .replaceFirst("```\\s*$", "")
                            .trim();
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(trimmed);
            JsonNode commandNode = node.get("command");
            if (commandNode == null
                    || commandNode.isNull()
                    || !commandNode.isString()
                    || commandNode.asString().isBlank()) {
                return Optional.empty();
            }
            List<String> args = new ArrayList<>();
            JsonNode argsNode = node.get("args");
            if (argsNode != null && argsNode.isArray()) {
                argsNode.forEach(
                        n -> {
                            if (n.isString()) {
                                args.add(n.asString());
                            }
                        });
            }
            JsonNode reasonNode = node.get("reason");
            String reason =
                    reasonNode != null && reasonNode.isString() && !reasonNode.asString().isBlank()
                            ? reasonNode.asString()
                            : "Suggested by the platform's AI based on the server's error output.";
            return Optional.of(new SuggestedFix(commandNode.asString(), List.copyOf(args), reason));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Re-checks a candidate (from EITHER tier) through the same guard that gates real registration
     * save/connect - see class javadoc for why this is non-negotiable for the LLM tier and
     * defense-in-depth for the pattern tier. A rejected candidate is logged and dropped, never
     * returned.
     */
    private Optional<SuggestedFix> validate(SuggestedFix candidate, String origin) {
        Optional<String> blocked = commandGuard.check(candidate.command(), candidate.args());
        if (blocked.isPresent()) {
            log.warn(
                    "Rejected {} MCP fix suggestion '{} {}' - violates the launcher allowlist: {}",
                    origin,
                    candidate.command(),
                    candidate.args(),
                    blocked.get());
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    /**
     * Mirrors {@code AssistService#buildChatClient}'s routing branch exactly: platform-pool tenants
     * (the default - {@code TenantLimits#usesPlatformPool}) route through the platform-hosted pool;
     * only a tenant with an explicit BYOK choice uses its own active credential. Low token cap +
     * near-zero temperature, same "cheap, deterministic-ish, one-off internal helper" posture as
     * {@code AssistService}'s "Ask AI". Returns null (not an exception) when there is no ambient
     * tenant to resolve against (e.g. a background reconnect with no HTTP request) - {@link
     * #askLlmForFix} treats that the same as any other "no suggestion available" outcome.
     */
    private ChatClient resolveChatClient() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId == null) {
            return null;
        }
        TenantPlan plan = tenantLimits.plan(tenantId);
        if (tenantLimits.usesPlatformPool(tenantId, plan)) {
            return freeTierChatClientService.buildClientForFreeTenant(
                    tenantId, plan, 300, 0.1, 1.0);
        }
        // Background: nobody waits on a self-heal hint, so a key the provider refused recently is
        // skipped (and the
        // call not made at all when that is the only one) instead of failing again on every
        // reconnect attempt.
        ProviderCredentialResolver.ResolvedProvider active = credentialResolver.resolveBest(true);
        String model =
                active.lastWorkingModel() != null
                        ? active.lastWorkingModel()
                        : resolveModelFor(active.providerName());
        return llmGatewayService.resolveClient(
                tenantId,
                new GatewayCallSpec(active.providerName(), active.apiKey(), model, 300, 0.1, 1.0));
    }

    private String resolveModelFor(ProviderName provider) {
        List<ProviderModelResponse> models = providerModelCatalogService.listModels(provider);
        if (models.isEmpty()) {
            throw new com.agenticai.core.providers.ProviderModelCatalogEmptyException(provider);
        }
        return models.get(0).modelId();
    }
}
