package com.agenticai.core.common.agentgen;

import com.agenticai.core.agents.assist.AssistService;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.dto.AgentCreateRequest;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.chat.structured.StructuredOutput;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Prompt-to-agent generation (Tier-3) - the audit gap "AskAiHelper assists; it doesn't go describe
 * -> full agent". {@code POST /api/agents/generate} takes a natural-language description and
 * returns a REAL agent row: one structured LLM call produces the spec, {@code AgentService#create}
 * performs the actual creation so every existing gate (plan caps, FREE-provider coercion, model
 * resolution, version snapshot) applies exactly as if the admin had typed the same fields by hand.
 *
 * <h2>Safety posture</h2>
 *
 * <ul>
 *   <li>Generated specs are CONSERVATIVE: only name/systemPrompt/
 *       description/knowledgeBaseEnabled/shareMemoryWithTenant/ reasoningDepth are applied.
 *       Generation params, provider/model and tool allow-lists are left at {@code AgentService}'s
 *       defaults (unrestricted tools, catalog-picked model) rather than trusting an LLM to name
 *       tools that may not exist or a model the tenant has no key for.
 *   <li>Channel binding never hijacks: a requested channel that already has a default agent is
 *       SKIPPED and reported, not overwritten - silently re-routing a live channel because a
 *       generator suggested it is exactly the class of surprise this codebase's least-surprise rule
 *       exists to prevent.
 *   <li>Everything is reviewable after the fact: the response returns the full generated spec, and
 *       the agent row itself carries the usual created-by/version history trail.
 * </ul>
 */
@Service
public class AgentGenerationService {

    private static final Logger log = LoggerFactory.getLogger(AgentGenerationService.class);

    private static final String SYSTEM_PROMPT =
            """
        You configure new agents on an AI-agent SaaS platform. Given an
        admin's natural-language description of the agent they want,
        produce ONE JSON object (no markdown fences, no commentary) with
        exactly these fields:

        - "name": short agent display name (max 60 chars, no quotes).
        - "description": one-sentence description of what the agent does.
        - "systemPrompt": the agent's full system prompt (2-6 paragraphs).
          Write it as the agent's own operating instructions: role, tone,
          what it should do, what it must refuse, and how it should answer
          when it does not know.
        - "knowledgeBaseEnabled": true if the agent should search the
          tenant's long-term knowledge base (documents, FAQs, uploaded
          content); false for pure conversational/creative agents.
        - "shareMemoryWithTenant": true if remembering facts across users
          of this tenant helps this agent (team assistants); false for
          per-user/personal agents.
        - "reasoningDepth": one of FAST, CAREFUL, RIGOROUS, PLANNED -
          FAST for simple FAQ/routing agents, CAREFUL for most, RIGOROUS
          or PLANNED only for agents doing multi-step analysis or
          high-stakes decisions.
        - "suggestedChannels": array naming which channels to bind this
          agent to, chosen ONLY from: WHATSAPP, SLACK, TELEGRAM, EMAIL,
          TEAMS. Empty array if none fit.

        Output the JSON object and nothing else.
        """;

    private final AssistService assistService;
    private final AgentService agentService;
    private final ChannelSessionService channelSessionService;
    private final ProviderCredentialResolver credentialResolver;
    private final TenantLimits tenantLimits;
    private final ObjectMapper objectMapper;

    public AgentGenerationService(
            AssistService assistService,
            AgentService agentService,
            ChannelSessionService channelSessionService,
            ProviderCredentialResolver credentialResolver,
            TenantLimits tenantLimits,
            ObjectMapper objectMapper) {
        this.assistService = assistService;
        this.agentService = agentService;
        this.channelSessionService = channelSessionService;
        this.credentialResolver = credentialResolver;
        this.tenantLimits = tenantLimits;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> generate(String description) {
        String raw = assistService.complete(SYSTEM_PROMPT, "Agent to build: " + description);
        JsonNode spec = parseSpec(raw);

        String name = textOr(spec, "name", null);
        if (name == null || name.isBlank()) {
            name = truncate(description, 60);
        }
        String systemPrompt = textOr(spec, "systemPrompt", null);
        if (systemPrompt == null || systemPrompt.isBlank()) {
            // A spec that produced no prompt at all is a generation
            // failure worth surfacing, not silently creating a
            // default-prompted agent nobody asked for.
            throw new AgentGenerationException(
                    "The model returned no systemPrompt for this description - try rephrasing it.");
        }
        String agentDescription = textOr(spec, "description", truncate(description, 200));

        // Provider: the tenant's own active BYOK provider when it has one
        // (AgentService coerces to PLATFORM_HOSTED for FREE tenants
        // itself). PLATFORM_HOSTED as the explicit fallback for a paid
        // tenant with no key resolves to the hosted pool the same way
        // create() treats it.
        ProviderName providerName = resolveProviderName();

        AgentResponse agent =
                agentService.create(
                        new AgentCreateRequest(
                                name,
                                systemPrompt,
                                providerName,
                                null,
                                null,
                                null,
                                null,
                                spec.path("knowledgeBaseEnabled").asBoolean(false),
                                null,
                                parseReasoningDepth(spec.path("reasoningDepth").asText(null)),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));

        List<String> applied = new ArrayList<>();
        if (spec.path("knowledgeBaseEnabled").asBoolean(false)) {
            applied.add("knowledgeBaseEnabled");
        }
        if (spec.path("shareMemoryWithTenant").asBoolean(false)) {
            agentService.setShareMemoryWithTenant(agent.id(), true);
            applied.add("shareMemoryWithTenant");
        }
        ReasoningDepth depth = parseReasoningDepth(spec.path("reasoningDepth").asText(null));
        if (depth != null) {
            agentService.setReasoningDepth(agent.id(), depth);
            applied.add("reasoningDepth=" + depth.name());
        }

        // Channel binding: only channels the spec named AND that have no
        // current default agent. Anything already routed stays routed.
        List<String> boundChannels = new ArrayList<>();
        List<Map<String, String>> skippedChannels = new ArrayList<>();
        for (JsonNode channelNode : spec.path("suggestedChannels")) {
            String channelName = channelNode.asText("").toUpperCase(java.util.Locale.ROOT);
            ChannelType channel;
            try {
                channel = ChannelType.valueOf(channelName);
            } catch (IllegalArgumentException e) {
                skippedChannels.add(Map.of("channel", channelName, "reason", "unknown channel"));
                continue;
            }
            if (channel == ChannelType.WHATSAPP) {
                skippedChannels.add(
                        Map.of(
                                "channel",
                                channelName,
                                "reason",
                                "WhatsApp needs a declared task for the agent - connect it on the Channels page"));
                continue;
            }
            var existing = channelSessionService.routingFor(channel);
            if (existing.isPresent() && existing.get().getAgentId() != null) {
                skippedChannels.add(
                        Map.of(
                                "channel",
                                channelName,
                                "reason",
                                "already routed to agent " + existing.get().getAgentId()));
                continue;
            }
            channelSessionService.setDefaultAgent(channel, agent.id());
            boundChannels.add(channelName);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("agentId", agent.id());
        response.put("name", agent.name());
        response.put("systemPrompt", systemPrompt);
        response.put("description", agentDescription);
        response.put("appliedCapabilities", applied);
        response.put("boundChannels", boundChannels);
        response.put("skippedChannels", skippedChannels);
        response.put(
                "note",
                "Review the generated agent in the workbench before exposing it - "
                        + "generated prompts are a starting point, not a vetted configuration.");
        return response;
    }

    private JsonNode parseSpec(String raw) {
        // Markdown fences / prose around the JSON are tolerated by StructuredOutput.
        JsonNode node = StructuredOutput.readTree(objectMapper, raw).orElse(null);
        if (node == null) {
            log.warn("Agent generation spec parse failed; raw={}", raw);
            throw new AgentGenerationException(
                    "The model returned unparseable JSON - try again or rephrase the description.");
        }
        if (!node.isObject()) {
            throw new AgentGenerationException("The model returned a non-object spec.");
        }
        return node;
    }

    private ProviderName resolveProviderName() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        // usesPlatformPool, not plan == FREE: the pool is the default for
        // every tenant, so a non-FREE tenant on it (the common case now)
        // must be labelled PLATFORM_HOSTED like the calls it actually makes.
        if (tenantId == null || tenantLimits.usesPlatformPool(tenantId)) {
            return ProviderName.PLATFORM_HOSTED;
        }
        try {
            return credentialResolver.resolveAnyActiveKey().providerName();
        } catch (RuntimeException e) {
            return ProviderName.PLATFORM_HOSTED;
        }
    }

    private ReasoningDepth parseReasoningDepth(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return ReasoningDepth.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank()
                ? value.asText().trim()
                : fallback;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
