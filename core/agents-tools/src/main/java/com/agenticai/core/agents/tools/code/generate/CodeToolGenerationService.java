package com.agenticai.core.agents.tools.code.generate;

import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.tools.code.CustomCodeToolLanguage;
import com.agenticai.core.agents.tools.code.CustomCodeToolsDisabledException;
import com.agenticai.core.agents.tools.code.ToolSandboxClient;
import com.agenticai.core.agents.tools.code.dto.CodeToolGenerationRequest;
import com.agenticai.core.agents.tools.code.dto.CodeToolGenerationResponse;
import com.agenticai.core.agents.tools.code.dto.SandboxValidateResponse;
import com.agenticai.core.agents.tools.code.dto.SandboxValidateResponse.SandboxValidationError;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderModelCatalogEmptyException;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderModelResponse;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Backs "Ask AI to generate the tool" in the Custom Code Tools editor - one throwaway completion
 * that drafts a {@code Tool.run(Map)} (Java) or top-level {@code run(input)} (Python) program from
 * a tenant's free-text requirement, then runs the exact same {@code /v1/validate} check {@code
 * CustomCodeToolService} runs on every save so the tenant sees a real verdict before they ever
 * click Save.
 *
 * <p>Reuses the identical LLM seam {@code AssistService} and {@code WorkflowGraphGeneratorService}
 * use - {@link ProviderCredentialResolver} (BYOK) / {@link FreeTierChatClientService} (FREE plan,
 * platform-hosted pool) funnelled through {@link LlmGatewayService#resolveClient}, "whichever
 * provider is active" rather than a specific agent's own provider. See {@link #buildChatClient()}.
 *
 * <p><strong>Deliberately does not persist anything and never calls {@code
 * CustomCodeToolService#create}.</strong> Same posture as {@code WorkflowGraphGeneratorService}:
 * this is a draft the tenant reviews in the editor, and saving - which re-validates server-side
 * regardless - stays their own, separate {@code POST}/{@code PUT} to {@code /api/tools/code}. A
 * generation endpoint that silently created a real, callable tool would let one AI-drafted program
 * become agent-visible without a human ever reading it.
 */
@Service
public class CodeToolGenerationService {

    private final ProviderCredentialResolver credentialResolver;
    private final LlmGatewayService llmGatewayService;
    private final ProviderModelCatalogService providerModelCatalogService;
    private final TenantLimits tenantLimits;
    private final FreeTierChatClientService freeTierChatClientService;
    // ObjectProvider, not a plain field - identical reasoning to
    // CustomCodeToolService: the ToolSandboxClient bean only exists when
    // tool-sandbox.enabled=true, so this service must still be constructible
    // on a deployment where the feature is off.
    private final ObjectProvider<ToolSandboxClient> sandboxClient;
    private final String defaultModel;
    private final int generationMaxTokens;

    public CodeToolGenerationService(
            ProviderCredentialResolver credentialResolver,
            LlmGatewayService llmGatewayService,
            ProviderModelCatalogService providerModelCatalogService,
            TenantLimits tenantLimits,
            FreeTierChatClientService freeTierChatClientService,
            ObjectProvider<ToolSandboxClient> sandboxClient,
            @Value("${app.agents.default-model}") String defaultModel,
            // Own config key, not app.agents.default-max-tokens (1024, tuned for
            // a short conversational reply) - a real Java/Python program routinely
            // needs more headroom than a paragraph of prose without running into
            // the truncated-output failure WorkflowGraphGeneratorService's own
            // max-tokens javadoc documents for the same reason.
            @Value("${app.tools.code.generate.max-tokens:2048}") int generationMaxTokens) {
        this.credentialResolver = credentialResolver;
        this.llmGatewayService = llmGatewayService;
        this.providerModelCatalogService = providerModelCatalogService;
        this.tenantLimits = tenantLimits;
        this.freeTierChatClientService = freeTierChatClientService;
        this.sandboxClient = sandboxClient;
        this.defaultModel = defaultModel;
        this.generationMaxTokens = generationMaxTokens;
    }

    /**
     * Drafts a program for {@code request}, then validates it. Never throws on a bad draft - {@code
     * valid=false} plus {@code errors} is the normal, expected shape of "the model's first attempt
     * didn't compile", exactly like {@link ToolSandboxClient#validate} itself. Throws only on real
     * infrastructure problems: {@link CustomCodeToolsDisabledException} (no sandbox on this
     * deployment - checked first, before spending an LLM call on a feature that can't finish
     * anyway) and whatever {@link LlmGatewayService}/{@link ToolSandboxClient} throw for a provider
     * or sandbox outage.
     */
    public CodeToolGenerationResponse generate(CodeToolGenerationRequest request) {
        // Checked before spending an LLM call: no point drafting code for a
        // feature that has nowhere to validate or run it.
        requireClient();

        ChatClient chatClient = buildChatClient();
        String system = buildSystemPrompt(request.language());
        String user = buildUserPrompt(request);
        String raw = chatClient.prompt().system(system).user(user).call().content();
        String source = stripCodeFence(raw == null ? "" : raw.trim());

        // The draft is model output steered by tenant free text: an allowlist screen runs first
        // and can only make it INVALID (the sandbox still gives the real verdict on a pass).
        List<SandboxValidationError> screened = GeneratedCodeScreen.screen(request.language(), source);
        if (!screened.isEmpty()) {
            SandboxValidateResponse rejected =
                    new SandboxValidateResponse(
                            false,
                            null,
                            screened,
                            List.of("Rejected before reaching the sandbox: the draft uses something custom tools may not."));
            return CodeToolGenerationResponse.from(source, request.language(), rejected);
        }
        SandboxValidateResponse verdict = validate(request.language(), request.toolName(), source);
        return CodeToolGenerationResponse.from(source, request.language(), verdict);
    }

    /**
     * The LLM-free half of {@link #generate} - the exact same {@code ToolSandboxClient.validate}
     * call this class runs on its own draft, exposed standalone for {@code
     * CodeToolGenerationController#validate} (see that endpoint's javadoc for its two callers).
     */
    public SandboxValidateResponse validate(
            CustomCodeToolLanguage language, String toolName, String sourceCode) {
        String toolNameForValidation = StringUtils.hasText(toolName) ? toolName : "generated_tool";
        return requireClient().validate(language, toolNameForValidation, sourceCode);
    }

    private ToolSandboxClient requireClient() {
        ToolSandboxClient client = sandboxClient.getIfAvailable();
        if (client == null) {
            throw new CustomCodeToolsDisabledException();
        }
        return client;
    }

    // ------------------------------------------------------------------ //
    // Prompt construction
    // ------------------------------------------------------------------ //

    private String buildSystemPrompt(CustomCodeToolLanguage language) {
        String contract = language == CustomCodeToolLanguage.JAVA ? JAVA_CONTRACT : PYTHON_CONTRACT;
        return """
            You write ONE tenant-authored program for a sandboxed "custom code
            tool" feature in an AI agent platform. The program you write is
            compiled/parsed and then executed by an isolated sandbox
            microservice that enforces a strict allowlist - it is NOT a
            general-purpose runtime, so staying inside the contract below is
            essential, not optional.

            %s

            Rules:
            - Reply with ONLY the finished source code for that one file/module
              - no markdown code fences, no explanation before or after, no
              commentary, no multiple files.
            - The program must be self-contained: no network calls, no file
              I/O, no process/thread spawning, no reflection - the sandbox has
              no route to the network or filesystem and denies all of these,
              so code that attempts them will be rejected outright.
            - Read inputs only from the given input map/dict; never assume a
              specific key exists without a safe default or an explicit,
              readable error in the returned result.
            - Return a small JSON-serializable result (a map/dict, list,
              string, number, or boolean) describing the outcome - this is
              what is handed back to the calling AI agent, so make it clear
              and directly useful, not merely "true"/"ok".
            - Prefer simple, obviously-correct code over clever code: this
              program is read and re-validated by a static checker before it
              ever runs.
            """
                .formatted(contract);
    }

    private String buildUserPrompt(CodeToolGenerationRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("Requirement: ").append(request.requirement());
        if (StringUtils.hasText(request.template())) {
            sb.append("\nStarting point / template the tenant picked: ").append(request.template());
        }
        if (StringUtils.hasText(request.toolName())) {
            sb.append("\nThe tool is currently named \"")
                    .append(request.toolName())
                    .append(
                            "\" in the editor - let that inform naming/messages, but it is not part of the code contract.");
        }
        sb.append("\nLanguage: ").append(request.language().name());
        return sb.toString();
    }

    private static final String JAVA_CONTRACT =
            """
        Contract (Java): a single class named exactly `Tool` in the default
        package (no package declaration) with the exact signature:

            public class Tool {
                public static Object run(java.util.Map<String, Object> input) {
                    ...
                }
            }

        Only these packages are reachable: java.lang.*, java.util.*
        (collections/text - NOT java.util.concurrent, java.util.stream is
        fine), java.math.*, java.time.*, java.text.*. Lambdas, records,
        string concatenation with `+`, and switch expressions are all fine
        (the sandbox specifically allows the bytecode they compile to).
        Everything else - java.io, java.net, java.nio, java.util.concurrent,
        java.lang.reflect, java.lang.Runtime/ProcessBuilder, java.lang.System
        beyond pure computation - is rejected by the sandbox's bytecode
        verifier, not merely discouraged. The same goes for reading system
        properties (Boolean.getBoolean, Integer.getInteger, Long.getLong),
        java.util.ResourceBundle, and parallel streams / Arrays.parallel*
        (the program runs single-threaded).""";

    private static final String PYTHON_CONTRACT =
            """
        Contract (Python): a top-level function with the exact signature:

            def run(input):
                ...

        `input` is a dict; return a JSON-serializable value. Only these
        modules may be imported: base64, binascii, bisect, calendar, cmath,
        collections, copy, dataclasses, datetime, decimal, difflib, enum,
        fractions, functools, hashlib, heapq, hmac, html, itertools, json,
        math, numbers, operator, random, re, statistics, string, textwrap,
        time, types, typing, unicodedata, uuid, zoneinfo. Do not import os,
        sys, subprocess, socket, pathlib, threading, multiprocessing,
        urllib, http, or anything file/network/process related - none of
        that is available and the sandbox will reject it. Builtins like
        open, eval, exec, compile, getattr, setattr, __import__, and input()
        (the builtin, not the `input` parameter) are also unavailable -
        never use them. Use `print(...)` only for the author's own test-run
        stdout, never to communicate the result - always return it.""";

    // ------------------------------------------------------------------ //
    // LLM client seam - identical to AssistService#buildChatClient /
    // WorkflowGraphGeneratorService#buildChatClient. See this class's
    // javadoc for why the same seam is reused rather than a new one.
    // ------------------------------------------------------------------ //

    private ChatClient buildChatClient() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : null;
        if (tenantLimits.usesPlatformPool(tenantId, plan)) {
            return freeTierChatClientService.buildClientForFreeTenant(
                    tenantId, plan, generationMaxTokens, 0.2, 1.0);
        }
        ProviderCredentialResolver.ActiveProvider active = credentialResolver.resolveAnyActiveKey();
        String model = resolveModelFor(active.providerName());
        return llmGatewayService.resolveClient(
                tenantId,
                new GatewayCallSpec(
                        active.providerName(),
                        active.apiKey(),
                        model,
                        generationMaxTokens,
                        0.2,
                        1.0));
    }

    private String resolveModelFor(ProviderName provider) {
        List<ProviderModelResponse> models = providerModelCatalogService.listModels(provider);
        if (!models.isEmpty()) {
            return models.get(0).modelId();
        }
        if (provider == ProviderName.ANTHROPIC) {
            return defaultModel;
        }
        throw new ProviderModelCatalogEmptyException(provider);
    }

    /**
     * Strips a single leading/trailing markdown code fence (```` ```java ... ``` ````/````
     * ```python ... ``` ````/```` ``` ... ``` ````) if the model added one despite being told not
     * to - models routinely do this anyway. Same approach as {@code
     * WorkflowGraphGeneratorService}'s own {@code stripCodeFence}.
     */
    private static String stripCodeFence(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            trimmed = firstNewline >= 0 ? trimmed.substring(firstNewline + 1) : trimmed;
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.trim();
    }
}
