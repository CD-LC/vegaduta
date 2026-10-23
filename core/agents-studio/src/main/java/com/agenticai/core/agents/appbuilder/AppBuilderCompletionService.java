package com.agenticai.core.agents.appbuilder;

import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.chat.structured.StructuredOutput;
import com.agenticai.core.providers.NoActiveProviderException;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

/**
 * App Builder generation routing fix (2026-08-06 live user report:
 * "Failed: generate() requires: pip install anthropic (and
 * ANTHROPIC_API_KEY)"). app-builder/app/generate.py used to call the
 * Anthropic Python SDK directly, hardcoded, with no fallback - this service
 * is what it calls instead (via {@link AppBuilderCompletionController}),
 * getting the SAME platform-pool/BYOK routing every other LLM call in this
 * platform already has.
 *
 * <p>Provider resolution deliberately mirrors {@code AssistService}
 * (core/agents/assist) - same "whichever provider is active for this
 * tenant" shape - but widened to cover a PAID tenant who opted into the
 * platform-hosted pool via {@code tenantUsePlatformLlm} (the flag {@code
 * AgentChatService#usesPlatformLlm} checks), not just FREE-plan tenants.
 * This is the exact three-source model VegaDūta already offers everywhere
 * else (platform pool / BYOK) - "edge" (on-device, browser-only) is
 * deliberately NOT this service's concern: on-device generation must be
 * produced client-side and posted back as already-generated files (see
 * app-builder's own follow-up work for that path), never routed through a
 * server-side completion call.
 *
 * <p>No Spring AI tool-use/structured-output machinery is used here -
 * deliberately the same plain {@code call().content()} + system-prompt-
 * enforced-JSON shape {@code AssistService#draft} already uses and this
 * codebase has actually exercised in production, rather than adopting an
 * unproven API surface for a first pass. The one corrective retry (matching
 * generate.py's own pre-existing "ONE retry, not a full rework loop" v0
 * scope) is handled by {@code AppBuilderCompletionController} calling this
 * service a second time with {@code previousFailure} populated - identical
 * call shape either way.
 */
@Service
public class AppBuilderCompletionService {

    private static final String SYSTEM_PROMPT = """
        You are an autonomous software engineer building a small web app for
        a non-technical user. You are given the app's tech stack, a handful
        of relevant existing files retrieved from the project, and an
        instruction describing what to build or change.

        Reply with ONLY a single JSON object, no markdown code fences, no
        prose before or after, matching exactly this shape:
        {"summary": "one or two plain-English sentences describing what you
        changed, written for the non-technical user (no file paths, no
        jargon - e.g. 'I added a dark-blue header with your business name
        and a contact form under it')",
         "files": [{"path": "relative/path", "content": "full new file content"}]}

        Include the FULL new content of every file you want to create or
        change - never partial diffs, never omit unchanged parts of a file
        you are rewriting. Keep changes minimal and scoped to the
        instruction. Never touch lockfiles, CI config, or secrets.

        Files marked PARTIAL were too large to show in full: you only see
        excerpts, so NEVER write a PARTIAL file (a full-file write would
        delete everything you cannot see). Put the change in a new file or
        in a file shown in full instead. When a PROJECT FILES list is
        given, only those files exist; do not import files that are not
        on it unless you create them.
        """;

    private final ProviderCredentialResolver credentialResolver;
    private final LlmGatewayService llmGatewayService;
    private final ProviderModelCatalogService providerModelCatalogService;
    private final TenantLimits tenantLimits;
    private final FreeTierChatClientService freeTierChatClientService;
    private final ObjectMapper objectMapper;
    private final String defaultModel;
    private final int maxTokens;

    public AppBuilderCompletionService(
        ProviderCredentialResolver credentialResolver,
        LlmGatewayService llmGatewayService,
        ProviderModelCatalogService providerModelCatalogService,
        TenantLimits tenantLimits,
        FreeTierChatClientService freeTierChatClientService,
        ObjectMapper objectMapper,
        @Value("${app.agents.default-model}") String defaultModel,
        @Value("${app.app-builder.max-tokens:8000}") int maxTokens
    ) {
        this.credentialResolver = credentialResolver;
        this.llmGatewayService = llmGatewayService;
        this.providerModelCatalogService = providerModelCatalogService;
        this.tenantLimits = tenantLimits;
        this.freeTierChatClientService = freeTierChatClientService;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
        this.maxTokens = maxTokens;
    }

