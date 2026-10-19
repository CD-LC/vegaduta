package com.agenticai.core.templates;

import java.util.List;

/**
 * THE published template standard - the single source of truth for what a marketplace template must
 * satisfy. Three consumers read it, so they cannot drift apart: the rule engine ({@link
 * TemplateReadinessService}) emits issues by {@link Rule#code()}, the AI reviewer ({@link
 * TemplateAiReviewService}) is handed the same rules as its checklist, and the submit screen
 * renders the whole guide to the author via {@code GET /api/templates/gallery/standard}. A unit
 * test fails if an emitted issue code is missing from here.
 */
public final class TemplateStandard {

    public static final int VERSION = 1;

    public enum Severity {
        BLOCKER,
        ADVISORY
    }

    /** Who checks it: the deterministic rule engine, or the AI reviewer (semantic checks). */
    public enum Checker {
        RULES,
        AI
    }

    /**
     * @param code stable id used in issues and tests
     * @param principle the agentic-AI principle it protects
     * @param severity BLOCKER stops the submission; ADVISORY is guidance
     * @param checker who evaluates it
     * @param requirement the rule in one sentence, written for the author
     * @param howToFix the concrete action that satisfies it
     */
    public record Rule(
            String code,
            String principle,
            Severity severity,
            Checker checker,
            String requirement,
            String howToFix) {}

    /** The whole guide, as returned to the UI. */
    public record Guide(
            int version,
            String intro,
            List<String> steps,
            List<Principle> principles,
            List<Rule> rules,
            String examplePrompt,
            String exampleSummary) {}

    public record Principle(String name, String why) {}

    private static final List<Principle> PRINCIPLES =
            List.of(
                    new Principle(
                            "Clear role and scope",
                            "An agent with a vague or contradictory brief behaves unpredictably."
                                    + " Say who it is, what it does and what it won't do."),
                    new Principle(
                            "Least privilege",
                            "Give the agent only the tools it needs. Every extra tool is something"
                                    + " it can misuse or be tricked into using."),
                    new Principle(
                            "Human oversight",
                            "Anything that sends, pays, deletes or books must wait for a person's"
                                    + " approval - agents propose, people decide."),
                    new Principle(
                            "Safety guardrails",
                            "Guardrails stay on so an importer cannot be talked out of the agent's"
                                    + " safety limits."),
                    new Principle(
                            "Honesty and transparency",
                            "The agent admits uncertainty and never invents facts, sources or"
                                    + " numbers. The listing never overstates it."),
                    new Principle(
                            "Honest capability claims",
                            "The listing says what the template really does. Promising actions it"
                                    + " cannot take wastes importers' time."),
                    new Principle(
                            "Data protection",
                            "Templates are public. No keys, tokens, passwords or personal details -"
                                    + " and no collecting more data than needed."),
                    new Principle(
                            "Reliability",
                            "Sensible limits so answers are not cut off and cost stays bounded."),
                    new Principle(
                            "Discoverability",
                            "A title, summary, category and tags so the right people find it."));

