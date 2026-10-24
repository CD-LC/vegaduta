package com.agenticai.core.agents.turn.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.classification.ToolClassification;
import com.agenticai.core.agents.tools.classification.ToolClassificationDecision;
import com.agenticai.core.agents.tools.classification.ToolClassificationRegistry;
import com.agenticai.core.agents.tools.classification.ToolClassificationRepository;
import com.agenticai.core.agents.tools.classification.ToolDeterminism;
import com.agenticai.core.agents.tools.classification.ToolSensitivity;
import com.agenticai.core.agents.tools.classification.ToolTrustTier;
import com.agenticai.core.guardrails.ToolEffectSandbox;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Which tools an eval turn may really run, decided from the tool's CLASSIFICATION (the platform's compiled-in
 * declarations here, the real table of the first-party tools): readers run, everything that could change or send
 * something is simulated, anything unknown is simulated, and the operator's list of vouched-for tools never overrides a
 * declaration.
 */
class EvalToolEffectPolicyTest {

    /**
     * The real registry over an empty table, so what resolves is the platform's compiled-in declarations; a name with
     * none is unclassified, exactly as the registry reports it.
     */
    private static final ToolClassificationRegistry REGISTRY = registry();

    private static ToolClassificationRegistry registry() {
        ToolClassificationRepository repository = mock(ToolClassificationRepository.class);
        when(repository.findByToolName(anyString())).thenReturn(Optional.empty());
        when(repository.findPatternRows()).thenReturn(List.of());
        return new ToolClassificationRegistry(repository, new SimpleMeterRegistry());
    }

    private static final Function<String, ToolClassificationDecision> BUILT_INS = REGISTRY::resolveIgnoringMcpAnnotations;

    private static ToolEffectSandbox.Verdict decide(String tool, String input, String... vouched) {
        return EvalToolEffectPolicy.decide(
                BUILT_INS, EvalToolEffectPolicy.compile(List.of(vouched)), tool, input);
    }

    private static void assertRuns(String tool) {
        assertThat(decide(tool, "{}").execute()).as("%s should run for real", tool).isTrue();
    }

    private static void assertSimulated(String tool) {
        ToolEffectSandbox.Verdict v = decide(tool, "{}");
        assertThat(v.execute()).as("%s must be simulated", tool).isFalse();
        assertThat(v.reason()).isNotBlank();
    }

    // ------------------------------------------------------------------ the first-party tools

    @Test
    void theReadersRunForReal() {
        for (String reader : List.of(
                "search_knowledge_base", "list_knowledge_collections", "recall_related_facts", "data_table_query",
                "fetch_web_page", "web_search", "deep_research", "workspace_read", "workspace_search",
                "search_conversation_history", "load_skill", "search_tools", "call_tool", "browser_read_page_text",
                "channel_read_messages", "check_payment_status", "sarvam_translate")) {
            assertRuns(reader);
        }
    }

    @Test
    void everyToolThatWritesSendsPaysOrLeavesTheTenantIsSimulated() {
        for (String effect : List.of(
                // writes inside the platform
                "remember_fact", "remember_shared_fact", "add_to_knowledge_base", "data_table_write", "workspace_write",
                "workspace_edit", "report_progress", "request_human", "generate_image", "generate_document", "speak",
                "create_workflow", "run_skill_script", "create_code_workspace",
                // reaches outside
                "google_gmail", "google_calendar", "google_drive", "google_sheets", "channel_send_message",
                "forward_attachment", "upload_video_to_youtube", "http_request_write", "a2a_call_agent",
                // shell, code, browser actions, scheduling
                "local_shell", "run_code", "run_in_code_workspace", "coding_task", "apply_code_changes",
                "delete_code_workspace", "schedule_task", "browser_click", "browser_type", "browser_totp",
                // money
                "create_payment_link",
                // a delegated agent runs its own turn, outside this one's sandbox
                "delegate_to_specialist", "delegate_parallel", "run_subtasks", "ask_agent_billing")) {
            assertSimulated(effect);
        }
    }

    @Test
    void aToolNobodyClassifiedIsSimulated() {
        ToolEffectSandbox.Verdict v = decide("mcp__crm__delete_contact", "{}");
        assertThat(v.execute()).isFalse();
        assertThat(v.reason()).contains("unclassified");
        assertSimulated("a_tool_nobody_has_heard_of");
    }

    @Test
    void aMissingNameOrRegistryIsSimulated() {
        assertThat(EvalToolEffectPolicy.decide(BUILT_INS, List.of(), null, "{}").execute()).isFalse();
        assertThat(EvalToolEffectPolicy.decide(BUILT_INS, List.of(), " ", "{}").execute()).isFalse();
        assertThat(EvalToolEffectPolicy.decide(null, List.of(), "web_search", "{}").execute())
                .as("no classification available").isFalse();
    }

    @Test
    void aClassificationThatCannotBeReadSimulatesInsteadOfRunning() {
        Function<String, ToolClassificationDecision> broken = name -> {
            throw new IllegalStateException("tool_classification table unavailable");
        };

        assertThat(EvalToolEffectPolicy.decide(broken, List.of(), "web_search", "{}").execute()).isFalse();
    }

    // ------------------------------------------------------------------ the operator's list

    @Test
    void theOperatorCanVouchForAnUnclassifiedReaderByNameOrGlob() {
        assertThat(decide("mcp__crm__lookup_contact", "{}", "mcp__crm__lookup_*").execute()).isTrue();
        assertThat(decide("mcp__crm__lookup_contact", "{}", "MCP__CRM__LOOKUP_CONTACT").execute())
                .as("names are matched case-insensitively").isTrue();
        assertThat(decide("mcp__crm__delete_contact", "{}", "mcp__crm__lookup_*").execute())
                .as("a name the glob does not cover stays simulated").isFalse();
    }

