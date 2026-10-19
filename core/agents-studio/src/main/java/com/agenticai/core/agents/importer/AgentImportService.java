package com.agenticai.core.agents.importer;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentCreateRequest;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.importer.AgentImportDtos.ImportResponse;
import com.agenticai.core.agents.importer.AgentImportDtos.ImportedAgentView;
import com.agenticai.core.agents.importer.AgentImportDtos.PreviewResponse;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Migration kits: turns an agent exported from another platform into a DRAFT agent on this one, with an
 * honest report of what converted, what needs a manual step and what has no equivalent.
 *
 * <p>The payload is DATA only. Nothing here fetches a URL, evaluates code or follows a reference outside
 * the payload, and imported text is never interpreted - it becomes the user's own agent prompt, exactly as if
 * they had typed it. Full payloads are never logged (only a source tag and counts).
 *
 * <p>{@link #importAgent} goes through {@link AgentService#create}, the same path as {@code POST
 * /api/agents}, so the plan's agent cap, FREE-plan provider coercion, default guardrails and version history
 * all apply unchanged. The agent is created private (not shared, on no channel), pinned to ADVANCED experience
 * mode so a SIMPLE tenant default cannot auto-enable the knowledge base, and its tool allow-list is narrowed to
 * {@link #SAFE_TOOL_NAMES} (read-only knowledge tools) so no send/egress tool is reachable until the user adds
 * it. It never turns a capability on and never attaches a credential.
 *
 * <p>Throws only types {@code GlobalExceptionHandler} already maps: {@link IllegalArgumentException} (400),
 * {@link ResponseStatusException} (404 when disabled, 413 when too large) and whatever {@code
 * AgentService#create} throws (403 plan cap, etc.).
 */
@Service
public class AgentImportService {

    private static final Logger log = LoggerFactory.getLogger(AgentImportService.class);

    /** Nesting depth the JSON parser accepts; real exports are well under 20 deep. */
    static final int MAX_NESTING_DEPTH = 64;
    /** Room for the {@code {source, payload, overrides}} wrapper around a payload of exactly the cap. */
    static final int ENVELOPE_SLACK_BYTES = 8192;
    /** Read-only, non-egress tools an imported agent may use until the user widens its allow-list. */
    static final Set<String> SAFE_TOOL_NAMES = Set.of("search_knowledge_base", "list_knowledge_collections");

    private final AgentService agentService;
    private final ProviderModelCatalogService catalogService;
    private final TenantLimits tenantLimits;
    private final boolean enabled;
    private final long maxPayloadBytes;
    private final ObjectMapper mapper;

    public AgentImportService(
        AgentService agentService,
        ProviderModelCatalogService catalogService,
        TenantLimits tenantLimits,
        @Value("${app.agent-import.enabled:true}") boolean enabled,
        @Value("${app.agent-import.max-payload-bytes:1048576}") long maxPayloadBytes
    ) {
        this.agentService = agentService;
        this.catalogService = catalogService;
        this.tenantLimits = tenantLimits;
        this.enabled = enabled;
        this.maxPayloadBytes = Math.max(1024L, maxPayloadBytes);
        JsonFactory factory = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(MAX_NESTING_DEPTH)
                // Envelope slack included: a JSON-in-string payload is one string value of up to the cap plus
                // escaping, and resolvePayload's explicit byte check (413) must see it before the parser's
                // own limit (which would answer a misleading 400).
                .maxStringLength((int) Math.min(Integer.MAX_VALUE, this.maxPayloadBytes + ENVELOPE_SLACK_BYTES))
                .maxNumberLength(64)
                .build())
            .build();
        this.mapper = JsonMappers.withFactory(factory);
    }

    /**
     * Creates nothing for the tenant: parses, maps and matches the model against the catalog. (The catalog
     * lookup may warm the shared provider-model cache, which is not tenant data.)
     */
    public PreviewResponse preview(String body) {
        Prepared p = prepare(body);
        log.info("Agent import preview source={} detected={} converted={}", AgentImportText.forLog(p.requestedSource),
            p.detected.wire(), p.report().converted().size());
        return new PreviewResponse(p.requestedSource, p.detected.wire(), p.view, p.report());
    }

    /** Creates a draft agent through the normal create path. */
    public ImportResponse importAgent(String body) {
        Prepared p = prepare(body);
        AgentImportDraft d = p.draft;
        AgentCreateRequest request = new AgentCreateRequest(
            d.name,
            d.instructions,
            p.provider,
            p.model,
            null,
            d.temperature,
            d.topP,
            null,
            ExperienceMode.ADVANCED,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
        AgentResponse created = agentService.create(request);
        boolean restricted = restrictTools(created.id(), d);
        d.report.converted("Draft agent", restricted
            ? "Created as a private agent: not shared and not on any channel. Its tools are limited to knowledge-base "
                + "search until you allow more; no capability was switched on and no credential or integration was attached."
            : "Created as a private agent: not shared and not on any channel. No capability was switched on and no "
                + "credential was attached, but its tool access could not be narrowed - review its tool allow-list before use.");
        log.info("Agent import created agent={} source={} needsManual={}", created.id(), p.detected.wire(),
            d.report.hasNeedsManual());
        return new ImportResponse(created.id(), d.report.toReport());
    }

    /** Narrows the new agent's tool allow-list to the read-only set; false (never throws) if that failed. */
    private boolean restrictTools(UUID agentId, AgentImportDraft d) {
        try {
            agentService.setAllowedToolNames(agentId, SAFE_TOOL_NAMES);
            return true;
        } catch (RuntimeException e) {
            log.warn("Agent import could not restrict tools on agent {}", agentId, e);
            d.report.warn("The agent's tool access could not be restricted automatically. Open its tool settings and "
                + "choose the tools it may use before sharing it.");
            return false;
        }
    }

    // ---- shared preparation ------------------------------------------------------------------------

    private Prepared prepare(String body) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent import is not enabled.");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("A JSON body with \"source\" and \"payload\" is required.");
        }
        if (utf8Length(body) > maxPayloadBytes + ENVELOPE_SLACK_BYTES) {
            throw tooLarge();
        }
        JsonNode envelope = parse(body, "The request body");
        if (envelope == null || !envelope.isObject()) {
            throw new IllegalArgumentException("The request body must be a JSON object.");
        }
        JsonNode sourceNode = envelope.get("source");
        if (sourceNode != null && !sourceNode.isNull() && !sourceNode.isTextual()) {
            throw new IllegalArgumentException("\"source\" must be a string.");
        }
        String requestedSource = sourceNode == null || sourceNode.isNull() || sourceNode.asText().isBlank()
            ? AgentImportSource.AUTO : sourceNode.asText();
        JsonNode payload = resolvePayload(envelope.get("payload"));

        AgentImportSource detectedShape = AgentImportDetector.detect(payload).orElse(null);
        AgentImportSource source;
        if (AgentImportSource.isAuto(requestedSource)) {
            source = detectedShape;
            if (source == null) {
                throw new IllegalArgumentException("Couldn't tell which platform this export came from. Choose a source: "
                    + "openai-assistant, openai-agents or flowise.");
            }
        } else {
            source = AgentImportSource.parse(requestedSource).orElseThrow(() -> new IllegalArgumentException(
                "Unknown source. Use one of: openai-assistant, openai-agents, flowise, auto."));
            if (detectedShape != null && detectedShape != source
                && (detectedShape == AgentImportSource.FLOWISE || source == AgentImportSource.FLOWISE)) {
                throw new IllegalArgumentException("The payload looks like a " + detectedShape.wire()
                    + " export but source was \"" + source.wire() + "\". Use source \"auto\" or \""
                    + detectedShape.wire() + "\".");
            }
        }

        AgentImportDraft draft = switch (source) {
            case OPENAI_ASSISTANT -> OpenAiAssistantImportMapper.map(payload);
            case OPENAI_AGENTS -> OpenAiAgentsImportMapper.map(payload);
            case FLOWISE -> mapFlowise(payload);
        };

        JsonNode overrides = envelope.get("overrides");
        if (overrides != null && overrides.isObject()) {
            String overrideName = AgentImportJson.text(overrides, "name");
            if (overrideName != null) {
                draft.name = overrideName;
            }
        }
        AgentImportFinalizer.finish(draft);

        ProviderName provider = ProviderName.PLATFORM_HOSTED;
        String model = null;
        boolean providerMapped = false;
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        // AgentService#create coerces every FREE tenant to PLATFORM_HOSTED / "platform-pool", so the preview and
        // the report must say that rather than the source's provider and model.
        boolean freePlan = tenantId != null && tenantLimits.plan(tenantId) == TenantPlan.FREE;
        Optional<ProviderName> mapped = AgentImportProviders.fromHint(draft.providerHint);
        if (freePlan) {
            if (draft.providerHint != null || draft.modelHint != null) {
                draft.report.warn("The free plan runs agents on the platform's model pool, so the source's provider and "
                    + "model were not applied.");
            }
        } else if (mapped.isPresent()) {
            providerMapped = true;
            provider = mapped.get();
            model = matchModel(provider, draft);
            if (tenantId != null && !tenantLimits.usesPlatformPool(tenantId)) {
                draft.report.needsManual("Provider key",
                    provider.name() + " runs on your own API key, and none is attached by the import.",
                    "Add your " + provider.name() + " key in provider settings before chatting with this agent.");
            }
        } else if (draft.providerHint != null || draft.modelHint != null) {
            draft.report.needsManual("Model",
                "The source's provider" + (draft.providerHint == null ? "" : " '" + AgentImportText.inline(draft.providerHint, 40) + "'")
                    + " has no matching provider on this platform, so no model was copied.",
                "Pick a provider and model for the agent.");
        } else if (!draft.report.hasNeedsManualFor("Model")) {
            draft.report.needsManual("Model", "The source doesn't say which model it uses.",
                "Pick a provider and model for the agent.");
        }

        ImportedAgentView view = new ImportedAgentView(
            draft.name,
            draft.description == null ? "" : draft.description,
            draft.instructions,
            providerMapped ? provider.name() : "",
            model == null ? "" : model,
            draft.temperature,
            draft.topP,
            List.copyOf(draft.suggestedCapabilities),
            List.copyOf(draft.suggestedTools)
        );
        return new Prepared(AgentImportText.inline(requestedSource, 40), source, draft, provider, model, view);
    }

    /** Matches the source model against the live catalog; null (and a needs-manual entry) when it isn't there. */
    private String matchModel(ProviderName provider, AgentImportDraft d) {
        String hint = d.modelHint;
        if (hint == null) {
            if (!d.report.hasNeedsManualFor("Model")) {
                d.report.needsManual("Model", "The source doesn't name a model.",
                    "Pick a model; the agent starts on the provider's default until you do.");
            }
            return null;
        }
        boolean found;
        try {
            found = catalogService.findModel(provider, hint).isPresent();
        } catch (RuntimeException e) {
            log.warn("Agent import model catalog lookup failed for provider {}", provider, e);
            d.report.warn("The model catalog could not be checked just now, so the model was not copied.");
            found = false;
        }
        if (found) {
            d.report.converted("Model", "'" + AgentImportText.inline(hint, 80) + "' is available on " + provider.name() + ".");
            return hint;
        }
        d.report.needsManual("Model '" + AgentImportText.inline(hint, 80) + "'",
            "It is not in this platform's model list for " + provider.name()
                + ", so it was not copied; the agent starts on the provider's default model.",
            "Pick the model you want in the agent's settings.");
        return null;
    }

    private AgentImportDraft mapFlowise(JsonNode payload) {
        String flowData = FlowiseImportMapper.flowDataText(payload);
        if (flowData != null && !payload.has("nodes")) {
            JsonNode flow = parse(flowData, "The flowData");
            return FlowiseImportMapper.map(flow, AgentImportJson.text(payload, "name"),
                AgentImportJson.text(payload, "description"));
        }
        return FlowiseImportMapper.map(payload, null, null);
    }

    /** The payload as an object: a JSON object as-is, or a string that itself holds JSON. */
    private JsonNode resolvePayload(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            throw new IllegalArgumentException("\"payload\" is required.");
        }
        if (payload.isTextual()) {
            String text = payload.asText();
            if (utf8Length(text) > maxPayloadBytes) {
                throw tooLarge();
            }
            JsonNode parsed;
            try {
                parsed = mapper.readTree(text);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("The payload is text but not JSON. Source code is not imported "
                    + "(OpenAI Agent Builder exports TypeScript/Python code, not JSON); paste a JSON object instead.");
            }
            payload = parsed;
        }
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("\"payload\" must be a JSON object.");
        }
        return payload;
    }

    private JsonNode parse(String text, String what) {
        try {
            return mapper.readTree(text);
        } catch (JsonProcessingException e) {
            // Deliberately not echoing the parser message: it can quote a fragment of the payload.
            throw new IllegalArgumentException(what + " is not valid JSON, or is nested too deeply (limit "
                + MAX_NESTING_DEPTH + " levels).");
        }
    }

    /**
     * Reads a request body without ever buffering more than the cap (plus envelope slack): 413 as soon as the
     * limit is crossed, so an oversized upload cannot fill memory before the size check runs.
     */
    public String readBounded(InputStream in) throws IOException {
        long limit = maxPayloadBytes + ENVELOPE_SLACK_BYTES;
        byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, limit + 1));
        if (bytes.length > limit) {
            throw tooLarge();
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
            "The payload is larger than the import limit of " + maxPayloadBytes + " bytes.");
    }

    private static long utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Result of {@link #prepare}: everything both routes need. */
    private record Prepared(
        String requestedSource,
        AgentImportSource detected,
        AgentImportDraft draft,
        ProviderName provider,
        String model,
        ImportedAgentView view
    ) {
        AgentImportDtos.ConversionReport report() {
            return draft.report.toReport();
        }
    }
}
