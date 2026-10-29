package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.RememberFactToolCallback;
import com.agenticai.core.agents.memory.RememberSharedFactToolCallback;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.codeintel.ApplyCodeChangesToolCallback;
import com.agenticai.core.codeintel.CodeCalleesToolCallback;
import com.agenticai.core.codeintel.CodeCallersToolCallback;
import com.agenticai.core.codeintel.CodeGraphStatsToolCallback;
import com.agenticai.core.codeintel.CodeImpactToolCallback;
import com.agenticai.core.codeintel.CodeIntelClient;
import com.agenticai.core.codeintel.CreateCodeWorkspaceToolCallback;
import com.agenticai.core.codeintel.DeleteCodeWorkspaceToolCallback;
import com.agenticai.core.codeintel.DiffCodeWorkspaceToolCallback;
import com.agenticai.core.codeintel.RunCodeWorkspaceApprovalExecutor;
import com.agenticai.core.codeintel.RunInCodeWorkspaceToolCallback;
import com.agenticai.core.codeintel.SearchCodeToolCallback;
import com.agenticai.core.graphmemory.EntityExtractionService;
import com.agenticai.core.graphmemory.GraphMemoryService;
import com.agenticai.core.graphmemory.GraphRecallToolCallback;
import com.agenticai.core.graphmemory.MemoryWriteArbitrationService;
import com.agenticai.core.graphmemory.RecallSharedGraphFactsToolCallback;
import com.agenticai.core.guardrails.GuardedToolCallback;
import com.agenticai.core.knowledge.AddToKnowledgeBaseToolCallback;
import com.agenticai.core.knowledge.KnowledgeCollectionService;
import com.agenticai.core.knowledge.KnowledgeIngestionService;
import com.agenticai.core.knowledge.KnowledgeSearchService;
import com.agenticai.core.knowledge.KnowledgeSearchToolCallback;
import com.agenticai.core.knowledge.KnowledgeUrlIngester;
import com.agenticai.core.knowledge.ListKnowledgeCollectionsToolCallback;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Tool-resolution group extracted from {@link AgentToolProvider} (extract-only, no behavior
 * change). Not a Spring bean: constructed by AgentToolProvider.
 */
/**
 * The four "knowledge"-profile-gated services {@link AgentKnowledgeToolResolver} resolves tools
 * from, bundled so the resolver's constructor stays under the 12-collaborator architecture cap
 * (StructureRulesTest) now that {@link KnowledgeUrlIngester} joined them. Every member is an
 * ObjectProvider for the same reason they always were: {@code @Profile("knowledge")}-scoped, absent
 * when that profile is off.
 */
record KnowledgeServiceProviders(
        ObjectProvider<KnowledgeSearchService> search,
        ObjectProvider<KnowledgeCollectionService> collections,
        ObjectProvider<KnowledgeIngestionService> ingestion,
        // Backs add_to_knowledge_base's `url` argument (server-side document download into a
        // collection). Null provider value -> the tool still resolves but explains that url
        // ingestion is unavailable, matching every other optional surface here.
        ObjectProvider<KnowledgeUrlIngester> urlIngester) {}

final class AgentKnowledgeToolResolver {

    private final AgentToolGuard toolGuard;

    // New feature - backs AddToKnowledgeBaseToolCallback (the write/insert companion to
    // search_knowledge_base) plus the two search/list tools; see the record's javadoc.
    private final KnowledgeServiceProviders knowledgeProviders;
    private final ObjectProvider<AgentMemoryService> agentMemoryService;
    // Shared cross-agent memory tier (#847).
    private final ObjectProvider<TenantMemoryService> tenantMemoryService;
    private final AgentService agentService;
    private final HumanApprovalGateService approvalGateService;
    private final ObjectProvider<GraphMemoryService> graphMemoryService;
    private final EntityExtractionService entityExtractionService;
    // Master Roadmap 2026 item #11 (Memory write arbitration) - always present (not
    // ObjectProvider-gated like graphMemoryService above), since MemoryWriteArbitrationService
    // itself has no @Profile("graph-memory") requirement and no-ops safely whenever the
    // graph-memory profile is off - see that class's javadoc.
    private final MemoryWriteArbitrationService memoryWriteArbitrationService;
    private final ObjectProvider<CodeIntelClient> codeIntelClient;

    // Set by AgentToolProvider (this class is not a bean); null = no purpose policy.
    private volatile com.agenticai.core.agents.consent.PurposeAccessPolicy purposePolicy;

    void setPurposePolicy(
            ObjectProvider<com.agenticai.core.agents.consent.PurposeAccessPolicy> provider) {
        this.purposePolicy = provider == null ? null : provider.getIfAvailable();
    }

