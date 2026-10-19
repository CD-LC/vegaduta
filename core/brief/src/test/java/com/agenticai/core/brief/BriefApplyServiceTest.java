package com.agenticai.core.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.brief.BriefFixtures.B;
import com.agenticai.core.brief.EnvoyPorts.AgentWiring;
import com.agenticai.core.brief.dto.ApplyDtos.ApplyRequest;
import com.agenticai.core.brief.dto.ApplyDtos.ApplyResult;
import com.agenticai.core.brief.dto.ApplyDtos.StepDto;
import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The saga: order, ref resolution, compensation, degrade, re-apply, background context. Production engine over fakes. */
class BriefApplyServiceTest {

    private final EnvoyKit kit = new EnvoyKit();

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static List<String> kinds(ApplyResult r) {
        return r.steps().stream().map(StepDto::kind).toList();
    }

    private static StepDto step(ApplyResult r, String kind) {
        return r.steps().stream().filter(s -> s.kind().equals(kind)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ order + ref resolution

    @Test
    void appliesInDependencyOrderAndResolvesRefsToRealIds() {
        UUID id = kit.save(EnvoyKit.richBrief());

        ApplyResult result = kit.apply.applyCurrent(id, false);

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(kinds(result)).containsExactly(
            "KNOWLEDGE", "AGENT", "WORKFLOW", "TRUST_POLICY", "WORKFLOW_ACTIVATE", "EVAL_DATASET");
        assertThat(kit.ports.calls).containsSubsequence("createKnowledge", "createAgent", "wireAgent", "createWorkflow",
            "setApprovalRequired:true", "activateWorkflow", "createEvalDataset");

        String agentId = kit.entityIdOf(id, "a1");
        String knowledgeId = kit.entityIdOf(id, "k1");
        // The agent is wired to the REAL collection id, and its empty allow-list became "no tools", never "unrestricted".
        AgentWiring wiring = kit.ports.wirings.get(agentId);
        assertThat(wiring.knowledgeIds()).containsExactly(knowledgeId);
        assertThat(wiring.toolNames()).containsExactly(BriefApplyService.NO_TOOLS);
        // The workflow graph points at the real agent id, not at "a1".
        WorkflowGraphDto graph = kit.ports.graphs.get(kit.entityIdOf(id, "w1"));
        WorkflowNodeDto agentNode = graph.nodes().stream().filter(n -> n.id().equals("n2")).findFirst().orElseThrow();
        assertThat(agentNode.config().get("agentId")).isEqualTo(agentId);
        assertThat(graph.toString()).doesNotContain("\"a1\"").doesNotContain("agentId=a1");
        // Created at the lowest rung: lowest tier, approvals in front of the tool and the output, policy tightened.
        assertThat(kit.ports.tiers.get(agentId)).isEqualTo(AgentAutonomyTier.SUPERVISED);
        assertThat(TrustGraphMaterializer.approvalCount(graph)).isEqualTo(2);
        assertThat(kit.ports.approvalRequired).contains(TrustGraphMaterializer.APPROVAL_ACTION_TYPE);
        assertThat(kit.ports.activeWorkflows).contains(kit.entityIdOf(id, "w1"));
        assertThat(kit.ports.gates).isEmpty(); // no gate below TRUSTED
        assertThat(kit.ledger.bindings(id).keySet()).containsExactlyInAnyOrder("k1", "a1", "w1", "eval");
        assertThat(kit.entity(id).getStatus()).isEqualTo("APPLIED");
    }

    // ------------------------------------------------------------------ compensation

    @Test
    void aFailureLateInTheApplyLeavesNoOrphans() {
        UUID id = kit.save(EnvoyKit.richBrief());
        kit.ports.failOn.add("createEvalDataset"); // the very last thing an apply creates

        ApplyResult result = kit.apply.applyCurrent(id, false);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failedStep().kind()).isEqualTo("EVAL_DATASET");
        assertThat(result.error()).contains("EVAL_DATASET").contains("boom in createEvalDataset");
        assertThat(kit.ports.liveEntities()).as("entities left behind").isZero();
        assertThat(kit.ports.approvalRequired).as("the policy this apply tightened is put back").isEmpty();
        assertThat(kit.ledger.bindings(id)).isEmpty();
        assertThat(result.orphans()).isEmpty();
        // Every step that had created something is marked COMPENSATED and the job says which step broke.
        assertThat(step(result, "KNOWLEDGE").status()).isEqualTo("COMPENSATED");
        assertThat(step(result, "AGENT").status()).isEqualTo("COMPENSATED");
        assertThat(step(result, "WORKFLOW").status()).isEqualTo("COMPENSATED");
        assertThat(kit.entity(id).getStatus()).isEqualTo("FAILED");
    }

    @Test
    void aFailureInTheMiddleUndoesEverythingCreatedBefore() {
        UUID id = kit.save(EnvoyKit.richBrief());
        kit.ports.failOn.add("createWorkflow");

        ApplyResult result = kit.apply.applyCurrent(id, false);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failedStep().kind()).isEqualTo("WORKFLOW");
        assertThat(kit.ports.liveEntities()).isZero();
        assertThat(kit.ports.calls).contains("deleteAgent", "deleteKnowledge");
        assertThat(kit.ledger.bindings(id)).isEmpty();
    }

