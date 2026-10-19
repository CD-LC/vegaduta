package com.agenticai.core.agents.skills.tools;

import com.agenticai.core.agents.skills.SkillService;
import com.agenticai.core.agents.skills.SkillValidationException;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Self-improvement, second half: where {@link ProposeSkillToolCallback} lets an agent write down a
 * brand-new method, this tool lets it fix a skill that already exists - the "the csv-import skill's
 * date parsing step is wrong, here is the corrected version" case. Without it an agent can only
 * ever ADD skills; the closed learning loop (notice a gap in a skill -> improve the skill -> next
 * run is better) needs both verbs.
 *
 * <p>Same review gate as {@code propose_skill}, enforced structurally rather than by instruction:
 * {@link SkillService#proposeImprovement} stages the rewrite in the skill row's {@code pending_*}
 * columns (migration V243) and the live description/instructions every agent's prompt advertises
 * stay byte-identical until a workspace admin applies or discards the staged change on the Skills
 * page. A hijacked conversation can therefore at worst leave a parked draft - it can never reach
 * another agent's context on its own.
 */
public class ImproveSkillToolCallback implements ToolCallback {

    public static final String NAME = "improve_skill";

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final SkillService skillService;
    private final UUID agentId;

    public ImproveSkillToolCallback(SkillService skillService, UUID agentId) {
        this.skillService = skillService;
        this.agentId = agentId;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(NAME)
                .description(
                        "Proposes an improvement to an EXISTING custom skill when you found its instructions wrong, "
                                + "incomplete or out of date while using it. Send the complete improved instructions, not a "
                                + "diff. The live skill keeps running unchanged: your version is staged for a workspace admin "
                                + "to review and apply, so tell the user it won't take effect until then. For a method that "
                                + "doesn't exist yet, use propose_skill instead.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "name": {"type": "string", "description": "The exact name of the existing custom skill to improve."},
                        "description": {"type": "string", "description": "The improved description - what the skill does AND when to use it, third person."},
                        "instructions": {"type": "string", "description": "The complete improved SKILL.md body - a full replacement, not a patch."},
                        "rationale": {"type": "string", "description": "What was wrong or missing and why this version is better - shown to the reviewing admin."}
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
        String rationale;
        try {
            JsonNode args =
                    MAPPER.readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
            name = args.path("name").asText("").trim();
            description = args.path("description").asText("").trim();
            instructions = args.path("instructions").asText("").trim();
            rationale = args.path("rationale").asText("").trim();
        } catch (Exception e) {
            return "Could not stage the improvement: the tool input was not valid JSON.";
        }
        if (name.isEmpty() || description.isEmpty() || instructions.isEmpty()) {
            return "Could not stage the improvement: name, description and instructions are all required.";
        }
        try {
            skillService.proposeImprovement(
                    name, description, instructions, null, rationale, "agent:" + agentId);
            return "Staged an improved version of \""
                    + name
                    + "\" for review. The live skill is unchanged until "
                    + "a workspace admin applies it on the Skills page. Tell the user this.";
        } catch (SkillValidationException e) {
            return "Could not stage the improvement: " + e.getMessage();
        } catch (RuntimeException e) {
            return "Could not stage the improvement right now.";
        }
    }
}
