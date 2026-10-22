package com.agenticai.core.agents.job.output;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobApprovalPark;
import com.agenticai.core.agents.job.AgentJobExecution;
import com.agenticai.core.agents.job.AgentJobOrigin;
import com.agenticai.core.agents.job.AgentJobOutputContract;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.AgentJobRepository;
import com.agenticai.core.agents.job.progress.AgentJobProgressService;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

/**
 * Fixtures for the structured-output tests.
 *
 * <p><b>Why this duplicates a little of {@code AgentJobTestSupport}.</b> That class is
 * package-private in {@code com.agenticai.core.agents.job} and these tests live in the {@code
 * .output} sub-package, so it is simply not visible here - and widening it is an edit to a file
 * this feature does not own. The duplication is deliberately kept to the three things these tests
 * actually need (a job with a schema, a repository that behaves like the real conditional UPDATEs,
 * and an execution wired to a specific output contract) rather than being a second copy of the
 * whole helper.
 *
 * <p>Plain Mockito, no Spring context and no database - the standing rule for {@code core/agents}
 * tests, since a full {@code mvn test} in {@code core/app} boots a real Postgres.
 */
final class StructuredOutputJobFixtures {

    static final String TENANT = "t_test";
    static final String REQUESTED_BY = "alice@example.com";
    static final String GOAL = "Summarise yesterday's support tickets";

    /**
     * A small, self-contained schema of exactly the shape a caller writes when they intend to
     * consume the result programmatically. {@code additionalProperties: false} is present on
     * purpose: it is what makes a "close enough" answer a violation, which is the case the repair
     * loop exists for.
     */
    static final String SCHEMA =
            """
        {
          "type": "object",
          "properties": {
            "summary": { "type": "string" },
            "count": { "type": "integer" }
          },
          "required": ["summary", "count"],
          "additionalProperties": false
        }
        """;

    static final String CONFORMING = "{\"summary\":\"three tickets, all billing\",\"count\":3}";

    /**
     * Deliberately brace-free. {@link StructuredResultCoercer} recovers a JSON object embedded in
     * prose as a last resort, so prose containing braces would test the recovery heuristic rather
     * than the violation path.
     */
    static final String PROSE = "I read yesterday's tickets - there were three, all about billing.";

    private StructuredOutputJobFixtures() {}

    static AgentJob queuedJob(String resultSchemaJson) {
        return AgentJob.queued(
                UUID.randomUUID(),
                TENANT,
                UUID.randomUUID(),
                GOAL,
                // subjectKey == requestedBy is the budget-escape guard; a synthetic
                // per-job subject would mint a fresh daily ceiling per submission.
                REQUESTED_BY,
                REQUESTED_BY,
                Set.of("ROLE_user"),
                AgentJobOrigin.API,
                null,
                60,
                1,
                15,
                resultSchemaJson);
    }

    /**
     * A repository mock that behaves like the real one for the calls an execution makes. {@code
     * claim} really does move the in-memory entity to RUNNING, because in production it is a
     * conditional UPDATE and the worker's next read comes back RUNNING - a mock that returned a
     * rowcount without touching the entity would leave it QUEUED and make the later {@code
     * succeed()}/{@code fail()} throw for a reason unrelated to what is under test.
     */
    static AgentJobRepository repositoryFor(AgentJob job) {
        AgentJobRepository repository = mock(AgentJobRepository.class);
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        when(repository.claim(eq(job.getId()), anyString(), any(Instant.class), any(Instant.class)))
                .thenAnswer(
                        invocation -> {
                            job.markRunning(invocation.getArgument(1), invocation.getArgument(2));
                            return 1;
                        });
        when(repository.save(any(AgentJob.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.isCancelRequested(job.getId())).thenReturn(Optional.of(false));
        return repository;
    }

    static AgentJobProperties properties() {
        AgentJobProperties properties = new AgentJobProperties();
        properties.setMaxTurns(1);
        return properties;
    }

    /** The real coercer stack - validator, prompt renderer and coercer, all genuine. */
    static StructuredResultCoercer coercer(AgentJobProperties properties) {
        ObjectMapper objectMapper = com.agenticai.core.json.JsonMappers.plain();
        return new StructuredResultCoercer(
                objectMapper,
                new JobResultSchemaValidator(objectMapper, properties),
                new SchemaPromptRenderer(),
                properties);
    }

    static AgentJobStructuredOutputContract contract(AgentJobProperties properties) {
        return new AgentJobStructuredOutputContract(coercer(properties), new SimpleMeterRegistry());
    }

    /**
     * An execution wired to a specific output contract through the same {@code ObjectProvider} seam
     * the container uses, so these tests exercise the production resolution path rather than a
     * test-only constructor that bypasses it.
     */
    static AgentJobExecution executionWith(
            AgentJobRepository repository,
            AgentChatService chatService,
            ToolCallLogRepository toolCallLogRepository,
            AgentJobProperties properties,
            AgentJobOutputContract outputContract) {
        return new AgentJobExecution(
                repository,
                chatService,
                toolCallLogRepository,
                properties,
                new SimpleMeterRegistry(),
                mock(AgentJobProgressService.class),
                providerOf(outputContract),
                parkProvider());
    }

    /** A tool-call log that reports zero calls, so the per-job ceiling never fires. */
    static ToolCallLogRepository unlimitedToolCalls() {
        ToolCallLogRepository toolCallLogRepository = mock(ToolCallLogRepository.class);
        when(toolCallLogRepository.countByAgentIdAndSessionId(any(), any())).thenReturn(0L);
        return toolCallLogRepository;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<AgentJobOutputContract> providerOf(
            AgentJobOutputContract contract) {
        ObjectProvider<AgentJobOutputContract> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(ArgumentMatchers.<Supplier<AgentJobOutputContract>>any()))
                .thenReturn(contract);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<AgentJobApprovalPark> parkProvider() {
        ObjectProvider<AgentJobApprovalPark> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(ArgumentMatchers.<Supplier<AgentJobApprovalPark>>any()))
                .thenReturn(AgentJobApprovalPark.NEVER_PARKS);
        return provider;
    }
}