    /**
     * Which engine a generation resolved to - surfaced verbatim in the HTTP
     * response so the app-builder pipeline (and ultimately the UI) can tell
     * the person what powered their app change instead of it being an
     * invisible server-side choice.
     */
    private record ResolvedTarget(ChatClient client, String providerName, String model) {
    }

    /**
     * Runs the completion and returns the parsed file list plus the engine
     * that produced it. Never returns null; throws {@link
     * AppBuilderGenerationException} if the LLM call itself fails OR the
     * reply cannot be parsed as the expected shape - the
     * ONE-retry-with-failure-context behavior lives in the controller, not
     * here, so this method always does exactly one call.
     */
    public AppBuilderGeneration generateFiles(String tenantId, AppBuilderGenerateFilesRequest request) {
        ResolvedTarget target = resolveTarget(tenantId);
        ChatClient chatClient = target.client();
        String user = buildUserPrompt(request);
        List<Media> media = decodeImages(request);
        String raw;
        try {
            raw = chatClient.prompt().system(SYSTEM_PROMPT).user(u -> {
                u.text(user);
                for (Media m : media) {
                    u.media(m);
                }
            }).call().content();
        } catch (RuntimeException e) {
            if (media.isEmpty() || !looksLikeMediaRejection(e)) {
                // Only a model refusing image input is worth a text-only
                // retry; a timeout/quota/5xx would just fail again after
                // resending the whole prompt.
                throw new AppBuilderGenerationException("LLM call failed: " + e.getMessage(), e);
            }
            // The resolved model rejected image input (text-only model,
            // or a provider that doesn't support media on this API
            // surface) — retry once WITHOUT the images rather than failing
            // the whole generate over a nicety. The text context (palette,
            // captions, SVG markup) still carries most of the signal.
            try {
                raw = chatClient.prompt().system(SYSTEM_PROMPT)
                    .user(user + "\n\n(Attached design images could not be "
                          + "processed by this model — rely on the text "
                          + "description and palette.)")
                    .call().content();
            } catch (RuntimeException e2) {
                throw new AppBuilderGenerationException("LLM call failed: " + e2.getMessage(), e2);
            }
        }
        AppBuilderGenerateFilesResponse parsed = parseResponse(raw);
        String summary = parsed.summary() == null || parsed.summary().isBlank() ? null : parsed.summary().trim();
        return new AppBuilderGeneration(parsed.files(), target.providerName(), target.model(), summary);
    }

    /**
     * Decode the optional design-input images to Spring AI {@link Media}
     * objects. Capped defensively (count + bytes) here too — the route
     * validates, but this service can also be called directly. A malformed
     * or oversized entry is skipped, never a hard failure: the text prompt
     * alone is still a valid request.
     */
    private List<Media> decodeImages(AppBuilderGenerateFilesRequest request) {
        List<Media> out = new ArrayList<>();
        List<AppBuilderImage> images = request.images();
        if (images == null) {
            return out;
        }
        for (AppBuilderImage img : images) {
            if (out.size() >= 4) break;
            if (img == null || img.dataBase64() == null || img.mediaType() == null) continue;
            if (!img.mediaType().startsWith("image/")) continue;
            try {
                byte[] bytes = Base64.getDecoder().decode(img.dataBase64());
                if (bytes.length > 4 * 1024 * 1024) continue;
                out.add(new Media(
                    MimeTypeUtils.parseMimeType(img.mediaType()),
                    new ByteArrayResource(bytes)));
            } catch (IllegalArgumentException ignored) {
                // bad base64 or bad mime — skip this attachment
            }
        }
        return out;
    }

    /** Whole-prompt budget for existing file contents. */
    static final int FILES_CHAR_BUDGET = 60_000;

