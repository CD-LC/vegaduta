package com.agenticai.core.workflow.temporal;

import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Hand-wired {@code
 * WorkflowServiceStubs}/{@code WorkflowClient}/{@code WorkerFactory} beans -
 * deliberately not the {@code temporal-spring-boot-starter}'s
 * auto-configuration, so these beans (and the network connection attempt to
 * a Temporal server they imply) are constructed if and only if the
 * "temporal" profile is active, matching this codebase's existing
 * feature-gating convention (see {@code WorkflowKafkaConfig}'s {@code
 * @Profile("workflow")}). A {@code core} started without this profile - the
 * default - never references this class at all.
 *
 * <p>Registers exactly one {@link Worker}, on {@link #TASK_QUEUE}, shared by
 * every tenant (see {@code WorkflowNodeActivitiesImpl}'s javadoc for why
 * tenant isolation is enforced by explicit data on every Activity input
 * instead of per-tenant task queues - the Stage 3 build plan entry called
 * out both options as acceptable).
 */
@Configuration
@Profile("temporal")
public class TemporalConfig {

    public static final String TASK_QUEUE = "workflow-execution";

    @Bean(destroyMethod = "shutdown")
    public WorkflowServiceStubs workflowServiceStubs(
        @Value("${app.workflow.temporal.target:127.0.0.1:7233}") String target
    ) {
        return WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
    }

    @Bean
    public WorkflowClient workflowClient(
        WorkflowServiceStubs serviceStubs,
        @Value("${app.workflow.temporal.namespace:default}") String namespace
    ) {
        return WorkflowClient.newInstance(
            serviceStubs, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
    }

    /**
     * Registers {@link WorkflowRunWorkflowImpl} (a plain Temporal-managed
     * POJO, not a Spring bean - Workflow implementations must have a public
     * no-arg constructor per the SDK's contract) and the Spring-managed
     * {@link WorkflowNodeActivitiesImpl} bean (Activities, unlike Workflow
     * implementations, ARE allowed to be arbitrary objects with real
     * dependencies - see Temporal's Worker#registerActivitiesImplementations
     * javadoc), then starts the factory. Returned as the bean itself (not
     * void) so {@code destroyMethod = "shutdown"} lets Spring stop polling
     * cleanly on application shutdown.
     *
     * <p>Gated on {@code app.workflow.temporal.worker-enabled} (default
     * true, so a plain {@code temporal}-profile core keeps its historical
     * in-process worker) because the dedicated {@code app-worker}
     * deployable exists precisely to take this job: an API process that
     * only STARTS and SIGNALS workflows should never also poll the task
     * queue for work - it would quietly split executions between the API
     * pod and the real workers. The WorkflowClient above stays
     * unconditional within the profile - starting/signalling is the API
     * pod's job.
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "app.workflow.temporal.worker-enabled", havingValue = "true", matchIfMissing = true)
    public WorkerFactory workerFactory(WorkflowClient workflowClient, WorkflowNodeActivities activities) {
        WorkerFactory factory = WorkerFactory.newInstance(workflowClient);
        Worker worker = factory.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(WorkflowRunWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        factory.start();
        return factory;
    }

    /**
     * The executor {@code WorkflowNodeActivitiesImpl#executeNode} runs the
     * actual node work on so the Temporal activity thread itself can
     * heartbeat (see that class's javadoc for why the activity method must
     * not block on the node). Virtual threads deliberately: node work is
     * almost entirely I/O-wait (LLM calls, tool subprocesses, channel
     * sends), matching the platform's {@code spring.threads.virtual}
     * posture for the request tier - a fixed pool of platform threads would
     * just recreate the "how many concurrent nodes can one worker run"
     * sizing problem Temporal exists to remove.
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService temporalActivityExecutor() {
        return TenantAwareExecutors.newVirtualThreadPerTaskExecutor("temporal-activity-");
    }
}
