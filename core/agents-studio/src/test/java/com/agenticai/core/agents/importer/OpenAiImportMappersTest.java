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

class OpenAiImportMappersTest {

    // ---- Assistants API ------------------------------------------------------------------------------

    @Test
    void assistantFixtureMapsCoreFieldsAndReportsEverythingItCannotConvert() {
        AgentImportDraft d = OpenAiAssistantImportMapper.map(json("openai-assistant.json"));
        AgentImportFinalizer.finish(d);
        ConversionReport report = d.report.toReport();

        assertThat(d.name).isEqualTo("Math Tutor");
        assertThat(d.instructions).startsWith("You are a personal math tutor.");
        assertThat(d.providerHint).isEqualTo("openai");
        assertThat(d.modelHint).isEqualTo("gpt-4o");
        // 1.2 is valid for OpenAI (0..2) but outside this platform's 0..1: clamped AND said so.
        assertThat(d.temperature).isEqualTo(1.0);
        assertThat(report.warnings()).anyMatch(w -> w.contains("temperature") && w.contains("0 to 1"));
        assertThat(d.topP).isEqualTo(1.0);

        assertThat(d.suggestedCapabilities).containsExactly("e2b-code-execution");
        assertThat(d.suggestedTools).containsExactlyInAnyOrder("run_code", "search_knowledge_base");

        assertThat(hasNeedsManual(report, "code_interpreter")).isTrue();
        assertThat(hasNeedsManual(report, "file_search")).isTrue();
        assertThat(hasNeedsManual(report, "Function tool 'get_order_status'")).isTrue();
        assertThat(report.needsManual()).anyMatch(i -> i.what().contains("get_order_status")
            && i.hint().toLowerCase().contains("custom tool") && i.hint().contains("Look up the status"));
        assertThat(hasNeedsManual(report, "response_format")).isTrue();
        assertThat(hasUnsupported(report, "Metadata (2 keys)")).isTrue();
        // attachments are counted, never copied
        assertThat(flat(report)).contains("2 files").contains("1 attached");
        assertThat(flat(report)).doesNotContain("file-1").doesNotContain("vs_123");
        assertThat(hasConverted(report, "Instructions")).isTrue();
    }

    @Test
    void assistantWithoutInstructionsGetsAPlaceholderThatTheReportFlags() {
        AgentImportDraft d = OpenAiAssistantImportMapper.map(parse("{\"object\":\"assistant\",\"model\":\"gpt-4o\"}"));
        AgentImportFinalizer.finish(d);

        assertThat(d.instructions).isEqualTo(AgentImportFinalizer.PLACEHOLDER_INSTRUCTIONS);
        assertThat(d.name).isEqualTo(AgentImportFinalizer.DEFAULT_NAME);
        assertThat(hasNeedsManual(d.report.toReport(), "Instructions")).isTrue();
    }

    @Test
    void assistantToleratesWrongTypesEverywhere() {
        String hostile = "{\"object\":\"assistant\",\"name\":42,\"description\":[1],\"model\":{\"a\":1},"
            + "\"instructions\":{\"x\":\"y\"},\"tools\":\"code_interpreter\",\"tool_resources\":[],"
            + "\"metadata\":\"nope\",\"temperature\":\"hot\",\"top_p\":{},\"response_format\":7}";
        AgentImportDraft d = OpenAiAssistantImportMapper.map(parse(hostile));
        AgentImportFinalizer.finish(d);

        assertThat(d.name).isEqualTo(AgentImportFinalizer.DEFAULT_NAME);
        assertThat(d.temperature).isNull();
        assertThat(d.topP).isNull();
        assertThat(d.modelHint).isNull();
        assertThat(hasUnsupported(d.report.toReport(), "Instructions")).isTrue();
        assertThat(hasUnsupported(d.report.toReport(), "Tools")).isTrue();
    }