    private static boolean looksLikeMediaRejection(RuntimeException e) {
        String m = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return m.contains("image") || m.contains("media") || m.contains("vision")
            || m.contains("multimodal") || m.contains("content type") || m.contains("unsupported")
            || m.contains("400") || m.contains("invalid");
    }

    /**
     * Existing files for the prompt. Entries for the same path (code-intel
     * returns several chunks per file) are merged under one header; a file
     * app-builder could send in full is shown in full, anything else - or
     * anything past {@link #FILES_CHAR_BUDGET} - is labelled PARTIAL so the
     * model knows not to rewrite it (the system prompt forbids that and
     * app-builder drops such writes).
     */
    static String filesBlob(List<AppBuilderFileEdit> files, List<String> fullPaths) {
        java.util.LinkedHashMap<String, StringBuilder> byPath = new java.util.LinkedHashMap<>();
        for (AppBuilderFileEdit f : files) {
            if (f == null || f.path() == null) continue;
            StringBuilder sb = byPath.computeIfAbsent(f.path(), k -> new StringBuilder());
            if (sb.length() > 0) sb.append("\n...\n");
            sb.append(f.content() == null ? "" : f.content());
        }
        java.util.Set<String> full = fullPaths == null ? java.util.Set.of() : new java.util.HashSet<>(fullPaths);
        StringBuilder out = new StringBuilder();
        int used = 0;
        for (var e : byPath.entrySet()) {
            String content = e.getValue().toString();
            boolean partial = !full.contains(e.getKey());
            int room = FILES_CHAR_BUDGET - used;
            if (room <= 200) break;
            if (content.length() > room) {
                content = content.substring(0, room);
                partial = true;
            }
            if (out.length() > 0) out.append("\n\n");
            out.append("# ").append(e.getKey()).append(partial ? "  (PARTIAL - excerpts only, do not write this file)" : "")
                .append("\n").append(content);
            used += content.length();
        }
        return out.toString();
    }

    private String buildUserPrompt(AppBuilderGenerateFilesRequest request) {
        String contextBlob = filesBlob(request.retrievedFiles(), request.fullFilePaths());
        String stack = request.stack() == null || request.stack().isBlank() ? "unknown stack" : request.stack();
        StringBuilder user = new StringBuilder()
            .append("STACK: ").append(stack).append("\n\n")
            .append("INSTRUCTION:\n").append(request.instruction()).append("\n\n");
        // Design context: wireframe spec / palette / SVG markup / asset
        // names composed client-side. Appended verbatim — it is the "next
        // gen designer" input channel (2026-09-26).
        if (request.context() != null && !request.context().isBlank()) {
            String ctx = request.context();
            user.append("DESIGN CONTEXT (user-provided — wireframe, palette, "
                    + "uploaded assets; honor it when writing UI code):\n")
                .append(ctx.length() > 8000 ? ctx.substring(0, 8000) : ctx)
                .append("\n\n");
        }
        if (request.images() != null && !request.images().isEmpty()) {
            user.append("The user attached ").append(request.images().size())
                .append(" design image(s) (screenshot / sketch / Figma export) "
                    + "— match their layout and look.\n\n");
        }
        if (request.projectFiles() != null && !request.projectFiles().isEmpty()) {
            user.append("PROJECT FILES:\n");
            request.projectFiles().stream().limit(300).forEach(p -> user.append(p).append("\n"));
            user.append("\n");
        }
        user.append("RELEVANT EXISTING FILES:\n")
            .append(contextBlob.isEmpty() ? "(none found)" : contextBlob);
        if (request.formatRetry()) {
            user.append("\n\nYour previous reply contained no usable file entries. "
                + "Reply with ONLY the required {\"summary\":\"...\",\"files\":[...]} JSON object.");
        } else if (request.previousFailure() != null && !request.previousFailure().isBlank()) {
            String failure = request.previousFailure();
            user.append("\n\nThe previous attempt's build/test command FAILED. Output:\n")
                .append(failure.length() > 4000 ? failure.substring(0, 4000) : failure)
                .append("\n\nFix the files so the command succeeds.");
        }
        return user.toString();
    }

