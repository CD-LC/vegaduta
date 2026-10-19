package com.agenticai.core.agents.skills.tools;

import com.agenticai.core.agents.skills.SkillDefinition;
import com.agenticai.core.agents.skills.SkillService;
import com.agenticai.core.agents.skills.SkillValidationException;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Self-improvement: after working out how to do a task well, the agent can write the method down as
 * a new Agent Skill so the next run starts from it instead of rediscovering it.
 *
 * <p>A skill's description is advertised in the system prompt of every agent in the workspace, so
 * an agent must not be able to publish one on its own. {@link SkillService#propose} stores it
 * DISABLED; it does nothing until a workspace admin reads it on the Skills page and turns it on.
 * That review step is the whole safety model here - a poisoned conversation can at worst leave a
 * disabled draft for a human to delete.
 */
public class ProposeSkillToolCallback implements ToolCallback {

    public static final String NAME = "propose_skill";

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final SkillService skillService;
    private final UUID agentId;

    public ProposeSkillToolCallback(SkillService skillService, UUID agentId) {
        this.skillService = skillService;
        this.agentId = agentId;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(NAME)
                .description(
                        "Saves a reusable method you just worked out as a new skill, so future conversations can load it "
                                + "instead of rediscovering it. Use it only after finishing a multi-step task that is likely to "
                                + "come up again, or when the user asks you to remember how to do something. Write general "
                                + "instructions, never the user's private data, names, secrets or one-off details. The skill is "
                                + "saved as a draft: a workspace admin must review and enable it before any agent can use it, "
                                + "so tell the user that.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "name": {"type": "string", "description": "Lowercase letters, digits and hyphens, e.g. monthly-gst-summary. At most 64 characters."},
                        "description": {"type": "string", "description": "What the skill does AND when to use it, in the third person. This is what future agents match against."},
                        "instructions": {"type": "string", "description": "The method in Markdown: steps, rules, output format, pitfalls you hit and how to avoid them."}
                    },
                    "required": ["name", "description", "instructions"]
                }
                """)
                .build();
    }

    @Override
    public String call(String toolInput) {
        String name;
        String description;
        String instructions;
        try {
            JsonNode args =
                    MAPPER.readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
            name = args.path("name").asText("").trim();
            description = args.path("description").asText("").trim();
            instructions = args.path("instructions").asText("").trim();
        } catch (Exception e) {
            return "Could not save the skill: the tool input was not valid JSON.";
        }
        if (name.isEmpty() || description.isEmpty() || instructions.isEmpty()) {
            return "Could not save the skill: name, description and instructions are all required.";
        }
        try {
            SkillDefinition saved =
                    skillService.propose(name, description, instructions, "agent:" + agentId);
            return "Saved \""
                    + saved.name()
                    + "\" as a draft skill. It is switched off until a workspace admin reviews "
                    + "and enables it on the Skills page. Tell the user this.";
        } catch (SkillValidationException e) {
            return "Could not save the skill: " + e.getMessage();
        } catch (RuntimeException e) {
            return "Could not save the skill right now.";
        }
    }
}
