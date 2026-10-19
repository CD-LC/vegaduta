package com.agenticai.core.agents.demo;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.core.TuningMode;
import com.agenticai.core.agents.dto.AgentCreateRequest;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.tenancy.demo.DemoPersona;
import com.agenticai.core.tenancy.demo.DemoWorkspaceSeeder;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Puts the one ready-to-talk agent inside a freshly provisioned demo schema
 * - the {@code core/agents}-side implementation of {@code core/tenancy}'s
 * {@link DemoWorkspaceSeeder} seam (see that interface for why the seam
 * exists: the module graph runs {@code agents -> tenancy}, so tenancy cannot
 * call this directly).
 *
 * <p><b>Goes through {@link AgentService#create}, not {@code new Agent(...)}
 * + a repository save.</b> That is deliberate and load-bearing: {@code
 * create} is where a FREE tenant's provider gets coerced to
 * {@code PLATFORM_HOSTED} and its model to {@code "platform-pool"}, where
 * {@code ExperienceMode.SIMPLE} turns on knowledge-base attachment and
 * unrestricted tool access, and where the per-plan agent cap is enforced.
 * A demo agent built by hand would silently diverge from every one of those
 * on the day one of them changes - and the whole premise of the sandbox is
 * that the visitor is using the real product.
 *
 * <p>{@code TuningMode.SIMPLE} means the generation parameters are inferred
 * from the system prompt rather than hardcoded here, which is also what a
 * real Simple-mode tenant gets.
 */
@Component
public class DemoAgentSeeder implements DemoWorkspaceSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoAgentSeeder.class);

    private final AgentService agentService;
    private final AgentRepository agentRepository;

    private final org.springframework.beans.factory.ObjectProvider<com.agenticai.core.agents.demo.studio.DemoStudioDocumentService> studioDocuments;
    private final org.springframework.beans.factory.ObjectProvider<com.agenticai.core.agents.demo.studio.DemoStudioConnectorService> studioConnectors;

    public DemoAgentSeeder(
        AgentService agentService,
        AgentRepository agentRepository,
        org.springframework.beans.factory.ObjectProvider<com.agenticai.core.agents.demo.studio.DemoStudioDocumentService> studioDocuments,
        org.springframework.beans.factory.ObjectProvider<com.agenticai.core.agents.demo.studio.DemoStudioConnectorService> studioConnectors
    ) {
        this.agentService = agentService;
        this.agentRepository = agentRepository;
        this.studioDocuments = studioDocuments;
        this.studioConnectors = studioConnectors;
    }

    /**
     * /try Agent Studio cleanup (V339 follow-up). Goes through the repository
     * rather than {@link AgentService#delete}, deliberately: that method
     * refuses on a FREE plan ("Deleting agents is a Premium feature"), and
     * the shared demo tenant IS a FREE tenant whenever free mode is off - so
     * the service path would have silently left every sandbox agent behind
     * (and did, for {@link #deleteDemoAgent}, which swallows the refusal).
     * Sandbox agents are throwaway by definition; the plan gate exists to
     * protect paying customers' data, not this.
     */
    @Override
    public void deleteAgentsCreatedBy(String tenantSchema, String createdBySubject) {
        if (createdBySubject == null || createdBySubject.isBlank()) {
            return;
        }
        for (Agent agent : agentRepository.findByCreatedBy(createdBySubject)) {
            try {
                com.agenticai.core.agents.demo.studio.DemoStudioDocumentService docs = studioDocuments.getIfAvailable();
                if (docs != null) {
                    docs.deleteAllFor(agent.getId());
                }
                com.agenticai.core.agents.demo.studio.DemoStudioConnectorService connectors = studioConnectors.getIfAvailable();
                if (connectors != null) {
                    connectors.deleteAllFor(agent.getId());
                }
                agentRepository.delete(agent);
                agentRepository.flush();
            } catch (RuntimeException e) {
                log.warn("Best-effort cleanup of demo agent {} ({}) in schema {} failed - leaving it behind",
                    agent.getId(), createdBySubject, tenantSchema, e);
            }
        }
    }

    @Override
    public SeededAgent seedDemoAgent(String tenantSchema, String businessName, DemoPersona persona) {
        // Contract (see DemoWorkspaceSeeder): TenantContext is ALREADY bound
        // to tenantSchema by the caller, so this repository write lands in
        // the demo tenant's own schema. Nothing here switches or clears it.
        // A typed businessName always wins over the persona's generic label/
        // name - "Priya's Bakery assistant" is more personal than "Restaurant
        // assistant" even when both were given.
        String label = businessName != null ? businessName
            : persona != null ? persona.businessLabel()
            : "your business";
        String name = businessName != null ? businessName + " assistant"
            : persona != null ? persona.defaultAgentName()
            : "Customer assistant";

        AgentResponse created = agentService.create(new AgentCreateRequest(
            name,
            systemPrompt(label, persona),
            // Coerced to PLATFORM_HOSTED by AgentService#create anyway (a demo
            // tenant is FREE) - passed explicitly so this request is honest
            // about what it is asking for rather than relying on the coercion.
            ProviderName.PLATFORM_HOSTED,
            null,  // model - resolved by AgentService (FREE -> "platform-pool")
            null,  // maxTokens  \
            null,  // temperature > inferred, see TuningMode.SIMPLE below
            null,  // topP       /
            null,  // knowledgeBaseEnabled - forced on by ExperienceMode.SIMPLE
            ExperienceMode.SIMPLE,
            ReasoningDepth.FAST,
            TuningMode.SIMPLE,
            null,  // edgeRoutingPolicy - no server policy, device prefs decide
            null,  // voiceModelProvider - use default fallback chain
            null,  // voiceModel
            null,  // videoModelProvider - use default fallback chain
            null,  // videoModel
            null,  // ttsProvider - use default fallback chain
            null   // ttsModel
        ));
        return new SeededAgent(created.id(), created.name());
    }

    /**
     * Best-effort cleanup - see {@link DemoWorkspaceSeeder#deleteDemoAgent}.
     * Swallows every failure, including "no such agent" (a repeat reset, or
     * a session whose seeding never actually landed): the caller only ever
     * wants "gone or already gone", never an exception.
     */
    @Override
    public void deleteDemoAgent(String tenantSchema, UUID agentId) {
        if (agentId == null) {
            return;
        }
        try {
            agentService.delete(agentId);
        } catch (RuntimeException e) {
            log.warn("Best-effort demo agent cleanup failed for {} in schema {} - leaving it behind", agentId, tenantSchema, e);
        }
    }

    /**
     * The sandbox agent's persona. Written to be immediately impressive on a
     * first message from a small-business owner, and - deliberately - to make
     * no claim the demo cannot back up.
     *
     * <p>Specifically, it is told to say plainly that WhatsApp/voice/payment
     * connections are set up after registering. The demo CAN show the
     * conversation; it cannot connect a real WhatsApp number to an anonymous
     * visitor (see the feasibility notes in this feature's report), and an
     * agent that implies otherwise would be the platform lying to a
     * prospect in its own shop window.
     *
     * <p>When the visitor picked a one-click persona preset ({@link
     * DemoPersona}, from {@code core/tenancy} where the session owns that
     * choice), its business-type paragraph is spliced in between the shared
     * sections. The SKELETON - language behaviour, sandbox honesty, the
     * no-invented-facts rule - deliberately stays here, written once, so every
     * persona (and the no-persona default) gets exactly the same guardrails.
     */
    private String systemPrompt(String businessLabel, DemoPersona persona) {
        // Spliced in as a formatted-string ARGUMENT, not into the template
        // text, so nothing in a persona block is ever interpreted as a format
        // specifier.
        String personaSection = persona == null ? "" : "\n" + persona.promptBlock().stripTrailing() + "\n";
        return """
            You are a friendly, highly capable AI assistant for %s - a small or \
            medium business in India. You are being tried out by the owner inside \
            a no-signup sandbox on the VegaDuta platform.

            How to behave:
            - Answer in the language the person writes in. Indian languages, \
            English, or a mix (Hinglish etc.) are all fine and normal.
            - Be concrete and short. Prefer a worked example over a description \
            of what you could do. If they say "draft a reply to a customer asking \
            about delivery", write the actual reply.
            - You are good at: customer replies, order and enquiry handling, \
            product descriptions, price lists, polite follow-ups, summarising \
            messages, and translating between Indian languages.
            - Sound like a capable colleague, not a chatbot. No filler, no \
            "As an AI language model".
            %s
            What you must be straight about:
            - This is a sandbox. If asked to actually connect WhatsApp, place a \
            phone call, take a UPI payment, or send anything to a real customer, \
            say plainly that those are set up on a real account after registering, \
            and offer to draft or design the thing instead so it is ready to use.
            - Never invent facts about %s - its prices, stock, staff or policies. \
            Ask for the detail, or clearly mark an example as an example.
            """.formatted(businessLabel, personaSection, businessLabel);
    }
}