    @Test
    void anAgentWhoseWiringFailsAfterCreationIsStillRemoved() {
        UUID id = kit.save(EnvoyKit.richBrief());
        kit.ports.failOn.add("wireAgent"); // fails AFTER createAgent returned - the entity exists but the step did not finish

        ApplyResult result = kit.apply.applyCurrent(id, false);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(kit.ports.agents).isEmpty();
        assertThat(kit.ports.knowledge).isEmpty();
    }

    @Test
    void aCompensationThatCannotCompleteIsReportedAsAnOrphanNotHidden() {
        UUID id = kit.save(EnvoyKit.richBrief());
        kit.ports.failOn.add("createWorkflow");
        kit.ports.failDelete.add("deleteAgent"); // e.g. a FREE plan cannot delete agents

        ApplyResult result = kit.apply.applyCurrent(id, false);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(kit.ports.agents).hasSize(1);
        assertThat(result.orphans()).extracting(StepDto::kind).containsExactly("AGENT");
        assertThat(result.orphans().get(0).error()).contains("compensation failed").contains("cannot deleteAgent");
        // The leftover stays bound so a later apply or purge still knows about it; everything else was undone.
        assertThat(kit.ledger.bindings(id).keySet()).containsExactly("a1");
        assertThat(kit.ports.knowledge).isEmpty();
    }

    // ------------------------------------------------------------------ degrade

    @Test
    void withoutIntegrationsPermissionToolsAndMcpAreSkippedNotFailedAndNeverWidenTheAllowList() {
        B b = BriefFixtures.valid();
        b.tools.add(new Brief.ToolSpec("t1", "OPENAPI", "Tally API", "https://x.example/spec.json",
            new Brief.ToolAuth("BEARER", "s1"), null));
        b.mcp.add(new Brief.McpServerSpec("m1", "io.example/mail", "Mail", Map.of()));
        b.agents = new ArrayList<>(List.of(new Brief.AgentSpec("a1", "Collector", "d", "You chase invoices.", "CAREFUL", "SIMPLE",
            null, List.of("t1", "mcp:m1/*", "search_knowledge_base"), List.of("k1"), List.of(), List.of(), null, false)));
        UUID id = kit.save(b.build());
        kit.ports.canIntegrate = false;

        ApplyResult result = kit.apply.applyCurrent(id, false);

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(step(result, "TOOL").error()).startsWith("SKIPPED: ").contains("Manage integrations");
        assertThat(step(result, "MCP").error()).startsWith("SKIPPED: ").contains("Manage integrations");
        assertThat(kit.ports.tools).isEmpty();
        assertThat(kit.ports.mcp).isEmpty();
        assertThat(kit.ledger.bindings(id).keySet()).doesNotContain("t1", "m1");
        // The skipped artifacts are dropped from the allow-list; the built-in tool stays; nothing is widened.
        AgentWiring wiring = kit.ports.wirings.get(kit.entityIdOf(id, "a1"));
        assertThat(wiring.toolNames()).containsExactly("search_knowledge_base");
        assertThat(step(result, "AGENT").error()).startsWith("NOTE: ").contains("cannot use t1").contains("cannot use m1");
    }