    private Set<UUID> purposeFiltered(UUID agentId, Set<UUID> collectionIds) {
        com.agenticai.core.agents.consent.PurposeAccessPolicy policy = purposePolicy;
        return policy == null ? collectionIds : policy.permittedCollections(agentId, collectionIds);
    }

    AgentKnowledgeToolResolver(
            AgentToolGuard toolGuard,
            KnowledgeServiceProviders knowledgeProviders,
            ObjectProvider<AgentMemoryService> agentMemoryService,
            ObjectProvider<TenantMemoryService> tenantMemoryService,
            AgentService agentService,
            HumanApprovalGateService approvalGateService,
            ObjectProvider<GraphMemoryService> graphMemoryService,
            EntityExtractionService entityExtractionService,
            MemoryWriteArbitrationService memoryWriteArbitrationService,
            ObjectProvider<CodeIntelClient> codeIntelClient) {
        this.toolGuard = toolGuard;
        this.knowledgeProviders = knowledgeProviders;
        this.agentMemoryService = agentMemoryService;
        this.tenantMemoryService = tenantMemoryService;
        this.agentService = agentService;
        this.approvalGateService = approvalGateService;
        this.graphMemoryService = graphMemoryService;
        this.entityExtractionService = entityExtractionService;
        this.memoryWriteArbitrationService = memoryWriteArbitrationService;
        this.codeIntelClient = codeIntelClient;
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

    /**
     * Knowledge Stage F2 - now resolves two tools together ({@code list_knowledge_collections} +
     * {@code search_knowledge_base}), both scoped to the same {@code allowedCollectionIds} snapshot
     * so a model can never list a collection it isn't then allowed to search (or vice versa). Empty
     * unless the "knowledge" profile is active (same gate as before) - {@code
     * knowledgeCollectionService} is {@code @Profile("knowledge")}-scoped exactly like {@code
     * knowledgeSearchService}, so both ObjectProviders resolve together or not at all.
     *
     * <p>New feature (migration V198): also resolves {@code add_to_knowledge_base} (see {@link
     * AddToKnowledgeBaseToolCallback}'s javadoc for the full scoping/poisoning-risk design), but
     * only when this specific agent has {@code knowledgeWriteEnabled} - a SEPARATE opt-in from
     * {@code knowledgeBaseEnabled} above, same "read tool always available on the base flag, write
     * tool needs its own explicit opt-in" shape {@link #resolveCodeWorkspaceTools} already uses for
     * {@code search_code} vs {@code apply_code_changes}. Scoped to {@code
     * Agent.allowedKnowledgeWriteCollectionIds}, which (unlike {@code allowedCollectionIds} above)
     * is deny-all when empty - a tenant-admin must explicitly grant at least one collection before
     * this agent can write anything, even one it can already search.
     */
    List<ToolCallback> resolveKnowledgeTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        KnowledgeSearchService searchService = knowledgeProviders.search().getIfAvailable();
        KnowledgeCollectionService collectionService =
                knowledgeProviders.collections().getIfAvailable();
        if (searchService == null
                || collectionService == null
                || !agentService.isKnowledgeBaseEnabled(agentId)) {
            return List.of();
        }
        Set<UUID> allowedCollectionIds =
                purposeFiltered(agentId, agentService.getAllowedKnowledgeCollectionIds(agentId));
        ToolCallback listTool =
                new ListKnowledgeCollectionsToolCallback(collectionService, allowedCollectionIds);
        // How many chunks a search returns and how relevant they must be: the agent's runtime
        // settings
        // (tenant defaults under its own overrides); the tool's historic 3 / no cut-off without a
        // resolver.
        com.agenticai.core.agents.runtime.AgentRuntimeSettings runtime =
                toolGuard.runtimeSettings(agentId);
        ToolCallback searchTool =
                runtime == null
                        ? new KnowledgeSearchToolCallback(searchService, allowedCollectionIds)
                        : new KnowledgeSearchToolCallback(
                                searchService,
                                allowedCollectionIds,
                                runtime.retrievalTopK(),
                                runtime.retrievalMinScore());
        List<ToolCallback> tools = new ArrayList<>();
        tools.add(
                guardAndLog(
                        listTool,
                        agentId,
                        sessionId,
                        stepCounter,
                        userMessage,
                        chatClient,
                        rigorous));
        tools.add(
                guardAndLog(
                        searchTool,
                        agentId,
                        sessionId,
                        stepCounter,
                        userMessage,
                        chatClient,
                        rigorous));

        KnowledgeIngestionService ingestionService =
                knowledgeProviders.ingestion().getIfAvailable();
        if (ingestionService != null && agentService.isKnowledgeWriteEnabled(agentId)) {
            Set<UUID> allowedWriteCollectionIds =
                    purposeFiltered(
                            agentId, agentService.getAllowedKnowledgeWriteCollectionIds(agentId));
            ToolCallback addTool =
                    new AddToKnowledgeBaseToolCallback(
                            ingestionService,
                            collectionService,
                            allowedWriteCollectionIds,
                            agentId,
                            sessionId,
                            knowledgeProviders.urlIngester().getIfAvailable());
            tools.add(
                    guardAndLog(
                            addTool,
                            agentId,
                            sessionId,
                            stepCounter,
                            userMessage,
                            chatClient,
                            rigorous));
        }
        return tools;
    }

