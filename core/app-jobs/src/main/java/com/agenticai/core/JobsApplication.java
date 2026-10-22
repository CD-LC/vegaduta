package com.agenticai.core;

import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import java.util.Map;
import org.springframework.ai.mcp.server.common.autoconfigure.StatelessToolCallbackConverterAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entry point for the background-jobs deployable ({@code app-jobs} module): the "jobs" shape of one
 * image run in several shapes (api / worker / jobs). It owns every {@code @Scheduled} sweep and job
 * poller the interactive api shape skips, so background work stops sharing CPU and DB connections
 * with chat. See {@code docs/plans/CORE-JOBS-DEPLOYABLE.md} and {@code
 * docs/deploy/DEPLOYMENT-SHAPES.md}.
 *
 * <p>Same full domain classpath and configuration ({@code app-config}) as the API server, because
 * agent jobs, workflow schedules and outbox relays execute real service code. What differs,
 * mirroring {@code WorkerApplication}'s pattern:
 *
 * <ul>
 *   <li><b>No REST.</b> {@code @ComponentScan} excludes {@code @RestController} /
 *       {@code @Controller} / {@code @ControllerAdvice}; the embedded web server exists only for
 *       actuator health/prometheus (port {@code 8096} by default, see {@code
 *       application-jobs.yml}).
 *   <li><b>Scheduling ON</b> ({@code @EnableScheduling}). Which beans actually tick is decided by
 *       the {@code shape-jobs} profile's {@code app.scheduling.skip-bean-classes} (only the
 *       API-JVM-state sweep is skipped here). With no shape profile every scheduled bean runs - the
 *       right default for a jobs pod that is the only one.
 *   <li><b>No schema migration.</b> {@code spring.flyway.enabled=false} and {@code
 *       app.tenancy.migrate-on-boot=false}: exactly one migrator, never a jobs replica racing
 *       per-tenant Flyway locks.
 *   <li><b>No Temporal worker.</b> {@code app.workflow.temporal.worker-enabled= false}: polling the
 *       task queue is the {@code app-worker} deployable's job. Workflow schedules still
 *       <i>start</i> workflows through the client.
 * </ul>
 *
 * <p>Those three are {@code setDefaultProperties} - lowest precedence, so env vars still win - and
 * do not depend on any profile being activated.
 *
 * <p>The Spring AI autoconfiguration exclusions are copied verbatim from {@code CoreApplication}
 * (BYOK platform - static single-key model beans must never be built; the ToolCallbackConverter
 * exclusion must not regress). The {@code applicationTaskExecutor} bean is also copied from it:
 * Boot's executor backs off when any {@code Executor} bean exists, and {@code @Qualifier
 * ("applicationTaskExecutor")} injection points would then fail to boot.
 */
@SpringBootApplication(
        exclude = {
            ToolCallbackConverterAutoConfiguration.class,
            // Spring AI 2.0 (ADR-0012 P3) added a stateless twin of the converter above with the
            // same context-wide
            // ToolCallback aggregation; inactive under protocol STREAMABLE but excluded so a
            // protocol flip cannot leak tools.
            StatelessToolCallbackConverterAutoConfiguration.class
        })
@ComponentScan(
        basePackages = "com.agenticai.core",
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.ANNOTATION,
                        classes = {RestController.class, Controller.class, ControllerAdvice.class}))
@EnableScheduling
@EnableAsync
public class JobsApplication {

    /** See the class javadoc; same bean as {@code CoreApplication}. */
    @Bean(name = "applicationTaskExecutor")
    public TaskExecutor applicationTaskExecutor() {
        // Tenant-aware (ADR-0024): an @Async method runs with the caller's tenant, restored after.
        return TenantAwareExecutors.newTaskExecutor("app-task-", true);
    }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(JobsApplication.class);
        app.setDefaultProperties(
                Map.of(
                        "spring.flyway.enabled",
                        "false",
                        "app.tenancy.migrate-on-boot",
                        "false",
                        "app.workflow.temporal.worker-enabled",
                        "false",
                        // Only the api shape registers the local LLM pool row.
                        "app.scheduling.skip-listener-classes",
                        "com.agenticai.core.providers.LocalLlmPoolBootstrap"));
        app.run(args);
    }
}