    @Test
    void withoutManageToolsAToolIsSkippedEvenWithIntegrationsPermission() {
        B b = BriefFixtures.valid();
        b.tools.add(new Brief.ToolSpec("t1", "CODE", "Ledger helper", null, null, "Sum overdue invoices"));
        UUID id = kit.save(b.build());
        kit.ports.canTools = false;

        ApplyResult result = kit.apply.applyCurrent(id, false);

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(step(result, "TOOL").error()).startsWith("SKIPPED: ").contains("Manage tools");
        assertThat(kit.ports.tools).isEmpty();
    }

    @Test
    void withoutManageChannelsARoutedChannelIsSkippedNotBound() {
        B b = BriefFixtures.valid();
        b.channels.add(new Brief.ChannelSpec("c1", "SLACK", "a1", null));
        UUID id = kit.save(b.build());
        kit.ports.canChannels = false;

        ApplyResult result = kit.apply.applyCurrent(id, false);

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(step(result, "CHANNEL").error()).startsWith("SKIPPED: ").contains("Manage channels");
        assertThat(kit.ports.channels).isEmpty();
    }

    @Test
    void whenEveryToolIsSkippedTheAgentGetsNoToolsRatherThanAnEmptyUnrestrictedList() {
        B b = BriefFixtures.valid();
        b.tools.add(new Brief.ToolSpec("t1", "OPENAPI", "Tally API", "https://x.example/spec.json", null, null));
        b.agents = new ArrayList<>(List.of(new Brief.AgentSpec("a1", "Collector", "d", "You chase invoices.", "CAREFUL", "SIMPLE",
            null, List.of("t1"), List.of("k1"), List.of(), List.of(), null, false)));
        UUID id = kit.save(b.build());
        kit.ports.canIntegrate = false;

        kit.apply.applyCurrent(id, false);

        assertThat(kit.ports.wirings.get(kit.entityIdOf(id, "a1")).toolNames()).containsExactly(BriefApplyService.NO_TOOLS);
    }

    @Test
    void toolsAndMcpAreCreatedAndTheirRealNamesGoIntoTheAllowListWhenAllowed() {
        B b = BriefFixtures.valid();
        b.tools.add(new Brief.ToolSpec("t1", "OPENAPI", "Tally API", "https://x.example/spec.json", null, null));
        b.mcp.add(new Brief.McpServerSpec("m1", "io.example/mail", "Mail", Map.of()));
        b.agents = new ArrayList<>(List.of(new Brief.AgentSpec("a1", "Collector", "d", "You chase invoices.", "CAREFUL", "SIMPLE",
            null, List.of("t1", "mcp:m1/*"), List.of("k1"), List.of(), List.of(), null, false)));
        UUID id = kit.save(b.build());

        ApplyResult result = kit.apply.applyCurrent(id, false);

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        String toolId = kit.entityIdOf(id, "t1");
        AgentWiring wiring = kit.ports.wirings.get(kit.entityIdOf(id, "a1"));
        assertThat(wiring.toolNames()).contains("tool_" + toolId)
            .anyMatch(n -> n.startsWith("mcp__brief-") && n.endsWith("__search"));
        assertThat(kit.ledger.bindings(id).get("t1").kind()).isEqualTo("TOOL_OPENAPI");
        // Tools and MCP come before the agents that use them.
        assertThat(kinds(result)).containsSubsequence("TOOL", "MCP", "AGENT");
    }

    @Test
    void anEvalSetOnAPlanWithoutEvalsIsSkippedWithTheReason() {
        UUID id = kit.save(EnvoyKit.richBrief());
        kit.ports.evalAllowed = false;

        ApplyResult result = kit.apply.applyCurrent(id, false);

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(step(result, "EVAL_DATASET").error()).startsWith("SKIPPED: ").contains("plan");
        assertThat(kit.ports.datasets).isEmpty();
    }

    // ------------------------------------------------------------------ re-apply