    /**
     * Code Intelligence tools (search_code, code_callers, code_callees, code_impact,
     * code_graph_stats) - the core side of the standalone code-intel microservice. Empty unless an
     * operator has set {@code code-intel.enabled=true} (which is what makes a {@link
     * CodeIntelClient} bean exist); otherwise this ObjectProvider is empty and no tools are
     * offered, exactly like every other optional capability wired here. All five are READ-ONLY
     * (semantic code search + the deterministic code graph); the write/execute surface stays behind
     * the service's own guardrail/approval layer AND this platform's real approval gate (see {@link
     * #resolveCodeWorkspaceTools}).
     *
     * <p>Bug fix (2026-08-16, direct report - an agent tasked with researching one repo pulled a
     * hit from a completely unrelated repo it was never granted, via {@code search_code}'s "omit
     * repo_id to search everything" fan-out): {@code
     * agentService.getAllowedCodeIntelRepoIds(agentId)} is now threaded into all five callbacks,
     * same shared per-agent grant {@link #resolveCodeWorkspaceTools} already uses - one
     * admin-configured repo allow-list governs both what an agent can READ and (if also
     * workspace-enabled) WRITE. Unlike that method's grant, EMPTY here means UNRESTRICTED, not
     * deny-all - see {@code SearchCodeToolCallback}'s constructor javadoc for why this tier can't
     * default to deny-all without silently breaking every existing agent's search capability
     * (nothing has ever populated this grant for a read-only use before today). A tenant-admin who
     * wants a specific agent locked to specific repos can now configure it via the same {@code
     * code-intel-repo-access} endpoint {@link #resolveCodeWorkspaceTools} already relies on.
     */
    List<ToolCallback> resolveCodeIntelTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        CodeIntelClient client = codeIntelClient.getIfAvailable();
        if (client == null) {
            return List.of();
        }
        Set<String> allowedRepoIds = agentService.getAllowedCodeIntelRepoIds(agentId);
        List<ToolCallback> tools =
                new java.util.ArrayList<>(
                        List.of(
                                guardAndLog(
                                        new SearchCodeToolCallback(client, allowedRepoIds),
                                        agentId,
                                        sessionId,
                                        stepCounter,
                                        userMessage,
                                        chatClient,
                                        rigorous),
                                guardAndLog(
                                        new CodeCallersToolCallback(client, allowedRepoIds),
                                        agentId,
                                        sessionId,
                                        stepCounter,
                                        userMessage,
                                        chatClient,
                                        rigorous),
                                guardAndLog(
                                        new CodeCalleesToolCallback(client, allowedRepoIds),
                                        agentId,
                                        sessionId,
                                        stepCounter,
                                        userMessage,
                                        chatClient,
                                        rigorous),
                                guardAndLog(
                                        new CodeImpactToolCallback(client, allowedRepoIds),
                                        agentId,
                                        sessionId,
                                        stepCounter,
                                        userMessage,
                                        chatClient,
                                        rigorous),
                                guardAndLog(
                                        new CodeGraphStatsToolCallback(client, allowedRepoIds),
                                        agentId,
                                        sessionId,
                                        stepCounter,
                                        userMessage,
                                        chatClient,
                                        rigorous)));
        // The twelve read-only analysis tools (code_map, find_symbols,
        // symbol_detail, code_outline, code_references, code_implementations,
        // code_path, code_dead_code, code_tests, code_api_surface,
        // code_hotspots, code_import_cycles) - same empty=unrestricted repo
        // scope, same guardAndLog wrapping as the original five above.
        for (ToolCallback analysisTool :
                com.agenticai.core.codeintel.CodeIntelAnalysisTools.all(client, allowedRepoIds)) {
            tools.add(
                    guardAndLog(
                            analysisTool,
                            agentId,
                            sessionId,
                            stepCounter,
                            userMessage,
                            chatClient,
                            rigorous));
        }
        return tools;
    }

    /**
     * Code workspace write/execute tools (create_code_workspace, apply_code_changes,
     * run_in_code_workspace, diff_code_workspace, delete_code_workspace) - the write/execute
     * companion to {@link #resolveCodeIntelTools}'s read-only five. Fully built and safe to call in
     * isolation: {@code apply_code_changes}/{@code run_in_code_workspace} always route through
     * {@code HumanApprovalGateService#evaluate} (see {@link ApplyCodeChangesToolCallback}/ {@link
     * RunInCodeWorkspaceToolCallback}'s javadoc) with a registered {@code ApprovalActionExecutor}
     * that actually performs the call once a tenant-admin approves - unlike this class's OWN
     * generic {@code "mcp_tool." + toolName} {@link GuardedToolCallback} gate (see that class's
     * javadoc), which today only records/audits a gated tool call and never resumes it, and which a
     * {@code FULLY_AUTONOMOUS} agent's {@code ApprovalGateMode.NEVER_GATE} can skip entirely.
     * Because these two tools do their OWN unconditional {@code evaluate()} call inside {@code
     * call()} itself, that outer per-agent-tier bypass can never skip the human-approval
     * requirement for an actual run/apply - only the harmless read-only/cleanup tools here
     * (create/diff/delete) are ever reachable without it.
     *
     * <p><b>NOW WIRED</b> (migration V103) - this used to be built but deliberately never called;
     * both gaps that javadoc called out are now closed:
     *
     * <ol>
     *   <li>Per-agent opt-in gate: empty unless {@code
     *       agentService.isCodeWorkspaceEnabled(agentId)}, mirroring {@link
     *       #resolveKnowledgeTools}'s {@code isKnowledgeBaseEnabled} check. Off by default for
     *       every pre-existing agent.
     *   <li>Per-agent {@code repo_id} scope: {@code
     *       agentService.getAllowedCodeIntelRepoIds(agentId)} is threaded into every one of the
     *       five tool callbacks below (their constructor overload, not the legacy single-arg one),
     *       which each re-validate their own {@code repo_id} argument against it BEFORE ever
     *       reaching {@link CodeIntelClient} or (for apply/run) even filing an approval request -
     *       see those constructors' javadoc. Unlike {@link #resolveKnowledgeTools}'s {@code
     *       allowedCollectionIds} (empty = unrestricted), an empty repo scope here means DENY-ALL:
     *       enabling the flag with no repo granted yet offers the tools but every call fails
     *       cleanly, rather than silently defaulting to "every repo the tenant has registered with
     *       code-intel" - see migration V103's header comment for why write/execute tools get the
     *       opposite default from read-only knowledge search.
     * </ol>
     *
     * Product/security sign-off on the two-gate design itself (this platform's approval +
     * code-intel's own independent policy - see {@link RunCodeWorkspaceApprovalExecutor}'s javadoc
     * for the residual gap around code-intel's own separate, disconnected {@code /approvals} flow)
     * is still a real open item, but is orthogonal to - and not blocked by - the two gates this
     * javadoc used to call out; both a tenant-admin opt-in AND an explicit repo grant are now
     * required before any agent can reach this surface at all.
     */
    List<ToolCallback> resolveCodeWorkspaceTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        CodeIntelClient client = codeIntelClient.getIfAvailable();
        if (client == null || !agentService.isCodeWorkspaceEnabled(agentId)) {
            return List.of();
        }
        Set<String> allowedRepoIds = agentService.getAllowedCodeIntelRepoIds(agentId);
        return List.of(
                guardAndLog(
                        new CreateCodeWorkspaceToolCallback(client, allowedRepoIds),
                        agentId,
                        sessionId,
                        stepCounter,
                        userMessage,
                        chatClient,
                        rigorous),
                guardAndLog(
                        new ApplyCodeChangesToolCallback(approvalGateService, allowedRepoIds),
                        agentId,
                        sessionId,
                        stepCounter,
                        userMessage,
                        chatClient,
                        rigorous),
                guardAndLog(
                        new RunInCodeWorkspaceToolCallback(approvalGateService, allowedRepoIds),
                        agentId,
                        sessionId,
                        stepCounter,
                        userMessage,
                        chatClient,
                        rigorous),
                guardAndLog(
                        new DiffCodeWorkspaceToolCallback(client, allowedRepoIds),
                        agentId,
                        sessionId,
                        stepCounter,
                        userMessage,
                        chatClient,
                        rigorous),
                guardAndLog(
                        new DeleteCodeWorkspaceToolCallback(client, allowedRepoIds),
                        agentId,
                        sessionId,
                        stepCounter,
                        userMessage,
                        chatClient,
                        rigorous));
    }

    /**
     * "Smarter, more dynamic agents" roadmap - long-term memory across sessions. Empty unless the
     * "knowledge" profile is active (same gate as {@link #resolveKnowledgeTools}, since {@code
     * AgentMemoryService} reuses the same sidecar/pgvector infrastructure) - always offered when
     * that profile is on, unlike the knowledge-base tools, since remembering a fact about the user
     * is a reasonable default capability for every agent, not an opt-in per-agent flag.
     *
     * <p>Also resolves {@code recall_related_facts} (graph-based multi-hop recall, see {@link
     * GraphRecallToolCallback}) and threads {@code GraphMemoryService}/ {@code
     * EntityExtractionService}/{@code chatClient} into {@code RememberFactToolCallback} so every
     * remembered fact is also offered to the graph - both entirely optional (see {@link
     * #resolveGraphMemoryTools}), degrading to today's vector-only behavior when the "graph-memory"
     * profile is off.
     */
    List<ToolCallback> resolveMemoryTools(
            UUID agentId,
            String sessionId,
            String subjectKey,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        AgentMemoryService memoryService = agentMemoryService.getIfAvailable();
        if (memoryService == null) {
            return List.of();
        }
        GraphMemoryService graphService = graphMemoryService.getIfAvailable();
        ToolCallback rememberTool =
                new RememberFactToolCallback(
                        memoryService,
                        agentId,
                        subjectKey,
                        graphService,
                        entityExtractionService,
                        memoryWriteArbitrationService,
                        chatClient);
        List<ToolCallback> tools = new ArrayList<>();
        tools.add(
                guardAndLog(
                        rememberTool,
                        agentId,
                        sessionId,
                        stepCounter,
                        userMessage,
                        chatClient,
                        rigorous));
        tools.addAll(
                resolveGraphMemoryTools(
                        agentId,
                        sessionId,
                        subjectKey,
                        stepCounter,
                        userMessage,
                        chatClient,
                        rigorous,
                        graphService));

        // Shared cross-agent memory tier (#847) - only offered when this specific agent has
        // opted in via shareMemoryWithTenant (default false, same shape as knowledgeBaseEnabled/
        // sarvamEnabled). Deliberately a second, separate tool rather than a "scope" parameter on
        // remember_fact - see RememberSharedFactToolCallback's javadoc for why.
        // Phase 2 MAG: also threads graphService/entityExtractionService/chatClient into
        // RememberSharedFactToolCallback so a shared fact is written to both the shared vector
        // store AND the shared graph tier. If graphService is null (graph-memory profile off),
        // RememberSharedFactToolCallback degrades to its existing vector-only behavior unchanged.
        TenantMemoryService tenantService = tenantMemoryService.getIfAvailable();
        if (tenantService != null && agentService.isShareMemoryWithTenant(agentId)) {
            ToolCallback rememberSharedTool =
                    new RememberSharedFactToolCallback(
                            tenantService,
                            agentId,
                            subjectKey,
                            graphService,
                            entityExtractionService,
                            chatClient);
            tools.add(
                    guardAndLog(
                            rememberSharedTool,
                            agentId,
                            sessionId,
                            stepCounter,
                            userMessage,
                            chatClient,
                            rigorous));
            if (graphService != null) {
                ToolCallback recallSharedGraphTool =
                        new RecallSharedGraphFactsToolCallback(graphService, subjectKey);
                tools.add(
                        guardAndLog(
                                recallSharedGraphTool,
                                agentId,
                                sessionId,
                                stepCounter,
                                userMessage,
                                chatClient,
                                rigorous));
            }
        }
        return tools;
    }

    /**
     * {@code recall_related_facts} - empty unless the "graph-memory" profile is active (same
     * "optional ObjectProvider, absent = no tool" gate every other optional capability in this
     * class uses). Takes the already-resolved {@code graphService} rather than re-resolving it,
     * since {@link #resolveMemoryTools} needs the same instance for {@code
     * RememberFactToolCallback}.
     */
    List<ToolCallback> resolveGraphMemoryTools(
            UUID agentId,
            String sessionId,
            String subjectKey,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous,
            GraphMemoryService graphService) {
        if (graphService == null) {
            return List.of();
        }
        ToolCallback recallTool = new GraphRecallToolCallback(graphService, agentId, subjectKey);
        return List.of(
                guardAndLog(
                        recallTool,
                        agentId,
                        sessionId,
                        stepCounter,
                        userMessage,
                        chatClient,
                        rigorous));
    }
}