    @Test
    void assistantUnknownToolTypeIsUnsupportedNotGuessed() {
        AgentImportDraft d = OpenAiAssistantImportMapper.map(parse(
            "{\"object\":\"assistant\",\"tools\":[{\"type\":\"teleport\"},{\"nope\":1},\"str\",{\"type\":\"function\"}]}"));
        ConversionReport report = d.report.toReport();

        assertThat(hasUnsupported(report, "teleport")).isTrue();
        assertThat(report.unsupported()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(hasNeedsManual(report, "(unnamed)")).isTrue();
    }

    @Test
    void assistantToolListIsCappedAtWhatTheApiAllows() {
        StringBuilder tools = new StringBuilder("[");
        for (int i = 0; i < 400; i++) {
            tools.append(i == 0 ? "" : ",").append("{\"type\":\"function\",\"function\":{\"name\":\"f").append(i).append("\"}}");
        }
        tools.append("]");
        AgentImportDraft d = OpenAiAssistantImportMapper.map(parse("{\"object\":\"assistant\",\"tools\":" + tools + "}"));
        ConversionReport report = d.report.toReport();

        assertThat(report.warnings()).anyMatch(w -> w.contains("400 tools"));
        // the report itself is also capped, so a hostile payload cannot inflate the response
        assertThat(report.needsManual().size()).isLessThanOrEqualTo(AgentImportReport.MAX_ITEMS_PER_LIST);
    }

    @Test
    void assistantMapperRejectsNonObject() {
        assertThatThrownBy(() -> OpenAiAssistantImportMapper.map(parse("[1,2]")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Agents SDK ----------------------------------------------------------------------------------

    @Test
    void agentsSdkFixtureMapsWhatIsVerifiedAndReportsTheRest() {
        AgentImportDraft d = OpenAiAgentsImportMapper.map(json("openai-agents-sdk.json"));
        AgentImportFinalizer.finish(d);
        ConversionReport report = d.report.toReport();

        assertThat(d.name).isEqualTo("Triage agent");
        assertThat(d.description).isEqualTo("Routes the customer to the right specialist.");
        assertThat(d.instructions).contains("hand off to the Billing agent");
        assertThat(d.providerHint).isEqualTo("openai");
        assertThat(d.modelHint).isEqualTo("gpt-4.1");
        assertThat(d.temperature).isEqualTo(0.3);
        assertThat(d.topP).isEqualTo(0.9);

        assertThat(d.suggestedTools).contains("web_search", "search_knowledge_base", "run_code", "delegate_to_specialist");
        assertThat(d.suggestedCapabilities).containsExactly("e2b-code-execution");

        assertThat(hasConverted(report, "Web search")).isTrue();
        assertThat(hasNeedsManual(report, "file_search")).isTrue();
        assertThat(hasNeedsManual(report, "Hosted MCP server 'docs'")).isTrue();
        assertThat(hasNeedsManual(report, "Function tool 'lookup_invoice'")).isTrue();
        assertThat(hasNeedsManual(report, "Handoff to 'Billing agent'")).isTrue();
        assertThat(hasNeedsManual(report, "Handoff to 'Refund agent'")).isTrue();
        assertThat(hasNeedsManual(report, "Guardrails (2)")).isTrue();
        assertThat(hasNeedsManual(report, "Structured output")).isTrue();
        assertThat(hasUnsupported(report, "Other model settings (tool_choice, parallel_tool_calls)")).isTrue();
        // an MCP URL can carry a token; the importer must not copy it into the report
        assertThat(flat(report)).doesNotContain("should_not_be_copied").doesNotContain("example.invalid");
    }

    @Test
    void agentsSdkProviderPrefixedModelsAreSplit() {
        AgentImportDraft a = OpenAiAgentsImportMapper.map(parse("{\"instructions\":\"x\",\"model\":\"litellm/anthropic/claude-sonnet-4\"}"));
        assertThat(a.providerHint).isEqualTo("anthropic");
        assertThat(a.modelHint).isEqualTo("claude-sonnet-4");

        AgentImportDraft b = OpenAiAgentsImportMapper.map(parse("{\"instructions\":\"x\",\"model\":\"gpt-4o\"}"));
        assertThat(b.providerHint).isEqualTo("openai");
        assertThat(b.modelHint).isEqualTo("gpt-4o");
    }

    @Test
    void agentsSdkDegenerateModelStringsDoNotThrow() {
        for (String model : new String[] {"/", "//", "a/", "/a", "litellm/", " "}) {
            AgentImportDraft d = OpenAiAgentsImportMapper.map(parse("{\"instructions\":\"x\",\"model\":\"" + model + "\"}"));
            assertThat(d.providerHint).isNotNull();
        }
    }

    @Test
    void agentsSdkNonStringInstructionsAndModelAreReportedNotCoerced() {
        AgentImportDraft d = OpenAiAgentsImportMapper.map(parse(
            "{\"name\":\"a\",\"instructions\":{\"fn\":\"dynamic\"},\"model\":{\"custom\":true},\"tools\":[5,null,[]],"
                + "\"handoffs\":[{},3],\"model_settings\":\"x\",\"hooks\":{\"on_start\":1}}"));
        ConversionReport report = d.report.toReport();

        assertThat(d.instructions).isNull();
        assertThat(hasUnsupported(report, "Instructions")).isTrue();
        assertThat(hasUnsupported(report, "Model")).isTrue();
        assertThat(hasUnsupported(report, "Runtime behaviour settings (hooks)")).isTrue();
    }

    @Test
    void agentsSdkAcceptsCamelCaseSpellings() {
        AgentImportDraft d = OpenAiAgentsImportMapper.map(parse(
            "{\"name\":\"a\",\"instructions\":\"i\",\"handoffDescription\":\"d\",\"modelSettings\":{\"topP\":0.5,\"temperature\":0.1},"
                + "\"inputGuardrails\":[{}],\"outputType\":\"X\"}"));
        assertThat(d.description).isEqualTo("d");
        assertThat(d.topP).isEqualTo(0.5);
        assertThat(d.temperature).isEqualTo(0.1);
        assertThat(hasNeedsManual(d.report.toReport(), "Guardrails (1)")).isTrue();
        assertThat(hasNeedsManual(d.report.toReport(), "Structured output")).isTrue();
    }

    @Test
    void toolTypeNormalisationHandlesSdkClassNamesAndApiNames() {
        assertThat(OpenAiAgentsImportMapper.normaliseToolType("WebSearchTool")).isEqualTo("websearch");
        assertThat(OpenAiAgentsImportMapper.normaliseToolType("web_search_preview")).isEqualTo("websearchpreview");
        assertThat(OpenAiAgentsImportMapper.normaliseToolType("code_interpreter")).isEqualTo("codeinterpreter");
    }
}
