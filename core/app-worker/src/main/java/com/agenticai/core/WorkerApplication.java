package com.agenticai.core;

import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import java.util.Map;
import org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entry point for the dedicated Temporal workflow-worker deployable ({@code app-worker} module) -
 * the first independent deployable carved out of the modular monolith (see {@code
 * docs/plans/CORE-DECOMPOSITION-EXECUTION-PLAN-2026-09-23.md}).
 *
 * <p><b>Same modules, different runtime shape.</b> This process shares the API server's full domain
 * classpath and its full configuration (via the {@code app-config} module) because Temporal
 * activities execute real node work - agent chat, tool calls, channel sends, document rendering -
 * that needs every service bean. What it deliberately does NOT do:
 *
 * <ul>
 *   <li><b>Serve REST.</b> {@code @ComponentScan} excludes
 *       {@code @RestController}/{@code @Controller}/{@code @ControllerAdvice} - the embedded web
 *       server exists only for actuator health/prometheus, not for the API surface.
 *   <li><b>Run scheduled jobs.</b> No {@code @EnableScheduling}: every {@code @Scheduled} bean
 *       (WorkflowRunRecoveryJob, schedulers, cleanup jobs) stays the API deployable's job, so
 *       nothing fires twice when both run.
 *   <li><b>Migrate schemas.</b> Flyway is off ({@code spring.flyway.enabled =false}) and {@code
 *       app.tenancy.migrate-on-boot=false} suppresses {@code ExistingTenantSchemaMigrationRunner} -
 *       per-tenant schema migration is a control-plane responsibility and must never race across N
 *       replicas.
 * </ul>
 *
 * <p>Those three defaults are set as {@code setDefaultProperties} - the lowest precedence, still
 * overridable by env vars - rather than relying on the {@code worker} profile, so forgetting to
 * activate it can't silently re-enable migrations in a worker pod.
 *
 * <p>The Spring AI autoconfiguration exclusions are copied verbatim from {@code CoreApplication}
 * (BYOK platform - static single-key model beans must never be built; see that class's javadoc,
 * including the ToolCallbackConverter capability-leak warning that must not regress).
 */
@SpringBootApplication(exclude = {ToolCallbackConverterAutoConfiguration.class})
@ComponentScan(
        basePackages = "com.agenticai.core",
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.ANNOTATION,
                        classes = {RestController.class, Controller.class, ControllerAdvice.class}))
@EnableAsync
public class WorkerApplication {

    /**
     * Same bean as {@code CoreApplication} and {@code JobsApplication}: Boot's own executor backs
     * off (an {@code Executor} bean already exists), so
     * {@code @Qualifier("applicationTaskExecutor")} injection points such as {@code
     * McpConnectDispatcher} would otherwise fail to boot the worker.
     */
    @Bean(name = "applicationTaskExecutor")
    public TaskExecutor applicationTaskExecutor() {
        // Tenant-aware (ADR-0024): an @Async method runs with the caller's tenant, restored after.
        return TenantAwareExecutors.newTaskExecutor("app-task-", true);
    }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(WorkerApplication.class);
        app.setDefaultProperties(
                Map.of(
                        "spring.flyway.enabled",
                        "false",
                        "app.tenancy.migrate-on-boot",
                        "false",
                        "app.workflow.temporal.worker-enabled",
                        "true",
                        // Only the api shape registers the local LLM pool row.
                        "app.scheduling.skip-listener-classes",
                        "com.agenticai.core.providers.LocalLlmPoolBootstrap"));
        app.run(args);
    }
}