    private static final List<Rule> RULES =
            List.of(
                    // ---- listing
                    rule(
                            "TITLE_TOO_SHORT",
                            "Discoverability",
                            Severity.BLOCKER,
                            Checker.RULES,
                            "Title of at least 4 characters that says what it is.",
                            "Use a descriptive title, e.g. \"Daily Planner & Goal Coach\"."),
                    rule(
                            "SUMMARY_TOO_SHORT",
                            "Honest capability claims",
                            Severity.BLOCKER,
                            Checker.RULES,
                            "Summary of at least 60 characters: what it does, who it is for, what"
                                    + " it cannot do.",
                            "Write 2-3 sentences. Say it is a chat assistant if it has no tools."),
                    rule(
                            "NO_TAGS",
                            "Discoverability",
                            Severity.BLOCKER,
                            Checker.RULES,
                            "At least one tag (three is ideal).",
                            "Add tags such as productivity, planning, personal."),
                    rule(
                            "FEW_TAGS",
                            "Discoverability",
                            Severity.ADVISORY,
                            Checker.RULES,
                            "Three tags for good search coverage.",
                            "Add up to three tags."),
                    rule(
                            "NO_CATEGORY",
                            "Discoverability",
                            Severity.ADVISORY,
                            Checker.RULES,
                            "A category so it appears in the right gallery section.",
                            "Pick the closest category."),
                    rule(
                            "CLAIMS_ACTIONS_WITHOUT_TOOLS",
                            "Honest capability claims",
                            Severity.ADVISORY,
                            Checker.RULES,
                            "If the summary promises actions (send, book, automate), the agent"
                                    + " needs the tools for them.",
                            "Attach the tools, or describe it honestly as an advisor."),
                    // ---- agent: role and scope
                    rule(
                            "PROMPT_TOO_SHORT",
                            "Clear role and scope",
                            Severity.BLOCKER,
                            Checker.RULES,
                            "At least 200 characters of your own instructions (the platform's"
                                    + " standard \"How you work\" block does not count).",
                            "Describe the role, what it helps with, tone and limits."),
                    rule(
                            "PROMPT_TRUNCATED",
                            "Clear role and scope",
                            Severity.BLOCKER,
                            Checker.RULES,
                            "The prompt ends on a finished sentence.",
                            "Re-save the agent with the complete prompt."),
                    rule(
                            "PROMPT_IS_BUILD_BRIEF",
                            "Clear role and scope",
                            Severity.BLOCKER,
                            Checker.RULES,
                            "The prompt is instructions for an assistant, not a brief to build a"
                                    + " website or app.",
                            "Rewrite it as a persona: who the agent is and how it should respond."),
                    rule(
                            "NO_ROLE_STATEMENT",
                            "Clear role and scope",
                            Severity.ADVISORY,
                            Checker.RULES,
                            "The prompt states the agent's role.",
                            "Open with \"You are ...\"."),
                    rule(
                            "NO_STATED_LIMITS",
                            "Clear role and scope",
                            Severity.ADVISORY,
                            Checker.RULES,
                            "The prompt says what the agent will not do.",
                            "Add a short \"You do not ...\" section."),
                    // ---- agent: safety
                    rule(
                            "NO_GUARDRAILS",
                            "Safety guardrails",
                            Severity.BLOCKER,
                            Checker.RULES,
                            "At least one guardrail is enabled.",
                            "Turn on the jailbreak guardrail in the agent's safety settings."),
                    rule(
                            "AUTONOMOUS_WITH_ACTION_TOOLS",
                            "Human oversight",
                            Severity.BLOCKER,
                            Checker.RULES,
                            "A fully autonomous agent may not hold tools that send, write or change"
                                    + " things.",
                            "Set the autonomy tier to Supervised."),
                    rule(
                            "ACTIONS_WITHOUT_APPROVAL",
                            "Human oversight",
                            Severity.ADVISORY,
                            Checker.RULES,
                            "Tools that change things are gated on a person's approval.",
                            "Use the Supervised tier or add request_human."),
                    rule(
                            "EMPTY_TOOL_ALLOWLIST",
                            "Least privilege",
                            Severity.ADVISORY,
                            Checker.RULES,
                            "A tool allow-list. An empty list means \"no restriction\" for"
                                    + " importers.",
                            "List only the tools it needs."),
                    rule(
                            "NO_UNCERTAINTY_RULE",
                            "Honesty and transparency",
                            Severity.ADVISORY,
                            Checker.RULES,
                            "The prompt tells the agent to admit uncertainty and not invent facts.",
                            "Add: \"Say when you are unsure; never invent facts, sources or"
                                    + " numbers.\""),
                    rule(
                            "HIGH_STAKES_NO_HANDOFF",
                            "Human oversight",
                            Severity.ADVISORY,
                            Checker.RULES,
                            "Medical, legal, tax or financial topics hand off to a qualified"
                                    + " professional.",
                            "Add a rule that it is not a substitute for professional advice."),
                    rule(
                            "SECRET_IN_PROMPT",
                            "Data protection",
                            Severity.BLOCKER,
                            Checker.RULES,
                            "No API keys, tokens or passwords in the prompt.",
                            "Remove it and rotate the credential."),
                    rule(
                            "EMAIL_IN_PROMPT",
                            "Data protection",
                            Severity.ADVISORY,
                            Checker.RULES,
                            "No personal contact details in a public template.",
                            "Remove them."),
                    rule(
                            "LOW_MAX_TOKENS",
                            "Reliability",
                            Severity.ADVISORY,
                            Checker.RULES,
                            "Replies are allowed at least 512 tokens.",
                            "Raise the maximum tokens."),
                    // ---- workflow
                    rule(
                            "NO_TRIGGER",
                            "Clear role and scope",
                            Severity.BLOCKER,
                            Checker.RULES,
                            "A workflow has a trigger node.",
                            "Add a trigger."),
                    rule(
                            "NO_OUTPUT",
                            "Clear role and scope",
                            Severity.BLOCKER,
                            Checker.RULES,
                            "A workflow has an output node.",
                            "Add an output node."),
                    rule(
                            "WORKFLOW_DESCRIPTION_SHORT",
                            "Honest capability claims",
                            Severity.ADVISORY,
                            Checker.RULES,
                            "A workflow description of at least 40 characters.",
                            "Explain the steps and the result."),
                    rule(
                            "WORKFLOW_ACTIONS_WITHOUT_APPROVAL",
                            "Human oversight",
                            Severity.ADVISORY,
                            Checker.RULES,
                            "Steps that change things sit behind an approval node.",
                            "Add an approval node first."),
                    rule(
                            "SECRET_IN_WORKFLOW",
                            "Data protection",
                            Severity.BLOCKER,
                            Checker.RULES,
                            "No keys, tokens or passwords anywhere in the workflow.",
                            "Remove it and rotate the credential."),
                    // ---- semantic checks (AI reviewer)
                    rule(
                            "AI_PURPOSE_MISMATCH",
                            "Honest capability claims",
                            Severity.BLOCKER,
                            Checker.AI,
                            "The prompt actually matches the title and summary.",
                            "Make the prompt do what the listing says."),
                    rule(
                            "AI_UNSAFE_INSTRUCTIONS",
                            "Safety guardrails",
                            Severity.BLOCKER,
                            Checker.AI,
                            "No instructions to deceive, bypass safeguards, harvest data or cause"
                                    + " harm.",
                            "Remove them."),
                    rule(
                            "AI_MISLEADING_CLAIMS",
                            "Honest capability claims",
                            Severity.ADVISORY,
                            Checker.AI,
                            "The listing does not overstate what the design can do.",
                            "Tone the claims down to what it does."),
                    rule(
                            "AI_PROMPT_QUALITY",
                            "Clear role and scope",
                            Severity.ADVISORY,
                            Checker.AI,
                            "The prompt is coherent, specific and free of contradictions.",
                            "Tighten it and remove contradictions."),
                    rule(
                            "AI_PRIVACY_RISK",
                            "Data protection",
                            Severity.ADVISORY,
                            Checker.AI,
                            "The agent does not ask for more personal data than the task needs.",
                            "Ask only for what you need."),
                    rule(
                            "AI_MISSING_SETUP_NOTES",
                            "Honest capability claims",
                            Severity.ADVISORY,
                            Checker.AI,
                            "If importers must connect something, the summary or prompt says so.",
                            "Mention what must be connected."));

