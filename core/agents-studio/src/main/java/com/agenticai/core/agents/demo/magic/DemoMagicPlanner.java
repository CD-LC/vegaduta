package com.agenticai.core.agents.demo.magic;

import com.agenticai.core.mcp.registry.OfficialMcpRegistryClient;
import com.agenticai.core.mcp.registry.RegistrySearchResponse;
import com.agenticai.core.mcp.registry.RegistrySearchResult;
import com.agenticai.core.providers.PlatformHostedLlmActiveResponse;
import com.agenticai.core.providers.PlatformHostedLlmProviderResponse;
import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The planning half of Magic Studio: turns one anonymous-visitor sentence
 * ("I want a poster for my bakery", "make a video about this person" + a
 * link) into the full behind-the-scenes plan the landing page reveals -
 * classified intent, the platform model that will actually run it, REAL
 * matches from the official MCP registry, the system prompt of the agent
 * that gets created for the workflow, a mermaid diagram of the pipeline, and
 * the input-form spec the published run page will render.
 *
 * <p><b>What is real and what is honest-about-not-being-real:</b>
 * <ul>
 *   <li>Intent classification is deterministic keyword scoring - cheap,
 *       instant, and free of an LLM dependency on the one endpoint that runs
 *       before any spend controls. The LLM does the WORK at execution time;
 *       it does not need to be burned on routing.</li>
 *   <li>MCP discovery calls {@link OfficialMcpRegistryClient#search} - the
 *       same cache-backed walk of registry.modelcontextprotocol.io the
 *       authenticated Tools screen uses. The tools shown are real registry
 *       entries; {@code free} means the entry needs zero credentials.</li>
 *   <li>Model selection reads the live platform-hosted pool
 *       ({@link PlatformHostedLlmProviderService#getActiveSummary}), so the
 *       plan names the model the turn will genuinely route to.</li>
 *   <li>VIDEO is planned honestly: the plan says outright that no free video
 *       MCP server is wired into this deployment yet, and the execution
 *       degrades to storyboard + script (which the platform CAN do) instead
 *       of pretending to render footage.</li>
 * </ul>
 */
@Component
public class DemoMagicPlanner {

    /** Wire-names, persisted on the workflow row - keep stable. */
    public enum MagicIntent {
        IMAGE, VIDEO, RESEARCH, WRITE, GENERAL
    }

    public record PlannedTool(
        String name, String description, boolean free, boolean runnable, String repositoryUrl
    ) {
    }

    /**
     * @param fallbacks the rest of the admin-configured pool chain, in the
     *     exact priority order execution's own failover walks it ("label ·
     *     model" strings, capped at 4) - shown so "model selection" on the
     *     reveal is the routing that actually happens, not a single name
     *     picked for display.
     */
    public record PlannedModel(String label, String model, boolean available, List<String> fallbacks) {
    }

    public record FormField(String id, String label, String type, boolean required, String placeholder) {
    }

    public record PlanStep(String id, String label, String detail) {
    }

    public record MagicPlan(
        String title,
        MagicIntent intent,
        PlannedModel model,
        String agentName,
        String agentSystemPrompt,
        List<PlannedTool> tools,
        boolean registryUnavailable,
        List<PlanStep> steps,
        String mermaid,
        List<FormField> formSpec,
        String honestyNote
    ) {
    }

    private static final Pattern VIDEO_HINT = Pattern.compile(
        "\\b(video|reel|shorts?|animation|animate|clip|film)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_HINT = Pattern.compile(
        "\\b(image|picture|photo|poster|logo|banner|drawing|artwork|art|illustration|thumbnail|wallpaper)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern RESEARCH_HINT = Pattern.compile(
        "\\b(research|analy[sz]e|report|summar(y|ise|ize)|scrape|profile|about (this|the) (person|company|site)|"
            + "find out|investigate|compare)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern WRITE_HINT = Pattern.compile(
        "\\b(write|draft|caption|post|email|letter|blog|description|script|copy|bio|message)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_HINT = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    private final OfficialMcpRegistryClient registryClient;
    private final PlatformHostedLlmProviderService hostedPool;

    public DemoMagicPlanner(
        OfficialMcpRegistryClient registryClient,
        PlatformHostedLlmProviderService hostedPool
    ) {
        this.registryClient = registryClient;
        this.hostedPool = hostedPool;
    }

    public MagicPlan plan(String prompt, String link, String imageModel) {
        MagicIntent intent = classify(prompt, link);
        PlannedModel model = selectModel(intent, imageModel);
        ToolDiscovery discovery = discoverTools(intent, prompt);
        String agentName = agentNameFor(intent);
        String agentPrompt = agentPromptFor(intent, prompt);
        List<PlanStep> steps = stepsFor(intent, model, discovery.tools(), link != null && !link.isBlank());
        return new MagicPlan(
            titleFor(intent, prompt),
            intent,
            model,
            agentName,
            agentPrompt,
            discovery.tools(),
            discovery.unavailable(),
            steps,
            mermaidFor(intent, model, discovery.tools(), agentName, link != null && !link.isBlank()),
            formSpecFor(intent),
            honestyNoteFor(intent)
        );
    }

    // ------------------------------------------------------------------
    // Intent + naming
    // ------------------------------------------------------------------

    MagicIntent classify(String prompt, String link) {
        String text = prompt == null ? "" : prompt;
        if (VIDEO_HINT.matcher(text).find()) {
            return MagicIntent.VIDEO;
        }
        if (IMAGE_HINT.matcher(text).find()) {
            return MagicIntent.IMAGE;
        }
        boolean hasLink = (link != null && !link.isBlank()) || URL_HINT.matcher(text).find();
        if (RESEARCH_HINT.matcher(text).find() || hasLink) {
            return MagicIntent.RESEARCH;
        }
        if (WRITE_HINT.matcher(text).find()) {
            return MagicIntent.WRITE;
        }
        return MagicIntent.GENERAL;
    }

    private static String titleFor(MagicIntent intent, String prompt) {
        String base = switch (intent) {
            case IMAGE -> "Image generator";
            case VIDEO -> "Video storyboard builder";
            case RESEARCH -> "Research assistant";
            case WRITE -> "Writing assistant";
            case GENERAL -> "Custom assistant";
        };
        String cleaned = prompt == null ? "" : prompt.strip().replaceAll("\\s+", " ");
        if (cleaned.length() > 60) {
            cleaned = cleaned.substring(0, 57) + "...";
        }
        return cleaned.isEmpty() ? base : base + " — " + cleaned;
    }

    private static String agentNameFor(MagicIntent intent) {
        return switch (intent) {
            case IMAGE -> "Visual creator agent";
            case VIDEO -> "Storyboard director agent";
            case RESEARCH -> "Research analyst agent";
            case WRITE -> "Copywriter agent";
            case GENERAL -> "Workflow assistant agent";
        };
    }

    // ------------------------------------------------------------------
    // Model + tools
    // ------------------------------------------------------------------

    /**
     * "It should select the right model" - and the honest version of that on
     * a FREE/demo tenant is the pool's OWN admin-configured routing: text
     * turns run against the priority-ordered chain of enabled hosted rows
     * ({@code resolveEnabledChain}'s ordering, lowest priority first) with
     * automatic failover on 429/timeout/5xx, and image intents route to the
     * dedicated platform image model instead. Which models are in that chain,
     * in what order, and whether the pool is on at all, are all live platform
     * -maintenance controls (the Platform LLM admin screen) - so the plan
     * reads the chain fresh per request and displays exactly what execution
     * will walk, primary plus fallbacks, rather than a hand-picked name.
     */
    private PlannedModel selectModel(MagicIntent intent, String imageModel) {
        if (intent == MagicIntent.IMAGE && imageModel != null && !imageModel.isBlank()) {
            return new PlannedModel("Platform image pool", imageModel, true, List.of());
        }
        try {
            PlatformHostedLlmActiveResponse active = hostedPool.getActiveSummary();
            boolean available = active != null && active.available();
            List<PlatformHostedLlmProviderResponse> chain = hostedPool.list().stream()
                .filter(PlatformHostedLlmProviderResponse::enabled)
                .sorted(Comparator.comparingInt(PlatformHostedLlmProviderResponse::priority))
                .toList();
            if (available && !chain.isEmpty()) {
                PlatformHostedLlmProviderResponse primary = chain.get(0);
                List<String> fallbacks = chain.stream()
                    .skip(1)
                    .limit(4)
                    .map(row -> (row.label() == null ? "pool row" : row.label())
                        + (row.model() == null ? "" : " · " + row.model()))
                    .toList();
                return new PlannedModel(
                    primary.label() == null ? "Platform hosted pool" : primary.label(),
                    primary.model() == null ? "platform-pool" : primary.model(),
                    true,
                    fallbacks
                );
            }
            if (available) {
                // Master switch on but the row listing raced empty - report
                // the summary's single-row view rather than nothing.
                return new PlannedModel(
                    active.label() == null ? "Platform hosted pool" : active.label(),
                    active.model() == null ? "platform-pool" : active.model(),
                    true,
                    List.of()
                );
            }
        } catch (RuntimeException e) {
            // Fall through to the honest-unavailable shape below.
        }
        return new PlannedModel("Platform hosted pool", "platform-pool", false, List.of());
    }

    private record ToolDiscovery(List<PlannedTool> tools, boolean unavailable) {
    }

    /**
     * Real registry discovery. The query is intent-shaped rather than the
     * raw prompt because the registry matcher scores names/descriptions -
     * "image generation" finds imagegen servers; a whole sentence about a
     * bakery poster does not.
     */
    private ToolDiscovery discoverTools(MagicIntent intent, String prompt) {
        String query = switch (intent) {
            case IMAGE -> "image generation";
            case VIDEO -> "video";
            case RESEARCH -> "web search fetch";
            case WRITE -> "content writing";
            case GENERAL -> firstKeywords(prompt);
        };
        try {
            RegistrySearchResponse response = registryClient.search(query, null);
            if (response == null || response.unavailable()) {
                return new ToolDiscovery(List.of(), true);
            }
            List<PlannedTool> tools = new ArrayList<>();
            for (RegistrySearchResult result : response.results()) {
                if (tools.size() >= 5) {
                    break;
                }
                boolean free = result.envVars() == null || result.envVars().isEmpty();
                tools.add(new PlannedTool(
                    result.displayName() != null && !result.displayName().isBlank()
                        ? result.displayName() : result.name(),
                    clip(result.description(), 180),
                    free,
                    result.runnable(),
                    result.repositoryUrl()
                ));
            }
            return new ToolDiscovery(List.copyOf(tools), false);
        } catch (RuntimeException e) {
            return new ToolDiscovery(List.of(), true);
        }
    }

    private static String firstKeywords(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "assistant";
        }
        String[] words = prompt.strip().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            String cleaned = word.replaceAll("[^\\p{L}\\p{N}]", "");
            if (cleaned.length() < 4) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(cleaned.toLowerCase(Locale.ROOT));
            if (sb.length() > 40) {
                break;
            }
        }
        return sb.isEmpty() ? "assistant" : sb.toString();
    }

    // ------------------------------------------------------------------
    // Agent prompt
    // ------------------------------------------------------------------

    private static String agentPromptFor(MagicIntent intent, String prompt) {
        String goal = prompt == null ? "" : prompt.strip();
        String role = switch (intent) {
            case IMAGE -> """
                You are a visual creator agent. Your job is to take a person's plain-language \
                request and produce the best possible single image prompt for an image model: \
                subject, composition, style, lighting, mood, and any text that must appear. \
                When asked to describe what you would generate, answer with the refined prompt \
                and a one-line rationale.""";
            case VIDEO -> """
                You are a storyboard director agent. Video rendering is not available in this \
                sandbox, and you never pretend it is. Instead you deliver what a director hands \
                a production team: a scene-by-scene storyboard (5-8 scenes, each with visual \
                description, on-screen text, and duration) plus a narration script, ready to \
                shoot or to feed a video tool.""";
            case RESEARCH -> """
                You are a research analyst agent. You work ONLY from the material provided in \
                the conversation (including any [Public page content] block) and from general \
                knowledge you are confident about. You never invent facts about a person or \
                company; when the provided material does not answer something, you say so. \
                Deliver findings as a short structured brief: who/what, key facts, and 3 \
                takeaways.""";
            case WRITE -> """
                You are a copywriter agent for small businesses in India. You write tight, \
                warm, concrete copy - posts, captions, replies, descriptions - in the language \
                the person writes in (Indian languages, English, or a mix are all normal). \
                Deliver the finished text first, variations second, no preamble.""";
            case GENERAL -> """
                You are a capable general assistant agent created for one specific workflow. \
                Be concrete and short; prefer doing the task over describing it; answer in the \
                language the person writes in.""";
        };
        return role + """


            The workflow you were created for: "%s"

            Sandbox honesty rules (non-negotiable):
            - This is a no-signup sandbox on the VegaDuta platform. If asked to send, post, \
            publish, or pay for anything for real, say plainly that those are connected after \
            registering, and produce the ready-to-use draft instead.
            - Never invent private facts about real people or businesses.
            """.formatted(goal.isEmpty() ? "general assistance" : clip(goal, 300));
    }

    // ------------------------------------------------------------------
    // Steps + mermaid + form
    // ------------------------------------------------------------------

    private static List<PlanStep> stepsFor(
        MagicIntent intent, PlannedModel model, List<PlannedTool> tools, boolean hasLink
    ) {
        List<PlanStep> steps = new ArrayList<>();
        steps.add(new PlanStep("intent", "Intent analysis",
            "Classified the request as " + intent.name() + " from the wording of the prompt."));
        steps.add(new PlanStep("discovery", "MCP tool discovery",
            tools.isEmpty()
                ? "Searched the official MCP registry; no directly usable servers surfaced for this intent."
                : "Searched the official MCP registry and matched " + tools.size()
                    + " candidate server(s); zero-credential ones can be enabled without any setup."));
        steps.add(new PlanStep("model", "Model selection",
            model.available()
                ? "Routed to the platform-hosted pool: " + model.label() + " (" + model.model() + ")"
                    + (model.fallbacks().isEmpty()
                        ? "."
                        : ", with automatic failover to " + String.join(", ", model.fallbacks())
                            + " - the chain and its order are platform-maintenance controls.")
                : "Platform pool currently unavailable - execution will degrade to a labelled sample."));
        steps.add(new PlanStep("agent", "Agent creation",
            "A dedicated workflow agent is created in the sandbox workspace with the system prompt shown here."));
        steps.add(new PlanStep("knowledge", "Knowledge base",
            hasLink
                ? "The public page behind the link is fetched (public hosts only), cleaned to text, and injected into the agent's context."
                : "Optional - a public link in the form is fetched (public hosts only) and injected into the agent's context."));
        steps.add(new PlanStep("execute", "Execution",
            switch (intent) {
                case IMAGE -> "The refined prompt is sent to the platform image model; the result is stored and served back.";
                case VIDEO -> "The agent produces a storyboard + narration script (no free video MCP server is wired yet - stated, not hidden).";
                default -> "The agent runs the turn through the platform pool with budgets and moderation applied.";
            }));
        return List.copyOf(steps);
    }

    /**
     * The behind-the-scenes diagram, as mermaid source - rendered by the
     * frontend as its own SVG pipeline AND offered as copyable mermaid text,
     * so the "show me how it worked" ask is answered in both forms.
     */
    private static String mermaidFor(
        MagicIntent intent, PlannedModel model, List<PlannedTool> tools, String agentName, boolean hasLink
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("flowchart TD\n");
        sb.append("    U[\"Visitor input\"] --> I[\"Intent analysis: ").append(intent.name()).append("\"]\n");
        sb.append("    I --> D[\"MCP registry discovery: ")
            .append(tools.isEmpty() ? "no match" : tools.size() + " server(s)").append("\"]\n");
        sb.append("    I --> M[\"Model selection: ").append(escape(model.model()));
        if (!model.fallbacks().isEmpty()) {
            sb.append(" (+").append(model.fallbacks().size()).append(" failover)");
        }
        sb.append("\"]\n");
        sb.append("    D --> A[\"").append(escape(agentName)).append("\"]\n");
        sb.append("    M --> A\n");
        if (hasLink) {
            sb.append("    K[\"Knowledge: public page scrape\"] --> A\n");
        } else {
            sb.append("    K[\"Knowledge: optional link / uploads\"] -.-> A\n");
        }
        switch (intent) {
            case IMAGE -> sb.append("    A --> X[\"Image generation\"]\n");
            case VIDEO -> sb.append("    A --> X[\"Storyboard + script (no free video MCP yet)\"]\n");
            default -> sb.append("    A --> X[\"Agent turn via platform pool\"]\n");
        }
        sb.append("    X --> R[\"Result on the public run page\"]\n");
        return sb.toString();
    }

    private static List<FormField> formSpecFor(MagicIntent intent) {
        return switch (intent) {
            case IMAGE -> List.of(
                new FormField("prompt", "Describe the image you want", "textarea", true,
                    "A warm poster for a Diwali sale at a small bakery..."));
            case VIDEO -> List.of(
                new FormField("prompt", "What should the video be about?", "textarea", true,
                    "A 30-second intro video about..."),
                new FormField("link", "Public link for context (optional)", "url", false,
                    "https://..."));
            case RESEARCH -> List.of(
                new FormField("prompt", "What do you want to know?", "textarea", true,
                    "Summarise what this page says about..."),
                new FormField("link", "Public link to analyse", "url", false,
                    "https://..."));
            case WRITE -> List.of(
                new FormField("prompt", "What should be written?", "textarea", true,
                    "An Instagram caption for..."));
            case GENERAL -> List.of(
                new FormField("prompt", "Your request", "textarea", true, "Ask anything..."));
        };
    }

    private static String honestyNoteFor(MagicIntent intent) {
        return switch (intent) {
            case VIDEO -> "No free, zero-credential video-rendering MCP server is wired into this deployment "
                + "yet, so this workflow delivers a storyboard and script rather than rendered footage - "
                + "the registry matches shown are what could be connected on a full account.";
            case IMAGE -> "Image generation uses the platform's hosted image model with the sandbox's "
                + "own allowance (2 images per sandbox by default).";
            default -> null;
        };
    }

    // ------------------------------------------------------------------

    private static String clip(String text, int max) {
        if (text == null) {
            return null;
        }
        String cleaned = text.strip();
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max - 3) + "...";
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("\"", "'");
    }
}
