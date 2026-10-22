package com.agenticai.core.templates;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.templates.dto.TemplateReadinessReport;
import com.agenticai.core.templates.dto.TemplateReadinessReport.Issue;
import com.agenticai.core.templates.dto.TemplateRequirement;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Rule-engine tests: the LifePilot regression, the published example, requirement derivation,
 * standard coverage.
 */
class TemplateReadinessServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TemplateReadinessService service = new TemplateReadinessService();

    private static final String BOILERPLATE =
            "\n\nHow you work:\n- Be concrete and short.\n- Say plainly when you are unsure.";

    private JsonNode agent(
            String prompt, String tier, String tools, String guardrails, int maxTokens)
            throws Exception {
        return mapper.readTree(
                "{\"systemPrompt\":"
                        + mapper.writeValueAsString(prompt + BOILERPLATE)
                        + ",\"providerName\":\"PLATFORM_HOSTED\",\"maxTokens\":"
                        + maxTokens
                        + ",\"autonomyTier\":\""
                        + tier
                        + "\",\"knowledgeBaseEnabled\":false,\"allowedToolNames\":"
                        + tools
                        + ",\"enabledGuardrails\":"
                        + guardrails
                        + "}");
    }

    private static List<String> codes(List<Issue> issues) {
        return issues.stream().map(Issue::code).toList();
    }

    @Test
    void lifepilotAsSubmitted_isBlocked() throws Exception {
        // The real template that was approved on 2026-10-11: a truncated "build a web app" brief,
        // no tags.
        String prompt =
                "You are an expert AI agent. Your job: # Build LifePilot AI - A Personal AI"
                        + " Assistant That Takes Action Create a functional, responsive web application"
                        + " called **LifePilot AI**, with the tagline: \"Your goals. Your assistant."
                        + " Your next step.\" ## 1. Product vision LifePilot AI i.";
        TemplateReadinessReport r =
                service.assess(
                        TemplateKind.AGENT,
                        "LifePilot AI Assistant",
                        "LifePilot AI is your personal AI assistant that helps you manage tasks,"
                                + " organize your life, and achieve goals effortlessly.",
                        List.of(),
                        "assistant",
                        agent(prompt, "SEMI_AUTONOMOUS", "[]", "[\"jailbreak\"]", 1024));

        assertThat(r.ready()).isFalse();
        assertThat(codes(r.blockers())).contains("NO_TAGS", "PROMPT_IS_BUILD_BRIEF");
        assertThat(codes(r.advisories())).contains("EMPTY_TOOL_ALLOWLIST");
    }

    @Test
    void publishedExample_passesItsOwnStandard() throws Exception {
        TemplateReadinessReport r =
                service.assess(
                        TemplateKind.AGENT,
                        "Daily Planner & Goal Coach",
                        TemplateStandard.EXAMPLE_SUMMARY,
                        List.of("productivity", "planning", "personal"),
                        "assistant",
                        agent(
                                TemplateStandard.EXAMPLE_PROMPT,
                                "SUPERVISED",
                                "[]",
                                "[\"jailbreak\"]",
                                1024));

        assertThat(codes(r.blockers())).isEmpty();
        assertThat(r.ready()).isTrue();
        assertThat(codes(r.advisories()))
                .doesNotContain(
                        "NO_ROLE_STATEMENT",
                        "NO_STATED_LIMITS",
                        "NO_UNCERTAINTY_RULE",
                        "CLAIMS_ACTIONS_WITHOUT_TOOLS",
                        "FEW_TAGS");
    }

    @Test
    void truncatedPromptAndShortSummary_areBlocked() throws Exception {
        TemplateReadinessReport r =
                service.assess(
                        TemplateKind.AGENT,
                        "Helper",
                        "Helps.",
                        List.of("x"),
                        "assistant",
                        agent(
                                "You are a helpful assistant that answers questions about gardening"
                                        + " and helps people plan their beds, choose plants and"
                                        + " understand soil, light and watering needs, and in all of"
                                        + " those you",
                                "SUPERVISED",
                                "[]",
                                "[\"jailbreak\"]",
                                1024));
        assertThat(codes(r.blockers())).contains("SUMMARY_TOO_SHORT", "PROMPT_TRUNCATED");
    }

    @Test
    void guardrailsRequired_andSecretsBlocked() throws Exception {
        String prompt =
                "You are a support assistant for Acme. Use api_key = sk-abcdefghijklmnopqrstuvwxyz"
                        + " when calling the billing system. You do not give legal advice. Say when you"
                        + " are unsure and never invent facts.";
        TemplateReadinessReport r =
                service.assess(
                        TemplateKind.AGENT,
                        "Acme Support Helper",
                        "A support assistant for Acme customers that answers billing questions and"
                                + " explains invoices clearly.",
                        List.of("support", "billing", "acme"),
                        "support",
                        agent(prompt, "SUPERVISED", "[\"search_knowledge_base\"]", "[]", 1024));
        assertThat(codes(r.blockers())).contains("NO_GUARDRAILS", "SECRET_IN_PROMPT");
    }

    @Test
    void fullyAutonomousWithActionTools_isBlocked() throws Exception {
        TemplateReadinessReport r =
                service.assess(
                        TemplateKind.AGENT,
                        "Auto Mailer",
                        "Drafts and sends follow-up emails for your sales leads after every call,"
                                + " using your Gmail account.",
                        List.of("sales", "email", "followup"),
                        "sales",
                        agent(
                                TemplateStandard.EXAMPLE_PROMPT,
                                "FULLY_AUTONOMOUS",
                                "[\"google_gmail\"]",
                                "[\"jailbreak\"]",
                                1024));
        assertThat(codes(r.blockers())).contains("AUTONOMOUS_WITH_ACTION_TOOLS");
    }

    @Test
    void requirements_areDerivedFromToolsAndFlags() throws Exception {
        JsonNode cfg =
                mapper.readTree(
                        "{\"providerName\":\"ANTHROPIC\",\"knowledgeBaseEnabled\":true,"
                                + "\"allowedToolNames\":[\"mcp__office-ally__check_claim_status\",\"mcp__office-ally__check_eligibility\","
                                + "\"google_gmail\",\"search_knowledge_base\",\"request_human\",\"search_code\",\"run_code\",\"generate_document\"]}");
        List<TemplateRequirement> reqs = service.requirements(TemplateKind.AGENT, cfg);

        assertThat(reqs)
                .extracting(TemplateRequirement::type)
                .containsExactlyInAnyOrder(
                        TemplateRequirement.Type.MCP_SERVER,
                                TemplateRequirement.Type.GOOGLE_CONNECTION,
                        TemplateRequirement.Type.KNOWLEDGE_BASE,
                                TemplateRequirement.Type.HUMAN_APPROVAL,
                        TemplateRequirement.Type.CODE_INTEL, TemplateRequirement.Type.CODE_SANDBOX,
                        TemplateRequirement.Type.BUILT_IN_TOOL, TemplateRequirement.Type.MODEL_KEY);
        // two tools on one server -> ONE requirement
        assertThat(reqs.stream().filter(x -> x.type() == TemplateRequirement.Type.MCP_SERVER))
                .hasSize(1);
        assertThat(
                        reqs.stream()
                                .filter(x -> x.type() == TemplateRequirement.Type.MCP_SERVER)
                                .findFirst()
                                .get()
                                .setupPath())
                .isEqualTo("/tools/servers");
        assertThat(TemplateRequirements.derivedTags(reqs))
                .contains("mcp:office-ally", "setup:google", "setup:knowledge");
    }

    @Test
    void workflowNeedsTriggerAndOutput() throws Exception {
        JsonNode wf =
                mapper.readTree(
                        "{\"description\":\"x\",\"graph\":{\"nodes\":[{\"type\":\"agent\",\"data\":{}}]}}");
        TemplateReadinessReport r =
                service.assess(
                        TemplateKind.WORKFLOW,
                        "Nightly report workflow",
                        "Builds a nightly summary of yesterday's tickets and posts it for the team"
                                + " to review each morning.",
                        List.of("reports", "support", "daily"),
                        "ops",
                        wf);
        assertThat(codes(r.blockers())).contains("NO_TRIGGER", "NO_OUTPUT");
    }

    @Test
    void everyEmittedIssueCodeIsInThePublishedStandard() throws Exception {
        // Run a spread of bad inputs and assert no issue uses a code the guide does not document.
        List<TemplateReadinessReport> reports =
                List.of(
                        service.assess(
                                TemplateKind.AGENT,
                                "a",
                                "x",
                                List.of(),
                                null,
                                agent(
                                        "Create a web app. ",
                                        "FULLY_AUTONOMOUS",
                                        "[\"google_gmail\",\"mcp__s__send_mail\"]",
                                        "[]",
                                        100)),
                        service.assess(
                                TemplateKind.WORKFLOW,
                                "a",
                                "x",
                                List.of(),
                                null,
                                mapper.readTree(
                                        "{\"graph\":{\"nodes\":[{\"type\":\"tool\",\"data\":{\"config\":{\"toolName\":\"send_email\"}}}]},\"x\":\"password:"
                                                + " hunter2hunter2\"}")));
        for (TemplateReadinessReport r : reports) {
            for (Issue i :
                    java.util.stream.Stream.concat(r.blockers().stream(), r.advisories().stream())
                            .toList()) {
                assertThat(TemplateStandard.hasRule(i.code()))
                        .as("code %s documented", i.code())
                        .isTrue();
            }
        }
    }
}
