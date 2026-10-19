package com.agenticai.core.brief.model;

import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Java mirror of {@code docs/plans/brief-schema.v1.json}: one declarative manifest for a whole
 * solution. Refs ({@code a1}, {@code w1}, {@code t1}, {@code m1}, {@code k1}, {@code s1},
 * {@code p1}, {@code c1}) are the only cross-references - never tenant ids - and secrets are
 * declared by NAME, never valued.
 *
 * <p>Enum-like fields (rung, reasoningDepth, kinds, scopes) are deliberately plain strings so a
 * bad value reaches {@code BriefValidator} and comes back as a readable, sectioned error rather
 * than a Jackson deserialisation failure with no path. Every list is normalised to non-null in
 * the compact constructors; {@code evalSet} and {@code rehearsal} stay nullable because the
 * schema does not require them. Unknown JSON properties are ignored on read (the schema says
 * {@code additionalProperties:false}; the validator, not Jackson, is where strictness lives so a
 * stray model-invented field is dropped rather than blowing up a whole plan).
 *
 * <p>Workflow graphs reuse the existing {@link WorkflowGraphDto} shape verbatim, with agent /
 * knowledge / tool ids in node config being refs that the apply engine resolves.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Brief(
    int briefVersion,
    String title,
    String goal,
    String locale,
    List<Clarification> clarifications,
    Trust trust,
    List<AgentSpec> agents,
    List<ProcedureSpec> procedures,
    List<WorkflowSpec> workflows,
    List<ToolSpec> tools,
    List<McpServerSpec> mcpServers,
    List<KnowledgeSpec> knowledge,
    List<SecretSpec> secrets,
    List<ChannelSpec> channels,
    EvalSet evalSet,
    Rehearsal rehearsal,
    Narrative narrative
) {

    public static final int CURRENT_VERSION = 1;

    public Brief {
        clarifications = nn(clarifications);
        agents = nn(agents);
        procedures = nn(procedures);
        workflows = nn(workflows);
        tools = nn(tools);
        mcpServers = nn(mcpServers);
        knowledge = nn(knowledge);
        secrets = nn(secrets);
        channels = nn(channels);
    }

    /** True when the planner is asking questions instead of proposing (everything else may be partial). */
    public boolean isClarificationOnly() {
        return !clarifications.isEmpty();
    }

    private static <T> List<T> nn(List<T> list) {
        return list == null ? List.of() : list;
    }

    // ------------------------------------------------------------------ sections

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Clarification(String question, List<String> options) {
        public Clarification {
            options = options == null ? List.of() : options;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Trust(String rung, List<String> irreversible, List<String> humanMustDecide) {
        public Trust {
            irreversible = irreversible == null ? List.of() : irreversible;
            humanMustDecide = humanMustDecide == null ? List.of() : humanMustDecide;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Generation(Double temperature, Double topP, Integer maxTokens) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Guardrail(String kind, Map<String, Object> config) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentSpec(
        String ref,
        String name,
        String description,
        String systemPrompt,
        String reasoningDepth,
        String tuningMode,
        Generation generation,
        List<String> toolAllowList,
        List<String> knowledgeRefs,
        List<String> procedureRefs,
        List<Guardrail> guardrails,
        String edgeRoutingPolicy,
        Boolean shareMemoryWithTenant
    ) {
        public AgentSpec {
            toolAllowList = toolAllowList == null ? List.of() : toolAllowList;
            knowledgeRefs = knowledgeRefs == null ? List.of() : knowledgeRefs;
            procedureRefs = procedureRefs == null ? List.of() : procedureRefs;
            guardrails = guardrails == null ? List.of() : guardrails;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProcedureSpec(String ref, String name, List<String> steps, String source) {
        public ProcedureSpec {
            steps = steps == null ? List.of() : steps;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Trigger(String kind, String cron, String channelRef) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkflowSpec(
        String ref, String name, WorkflowGraphDto graph, List<Trigger> triggers, String sampleInput
    ) {
        public WorkflowSpec {
            triggers = triggers == null ? List.of() : triggers;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolAuth(String type, String secretRef) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolSpec(
        String ref, String kind, String name, String specUrl, ToolAuth auth, String codeDescription
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EnvBinding(String secretRef) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record McpServerSpec(String ref, String registryId, String name, Map<String, EnvBinding> env) {
        public McpServerSpec {
            env = env == null ? Map.of() : env;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record KnowledgeSource(String kind, String url, String connectorType, String hint) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record KnowledgeSpec(String ref, String name, List<KnowledgeSource> sources) {
        public KnowledgeSpec {
            sources = sources == null ? List.of() : sources;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SecretSpec(String ref, String name, String scope, String why, String obtainAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChannelSpec(String ref, String kind, String bindTo, Boolean optional) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EvalCase(String input, String expected, String rubric) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Gate(Double minScore) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EvalSet(String targetAgent, List<EvalCase> cases, Gate gate) {
        public EvalSet {
            cases = cases == null ? List.of() : cases;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Persona(String name, List<String> traits, String opening) {
        public Persona {
            traits = traits == null ? List.of() : traits;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Rehearsal(List<Persona> personas) {
        public Rehearsal {
            personas = personas == null ? List.of() : personas;
        }

        /**
         * Used when a brief lists no personas of its own, so rehearsal never dead-ends: one ordinary user and one
         * who pushes back. Goal-shaped personas are always preferred - these are the floor, not the target.
         */
        public static List<Persona> fallbackPersonas() {
            return List.of(
                new Persona("A typical user", List.of("busy", "practical"), "Hi - I need some help."),
                new Persona("A difficult user", List.of("impatient", "skeptical", "pushy"), "I do not have time for this."));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NodeNarrative(String why, String tune, String proof) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Narrative(String whyAgentic, Map<String, NodeNarrative> perNode) {
        public Narrative {
            perNode = perNode == null ? Map.of() : perNode;
        }
    }
}
