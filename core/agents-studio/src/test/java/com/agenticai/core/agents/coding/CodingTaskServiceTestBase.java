package com.agenticai.core.agents.coding;

import static com.agenticai.core.agents.coding.CodingTaskTestSupport.await;
import static com.agenticai.core.agents.coding.CodingTaskTestSupport.sampleRepo;
import static com.agenticai.core.agents.coding.CodingTaskTestSupport.zip;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.coding.CodingTaskDtos.TaskView;
import com.agenticai.core.agents.coding.CodingTaskService.StartInput;
import com.agenticai.core.agents.coding.CodingTaskTestSupport.FakeSandbox;
import com.agenticai.core.agents.coding.CodingTaskTestSupport.InMemoryStore;
import com.agenticai.core.agents.coding.CodingTaskTestSupport.ScriptedModel;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.sdlc.harness.CodingCommandPolicy;
import com.agenticai.core.sdlc.harness.CodingSandbox;
import com.agenticai.core.sdlc.harness.GitUrlPolicy;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.security.TenantStatusGuard;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Shared fixture for {@link CodingTaskServiceTest} and {@link CodingTaskServiceLimitsTest}: the fakes
 * (store, sandbox, model), the settings and service builders and the polling helpers. Split from the
 * one test class to keep each file under the file-size cap.
 */
abstract class CodingTaskServiceTestBase {

    static final String REPO = "https://github.com/acme/widgets";
    static final String CMD = "mvn -q test";

    final UUID agentId = UUID.randomUUID();
    final AtomicLong now = new AtomicLong(1_000_000L);
    InMemoryStore store;
    FakeSandbox sandbox;
    ScriptedModel model;
    AgentService agentService;
    TenantLimits tenantLimits;
    TenantStatusGuard statusGuard;
    CodingTaskExecutor executor;
    final List<CodingSandbox> opened = new ArrayList<>();
    String notReady;
    CodingTaskService.ModelFactory modelFactory;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        sandbox = new FakeSandbox();
        model = new ScriptedModel();
        agentService = mock(AgentService.class);
        tenantLimits = mock(TenantLimits.class);
        when(tenantLimits.storedPlan(any())).thenReturn(TenantPlan.PREMIUM);
        statusGuard = mock(TenantStatusGuard.class);
        when(statusGuard.isUsable(any())).thenReturn(true);
        executor = new CodingTaskExecutor();
        notReady = null;
        modelFactory = (agent, subject, taskId) -> model;
        as("tenantA", "user1");
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
        TenantContext.clear();
    }

    static void as(String tenant, String user) {
        TenantContext.set(TenantContext.of(tenant, user, java.util.Set.of("USER")));
    }

    CodingTaskSettings settings(boolean enabled, boolean freePlan, int approvalTimeoutSeconds) {
        return new CodingTaskSettings(enabled, freePlan, 3, 20, 900, 150_000, 40, 1800, 400_000, 5,
            1024 * 1024, 4000, approvalTimeoutSeconds, 600, 5, false, "e2b", "", 300, 8000);
    }

    CodingTaskService service(CodingTaskSettings settings, CodingTaskExecutor exec) {
        CodingTaskService.SandboxOpener opener = new CodingTaskService.SandboxOpener() {
            @Override
            public CodingSandbox open(int ttlSeconds) {
                opened.add(sandbox);
                return sandbox;
            }

            @Override
            public String notReadyReason() {
                return notReady;
            }

            @Override
            public CodingCommandPolicy policy() {
                return CodingCommandPolicy.defaults();
            }

            @Override
            public String runnerName() {
                return "e2b";
            }
        };
        GitUrlPolicy git = new GitUrlPolicy(List.of(), host -> new InetAddress[] {publicIp()});
        return new CodingTaskService(settings, store, exec, agentService, tenantLimits, statusGuard, opener,
            modelFactory, git, now::get, 5);
    }

    CodingTaskService service() {
        return service(settings(true, false, 900), executor);
    }

    static InetAddress publicIp() {
        try {
            return InetAddress.getByName("93.184.216.34");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    StartInput gitInput() {
        return new StartInput(agentId, "Add validation to Foo", CMD, REPO, null, null, null, null, null);
    }

    StartInput archiveInput() {
        return new StartInput(agentId, "Add validation to Foo", CMD, null, zip(sampleRepo()), null, null, null, null);
    }

    TaskView waitForStatus(CodingTaskService svc, String taskId, String status) {
        await(() -> svc.get(taskId, "user1").status().equals(status));
        return svc.get(taskId, "user1");
    }

    TaskView waitForTerminal(CodingTaskService svc, String taskId) {
        await(() -> CodingTaskDtos.isTerminal(svc.get(taskId, "user1").status()));
        return svc.get(taskId, "user1");
    }

    static InetAddress loopbackish() {
        try {
            return InetAddress.getByName("10.0.0.7");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    CodingTaskService.SandboxOpener openerOf(FakeSandbox sb) {
        return new CodingTaskService.SandboxOpener() {
            @Override
            public CodingSandbox open(int ttlSeconds) {
                return sb;
            }

            @Override
            public String notReadyReason() {
                return null;
            }

            @Override
            public CodingCommandPolicy policy() {
                return CodingCommandPolicy.defaults();
            }

            @Override
            public String runnerName() {
                return "e2b";
            }
        };
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
