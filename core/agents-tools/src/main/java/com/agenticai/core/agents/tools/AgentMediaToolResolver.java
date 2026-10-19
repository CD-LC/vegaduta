package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.tools.audio.SpeakToolCallback;
import com.agenticai.core.agents.tools.documentgen.GenerateDocumentToolCallback;
import com.agenticai.core.agentspi.MediaToolSource;
import com.agenticai.core.agentspi.SpeakBackend;
import com.agenticai.core.documentgen.service.DocumentGenerationService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Tool-resolution group extracted from {@link AgentToolProvider} (extract-only, no behavior
 * change). Not a Spring bean: constructed by AgentToolProvider.
 */
final class AgentMediaToolResolver {

    private final AgentToolGuard toolGuard;

    private final AgentService agentService;
    // Language (Sarvam), image, video and music tools come from vendor adapters behind the kernel's
    // MediaToolSource (ADR-0011 rule a): sarvam.agenttools.SarvamLanguageToolSource,
    // imagegen.agenttools.ImageGenerationToolSource / VideoGenerationToolSource,
    // elevenlabs.agenttools.MusicGenerationToolSource. Each applies its
    // vendor's cheap, non-throwing credential gate; this class keeps the kernel's per-agent gates
    // and the tool guard. A slim test context with none of them simply offers none of the tools.
    private final List<MediaToolSource> mediaToolSources;
    // Event-driven document generation (DOCX/PDF/XLSX/PPTX/CSV/TXT/MD/JSON) -
    // ObjectProvider for the same reason every other cross-module optional
    // capability in this class uses one: core/documentgen is always on the
    // classpath in the real app now that agents depends on it directly, but
    // this keeps slim test contexts constructible without a
    // DocumentGenerationService bean. No cheap-boolean gate needed here (unlike
    // the image/video sources, which check provider-credential state before
    // offering the tool) - document generation needs no external
    // provider credential, only a paid plan tier, and that check already lives
    // inside DocumentGenerationQuotaService#checkAllowed, enforced at call time
    // by GenerateDocumentToolCallback itself (see its consumerReason javadoc).
    private final ObjectProvider<DocumentGenerationService> documentGenerationService;
    // Registry refactor (2026-08-09, docs/GENERIC-PROVIDER-CAPABILITY-REGISTRY-2026-08-09.md
    // Phase 1 slice): resolveAudioTools delegates backend selection to this registry instead
    // of a hand-written priority chain - see its javadoc.
    private final com.agenticai.core.agents.tools.audio.SpeakBackendRegistry speakBackendRegistry;

    AgentMediaToolResolver(
            AgentToolGuard toolGuard,
            AgentService agentService,
            List<MediaToolSource> mediaToolSources,
            ObjectProvider<DocumentGenerationService> documentGenerationService,
            com.agenticai.core.agents.tools.audio.SpeakBackendRegistry speakBackendRegistry) {
        this.toolGuard = toolGuard;
        this.agentService = agentService;
        this.mediaToolSources = List.copyOf(mediaToolSources);
        this.documentGenerationService = documentGenerationService;
        this.speakBackendRegistry = speakBackendRegistry;
    }

    private ToolCallback guardAndLog(
            ToolCallback raw,
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        return toolGuard.guardAndLog(
                raw, agentId, sessionId, stepCounter, userMessage, chatClient, rigorous);
    }

    /** Every source of {@code kind}'s raw tools, guarded. */
    private List<ToolCallback> guardedTools(
            MediaToolSource.Kind kind,
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        return mediaToolSources.stream()
                .filter(source -> source.kind() == kind)
                .flatMap(source -> source.rawTools().stream())
                .map(
                        raw ->
                                guardAndLog(
                                        raw,
                                        agentId,
                                        sessionId,
                                        stepCounter,
                                        userMessage,
                                        chatClient,
                                        rigorous))
                .toList();
    }

