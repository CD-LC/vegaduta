package com.agenticai.core.agents.skills.tools;

import com.agenticai.core.agents.skills.SkillDefinition;
import com.agenticai.core.agents.skills.SkillService;
import com.agenticai.core.json.JsonMappers;
import java.util.Optional;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Level 3 of progressive disclosure - reads ONE bundled file of a skill. A skill can ship dozens of
 * reference files; each costs zero tokens until the instructions send the model to it.
 *
 * <p>Path safety is by construction: the path is only ever used as a key into the skill's own
 * already-loaded file map, never resolved against a filesystem, so there is nothing for "../" to
 * traverse.
 */
public class ReadSkillFileToolCallback implements ToolCallback {

    public static final String NAME = "read_skill_file";

    /** Keeps one oversized reference file from flooding the context window. */
    static final int MAX_OUTPUT_CHARS = 60_000;

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final SkillService skillService;
    private final UUID agentId;

    public ReadSkillFileToolCallback(SkillService skillService, UUID agentId) {
        this.skillService = skillService;
        this.agentId = agentId;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(NAME)
                .description(
                        "Reads one bundled file of a skill you have already loaded with load_skill (for example a reference "
                                + "guide or template that the skill's instructions point to). Only read a file when the skill's "
                                + "instructions call for it.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "skill": {"type": "string", "description": "The skill name."},
                        "path": {"type": "string", "description": "The file path exactly as listed by load_skill, e.g. REFERENCE.md or templates/brief.md."}
                    },
                    "required": ["skill", "path"]
                }
                """)
                .build();
    }

    @Override
    public String call(String toolInput) {
        String skillName;
        String path;
        try {
            JsonNode args =
                    MAPPER.readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
            skillName = args.path("skill").asString("").trim();
            path = args.path("path").asString("").trim().replace('\\', '/');
        } catch (Exception e) {
            return "Could not read the file: the tool input was not valid JSON.";
        }
        if (skillName.isEmpty() || path.isEmpty()) {
            return "Could not read the file: both skill and path are required.";
        }
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        Optional<SkillDefinition> found;
        try {
            found = skillService.loadForAgent(agentId, skillName);
        } catch (RuntimeException e) {
            return "Could not read from skill \"" + skillName + "\" right now.";
        }
        if (found.isEmpty()) {
            try {
                Optional<String> paused = skillService.blockedReason(agentId, skillName);
                if (paused != null && paused.isPresent()) {
                    return paused.get();
                }
            } catch (RuntimeException e) {
                // fall through to the generic answer
            }
            return "There is no skill named \"" + skillName + "\" available to you.";
        }
        SkillDefinition skill = found.get();
        String content = skill.files().get(path);
        if (content == null) {
            return "Skill \""
                    + skillName
                    + "\" has no file \""
                    + path
                    + "\". Its files are: "
                    + (skill.files().isEmpty()
                            ? "(none)"
                            : String.join(", ", skill.files().keySet().stream().sorted().toList()))
                    + ".";
        }
        if (content.length() > MAX_OUTPUT_CHARS) {
            return content.substring(0, MAX_OUTPUT_CHARS)
                    + "\n\n[File truncated: showing the first "
                    + MAX_OUTPUT_CHARS
                    + " of "
                    + content.length()
                    + " characters.]";
        }
        return content;
    }
}
