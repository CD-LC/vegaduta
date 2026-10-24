package com.agenticai.core.agents.skills.tools;

import com.agenticai.core.agents.skills.SkillDefinition;
import com.agenticai.core.agents.skills.SkillService;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptSlot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Level 2 of progressive disclosure - the platform's equivalent of "cat my-skill/SKILL.md". Until
 * the model calls this, a skill costs only its one-line description; this is the moment its
 * instructions enter the context window.
 *
 * <p>Never throws: every failure is a short sentence the model can act on (or relay), the same
 * contract as every other built-in tool callback.
 */
public class LoadSkillToolCallback implements ToolCallback {

    public static final String NAME = "load_skill";

    /** Registry key of the framing line of a workspace-authored skill ({@code prompts/en/toolresult.skill.workspace_framing.md}). */
    public static final String WORKSPACE_FRAMING_KEY = "toolresult.skill.workspace_framing";

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final SkillService skillService;
    private final UUID agentId;
    private final boolean scriptsRunnable;
    private final PromptSlot prompts = new PromptSlot();

    public LoadSkillToolCallback(SkillService skillService, UUID agentId, boolean scriptsRunnable) {
        this.skillService = skillService;
        this.agentId = agentId;
        this.scriptsRunnable = scriptsRunnable;
    }

    /** Reads the framing line from {@code resolver} for this agent and its tenant; null keeps the shipped wording. */
    public LoadSkillToolCallback withPrompts(PromptResolver resolver) {
        prompts.use(resolver);
        return this;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(NAME)
                .description(
                        "Loads a skill's full instructions. Call this with a skill name from the 'Available skills' list in "
                                + "your instructions as soon as the user's request matches that skill's description, BEFORE doing "
                                + "the task, then follow the returned instructions.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "name": {"type": "string", "description": "The skill name, exactly as listed under 'Available skills'."}
                    },
                    "required": ["name"]
                }
                """)
                .build();
    }

    @Override
    public String call(String toolInput) {
        String name;
        try {
            JsonNode args =
                    MAPPER.readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
            name = args.path("name").asText("").trim();
        } catch (Exception e) {
            return "Could not load a skill: the tool input was not valid JSON.";
        }
        if (name.isEmpty()) {
            return "Could not load a skill: no skill name was given." + availableHint();
        }
        Optional<SkillDefinition> found;
        try {
            found = skillService.loadForAgent(agentId, name);
        } catch (RuntimeException e) {
            return "Could not load skill \"" + name + "\" right now. Continue without it.";
        }
        if (found.isEmpty()) {
            // Supply-chain gate (app.skills.supply-chain.enforce): say a skill is paused for review
            // rather than pretending it does not exist. Empty unless enforcing.
            Optional<String> paused = pausedReason(name);
            if (paused.isPresent()) {
                return paused.get();
            }
            return "There is no skill named \"" + name + "\" available to you." + availableHint();
        }
        SkillDefinition skill = found.get();
        if (!skill.builtIn()) {
            try {
                skillService.recordUse(skill.name()); // usage counter (V590)
            } catch (RuntimeException ignored) {
                // A counter must never cost the agent the skill it asked for (a failed bulk
                // UPDATE can also surface as a rollback-only commit error from the proxy).
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# Skill: ").append(skill.name()).append("\n\n");
        if (!skill.builtIn()) {
            // A custom skill is workspace-authored content, not platform policy. Say so, so its
            // text is weighed as task guidance and cannot present itself as a higher authority.
            sb.append(prompts.text(WORKSPACE_FRAMING_KEY, agentId)).append("\n\n");
        }
        sb.append(skill.body()).append('\n');
        if (!skill.files().isEmpty()) {
            sb.append(
                            "\n---\nBundled files for this skill (not loaded yet). Read one with read_skill_file")
                    .append("(skill=\"")
                    .append(skill.name())
                    .append("\", path=...) only when the instructions above call for it");
            if (scriptsRunnable) {
                sb.append("; run a script with run_skill_script(skill, path, input)");
            }
            sb.append(":\n");
            skill.files().keySet().stream()
                    .sorted()
                    .forEach(path -> sb.append("- ").append(path).append('\n'));
        }
        return sb.toString();
    }

    private Optional<String> pausedReason(String name) {
        try {
            Optional<String> reason = skillService.blockedReason(agentId, name);
            return reason == null ? Optional.empty() : reason;
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private String availableHint() {
        try {
            String names =
                    skillService.metadataForAgent(agentId).stream()
                            .map(SkillService.SkillMetadata::name)
                            .collect(Collectors.joining(", "));
            return names.isEmpty() ? "" : " Available skills: " + names + ".";
        } catch (RuntimeException e) {
            return "";
        }
    }
}
