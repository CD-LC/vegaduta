package com.agenticai.core.agents.job.progress;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Lets a running job say what it is doing. The "is this thing alive or hung?"
 * feature, in one tool.
 *
 * <h2>Wired through {@code extraTools}, and that is the whole point</h2>
 * {@code AgentChatService.chat(...)} already takes an {@code extraTools}
 * parameter. The job runner passes this callback there, per job, which is why
 * this entire feature needs NO edit to {@code AgentToolProvider} (a file a
 * parallel session owns), no edit to {@code AgentChatService}, and no change
 * whatsoever to the tool list a normal chat turn resolves - a chat turn's
 * resolved tools are byte-identical to what they were before this class existed.
 *
 * <h2>It MUST be wrapped in {@code TenantContextPropagatingToolCallback}</h2>
 * {@code extraTools} are added RAW - they do not go through {@code
 * AgentToolProvider#guardAndLog}, which is what normally applies that wrapper to
 * the resolved list. Unwrapped, {@link #call} would run on a Reactor scheduler
 * thread with no {@code TenantContext}, {@code TenantSchemaResolver} would fall
 * back to the empty {@code public} schema, and every progress note would be
 * inserted into a table nobody reads while the job's own trace stayed
 * permanently empty. That is precisely the bug {@code
 * TenantContextPropagatingToolCallback}'s javadoc exists to document. The
 * context must be captured BEFORE the Flux is subscribed:
 *
 * <pre>{@code
 * new TenantContextPropagatingToolCallback(
 *     new ReportProgressToolCallback(progressService, jobId),
 *     TenantContext.current().orElse(null))
 * }</pre>
 *
 * <h2>Deliberately NOT wrapped in {@code LoggingToolCallback}</h2>
 * Stating the omission because otherwise it reads as an oversight. Every note
 * this tool writes already lands in {@code agent_job_progress}, and the job's
 * trace view merges that table with {@code tool_call_log}. Wrapping it in the
 * logging decorator would put every note in BOTH tables, so the merged trace
 * would show each one twice - once as a PROGRESS entry and once as a TOOL_CALL
 * entry whose input is the note text. Nor is the guardrail wrapper appropriate:
 * there is no approval policy that could sensibly gate "the agent described its
 * own progress", and gating it would let a SUPERVISED agent park itself awaiting
 * approval to say what it was doing.
 *
 * <p>{@link #call} never throws. A tool that can kill the turn it is narrating
 * would be worse than no tool at all - and {@link AgentJobProgressService} is
 * best-effort by contract anyway, so there is nothing to propagate.
 */
public class ReportProgressToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ReportProgressToolCallback.class);

    /**
     * A private static mapper rather than an injected one, matching {@code
     * RememberFactToolCallback} and every other native {@code ToolCallback} in
     * this codebase. These callbacks are constructed per turn by hand, not by
     * Spring, and the only JSON they touch is their own tiny fixed-shape input
     * and result - none of the application {@code ObjectMapper}'s configuration
     * is relevant to either, so threading it through every construction site
     * would be ceremony with no behavioural difference.
     */
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final AgentJobProgressService progressService;
    private final UUID jobId;

    public ReportProgressToolCallback(AgentJobProgressService progressService, UUID jobId) {
        this.progressService = progressService;
        this.jobId = jobId;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("report_progress")
            .description(
                // Written to earn calls at the RIGHT moments rather than as many
                // as possible. A model that narrates every thought produces a
                // trail that is as useless as an empty one, so the description
                // names the milestone shape explicitly and says what not to do.
                "Tells the person who started this job what you are working on right now. Use it when you "
                    + "begin a distinct step of a long task, when you finish one, and before anything that "
                    + "will take a while - the job may run for many minutes and this is the only thing they "
                    + "can see while it does. Write one short sentence about what is actually happening "
                    + "(\"Reconciling 340 invoices against the bank statement\"), not a description of your "
                    + "own reasoning. Do not call this more than once for the same step."
            )
            .inputSchema("""
                {
                    "type": "object",
                    "properties": {
                        "note": {
                            "type": "string",
                            "description": "One short sentence describing what you are doing right now (e.g. \\"Fetching last month's invoices from Drive\\")"
                        },
                        "percent": {
                            "type": "integer",
                            "minimum": 0,
                            "maximum": 100,
                            "description": "Optional. How far through the whole job you are, 0-100. Only include this if you genuinely know - omit it rather than guessing."
                        }
                    },
                    "required": ["note"]
                }
                """)
            .build();
    }

    @Override
    public String call(String toolInput) {
        try {
            JsonNode args = MAPPER.readTree(toolInput == null ? "{}" : toolInput);
            String note = args.path("note").asText(null);
            if (note == null || note.isBlank()) {
                // Told plainly, with the fix, because the model will retry and a
                // vague error just produces the same malformed call again.
                return json(Map.of("recorded", false, "error", "note is required and must be a non-empty string"));
            }

            // Absent and non-numeric both mean "no percentage", not zero.
            // Reporting 0% for a job that is halfway done would be a lie the
            // operator has no way to detect.
            Integer percent = args.path("percent").isIntegralNumber() ? args.path("percent").asInt() : null;

            boolean recorded = progressService.tryRecord(jobId, note, percent, ProgressSource.AGENT);
            if (recorded) {
                return json(Map.of("recorded", true));
            }
            // Honest, and deliberately so: telling the model "recorded" when the
            // note was dropped at the per-job cap or the dedupe window teaches it
            // that calling more often works, which is the behaviour those guards
            // exist to stop.
            return json(Map.of(
                "recorded", false,
                "reason", "This note was not stored - it repeated the previous one, or this job has reached its "
                    + "progress-note limit. Carry on with the task; do not retry this call."
            ));

        } catch (Exception e) {
            // A malformed tool input is the model's mistake and is common; it is
            // not a platform fault worth an ERROR line.
            log.debug("report_progress received input it could not parse for job {}", jobId, e);
            return json(Map.of("recorded", false, "error", "input must be a JSON object with a \"note\" string"));
        }
    }

    /**
     * Same bug class {@code KnowledgeSearchToolCallback} and {@code
     * RememberFactToolCallback} both document: a hand-built {@code "{\"error\":
     * \"...\"}"} string breaks the moment the message contains a raw quote.
     */
    private String json(Map<String, Object> body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception impossible) {
            return "{\"recorded\": false}";
        }
    }
}
