package com.agenticai.core.agents.importer;

import static com.agenticai.core.agents.importer.AgentImportFixtures.flat;
import static com.agenticai.core.agents.importer.AgentImportFixtures.hasConverted;
import static com.agenticai.core.agents.importer.AgentImportFixtures.hasNeedsManual;
import static com.agenticai.core.agents.importer.AgentImportFixtures.hasUnsupported;
import static com.agenticai.core.agents.importer.AgentImportFixtures.json;
import static com.agenticai.core.agents.importer.AgentImportFixtures.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.agents.importer.AgentImportDtos.ConversionReport;
import org.junit.jupiter.api.Test;

class FlowiseImportMapperTest {

    @Test
    void toolAgentChatflowExtractsPromptModelToolsAndMemory() {
        AgentImportDraft d = FlowiseImportMapper.map(json("flowise-tool-agent.json"), null, null);
        AgentImportFinalizer.finish(d);
        ConversionReport report = d.report.toReport();

        assertThat(d.instructions).isEqualTo("You are a helpful research assistant. Use tools when needed and cite sources.");
        assertThat(d.providerHint).isEqualTo("openai");
        assertThat(d.modelHint).isEqualTo("gpt-4o-mini");
        assertThat(d.temperature).isEqualTo(0.9);
        assertThat(d.topP).isNull();

        // search tool -> platform web_search; calculator has no equivalent; customTool needs a manual step
        assertThat(d.suggestedTools).containsExactly("web_search");
        assertThat(hasConverted(report, "Serp API")).isTrue();
        assertThat(hasUnsupported(report, "Calculator")).isTrue();
        assertThat(hasNeedsManual(report, "Custom Tool")).isTrue();
        assertThat(hasConverted(report, "Memory 'Buffer Memory'")).isTrue();
        // sticky notes are silently skipped, never reported as flow nodes
        assertThat(hasUnsupported(report, "stickyNote")).isFalse();
    }

    @Test
    void credentialFieldsAreCountedReportedAndNeverCopied() {
        AgentImportDraft d = FlowiseImportMapper.map(json("flowise-tool-agent.json"), null, null);
        AgentImportFinalizer.finish(d);
        ConversionReport report = d.report.toReport();

        // data.credential on two nodes + openAIApiKey inside inputs
        assertThat(report.needsManual()).anyMatch(i -> i.what().equals("Credentials (3 fields)")
            && i.why().contains("not imported"));
        String all = flat(report) + " " + d.instructions + " " + d.name + " " + d.modelHint;
        assertThat(all).doesNotContain("sk-should-never-be-read")
            .doesNotContain("11111111-2222-3333-4444-555555555555")
            .doesNotContain("5e0c2c0e");
    }

    @Test
    void ragChatflowReadsPromptThroughReferenceAndReportsRetrievalAsManual() {
        AgentImportDraft d = FlowiseImportMapper.map(json("flowise-rag-chatflow.json"), "Docs QnA", "Answers from the product docs.");
        AgentImportFinalizer.finish(d);
        ConversionReport report = d.report.toReport();

        assertThat(d.name).isEqualTo("Docs QnA");
        assertThat(d.instructions).isEqualTo("Answer only from the provided context. If unsure, say you do not know.");
        assertThat(d.providerHint).isEqualTo("anthropic");
        assertThat(d.modelHint).isEqualTo("claude-3-5-sonnet-latest");
        // Flowise stores some numbers as strings
        assertThat(d.temperature).isEqualTo(0.2);
        assertThat(d.suggestedTools).containsExactly("search_knowledge_base");
        assertThat(report.needsManual()).anyMatch(i -> i.what().startsWith("Knowledge / retrieval")
            && i.what().contains("Faiss") && i.hint().toLowerCase().contains("knowledge base"));
        assertThat(hasUnsupported(report, "Embedding / text-splitter nodes (1)")).isTrue();
        assertThat(report.warnings()).anyMatch(w -> w.contains("rephrase prompt"));
        // the embeddings node carried a credential
        assertThat(hasNeedsManual(report, "Credentials (1 fields)")).isTrue();
    }

    @Test
    void agentflowV2PicksFirstAgentInFlowOrderConvertsHtmlAndListsTheRest() {
        AgentImportDraft d = FlowiseImportMapper.map(json("flowise-agentflow-v2.json"), null, null);
        AgentImportFinalizer.finish(d);
        ConversionReport report = d.report.toReport();

        assertThat(d.name).isEqualTo("Refund Agent");
        assertThat(d.instructions).isEqualTo("You are a refund agent. Help the user with refunds.\nBe brief & polite.");
        assertThat(d.providerHint).isEqualTo("gemini");
        assertThat(d.modelHint).isEqualTo("gemini-2.0-flash");
        assertThat(d.temperature).isEqualTo(0.4);

        assertThat(hasNeedsManual(report, "Additional agent 'Sales Agent'")).isTrue();
        assertThat(d.suggestedTools).contains("delegate_to_specialist", "web_search", "search_knowledge_base");
        assertThat(hasUnsupported(report, "conditionAgentAgentflow")).isTrue();
        assertThat(hasUnsupported(report, "startAgentflow")).isFalse();
        assertThat(report.warnings()).anyMatch(w -> w.contains("non-system"));
        assertThat(hasNeedsManual(report, "Credentials (1 fields)")).isTrue();
    }

