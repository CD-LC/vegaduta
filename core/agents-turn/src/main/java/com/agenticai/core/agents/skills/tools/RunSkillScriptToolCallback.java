package com.agenticai.core.agents.skills.tools;

import com.agenticai.core.agents.skills.SkillDefinition;
import com.agenticai.core.agents.skills.SkillService;
import com.agenticai.core.agents.tools.code.ToolSandboxClient;
import com.agenticai.core.agents.tools.code.ToolSandboxProperties;
import com.agenticai.core.agents.tools.code.dto.SandboxExecuteRequest;
import com.agenticai.core.agents.tools.code.dto.SandboxExecuteResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Level 3, executable half: runs a script bundled with a skill and returns only its output - the
 * script's source never enters the context window, which is what makes a bundled script cheaper and
 * more reliable than having the model regenerate equivalent code each time.
 *
 * <p>Execution is delegated to the same isolated {@code tool-sandbox} microservice that runs tenant
 * custom-code tools (no network, hard time/memory ceilings, never core's JVM), so a skill script
 * gets exactly that blast radius and no more. Two deliberate restrictions on top:
 *
 * <ul>
 *   <li><b>No secrets.</b> A custom-code tool can be granted vault secrets by a workspace admin; a
 *       skill script never is. A skill is importable from a zip someone found online, and the model
 *       - not a person - decides when it runs.
 *   <li>The script must follow the sandbox's program contract: Python defining {@code run(input)}
 *       (or the Java equivalent). That is the sandbox's only entry point; there is no shell.
 * </ul>
 *
 * <p>Only offered when the sandbox is deployed ({@code tool-sandbox.enabled=true}); without it,
 * skills still work fully at Levels 1-2 and for readable Level 3 files.
 */
public class RunSkillScriptToolCallback implements ToolCallback {

    public static final String NAME = "run_skill_script";
    private static final Logger log = LoggerFactory.getLogger(RunSkillScriptToolCallback.class);
    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    private static final int MAX_OUTPUT_CHARS = 20_000;

    private final SkillService skillService;
    private final ToolSandboxClient sandbox;
    private final ToolSandboxProperties properties;
    private final UUID agentId;

    public RunSkillScriptToolCallback(
            SkillService skillService,
            ToolSandboxClient sandbox,
            ToolSandboxProperties properties,
            UUID agentId) {
        this.skillService = skillService;
        this.sandbox = sandbox;
        this.properties = properties;
        this.agentId = agentId;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(NAME)
                .description(
                        "Runs a script bundled with a skill you have already loaded (a .py or .java file listed by "
                                + "load_skill) in an isolated sandbox and returns its result. Use it only when the skill's "
                                + "instructions tell you to run that script. The sandbox has no network access.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "skill": {"type": "string", "description": "The skill name."},
                        "path": {"type": "string", "description": "The script path exactly as listed by load_skill, e.g. scripts/validate.py."},
                        "input": {"type": "object", "description": "JSON object passed to the script's run(input) function. See the skill's instructions for the fields it expects."}
                    },
                    "required": ["skill", "path"]
                }
                """)
                .build();
    }

    @Override
    public String call(String toolInput) {
        try {
            JsonNode args =
                    MAPPER.readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
            String skillName = args.path("skill").asString("").trim();
            String path = args.path("path").asString("").trim().replace('\\', '/');
            if (skillName.isEmpty() || path.isEmpty()) {
                return error("Both skill and path are required.");
            }
            String language = languageFor(path);
            if (language == null) {
                return error(
                        "Only .py and .java skill scripts can be run. Read other files with read_skill_file.");
            }
            Optional<SkillDefinition> found = skillService.loadForAgent(agentId, skillName);
            if (found.isEmpty()) {
                return error("There is no skill named \"" + skillName + "\" available to you.");
            }
            String source = found.get().files().get(path);
            if (source == null) {
                return error("Skill \"" + skillName + "\" has no file \"" + path + "\".");
            }
            if (source.getBytes(StandardCharsets.UTF_8).length > properties.getMaxSourceBytes()) {
                return error(
                        "That script is larger than the sandbox accepts ("
                                + properties.getMaxSourceBytes()
                                + " bytes).");
            }
            JsonNode input =
                    args.path("input").isObject() ? args.path("input") : MAPPER.createObjectNode();

            SandboxExecuteResponse response =
                    sandbox.execute(
                            new SandboxExecuteRequest(
                                    language,
                                    "skill_script",
                                    source,
                                    input,
                                    properties.getDefaultTimeoutMs(),
                                    properties.getDefaultMemoryMb()));
            if (response == null) {
                return error("The code sandbox returned an empty response.");
            }
            if (!response.ok()) {
                ObjectNode node = MAPPER.createObjectNode();
                node.put(
                        "error",
                        response.error() == null || response.error().isBlank()
                                ? "The script failed."
                                : response.error());
                if (response.errorType() != null) {
                    node.put("errorType", response.errorType());
                }
                if (response.stderr() != null && !response.stderr().isBlank()) {
                    node.put("stderr", cap(response.stderr()));
                }
                return node.toString();
            }
            ObjectNode node = MAPPER.createObjectNode();
            node.put("ok", true);
            node.set("result", response.result());
            if (response.stdout() != null && !response.stdout().isBlank()) {
                node.put("stdout", cap(response.stdout()));
            }
            return cap(node.toString());
        } catch (Exception e) {
            log.warn("run_skill_script failed for agent {}: {}", agentId, e.getMessage());
            return error(
                    "The script could not be run: "
                            + (e.getMessage() == null
                                    ? e.getClass().getSimpleName()
                                    : e.getMessage()));
        }
    }

    private static String languageFor(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".py")) {
            return "python";
        }
        if (lower.endsWith(".java")) {
            return "java";
        }
        return null;
    }

    private static String cap(String text) {
        return text.length() <= MAX_OUTPUT_CHARS
                ? text
                : text.substring(0, MAX_OUTPUT_CHARS) + "... [truncated]";
    }

    private static String error(String message) {
        return MAPPER.createObjectNode().put("error", message).toString();
    }
}
