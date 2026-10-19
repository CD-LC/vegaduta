package com.agenticai.core.platform.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.platform.ops.LangfuseApiClientTest.MutableClock;
import com.agenticai.core.platform.ops.ScheduledJobRegistryService.JobView;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.FixedRateTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;
import org.springframework.web.server.ResponseStatusException;

class ScheduledJobRegistryServiceTest {

    /** Stand-in scheduled bean. */
    public static class SampleJob {
        final AtomicInteger runs = new AtomicInteger();

        public void tick() {
            runs.incrementAndGet();
        }

        public void nightly() {
            throw new IllegalStateException("boom\nsecond line");
        }
    }

    private final SampleJob bean = new SampleJob();
    private final MutableClock clock = new MutableClock();
    private final ScheduledJobRunTracker tracker = new ScheduledJobRunTracker(clock);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static ScheduledTask scheduled(org.springframework.scheduling.config.Task task) {
        ScheduledTask st = mock(ScheduledTask.class);
        when(st.getTask()).thenReturn(task);
        return st;
    }

    private ScheduledJobRegistryService service(String allowlist) throws Exception {
        Method tick = SampleJob.class.getMethod("tick");
        Method nightly = SampleJob.class.getMethod("nightly");
        ScheduledTask a = scheduled(new FixedRateTask(new ScheduledMethodRunnable(bean, tick), Duration.ofMinutes(5), Duration.ZERO));
        ScheduledTask b = scheduled(new CronTask(new ScheduledMethodRunnable(bean, nightly), "0 20 0 * * *"));
        ScheduledTaskHolder holder = () -> Set.of(a, b);
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("holder", holder);
        return new ScheduledJobRegistryService(factory.getBeanProvider(ScheduledTaskHolder.class),
            Map.of(SampleJob.class.getName(), "sampleJob")::get, tracker, allowlist, executor);
    }

    private static String allow(String method) {
        return SampleJob.class.getName() + "#" + method;
    }

    @Test
    void listsEveryRegisteredTaskWithStableIdsAndSchedules() throws Exception {
        List<JobView> jobs = service("").list();
        assertEquals(List.of("sampleJob#nightly", "sampleJob#tick"), jobs.stream().map(JobView::id).toList());
        JobView nightly = jobs.get(0);
        assertEquals("sampleJob", nightly.bean());
        assertEquals("nightly", nightly.method());
        assertEquals("cron 0 20 0 * * *", nightly.schedule());
        assertEquals("fixedRate 5m", jobs.get(1).schedule());
        assertNull(nightly.lastStatus());
        assertFalse(nightly.runNowAllowed());
    }

    @Test
    void lastRunComesFromTheTracker() throws Exception {
        Method nightly = SampleJob.class.getMethod("nightly");
        ScheduledTaskObservationContext ctx = new ScheduledTaskObservationContext(bean, nightly);
        tracker.onStart(ctx);
        assertEquals("RUNNING", service("").list().get(0).lastStatus());
        clock.advance(Duration.ofMillis(1500));
        ctx.setError(new IllegalStateException("boom\nsecond line"));
        tracker.onError(ctx);
        tracker.onStop(ctx);

        JobView view = service("").list().get(0);
        assertEquals("FAILED", view.lastStatus());
        assertEquals("IllegalStateException: boom", view.lastError());
        assertEquals(1500L, view.lastDurationMs());
        assertEquals(view.lastStartedAt().plusMillis(1500), view.lastFinishedAt());

        ScheduledTaskObservationContext ok = new ScheduledTaskObservationContext(bean, nightly);
        tracker.onStart(ok);
        tracker.onStop(ok);
        JobView after = service("").list().get(0);
        assertEquals("SUCCESS", after.lastStatus());
        assertNull(after.lastError());
    }

    @Test
    void runNowOnlyForAllowlistedJobs() throws Exception {
        ScheduledJobRegistryService svc = service(allow("tick"));
        assertTrue(svc.list().get(1).runNowAllowed());

        ResponseStatusException denied = assertThrows(ResponseStatusException.class, () -> svc.runNow("sampleJob#nightly", "ops"));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        ResponseStatusException missing = assertThrows(ResponseStatusException.class, () -> svc.runNow("nope#x", "ops"));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());

        svc.runNow("sampleJob#tick", "ops");
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(1, bean.runs.get());
    }

    @Test
    void runNowRefusesAJobAlreadyRunningHere() throws Exception {
        ScheduledJobRegistryService svc = service(allow("tick"));
        tracker.onStart(new ScheduledTaskObservationContext(bean, SampleJob.class.getMethod("tick")));
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> svc.runNow("sampleJob#tick", "ops"));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    @Test
    void helpers() {
        assertEquals("fixedDelay 30s", ScheduledJobRegistryService.describeSchedule(
            new FixedDelayTask(() -> { }, Duration.ofSeconds(30), Duration.ZERO)));
        assertEquals("1h", ScheduledJobRegistryService.human(Duration.ofHours(1)));
        assertEquals("1500ms", ScheduledJobRegistryService.human(Duration.ofMillis(1500)));
        assertEquals("com.x.Foo", ScheduledJobRegistryService.stripProxySuffix("com.x.Foo$$SpringCGLIB$$0"));
        assertEquals(Set.of("a#b", "c#d"), ScheduledJobRegistryService.parseAllowlist(" a#b, ,c#d "));
        assertTrue(ScheduledJobRegistryService.parseAllowlist(ScheduledJobRegistryService.DEFAULT_ALLOWLIST)
            .contains("com.agenticai.core.platform.LlmUsageRollupScheduler#rollupYesterday"));
    }

    @Test
    void controllerRunNeedsTheMasterKey() throws Exception {
        PlatformOpsController controller = new PlatformOpsController(mock(PlatformVersionService.class),
            service(allow("tick")), mock(PlatformMigrationService.class), "mk");
        assertThrows(com.agenticai.core.tenancy.MasterKeyMissingException.class, () -> controller.runJob("sampleJob#tick", null));
        assertThrows(com.agenticai.core.tenancy.MasterKeyInvalidException.class, () -> controller.runJob("sampleJob#tick", "no"));
        assertEquals(Map.of("started", true), controller.runJob("sampleJob#tick", "mk"));
    }
}