    /**
     * Same platform-vs-BYOK resolution shape as {@code AssistService
     * #buildChatClient}, widened to also cover a PAID tenant's
     * {@code tenantUsePlatformLlm} opt-in (see class javadoc) - mirrors
     * {@link TenantLimits#usesPlatformPool(String, TenantPlan)} - the one
     * place that condition lives - rather than AssistService's narrower
     * FREE-only check, since app-builder should honor the same platform-pool
     * choice a tenant already made for their agents. {@code plan} is resolved
     * here anyway for {@code buildClientForFreeTenant}, so passing it through
     * keeps this to a single tenant lookup.
     */
    private ResolvedTarget resolveTarget(String tenantId) {
        TenantPlan plan = tenantLimits.plan(tenantId);
        if (tenantLimits.usesPlatformPool(tenantId, plan)) {
            // model stays null on purpose: buildClientForFreeTenant is a
            // fallback CHAIN over the pool, not a single model - the call's
            // actual model is decided inside it, so no honest id exists here.
            return new ResolvedTarget(
                freeTierChatClientService.buildClientForFreeTenant(tenantId, plan, maxTokens, 0.3, 1.0),
                ProviderName.PLATFORM_HOSTED.name(), null);
        }
        ProviderCredentialResolver.ActiveProvider active;
        try {
            active = credentialResolver.resolveAnyActiveKey();
        } catch (NoActiveProviderException e) {
            throw new AppBuilderGenerationException(
                "No platform-pool opt-in and no active BYOK provider configured for this tenant.", e
            );
        }
        String model = resolveModelFor(active.providerName());
        return new ResolvedTarget(
            llmGatewayService.resolveClient(tenantId, new GatewayCallSpec(
                active.providerName(), active.apiKey(), model, maxTokens, 0.3, 1.0
            )),
            active.providerName().name(), model);
    }

    /**
     * Identical shape to {@code AssistService#resolveModelFor} - including
     * why {@code defaultModel} (an Anthropic model id) is ONLY a safe
     * fallback when the resolved provider IS Anthropic: passing it to any
     * other provider previously produced a 404 "model not found" at the
     * gateway (reproduced via Gemini, see AssistService's own javadoc) -
     * this must not be re-simplified into an unconditional fallback.
     */
    private String resolveModelFor(ProviderName provider) {
        // defaultModelFor, not the catalog's first alphabetical entry - that
        // could be a retired or non-chat model (see AssistService).
        java.util.Optional<String> picked = providerModelCatalogService.defaultModelFor(provider);
        if (picked.isPresent()) {
            return picked.get();
        }
        if (provider == ProviderName.ANTHROPIC) {
            return defaultModel;
        }
        throw new AppBuilderGenerationException(
            "No models available in the catalog for provider " + provider + " and it is not Anthropic."
        );
    }

    /**
     * Parses the model's reply as {@code {"files": [{"path","content"}]}}.
     * Tolerant of a stray markdown code fence (some providers wrap JSON in
     * ```json ... ``` even when told not to) - stripped before parsing, not
     * treated as a hard failure. Filters out any entry missing path/content
     * rather than failing the whole batch over one malformed entry, matching
     * generate.py's own pre-existing filter.
     */
    private AppBuilderGenerateFilesResponse parseResponse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AppBuilderGenerationException("The model returned an empty reply.");
        }
        try {
            AppBuilderGenerateFilesResponse parsed =
                StructuredOutput.parse(objectMapper, raw, AppBuilderGenerateFilesResponse.class);
            List<AppBuilderFileEdit> result = new ArrayList<>();
            if (parsed.files() != null) {
                for (AppBuilderFileEdit f : parsed.files()) {
                    if (f != null && f.path() != null && f.content() != null) {
                        result.add(f);
                    }
                }
            }
            return new AppBuilderGenerateFilesResponse(result, parsed.summary());
        } catch (Exception e) {
            throw new AppBuilderGenerationException(
                "Could not parse the model's reply as the expected {files:[...]} JSON shape.", e
            );
        }
    }
}