    @Test
    void flowWithNoAgentStillProducesADraftAndSaysSo() {
        AgentImportDraft d = FlowiseImportMapper.map(parse("{\"nodes\":[],\"edges\":[]}"), null, null);
        AgentImportFinalizer.finish(d);

        assertThat(d.instructions).isEqualTo(AgentImportFinalizer.PLACEHOLDER_INSTRUCTIONS);
        assertThat(hasNeedsManual(d.report.toReport(), "Agent")).isTrue();
    }

    // ---- hostile input -------------------------------------------------------------------------------

    @Test
    void tooManyNodesIsRejectedBeforeAnyWork() {
        StringBuilder sb = new StringBuilder("{\"nodes\":[");
        for (int i = 0; i <= FlowiseImportMapper.MAX_NODES; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"id\":\"n").append(i).append("\",\"data\":{\"name\":\"x\"}}");
        }
        sb.append("]}");
        assertThatThrownBy(() -> FlowiseImportMapper.map(parse(sb.toString()), null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("limit is " + FlowiseImportMapper.MAX_NODES);
    }

    @Test
    void exactlyTheNodeCapIsAccepted() {
        StringBuilder sb = new StringBuilder("{\"nodes\":[");
        for (int i = 0; i < FlowiseImportMapper.MAX_NODES; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"id\":\"n").append(i).append("\",\"data\":{\"name\":\"x\",\"category\":\"Utilities\"}}");
        }
        sb.append("]}");
        AgentImportDraft d = FlowiseImportMapper.map(parse(sb.toString()), null, null);
        // 500 identical utility nodes collapse into ONE report line, not 500
        assertThat(d.report.toReport().unsupported()).hasSize(1);
    }

    @Test
    void nonFlowShapesAreRejected() {
        assertThatThrownBy(() -> FlowiseImportMapper.map(parse("{\"nodes\":\"nope\"}"), null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FlowiseImportMapper.map(parse("[]"), null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FlowiseImportMapper.map(parse("{}"), null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wrongTypesInsideNodesDegradeToSkippedNodesNotExceptions() {
        String flow = "{\"nodes\":[1,null,\"x\",[],{\"data\":5},{\"data\":{\"name\":7,\"label\":[],\"category\":{},\"inputs\":\"str\"}},"
            + "{\"id\":{\"a\":1},\"data\":{\"name\":\"toolAgent\",\"category\":\"Agents\",\"inputs\":{\"systemMessage\":99,\"model\":12,\"tools\":\"{{x\"}}}],"
            + "\"edges\":[1,\"a\",{\"source\":5,\"target\":[]},null]}";
        AgentImportDraft d = FlowiseImportMapper.map(parse(flow), null, null);
        AgentImportFinalizer.finish(d);

        assertThat(d.report.toReport().warnings()).anyMatch(w -> w.contains("not readable node objects"));
        assertThat(d.instructions).isEqualTo(AgentImportFinalizer.PLACEHOLDER_INSTRUCTIONS);
    }

    @Test
    void referenceCyclesAndSelfReferencesDoNotLoop() {
        String flow = "{\"nodes\":["
            + "{\"id\":\"a\",\"data\":{\"id\":\"a\",\"name\":\"toolAgent\",\"category\":\"Agents\",\"inputs\":{\"model\":\"{{a.data.instance}}\",\"systemMessage\":\"hi\"}}},"
            + "{\"id\":\"s\",\"data\":{\"id\":\"s\",\"name\":\"startAgentflow\",\"category\":\"Agent Flows\",\"inputs\":{}}}"
            + "],\"edges\":[{\"source\":\"s\",\"target\":\"s\"},{\"source\":\"a\",\"target\":\"a\"}]}";
        AgentImportDraft d = FlowiseImportMapper.map(parse(flow), null, null);

        assertThat(d.instructions).isEqualTo("hi");
        // a is not a chat model, so its own provider is unknown rather than guessed
        assertThat(d.providerHint).isNull();
    }

    @Test
    void agentflowCycleBetweenAgentsStillTerminates() {
        String flow = "{\"nodes\":["
            + "{\"id\":\"s\",\"type\":\"agentFlow\",\"data\":{\"id\":\"s\",\"name\":\"startAgentflow\",\"category\":\"Agent Flows\",\"inputs\":{}}},"
            + "{\"id\":\"x\",\"type\":\"agentFlow\",\"data\":{\"id\":\"x\",\"label\":\"X\",\"name\":\"agentAgentflow\",\"category\":\"Agent Flows\",\"inputs\":{\"agentMessages\":[{\"role\":\"system\",\"content\":\"<p>x</p>\"}],\"agentModelConfig\":{}}}},"
            + "{\"id\":\"y\",\"type\":\"agentFlow\",\"data\":{\"id\":\"y\",\"label\":\"Y\",\"name\":\"agentAgentflow\",\"category\":\"Agent Flows\",\"inputs\":{\"agentMessages\":[]}}}"
            + "],\"edges\":[{\"source\":\"s\",\"target\":\"x\"},{\"source\":\"x\",\"target\":\"y\"},{\"source\":\"y\",\"target\":\"x\"}]}";
        AgentImportDraft d = FlowiseImportMapper.map(parse(flow), null, null);
        assertThat(d.name).isEqualTo("X");
        assertThat(d.instructions).isEqualTo("x");
    }

    @Test
    void flowDataWrapperTextIsExposedForTheService() {
        assertThat(FlowiseImportMapper.flowDataText(parse("{\"name\":\"n\",\"flowData\":\"{\\\"nodes\\\":[]}\"}")))
            .isEqualTo("{\"nodes\":[]}");
        assertThat(FlowiseImportMapper.flowDataText(parse("{\"nodes\":[]}"))).isNull();
    }
}
