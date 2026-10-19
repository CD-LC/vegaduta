package com.agenticai.core.agents.core;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;

/**
 * The per-agent boolean capability opt-ins (knowledge base and write, Sarvam, E2B code execution,
 * xAI server tools, code workspace, tenant-shared memory, biometric consent). Embedded in {@link
 * Agent}; the columns keep their names. Every flag defaults to off; turning one on is an explicit
 * per-agent opt-in.
 */
@Embeddable
public class CapabilityFlags {

    // Stage 6: when true, AgentToolProvider gives this agent a
    // search_knowledge_base tool backed by KnowledgeSearchService. Off by
    // default - an agent with no uploaded knowledge shouldn't advertise a
    // tool that will only ever return empty results.
    @Column(name = "knowledge_base_enabled", nullable = false)
    private boolean knowledgeBaseEnabled;

    // Direct feedback: "like every other llm as anthropic, gemini - user
    // should be able to attach sarvam to agents...keep it as like other llm
    // providers" - Sarvam's translate/transcribe/speak tools used to attach
    // to every agent automatically the moment the TENANT activated a Sarvam
    // key (see AgentToolProvider#resolveSarvamTools's prior javadoc), which
    // read as Sarvam silently reaching into agents that never asked for it.
    // This flips that to an explicit per-agent opt-in, same
    // "off by default, no behavior change until someone turns it on" shape
    // knowledgeBaseEnabled already established - migration V63 backfills
    // every pre-existing agent to false.
    @Column(name = "sarvam_enabled", nullable = false)
    private boolean sarvamEnabled;

    // Master Roadmap 2026 item #14 fast-follow (#1071) - same "off by
    // default, explicit per-agent opt-in" shape as sarvamEnabled above.
    // Attaches the run_code tool (E2bRunCodeToolCallback), which still
    // separately requires the tenant to have an ACTIVE E2bCredential AND a
    // configured code-execution template - see
    // AgentToolProvider#resolveE2bTools. Migration V96 backfills every
    // pre-existing agent to false.
    @Column(name = "e2b_enabled", nullable = false)
    private boolean e2bEnabled;

    // xAI Agent Tools API (2026-09-23, migration V239) - same "off by default,
    // explicit per-agent opt-in" shape as e2bEnabled above, and for a similar
    // reason: the server-side tools it enables (web_search, x_search,
    // code_execution) run on xAI's own infrastructure and are billed by xAI per
    // invocation, outside this platform's guardrail/approval wrapping - unlike
    // the platform's own web_search/run_code ToolCallbacks, which execute here.
    // Only meaningful for agents whose providerName is GROK; ignored by every
    // other provider path (see GrokAgentChatClientFactory's providerOptions
    // overload and AgentChatService#buildChatClientFor).
    @Column(name = "xai_server_tools_enabled", nullable = false)
    private boolean xaiServerToolsEnabled;

    // Wires AgentToolProvider#resolveCodeWorkspaceTools (create/apply/run/
    // diff/delete_code_workspace - the write/execute companion to the
    // read-only code-intel tools) - same "off by default, explicit
    // per-agent opt-in" shape as e2bEnabled/sarvamEnabled above. Unlike
    // those, this flag ALONE is not enough to let this agent touch any
    // repo - see allowedCodeIntelRepoIds below, which (unlike
    // allowedKnowledgeCollectionIds) is deny-all when empty rather than
    // unrestricted, since these tools can write/execute rather than just
    // read. Migration V103 backfills every pre-existing agent to false.
    @Column(name = "code_workspace_enabled", nullable = false)
    private boolean codeWorkspaceEnabled;

    // New feature (migration V198) - wires AgentToolProvider's
    // add_to_knowledge_base tool (AddToKnowledgeBaseToolCallback, backed by
    // KnowledgeIngestionService#ingest): lets this agent WRITE content into
    // a knowledge collection, not just search it. Same "off by default,
    // explicit per-agent opt-in" shape as codeWorkspaceEnabled above, and
    // for the same reason: this flag ALONE is not enough to let this agent
    // write into any collection - see allowedKnowledgeWriteCollectionIds
    // below, which (like allowedCodeIntelRepoIds, and unlike
    // allowedKnowledgeCollectionIds) is deny-all when empty. A write tool is
    // a persistent-poisoning vector search never was: content it writes is
    // retrieved by every future search turn against that collection, from
    // potentially every user/agent sharing it, so this stays a deliberate,
    // narrow opt-in rather than inheriting knowledgeBaseEnabled's default.
    // Migration V198 backfills every pre-existing agent to false.
    @Column(name = "knowledge_write_enabled", nullable = false)
    private boolean knowledgeWriteEnabled;

    // Shared cross-agent memory tier (#847) - same "off by default, explicit
    // per-agent opt-in" shape as knowledgeBaseEnabled/sarvamEnabled above.
    // When true, this agent both writes to and automatically recalls from
    // TenantMemoryService's tenant-wide pool (in addition to, never instead
    // of, its own private AgentMemoryService bank) - see
    // AgentToolProvider#resolveMemoryTools and
    // AgentChatService#recallMemoryFactsBlock. Migration V78 backfills every
    // pre-existing agent to false.
    @Column(name = "share_memory_with_tenant", nullable = false)
    private boolean shareMemoryWithTenant;