    @Test
    void theListNeverOverridesADeclaredClassification() {
        assertThat(decide("google_gmail", "{}", "google_gmail").execute())
                .as("a declared EGRESS tool cannot be made to run by naming it").isFalse();
        assertThat(decide("remember_fact", "{}", "*").execute()).as("even a wildcard").isFalse();
        assertThat(decide("local_shell", "{}", "local_*").execute()).isFalse();
    }

    @Test
    void aQuarantinedOrInvalidlyDeclaredToolCannotBeVouchedFor() {
        Function<String, ToolClassificationDecision> quarantined = name -> new ToolClassificationDecision.Blocked(
                name, ToolClassificationDecision.Reason.QUARANTINED, "held back by an admin");

        assertThat(EvalToolEffectPolicy.decide(
                        quarantined, EvalToolEffectPolicy.compile(List.of("*")), "mcp__x__y", "{}").execute())
                .isFalse();
    }

    // ------------------------------------------------------------------ arguments that matter

    @Test
    void httpRequestIsAReaderOnlyForReadMethods() {
        assertThat(decide("http_request", "{\"url\":\"https://x.test\"}").execute()).as("no method means GET").isTrue();
        assertThat(decide("http_request", "{\"method\":\"get\",\"url\":\"https://x.test\"}").execute()).isTrue();
        assertThat(decide("http_request", "{\"method\":\"HEAD\"}").execute()).isTrue();
        assertThat(decide("http_request", "{\"method\":\"OPTIONS\"}").execute()).isTrue();
        assertThat(decide("http_request", "{\"method\":\"POST\",\"url\":\"https://x.test\"}").execute()).isFalse();
        assertThat(decide("http_request", "{\"method\":\"delete\"}").execute()).isFalse();
        assertThat(decide("http_request", "{\"method\":\"PATCH\"}").execute()).isFalse();
    }

    @Test
    void httpRequestWithArgumentsThatCannotBeReadIsSimulated() {
        assertThat(decide("http_request", "not json").execute()).isFalse();
        assertThat(decide("http_request", "[1,2]").execute()).isFalse();
    }

    // ------------------------------------------------------------------ the declared shape of a reader

    private static ToolClassification declared(
            ToolSensitivity sensitivity, ToolDeterminism determinism, boolean irreversible) {
        return new ToolClassification(
                "t", ToolTrustTier.TRUSTED_INTERNAL, sensitivity, determinism, null, irreversible, true);
    }

    @Test
    void aReaderMustBeRepeatableAndReversibleToo() {
        assertThat(EvalToolEffectPolicy.isReader(declared(ToolSensitivity.READ_TENANT, ToolDeterminism.SIDE_EFFECT, false)))
                .isTrue();
        assertThat(EvalToolEffectPolicy.isReader(declared(ToolSensitivity.READ_PUBLIC, ToolDeterminism.PURE, false)))
                .isTrue();
        assertThat(EvalToolEffectPolicy.isReader(declared(ToolSensitivity.CREDENTIALED, ToolDeterminism.SIDE_EFFECT, false)))
                .as("a read with a stored credential, such as a status lookup").isTrue();
        assertThat(EvalToolEffectPolicy.isReader(declared(ToolSensitivity.CREDENTIALED, ToolDeterminism.DURABLE, false)))
                .isFalse();
        assertThat(EvalToolEffectPolicy.isReader(declared(ToolSensitivity.READ_TENANT, ToolDeterminism.SIDE_EFFECT, true)))
                .as("irreversible").isFalse();
        assertThat(EvalToolEffectPolicy.isReader(declared(ToolSensitivity.READ_TENANT, ToolDeterminism.DURABLE, false)))
                .as("durable").isFalse();
        assertThat(EvalToolEffectPolicy.isReader(declared(ToolSensitivity.WRITE_TENANT, ToolDeterminism.SIDE_EFFECT, false)))
                .isFalse();
        assertThat(EvalToolEffectPolicy.isReader(declared(ToolSensitivity.EGRESS, ToolDeterminism.PURE, false)))
                .isFalse();
    }

    // ------------------------------------------------------------------ the bean

    @Test
    @SuppressWarnings("unchecked")
    void theBeanAsksTheRegistryWithoutTheMcpServersOwnAnnotations() {
        ToolClassificationRegistry registry = mock(ToolClassificationRegistry.class);
        ObjectProvider<ToolClassificationRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        when(registry.resolveIgnoringMcpAnnotations("mcp__crm__lookup"))
                .thenReturn(new ToolClassificationDecision.Blocked(
                        "mcp__crm__lookup", ToolClassificationDecision.Reason.UNCLASSIFIED, null));

        ToolEffectSandbox rule = new EvalToolEffectPolicy(provider).forRun(List.of());

        assertThat(rule.check("mcp__crm__lookup", "{}").execute())
                .as("a server saying readOnlyHint is a claim by a third party").isFalse();
        verify(registry).resolveIgnoringMcpAnnotations("mcp__crm__lookup");
        verify(registry, never()).resolve(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void withNoRegistryEveryCallIsSimulated() {
        ObjectProvider<ToolClassificationRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        assertThat(new EvalToolEffectPolicy(provider).forRun(List.of("*")).check("web_search", "{}").execute()).isFalse();
    }
}