    @Test
    void reApplyUpdatesInPlaceThroughTheServicesAndCreatesNothingTwice() {
        UUID id = kit.savedAndApplied(EnvoyKit.richBrief());
        String agentId = kit.entityIdOf(id, "a1");
        long creates = kit.ports.count("createAgent");

        Brief edited = EnvoyKit.richBrief();
        Brief.AgentSpec a = edited.agents().get(0);
        Brief.AgentSpec renamed = new Brief.AgentSpec(a.ref(), a.name(), a.description(), "You chase invoices, firmly.",
            a.reasoningDepth(), a.tuningMode(), a.generation(), a.toolAllowList(), a.knowledgeRefs(), a.procedureRefs(),
            a.guardrails(), a.edgeRoutingPolicy(), a.shareMemoryWithTenant());
        Brief next = new Brief(edited.briefVersion(), edited.title(), edited.goal(), edited.locale(), edited.clarifications(),
            edited.trust(), List.of(renamed), edited.procedures(), edited.workflows(), edited.tools(), edited.mcpServers(),
            edited.knowledge(), edited.secrets(), edited.channels(), edited.evalSet(), edited.rehearsal(), edited.narrative());

        ApplyResult result = kit.apply.apply(id, new ApplyRequest(false, next, 1, "firmer"));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(kit.entity(id).getCurrentVersion()).isEqualTo(2);
        assertThat(kit.ports.count("createAgent")).isEqualTo(creates);
        assertThat(kit.ports.count("updateAgent")).isEqualTo(1);
        assertThat(kit.ports.agents.get(agentId).systemPrompt()).isEqualTo("You chase invoices, firmly.");
        assertThat(kinds(result)).contains("AGENT_UPDATE").doesNotContain("AGENT", "KNOWLEDGE", "WORKFLOW", "EVAL_DATASET");
    }