    // Face recognition (2026-08-06) - same "off by default, explicit
    // per-agent opt-in" shape as knowledgeBaseEnabled/shareMemoryWithTenant
    // above, but for a stronger reason than cost/scope: this gates BOTH
    // whether knowledge-base photo uploads offer face-enrollment AND
    // whether camera turns attempt on-device face matching, because the
    // underlying data (a face embedding) is biometric/special-category data
    // under GDPR Art.9 and a sensitive-personal-data trigger under India's
    // DPDP Act - it must never be processed without an explicit, informed
    // opt-in, unlike the other opt-in flags above which are about cost or
    // scope, not legal category. Migration V187 backfills every
    // pre-existing agent to false. See FaceEnrollmentService/face.ts for
    // the 100% client-side pipeline this gates.
    @Column(name = "biometric_consent_enabled", nullable = false)
    private boolean biometricConsentEnabled;

    /** JPA, and the all-off starting state. */
    protected CapabilityFlags() {}

    public static CapabilityFlags none() {
        return new CapabilityFlags();
    }

    private CapabilityFlags copy() {
        CapabilityFlags c = new CapabilityFlags();
        c.knowledgeBaseEnabled = knowledgeBaseEnabled;
        c.sarvamEnabled = sarvamEnabled;
        c.e2bEnabled = e2bEnabled;
        c.xaiServerToolsEnabled = xaiServerToolsEnabled;
        c.codeWorkspaceEnabled = codeWorkspaceEnabled;
        c.knowledgeWriteEnabled = knowledgeWriteEnabled;
        c.shareMemoryWithTenant = shareMemoryWithTenant;
        c.biometricConsentEnabled = biometricConsentEnabled;
        return c;
    }

    public CapabilityFlags withKnowledgeBase(boolean value) {
        CapabilityFlags c = copy();
        c.knowledgeBaseEnabled = value;
        return c;
    }

    public CapabilityFlags withSarvam(boolean value) {
        CapabilityFlags c = copy();
        c.sarvamEnabled = value;
        return c;
    }

    public CapabilityFlags withE2b(boolean value) {
        CapabilityFlags c = copy();
        c.e2bEnabled = value;
        return c;
    }

    public CapabilityFlags withXaiServerTools(boolean value) {
        CapabilityFlags c = copy();
        c.xaiServerToolsEnabled = value;
        return c;
    }

    public CapabilityFlags withCodeWorkspace(boolean value) {
        CapabilityFlags c = copy();
        c.codeWorkspaceEnabled = value;
        return c;
    }

    public CapabilityFlags withKnowledgeWrite(boolean value) {
        CapabilityFlags c = copy();
        c.knowledgeWriteEnabled = value;
        return c;
    }

    public CapabilityFlags withShareMemoryWithTenant(boolean value) {
        CapabilityFlags c = copy();
        c.shareMemoryWithTenant = value;
        return c;
    }

    public CapabilityFlags withBiometricConsent(boolean value) {
        CapabilityFlags c = copy();
        c.biometricConsentEnabled = value;
        return c;
    }

    public boolean isKnowledgeBaseEnabled() {
        return knowledgeBaseEnabled;
    }

    public boolean isSarvamEnabled() {
        return sarvamEnabled;
    }

    public boolean isE2bEnabled() {
        return e2bEnabled;
    }

    public boolean isXaiServerToolsEnabled() {
        return xaiServerToolsEnabled;
    }

    public boolean isCodeWorkspaceEnabled() {
        return codeWorkspaceEnabled;
    }

    public boolean isKnowledgeWriteEnabled() {
        return knowledgeWriteEnabled;
    }

    public boolean isShareMemoryWithTenant() {
        return shareMemoryWithTenant;
    }

    public boolean isBiometricConsentEnabled() {
        return biometricConsentEnabled;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CapabilityFlags c
                && knowledgeBaseEnabled == c.knowledgeBaseEnabled
                && sarvamEnabled == c.sarvamEnabled
                && e2bEnabled == c.e2bEnabled
                && xaiServerToolsEnabled == c.xaiServerToolsEnabled
                && codeWorkspaceEnabled == c.codeWorkspaceEnabled
                && knowledgeWriteEnabled == c.knowledgeWriteEnabled
                && shareMemoryWithTenant == c.shareMemoryWithTenant
                && biometricConsentEnabled == c.biometricConsentEnabled;
    }

    @Override
    public int hashCode() {
        return Objects.hash(knowledgeBaseEnabled, sarvamEnabled, e2bEnabled, xaiServerToolsEnabled,
                codeWorkspaceEnabled, knowledgeWriteEnabled, shareMemoryWithTenant, biometricConsentEnabled);
    }
}