    private static final List<String> STEPS =
            List.of(
                    "Build and test the agent or workflow in your workspace until it behaves the"
                            + " way you want.",
                    "Open it and choose \"Share as template\". The platform snapshots the design -"
                            + " never keys, customer data or your workspace identity.",
                    "Write the listing: a clear title, an honest summary (what it does, who it is"
                            + " for, what it cannot do), a category and up to three tags.",
                    "Run the check. Fix every blocker; advisories are optional but recommended.",
                    "Submit. An AI review checks your template against this same standard, then it"
                            + " goes to the platform team, who approve or return it with a reason.",
                    "Anything your template needs importers to connect (MCP servers, Google, a"
                        + " knowledge base) is detected from your design and shown on the listing"
                        + " automatically.");

    public static final String EXAMPLE_SUMMARY =
            "A planning and coaching chat assistant. Tell it what you want to achieve and it helps"
                    + " you break goals into steps, prioritise your day and keep you on track. It"
                    + " advises only - it cannot send messages or edit your calendar.";

    public static final String EXAMPLE_PROMPT =
            String.join(
                    "\n",
                    "You are LifePilot, a calm, practical planning and goal-coaching assistant.",
                    "",
                    "What you help with:",
                    "- Turn a goal into 3-7 concrete next steps with rough timings.",
                    "- Prioritise a day's tasks by urgency and impact, and say why.",
                    "- Check in on progress and adjust the plan without judgement.",
                    "",
                    "How you respond: friendly, brief, and specific. Lead with the plan, then one"
                            + " question if something is missing.",
                    "",
                    "Limits: you do not give medical, legal or financial advice - suggest a"
                            + " qualified professional instead. ",
                    "You cannot access the user's calendar, email or files; say so plainly rather"
                            + " than pretending. ",
                    "If you are unsure, say so. Never invent facts, deadlines or sources.",
                    "Before suggesting anything that cannot be undone, ask the user to confirm.");

    private TemplateStandard() {}

    public static List<Rule> rules() {
        return RULES;
    }

    public static boolean hasRule(String code) {
        return RULES.stream().anyMatch(r -> r.code().equals(code));
    }

    public static Guide guide() {
        return new Guide(
                VERSION,
                "Templates are public and other people run them with their own data. This is the"
                    + " standard every template is checked against - first by automatic rules, then"
                    + " by an AI reviewer, then by the platform team.",
                STEPS,
                PRINCIPLES,
                RULES,
                EXAMPLE_PROMPT,
                EXAMPLE_SUMMARY);
    }

    /** Rules rendered as a compact checklist for the AI reviewer's prompt. */
    static String aiChecklist() {
        StringBuilder sb = new StringBuilder();
        for (Rule r : RULES) {
            if (r.checker() == Checker.AI) {
                sb.append("- ")
                        .append(r.code())
                        .append(" [")
                        .append(r.severity())
                        .append("] (")
                        .append(r.principle())
                        .append("): ")
                        .append(r.requirement())
                        .append('\n');
            }
        }
        return sb.toString();
    }

    private static Rule rule(
            String code, String principle, Severity s, Checker c, String requirement, String fix) {
        return new Rule(code, principle, s, c, requirement, fix);
    }
}