    /**
     * Sarvam attaches to agents like every other LLM provider - the language tools ({@code sarvam_translate}/{@code sarvam_transcribe},
     * plus transliterate/identify-language) require BOTH a tenant-level Sarvam credential (checked
     * by the {@link MediaToolSource.Kind#LANGUAGE} source with a cheap boolean, never the throwing
     * resolver) AND this specific agent having been explicitly attached via {@code
     * agentService.isSarvamEnabled(agentId)} (off by default - see {@code Agent.sarvamEnabled}'s
     * javadoc). Originally this offered the tools to every agent automatically the moment the
     * tenant connected Sarvam, mirroring {@code
     * AgentIntegrationToolResolver#resolveWorkspaceTools}'s tenant-only gate for Google Workspace -
     * that read as Sarvam silently reaching into agents that never asked for it, so Sarvam alone
     * was moved to this two-gate, explicit-attachment model; Google Workspace's tenant-only gate is
     * unchanged. Speak lives in {@link #resolveAudioTools}.
     */
    List<ToolCallback> resolveSarvamTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        if (!agentService.isSarvamEnabled(agentId)) {
            return List.of();
        }
        return guardedTools(
                MediaToolSource.Kind.LANGUAGE,
                agentId,
                sessionId,
                stepCounter,
                userMessage,
                chatClient,
                rigorous);
    }

    /**
     * Master-Roadmap-2026.md item #3 (image generation). No per-agent opt-in flag (no {@code
     * Agent.imageGenEnabled} column) - the {@link MediaToolSource.Kind#IMAGE} source's tenant-level
     * gate (a BYOK image provider, or the platform pool) is enough.
     */
    List<ToolCallback> resolveImageGenTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        return guardedTools(
                MediaToolSource.Kind.IMAGE,
                agentId,
                sessionId,
                stepCounter,
                userMessage,
                chatClient,
                rigorous);
    }

    /**
     * Video counterpart of {@link #resolveImageGenTools}. The {@link MediaToolSource.Kind#VIDEO}
     * source offers {@code generate_video} only to tenants with their own video credential (video
     * is PAID/BYOK-only, product decision 2026-08-03).
     */
    List<ToolCallback> resolveVideoGenTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        return guardedTools(
                MediaToolSource.Kind.VIDEO,
                agentId,
                sessionId,
                stepCounter,
                userMessage,
                chatClient,
                rigorous);
    }

    /**
     * Event-driven document generation (DOCX/PDF/XLSX/PPTX/CSV/TXT/MD/JSON) - empty only if {@code
     * DocumentGenerationService} isn't on the classpath (slim test contexts, see the field's own
     * javadoc). Unlike {@link #resolveImageGenTools}/{@link #resolveVideoGenTools}, there is no
     * provider-credential gate to check here (document rendering is local, not a third-party API
     * call) - the FREE-plan/quota gate lives inside {@code
     * DocumentGenerationQuotaService#checkAllowed}, enforced by {@link
     * GenerateDocumentToolCallback} itself at call time and turned into a person-facing sentence
     * there, the same "cheap boolean gate at offer-time, real enforcement at call-time" split every
     * other optional-tool gate in this class uses.
     */
    List<ToolCallback> resolveDocumentGenTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        DocumentGenerationService service = documentGenerationService.getIfAvailable();
        if (service == null) {
            return List.of();
        }
        ToolCallback generateDocument = new GenerateDocumentToolCallback(service);
        return List.of(
                guardAndLog(
                        generateDocument,
                        agentId,
                        sessionId,
                        stepCounter,
                        userMessage,
                        chatClient,
                        rigorous));
    }

    /**
     * Master-Roadmap-2026.md item #4 (voice cloning), generalized 2026-08-02 (audio provider gap):
     * used to be two separate, differently-named tools ({@code speak_with_cloned_voice} for
     * ElevenLabs, {@code sarvam_speak} for Sarvam - a tenant with both configured saw both and had
     * to guess which to call). Now exposes exactly ONE {@code speak} tool, backed by whichever
     * provider is eligible - see {@link SpeakBackend}'s javadoc for why this is "pick one backend"
     * rather than an {@code ImageGenerationOrchestratorService}-style try-in-order fallback: the
     * two products are not interchangeable (a cloned brand voice vs. a requested-language stock
     * voice).
     *
     * <p>Priority: ElevenLabs first (a cloned voice is a deliberate, one-time tenant opt-in - see
     * {@code core/elevenlabs}'s pom.xml javadoc - so if a tenant went to the trouble of cloning
     * one, that is almost certainly the voice they want their agent to use), Sarvam otherwise.
     *
     * <p>ElevenLabs gate unchanged from the old {@code resolveElevenLabsTools}: a cheap,
     * non-throwing {@code elevenLabsCredentialService.isActive()} AND {@code
     * elevenLabsVoiceService.hasAnyVoice()} check, never the throwing {@code
     * resolveActiveApiKey()}/{@code mostRecent()} - see resolveImageGenTools' javadoc for why.
     * Sarvam gate unchanged from the old {@code resolveSarvamTools}'s speak line: {@code
     * sarvamCredentialService.isActive()} AND {@code agentService.isSarvamEnabled(agentId)} - that
     * per-agent opt-in was itself added after direct feedback (see resolveSarvamTools' own javadoc
     * for that history), so it is preserved here rather than silently dropped just because speak
     * moved out of that method's tool bundle.
     */
    /**
     * Backend selection delegates to {@link
     * com.agenticai.core.agents.tools.audio.SpeakBackendRegistry}
     * (docs/GENERIC-PROVIDER-CAPABILITY-REGISTRY-2026-08-09.md, Phase 1 slice) instead of a
     * hand-written priority chain living in this method - see that class and {@code
     * com.agenticai.core.agentspi.SpeakCapabilityProvider}'s four implementations for the exact
     * same priority order this replaces (ElevenLabs BYOK, Sarvam BYOK, platform ElevenLabs,
     * platform Sarvam). Behavior is unchanged; only the selection mechanism moved.
     */
    List<ToolCallback> resolveAudioTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        SpeakBackend backend = speakBackendRegistry.resolve().orElse(null);
        if (backend == null) {
            return List.of();
        }
        ToolCallback speak = new SpeakToolCallback(backend);
        return List.of(
                guardAndLog(
                        speak, agentId, sessionId, stepCounter, userMessage, chatClient, rigorous));
    }

    /**
     * Music counterpart of {@link #resolveImageGenTools}, same BYOK-or-platform shape: the {@link
     * MediaToolSource.Kind#MUSIC} source offers {@code generate_music} when the tenant has an
     * active music key of their own OR the platform key is set (cheap, non-throwing). Subsidising
     * it from the platform key is affordable for the same reason image is and video is not: a
     * 20-30s clip is a few cents, and the vendor's quota service caps both the count and the length
     * per tenant before the billable call.
     */
    List<ToolCallback> resolveMusicGenTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        return guardedTools(
                MediaToolSource.Kind.MUSIC,
                agentId,
                sessionId,
                stepCounter,
                userMessage,
                chatClient,
                rigorous);
    }
}