    @Test
    void aFailedReApplyRestoresWhatItChangedAndKeepsPointingAtTheRunningVersion() {
        UUID id = kit.savedAndApplied(EnvoyKit.richBrief());
        String agentId = kit.entityIdOf(id, "a1");
        Brief before = kit.store.loadCurrent(kit.entity(id));
        WorkflowGraphDto workflowBefore = kit.ports.graphs.get(kit.entityIdOf(id, "w1"));

        Brief edited = EnvoyKit.richBrief();
        Brief.AgentSpec a = edited.agents().get(0);
        Brief.AgentSpec changed = new Brief.AgentSpec(a.ref(), a.name(), a.description(), "A different prompt.",
            a.reasoningDepth(), a.tuningMode(), a.generation(), a.toolAllowList(), a.knowledgeRefs(), a.procedureRefs(),
            a.guardrails(), a.edgeRoutingPolicy(), a.shareMemoryWithTenant());
        Brief.WorkflowSpec w = edited.workflows().get(0);
        Brief.WorkflowSpec renamedWorkflow = new Brief.WorkflowSpec(w.ref(), "Chase harder", w.graph(), w.triggers(), w.sampleInput());
        Brief.EvalSet e = edited.evalSet();
        Brief.EvalSet stricter = new Brief.EvalSet(e.targetAgent(), e.cases(), new Brief.Gate(0.9));
        Brief next = new Brief(edited.briefVersion(), edited.title(), edited.goal(), edited.locale(), edited.clarifications(),
            edited.trust(), List.of(changed), edited.procedures(), List.of(renamedWorkflow), edited.tools(), edited.mcpServers(),
            edited.knowledge(), edited.secrets(), edited.channels(), stricter, edited.rehearsal(), edited.narrative());
        kit.ports.failOn.add("updateEvalDataset"); // the LAST update, after the agent and the workflow were already changed

        ApplyResult result = kit.apply.apply(id, new ApplyRequest(false, next, 1, "risky"));

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failedStep().kind()).isEqualTo("EVAL_DATASET_UPDATE");
        assertThat(step(result, "WORKFLOW_UPDATE").status()).isEqualTo("COMPENSATED");
        assertThat(kit.ports.graphs.get(kit.entityIdOf(id, "w1"))).isEqualTo(workflowBefore);
        // The agent update that had already happened is undone by re-applying the previous spec.
        assertThat(kit.ports.agents.get(agentId).systemPrompt()).isEqualTo(before.agents().get(0).systemPrompt());
        assertThat(step(result, "AGENT_UPDATE").status()).isEqualTo("COMPENSATED");
        assertThat(kit.entity(id).getStatus()).isEqualTo("APPLIED");
        assertThat(kit.entity(id).getCurrentVersion()).as("still pointing at what is running").isEqualTo(1);
        assertThat(kit.versions).as("the failed proposal stays in the history").hasSize(2);
        assertThat(kit.ports.liveEntities()).isEqualTo(4); // knowledge, agent, workflow, dataset - nothing lost, nothing added
    }

    @Test
    void reApplyPrunesWhatTheBriefNoLongerDeclaresAndTheEnvoyStaysConsistent() {
        UUID id = kit.savedAndApplied(EnvoyKit.richBrief());
        Brief edited = EnvoyKit.richBrief();
        Brief next = new Brief(edited.briefVersion(), edited.title(), edited.goal(), edited.locale(), edited.clarifications(),
            edited.trust(), edited.agents(), edited.procedures(), edited.workflows(), edited.tools(), edited.mcpServers(),
            List.of(), edited.secrets(), edited.channels(), edited.evalSet(), edited.rehearsal(), edited.narrative());
        // The agent still names k1, which is gone: a dangling ref is a validation error in production; here we only
        // check the prune itself, so drop the reference too.
        Brief.AgentSpec a = next.agents().get(0);
        Brief.AgentSpec noKnowledge = new Brief.AgentSpec(a.ref(), a.name(), a.description(), a.systemPrompt(), a.reasoningDepth(),
            a.tuningMode(), a.generation(), a.toolAllowList(), List.of(), a.procedureRefs(), a.guardrails(),
            a.edgeRoutingPolicy(), a.shareMemoryWithTenant());
        next = new Brief(next.briefVersion(), next.title(), next.goal(), next.locale(), next.clarifications(), next.trust(),
            List.of(noKnowledge), next.procedures(), next.workflows(), next.tools(), next.mcpServers(), List.of(),
            next.secrets(), next.channels(), next.evalSet(), next.rehearsal(), next.narrative());

        ApplyResult result = kit.apply.apply(id, new ApplyRequest(false, next, 1, "drop knowledge"));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(kit.ports.knowledge).isEmpty();
        assertThat(kit.ledger.bindings(id).keySet()).doesNotContain("k1");
        assertThat(kinds(result)).contains("PRUNE_KNOWLEDGE");
    }

    @Test
    void aStaleProposalIsRefusedRatherThanApplied() {
        UUID id = kit.savedAndApplied(EnvoyKit.richBrief());

        assertThatThrownBy(() -> kit.apply.apply(id, new ApplyRequest(false, EnvoyKit.richBrief(), 7, null)))
            .isInstanceOf(BriefConflictException.class).hasMessageContaining("version 1");
    }

    // ------------------------------------------------------------------ concurrency + background

    @Test
    void aSecondApplyWhileOneIsRunningIsRefused() {
        UUID id = kit.save(EnvoyKit.richBrief());
        kit.ledger.startJob(id, 1, UUID.randomUUID()); // a job that is still PENDING

        assertThatThrownBy(() -> kit.apply.applyCurrent(id, false))
            .isInstanceOf(BriefConflictException.class).hasMessageContaining("already being applied");
    }

    @Test
    void aBackgroundJobRunsUnderTheCallersTenantContextAndClearsItAfterwards() throws Exception {
        UUID id = kit.save(EnvoyKit.richBrief());
        TenantContext.set(TenantContext.of("tenant-42", "alice", Set.of("PERM_MANAGE_AGENTS")));

        ApplyResult started = kit.apply.applyCurrent(id, true);
        assertThat(started.jobId()).isNotNull();

        ApplyResult finished = null;
        for (int i = 0; i < 200 && (finished == null || "RUNNING".equals(finished.status())); i++) {
            Thread.sleep(25);
            finished = kit.apply.job(id, started.jobId());
        }
        assertThat(finished.status()).isEqualTo("SUCCEEDED");
        // Thread-local context is restored on the worker: without it every tenant-schema read would misroute.
        assertThat(kit.ports.tenantSeenOnCreateKnowledge).isEqualTo("tenant-42");
        assertThat(kit.ports.knowledge).hasSize(1);
    }

    @Test
    void purgeRemovesEverythingTheBriefCreatedInReverseOrder() {
        UUID id = kit.savedAndApplied(EnvoyKit.richBrief());
        assertThat(kit.ports.liveEntities()).isPositive();

        ApplyResult purged = kit.apply.purge(id);

        assertThat(purged.status()).isEqualTo("SUCCEEDED");
        assertThat(kit.ports.liveEntities()).isZero();
        assertThat(kit.ledger.bindings(id)).isEmpty();
        assertThat(kit.ports.calls).containsSubsequence("deleteEvalDataset", "deleteWorkflow", "deleteAgent", "deleteKnowledge");
    }
}
