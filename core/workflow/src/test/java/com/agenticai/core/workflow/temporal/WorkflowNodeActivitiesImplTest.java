package com.agenticai.core.workflow.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.ExpressionScope;
import com.agenticai.core.workflow.ExpressionScopeContext;
import com.agenticai.core.workflow.WorkflowIntelligenceSettings;
import com.agenticai.core.workflow.WorkflowNodeExecutor;
import com.agenticai.core.workflow.WorkflowRunRepository;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import io.temporal.activity.ActivityOptions;
import io.temporal.testing.TestActivityEnvironment;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The real {@link WorkflowNodeActivitiesImpl} in Temporal's activity test environment (real
 * serialisation of the activity input, real activity context), with the node executor mocked. What
 * it proves: the {@link ExpressionScope} the workflow put on the input is bound, on the thread that
 * calls {@code executeNode}, exactly as serialised ({@code $item}, {@code $index}, {@code $items},
 * run mode), is cleared afterwards, and that the container-output activity reaches the executor
 * under the caller's tenant.
 */
class WorkflowNodeActivitiesImplTest {

    private static final WorkflowNodeDto NODE =
            new WorkflowNodeDto(
                    "n3",
                    "code",
                    Map.of(),
                    Map.of("label", "Body", "config", Map.of("operation", "uppercase")));

    private TestActivityEnvironment env;
    private ExecutorService executorService;
    private WorkflowNodeExecutor executor;
    private WorkflowNodeActivities stub;

    @BeforeEach
    void setUp() {
        executor = mock(WorkflowNodeExecutor.class);
        executorService = Executors.newCachedThreadPool();
        WorkflowNodeActivitiesImpl impl =
                new WorkflowNodeActivitiesImpl(
                        executor,
                        mock(WorkflowRunRepository.class),
                        mock(WorkflowEventPublisher.class),
                        com.agenticai.core.json.JsonMappers.plain(),
                        executorService);
        // @Value is not applied outside Spring; 0 would make the heartbeat loop spin.
        ReflectionTestUtils.setField(impl, "heartbeatIntervalSeconds", 5L);
        env = TestActivityEnvironment.newInstance();
        env.registerActivitiesImplementations(impl);
        stub =
                env.newActivityStub(
                        WorkflowNodeActivities.class,
                        ActivityOptions.newBuilder()
                                .setScheduleToCloseTimeout(Duration.ofSeconds(30))
                                .setRetryOptions(
                                        io.temporal.common.RetryOptions.newBuilder()
                                                .setMaximumAttempts(1)
                                                .build())
                                .build());
    }

    @AfterEach
    void tearDown() {
        env.close();
        executorService.shutdownNow();
    }

    private NodeExecutionActivityInput input(ExpressionScope scope) {
        return new NodeExecutionActivityInput(
                "def-1",
                "WF",
                "11111111-1111-1111-1111-111111111111",
                NODE,
                "b",
                "ann",
                "tenant-1",
                "user-1",
                Set.of("ROLE_tenant-admin"),
                WorkflowIntelligenceSettings.defaults(),
                null,
                scope);
    }

    @Test
    void theExpressionScopeOnTheInputIsBoundForTheNodeExecution() {
        AtomicReference<ExpressionScope> seen = new AtomicReference<>();
        AtomicReference<String> tenant = new AtomicReference<>();
        when(executor.executeNode(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            seen.set(ExpressionScopeContext.current());
                            tenant.set(
                                    TenantContext.current()
                                            .map(TenantContext::getTenantId)
                                            .orElse(null));
                            return WorkflowNodeExecutor.NodeExecutionResult.ok("B", "done", null);
                        });
        ExpressionScope scope =
                ExpressionScope.forMode(
                        ExpressionScope.iteration("b", 1, List.of("a", "b", "c")),
                        com.agenticai.core.workflow.RunMode.DRAFT);

        NodeExecutionActivityResult result = stub.executeNode(input(scope));

        assertThat(result.output()).isEqualTo("B");
        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().item()).isEqualTo("b");
        assertThat(seen.get().index()).isEqualTo(1);
        assertThat(seen.get().items()).containsExactly("a", "b", "c");
        assertThat(seen.get().mode()).isEqualTo("draft");
        assertThat(tenant.get())
                .as("tenant context is set on the same thread")
                .isEqualTo("tenant-1");
    }

    @Test
    void anInputWithNoScopeBindsNoScopeAndNeverSeesTheLastOne() {
        AtomicReference<ExpressionScope> seen = new AtomicReference<>();
        when(executor.executeNode(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            seen.set(ExpressionScopeContext.current());
                            return WorkflowNodeExecutor.NodeExecutionResult.ok("x", "done", null);
                        });

        stub.executeNode(input(ExpressionScope.iteration("a", 0, List.of("a"))));
        assertThat(seen.get()).isNotNull();
        stub.executeNode(input(null));

        assertThat(seen.get())
                .as("the second execution, likely on the same pooled thread, sees no scope")
                .isNull();
    }

    @Test
    void theScopeIsClearedFromTheWorkerThreadEvenWhenTheNodeThrows() {
        AtomicReference<Thread> thread = new AtomicReference<>();
        when(executor.executeNode(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            thread.set(Thread.currentThread());
                            throw new IllegalStateException("boom");
                        });

        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> stub.executeNode(input(ExpressionScope.iteration("a", 0, List.of("a")))));

        // The pool is cached, so the next task usually reuses the thread that just threw.
        AtomicReference<ExpressionScope> after = new AtomicReference<>(ExpressionScope.test());
        doAnswer(
                        inv -> {
                            after.set(ExpressionScopeContext.current());
                            return WorkflowNodeExecutor.NodeExecutionResult.ok("x", "done", null);
                        })
                .when(executor)
                .executeNode(any(), any(), any(), any(), any(), any(), any());
        stub.executeNode(input(null));
        assertThat(after.get()).isNull();
    }

    @Test
    void recordContainerOutputReachesTheExecutorUnderTheCallersTenant() {
        AtomicReference<String> tenant = new AtomicReference<>();
        doAnswer(
                        inv -> {
                            tenant.set(
                                    TenantContext.current()
                                            .map(TenantContext::getTenantId)
                                            .orElse(null));
                            return null;
                        })
                .when(executor)
                .recordContainerOutput(any(), any(), any(), any(), any(), any());
        WorkflowNodeDto loop = new WorkflowNodeDto("l1", "loop", Map.of(), Map.of("label", "Each"));

        stub.recordContainerOutput(
                new ContainerOutputInput(
                        "def-1",
                        "WF",
                        "11111111-1111-1111-1111-111111111111",
                        "ann",
                        loop,
                        "[\"A\",\"B\"]",
                        "tenant-1",
                        "user-1",
                        Set.of()));

        verify(executor)
                .recordContainerOutput(
                        eq("def-1"),
                        eq("WF"),
                        eq("11111111-1111-1111-1111-111111111111"),
                        eq("ann"),
                        any(WorkflowNodeDto.class),
                        eq("[\"A\",\"B\"]"));
        assertThat(tenant.get()).isEqualTo("tenant-1");
    }
}
