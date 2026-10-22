package com.agenticai.core.templates;

import com.agenticai.core.templates.dto.TemplateReadinessReport;
import com.agenticai.core.templates.dto.TemplateReadinessReport.Issue;
import com.agenticai.core.templates.dto.TemplateRequirement;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * The marketplace quality bar, and the single place that works out what an importer has to
 * activate.
 *
 * <p>Two jobs, both pure functions of the sanitised snapshot (no I/O, no tenant state), so they are
 * cheap to run on every read and trivially testable:
 *
 * <ol>
 *   <li>{@link #requirements} - which MCP servers, connections, knowledge base, sandbox, model key
 *       etc. the template needs. Derived at read time, so all templates (platform-seeded and
 *       community) report it with no migration.
 *   <li>{@link #assess} - the submit gate. Checks are grouped by the agentic-AI principle they
 *       protect: clear role and scope, least-privilege tools, human oversight of irreversible
 *       actions, safety guardrails, honest capability claims, data protection, bounded cost.
 *       Blockers stop a submission; advisories are guidance.
 * </ol>
 *
 * <p>Gates apply to <b>new submissions only</b>. Already-approved templates are never
 * auto-withdrawn by a rule change.
 */
@Service
public class TemplateReadinessService {

    /** The platform appends this block to every agent prompt; it is not author content. */
    static final String PLATFORM_BOILERPLATE_MARKER = "How you work:";

    static final int MIN_SUMMARY_CHARS = 60;
    static final int MIN_AUTHORED_PROMPT_CHARS = 200;
    static final int RECOMMENDED_TAGS = 3;
    static final int MIN_MAX_TOKENS = 512;

    private static final Pattern ROLE =
            Pattern.compile(
                    "\\b(you are|your (job|role|goal|task|purpose)|act as|your mission)\\b",
                    Pattern.CASE_INSENSITIVE);

    /** A prompt that asks the model to BUILD software is a task brief, not an agent persona. */
    private static final Pattern BUILD_BRIEF =
            Pattern.compile(
                    "\\b(create|build|develop|generate|design)\\b.{0,60}\\b(web ?site|web"
                            + " ?app(lication)?|landing page|mobile app|full[- ]stack|frontend|react"
                            + " app)\\b",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern HONESTY =
            Pattern.compile(
                    "(not sure|unsure|uncertain|don't know|do not know|never (invent|make"
                            + " up|fabricate)|do not (guess|invent)|cite|source|admit)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern SCOPE_LIMITS =
            Pattern.compile(
                    "(do(es)? not|don't|never|cannot|can't|won't|out of scope|only"
                            + " help|decline|refuse)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern HIGH_STAKES =
            Pattern.compile(
                    "\\b(medical|diagnos\\w*|legal"
                            + " advice|lawyer|tax|invest\\w*|prescri\\w*|clinical|insurance claim)\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern PROFESSIONAL_HANDOFF =
            Pattern.compile(
                    "(consult|qualified|licensed|professional|human review|escalat|hand ?off|not a"
                            + " substitute)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern CLAIMS_ACTIONS =
            Pattern.compile(
                    "\\b(sends?|emails?|books?|schedules?|posts?|publish\\w*|pays?|orders?|syncs?|integrat\\w*|automat\\w*|takes?"
                            + " action|manages? (your )?(inbox|calendar|files|tasks))\\b",
                    Pattern.CASE_INSENSITIVE);

    /**
     * The summary already admits the limit ("cannot send...", "advises only") - not an overclaim.
     */
    private static final Pattern ADMITS_LIMITS =
            Pattern.compile(
                    "(cannot|can't|can not|does not|doesn't|will not|won't|no tools|advis\\w+"
                            + " only|only advis\\w+|chat assistant)",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern SECRET =
            Pattern.compile(
                    "(sk-[A-Za-z0-9_-]{20,}|AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----|"
                            + "(?i:bearer)\\s+[A-Za-z0-9._-]{20,}|(?i:(api[_-]?key|secret|password|token))\\s*[:=]\\s*\\S{8,})");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    private static final Pattern ACTION_TOOL =
            Pattern.compile(
                    "(^|_)(send|create|update|delete|remove|post|publish|pay|book|schedule|write|approve|submit|cancel|"
                            + "transfer)(_|$)");

    private static final Set<String> GOOGLE_TOOLS_PREFIX = Set.of("google_");

    // ---------------------------------------------------------------- requirements

    /** What an importer must activate. Never throws; unknown shapes yield an empty list. */
    public List<TemplateRequirement> requirements(TemplateKind kind, JsonNode config) {
        return TemplateRequirements.requirements(kind, config);
    }

    // ---------------------------------------------------------------------- assess

    /**
     * @param userTags the tags the owner typed - derived tags are deliberately not counted, or they
     *     would mask an untagged listing
     */
    public TemplateReadinessReport assess(
            TemplateKind kind,
            String title,
            String summary,
            List<String> userTags,
            String category,
            JsonNode config) {
        List<Issue> blockers = new ArrayList<>();
        List<Issue> advisories = new ArrayList<>();
        List<TemplateRequirement> requirements = requirements(kind, config);

        assessListing(kind, title, summary, userTags, category, config, blockers, advisories);
        if (kind == TemplateKind.AGENT) {
            assessAgent(summary, config, blockers, advisories);
        } else {
            assessWorkflow(config, blockers, advisories);
        }
        return new TemplateReadinessReport(
                blockers.isEmpty(),
                List.copyOf(blockers),
                List.copyOf(advisories),
                requirements,
                TemplateRequirements.derivedTags(requirements));
    }

    private void assessListing(
            TemplateKind kind,
            String title,
            String summary,
            List<String> userTags,
            String category,
            JsonNode config,
            List<Issue> blockers,
            List<Issue> advisories) {
        String t = title == null ? "" : title.trim();
        String s = summary == null ? "" : summary.trim();
        int tagCount =
                userTags == null
                        ? 0
                        : (int) userTags.stream().filter(x -> x != null && !x.isBlank()).count();

        if (t.length() < 4) {
            blockers.add(
                    new Issue(
                            "TITLE_TOO_SHORT",
                            "Clear purpose",
                            "The title is too short to tell people what this is.",
                            "Use a descriptive title of 4+ characters."));
        }
        if (s.length() < MIN_SUMMARY_CHARS) {
            blockers.add(
                    new Issue(
                            "SUMMARY_TOO_SHORT",
                            "Honest capability claims",
                            "The summary is "
                                    + s.length()
                                    + " characters; people need at least "
                                    + MIN_SUMMARY_CHARS
                                    + " to judge whether this fits them.",
                            "Say what it does, who it is for, and what it cannot do."));
        }
        if (tagCount == 0) {
            blockers.add(
                    new Issue(
                            "NO_TAGS",
                            "Discoverability",
                            "No tags - nobody can find this by searching or filtering.",
                            "Add at least one tag; "
                                    + RECOMMENDED_TAGS
                                    + " is ideal (e.g. productivity, planning, personal)."));
        } else if (tagCount < RECOMMENDED_TAGS) {
            advisories.add(
                    new Issue(
                            "FEW_TAGS",
                            "Discoverability",
                            "Only " + tagCount + " tag(s).",
                            "Add up to " + RECOMMENDED_TAGS + " tags for better search."));
        }
        if (category == null || category.isBlank()) {
            advisories.add(
                    new Issue(
                            "NO_CATEGORY",
                            "Discoverability",
                            "No category chosen.",
                            "Pick the closest category so it appears in the right gallery"
                                    + " section."));
        }
        // Claim-vs-capability: promising actions with no way to act is the classic overstated
        // listing.
        if (kind == TemplateKind.AGENT
                && CLAIMS_ACTIONS.matcher(s).find()
                && !ADMITS_LIMITS.matcher(s).find()
                && TemplateRequirements.toolNames(kind, config).isEmpty()) {
            advisories.add(
                    new Issue(
                            "CLAIMS_ACTIONS_WITHOUT_TOOLS",
                            "Honest capability claims",
                            "The summary promises actions (sending, booking, automating...) but the"
                                    + " agent has no tools.",
                            "Either attach the tools it needs or describe it honestly as a chat"
                                    + " assistant that advises."));
        }
    }

    private void assessAgent(
            String summary, JsonNode config, List<Issue> blockers, List<Issue> advisories) {
        String prompt = config.path("systemPrompt").asText("");
        String authored = authoredPrompt(prompt);
        Set<String> tools = TemplateRequirements.toolNames(TemplateKind.AGENT, config);
        String tier = config.path("autonomyTier").asText("");
        boolean actionCapable = tools.stream().anyMatch(TemplateReadinessService::isActionTool);

        // -- Clear role and scope
        if (authored.length() < MIN_AUTHORED_PROMPT_CHARS) {
            blockers.add(
                    new Issue(
                            "PROMPT_TOO_SHORT",
                            "Clear role and scope",
                            "The system prompt has only "
                                    + authored.length()
                                    + " characters of your own instructions.",
                            "Describe the role, what it helps with, its tone, and its limits (200+"
                                    + " characters)."));
        }
        if (looksTruncated(authored)) {
            blockers.add(
                    new Issue(
                            "PROMPT_TRUNCATED",
                            "Clear role and scope",
                            "The system prompt ends mid-sentence - it was probably cut off when it"
                                    + " was pasted.",
                            "Re-save the agent with the complete prompt, ending on a finished"
                                    + " sentence."));
        }
        if (BUILD_BRIEF.matcher(head(authored, 500)).find()
                && !tools.contains("run_code")
                && !config.path("codeWorkspaceEnabled").asBoolean(false)) {
            blockers.add(
                    new Issue(
                            "PROMPT_IS_BUILD_BRIEF",
                            "Clear role and scope",
                            "The prompt reads like a brief to build a web app, not instructions for"
                                    + " an assistant.",
                            "Rewrite it as a persona: who the agent is, what it helps with, how it"
                                    + " should respond."));
        }
        if (!authored.isBlank() && !ROLE.matcher(authored).find()) {
            advisories.add(
                    new Issue(
                            "NO_ROLE_STATEMENT",
                            "Clear role and scope",
                            "The prompt never states the agent's role.",
                            "Open with \"You are ...\" and one line of purpose."));
        }
        if (!authored.isBlank() && !SCOPE_LIMITS.matcher(authored).find()) {
            advisories.add(
                    new Issue(
                            "NO_STATED_LIMITS",
                            "Clear role and scope",
                            "The prompt does not say what the agent will not do.",
                            "Add limits, e.g. what it declines or hands to a person."));
        }

        // -- Safety guardrails
        JsonNode guardrails = config.path("enabledGuardrails");
        if (!guardrails.isArray() || guardrails.isEmpty()) {
            blockers.add(
                    new Issue(
                            "NO_GUARDRAILS",
                            "Safety guardrails",
                            "No guardrails are enabled on this agent.",
                            "Turn on at least the jailbreak guardrail in the agent's safety"
                                    + " settings, then re-snapshot."));
        }

        // -- Human oversight of irreversible actions
        if (actionCapable && "FULLY_AUTONOMOUS".equals(tier)) {
            blockers.add(
                    new Issue(
                            "AUTONOMOUS_WITH_ACTION_TOOLS",
                            "Human oversight",
                            "Fully autonomous agents with tools that send, write or change things"
                                    + " cannot be shared.",
                            "Set the autonomy tier to Supervised so a person approves each"
                                    + " action."));
        } else if (actionCapable
                && !"SUPERVISED".equals(tier)
                && !tools.contains("request_human")) {
            advisories.add(
                    new Issue(
                            "ACTIONS_WITHOUT_APPROVAL",
                            "Human oversight",
                            "It has tools that can change things, but actions are not gated on"
                                    + " approval.",
                            "Use the Supervised tier, or add the request_human tool for risky"
                                    + " steps."));
        }

        // -- Least privilege
        if (tools.isEmpty()) {
            advisories.add(
                    new Issue(
                            "EMPTY_TOOL_ALLOWLIST",
                            "Least privilege",
                            "No tool allow-list. The platform treats an empty list as \"no"
                                    + " restriction\", so importers' agents may be able to use every"
                                    + " tool in their workspace.",
                            "List only the tools this agent needs; for a pure chat assistant, keep"
                                    + " the tier Supervised."));
        }

        // -- Honesty, safety wording, data protection
        if (!authored.isBlank()
                && !HONESTY.matcher(authored).find()
                && !HONESTY.matcher(prompt).find()) {
            advisories.add(
                    new Issue(
                            "NO_UNCERTAINTY_RULE",
                            "Honesty and transparency",
                            "Nothing tells the agent to admit uncertainty or avoid inventing"
                                    + " facts.",
                            "Add: say when you are unsure; never invent facts, sources or"
                                    + " numbers."));
        }
        if (HIGH_STAKES.matcher(authored + " " + (summary == null ? "" : summary)).find()
                && !PROFESSIONAL_HANDOFF.matcher(authored).find()) {
            advisories.add(
                    new Issue(
                            "HIGH_STAKES_NO_HANDOFF",
                            "Human oversight",
                            "The topic is medical, legal, tax or financial but the prompt has no"
                                    + " hand-off to a professional.",
                            "Add a rule to point the user to a qualified professional and not"
                                    + " present answers as advice."));
        }
        if (SECRET.matcher(prompt).find()) {
            blockers.add(
                    new Issue(
                            "SECRET_IN_PROMPT",
                            "Data protection",
                            "The prompt contains something that looks like an API key, token or"
                                    + " password.",
                            "Remove it and rotate the credential - templates are public."));
        }
        if (EMAIL.matcher(authored).find()) {
            advisories.add(
                    new Issue(
                            "EMAIL_IN_PROMPT",
                            "Data protection",
                            "The prompt contains an email address; templates are public.",
                            "Remove personal contact details."));
        }

        // -- Bounded cost
        if (config.path("maxTokens").asInt(0) > 0
                && config.path("maxTokens").asInt() < MIN_MAX_TOKENS) {
            advisories.add(
                    new Issue(
                            "LOW_MAX_TOKENS",
                            "Reliability",
                            "Replies are capped at "
                                    + config.path("maxTokens").asInt()
                                    + " tokens, so longer answers will be cut off.",
                            "Raise the maximum to at least " + MIN_MAX_TOKENS + " tokens."));
        }
    }

    private void assessWorkflow(JsonNode config, List<Issue> blockers, List<Issue> advisories) {
        JsonNode nodes = config.path("graph").path("nodes");
        boolean hasTrigger = false;
        boolean hasOutput = false;
        boolean hasApproval = false;
        boolean actionTool = false;
        if (nodes.isArray()) {
            for (JsonNode n : nodes) {
                String type = n.path("type").asText("");
                hasTrigger |= "trigger".equals(type);
                hasOutput |= "output".equals(type);
                hasApproval |= "approval".equals(type);
                if ("tool".equals(type)) {
                    actionTool |=
                            isActionTool(n.path("data").path("config").path("toolName").asText(""));
                }
            }
        }
        if (!hasTrigger) {
            blockers.add(
                    new Issue(
                            "NO_TRIGGER",
                            "Clear role and scope",
                            "The workflow has no trigger node, so nothing starts it.",
                            "Add a trigger (manual, schedule, webhook or channel)."));
        }
        if (!hasOutput) {
            blockers.add(
                    new Issue(
                            "NO_OUTPUT",
                            "Clear role and scope",
                            "The workflow has no output node, so results go nowhere.",
                            "Add an output node."));
        }
        String description = config.path("description").asText("");
        if (description.trim().length() < 40) {
            advisories.add(
                    new Issue(
                            "WORKFLOW_DESCRIPTION_SHORT",
                            "Honest capability claims",
                            "The workflow description is very short.",
                            "Explain the steps and what the run produces."));
        }
        if (actionTool && !hasApproval) {
            advisories.add(
                    new Issue(
                            "WORKFLOW_ACTIONS_WITHOUT_APPROVAL",
                            "Human oversight",
                            "A tool node can change things but the workflow has no approval step.",
                            "Add an approval node before the step that changes something."));
        }
        if (SECRET.matcher(config.toString()).find()) {
            blockers.add(
                    new Issue(
                            "SECRET_IN_WORKFLOW",
                            "Data protection",
                            "The workflow contains something that looks like an API key, token or"
                                    + " password.",
                            "Remove it and rotate the credential - templates are public."));
        }
    }

    // --------------------------------------------------------------------- helpers

    static boolean isActionTool(String tool) {
        if (tool == null || tool.isBlank()) {
            return false;
        }
        String lower = tool.toLowerCase(Locale.ROOT);
        if (lower.equals("run_code") || GOOGLE_TOOLS_PREFIX.stream().anyMatch(lower::startsWith)) {
            return true;
        }
        String leaf =
                lower.startsWith("mcp__") ? lower.substring(lower.lastIndexOf("__") + 2) : lower;
        return ACTION_TOOL.matcher(leaf).find();
    }

    private static String authoredPrompt(String prompt) {
        if (prompt == null) {
            return "";
        }
        int marker = prompt.indexOf(PLATFORM_BOILERPLATE_MARKER);
        return (marker >= 0 ? prompt.substring(0, marker) : prompt).trim();
    }

    private static boolean looksTruncated(String authored) {
        if (authored.length() < 40) {
            return false;
        }
        char last = authored.charAt(authored.length() - 1);
        return Character.isLetterOrDigit(last)
                || last == ','
                || last == '-'
                || last == '('
                || last == '/';
    }

    private static String head(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
