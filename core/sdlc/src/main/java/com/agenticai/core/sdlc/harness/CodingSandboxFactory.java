package com.agenticai.core.sdlc.harness;

import com.agenticai.core.sdlc.E2bAccess;
import com.agenticai.core.sdlc.E2bAccessResolver;
import com.agenticai.core.sdlc.E2bCodeExecutionClient;
import com.agenticai.core.sdlc.dto.SandboxFile;
import com.agenticai.core.sdlc.dto.RunCodeResponse;
import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Opens the {@link CodingSandbox} a coding task runs in. The runner is chosen by
 * {@code app.coding-harness.runner}:
 * <ul>
 *   <li>{@code e2b} (default) - a persistent sandbox on the workspace's own E2B key. The
 *       platform-managed fallback key is NOT used unless {@code app.coding-harness.allow-platform-e2b}
 *       is set: a task can run for many minutes and the fallback is metered per short {@code run_code}
 *       call, so the platform must not silently subsidise it.</li>
 *   <li>{@code local} - development only; honoured solely when
 *       {@code app.coding-harness.allow-local-runner=true} AND none of the {@code security}, {@code prod},
 *       {@code production} Spring profiles is active ({@code security} is this repo's real-deployment
 *       marker, see NoSecurityConfig). It additionally needs the separate acknowledgement
 *       {@code app.coding-harness.acknowledge-local-runner-risk=true} and a loopback bind
 *       ({@code server.address=127.0.0.1}), and logs a WARN banner when it opens
 *       (see {@link LocalRunnerGate}). Anything else refuses.</li>
 * </ul>
 * Only consulted by code that already checked {@code app.coding-harness.enabled}; with the flag off
 * this bean is inert.
 */
@Component
public class CodingSandboxFactory {

    private static final Logger log = LoggerFactory.getLogger(CodingSandboxFactory.class);
    private static final List<String> DEPLOYMENT_PROFILES = List.of("security", "prod", "production");

    private final E2bAccessResolver accessResolver;
    private final E2bCodeExecutionClient e2bClient;
    private final Environment environment;
    private final boolean enabled;
    private final String runner;
    private final boolean allowLocalRunner;
    private final boolean allowPlatformE2b;
    private final boolean networkEnabled;
    private final String templateOverride;
    private final int commandTimeoutSeconds;
    private final CodingCommandPolicy policy;
    private final String localBash;
    private final Path localWorkspaceDir;

    public CodingSandboxFactory(
        E2bAccessResolver accessResolver,
        E2bCodeExecutionClient e2bClient,
        Environment environment,
        @Value("${app.coding-harness.enabled:false}") boolean enabled,
        @Value("${app.coding-harness.runner:e2b}") String runner,
        @Value("${app.coding-harness.allow-local-runner:false}") boolean allowLocalRunner,
        @Value("${app.coding-harness.allow-platform-e2b:false}") boolean allowPlatformE2b,
        @Value("${app.coding-harness.network-enabled:false}") boolean networkEnabled,
        @Value("${app.coding-harness.e2b-template-id:}") String templateOverride,
        @Value("${app.coding-harness.command-timeout-seconds:100}") int commandTimeoutSeconds,
        @Value("${app.coding-harness.allowed-runners:mvn,gradle,npm,pytest,go}") String allowedRunners,
        @Value("${app.coding-harness.local-bash:bash}") String localBash,
        @Value("${app.coding-harness.local-workspace-dir:}") String localWorkspaceDir
    ) {
        this.accessResolver = accessResolver;
        this.e2bClient = e2bClient;
        this.environment = environment;
        this.enabled = enabled;
        this.runner = runner == null ? "e2b" : runner.strip().toLowerCase(Locale.ROOT);
        this.allowLocalRunner = allowLocalRunner;
        this.allowPlatformE2b = allowPlatformE2b;
        this.networkEnabled = networkEnabled;
        this.templateOverride = templateOverride == null ? "" : templateOverride.strip();
        this.commandTimeoutSeconds = commandTimeoutSeconds;
        this.localBash = localBash;
        this.localWorkspaceDir =
                localWorkspaceDir == null || localWorkspaceDir.isBlank()
                        ? null
                        : Path.of(localWorkspaceDir.strip());
        this.policy = new CodingCommandPolicy(
            allowedRunners == null ? List.of() : Arrays.asList(allowedRunners.split(",")));
    }

    /** Fails the boot (only when the harness is enabled) if the local runner was opted into in a deployment profile. */
    @PostConstruct
    void refuseLocalRunnerInDeployment() {
        if (enabled && "local".equals(runner) && allowLocalRunner && deploymentProfileActive()) {
            throw new IllegalStateException("Refusing to start: app.coding-harness.runner=local with "
                + "allow-local-runner=true is development only and must not run under a deployment profile "
                + "(security/prod/production). It executes tenant-influenced commands on the core host.");
        }
        if (enabled && "local".equals(runner) && !allowLocalRunner) {
            log.warn("app.coding-harness.runner=local but allow-local-runner is false - coding tasks will be refused.");
        }
        if (enabled && "local".equals(runner) && allowLocalRunner) {
            LocalRunnerGate.refusal(environment).ifPresentOrElse(
                reason -> log.warn("app.coding-harness.runner=local: coding tasks will be refused. {}", reason),
                () -> log.warn(LocalRunnerGate.banner()));
        }
    }

    public CodingCommandPolicy policy() {
        return policy;
    }

    /**
     * Cheap, non-throwing: could {@link #open} plausibly succeed for the current tenant right now? Used
     * to refuse a task up front (a clear 4xx) instead of failing it a minute later in the worker.
     * Returns {@code null} when ready, otherwise a user-safe reason.
     */
    public String notReadyReason() {
        return switch (runner) {
            case "local" -> !allowLocalRunner ? "The local runner is not allowed on this deployment."
                : deploymentProfileActive() ? "The local runner is refused under a deployment profile."
                : LocalRunnerGate.refusal(environment).orElse(null);
            case "e2b" -> {
                try {
                    if (!accessResolver.codeExecutionReady()) {
                        yield "Coding tasks need code execution: activate your own E2B key with a coding template on the Providers page.";
                    }
                    if (!accessResolver.tenantReady() && !allowPlatformE2b) {
                        yield "Coding tasks need your own E2B key. Add one on the Providers page.";
                    }
                    yield null;
                } catch (RuntimeException e) {
                    yield "Coding tasks are not available right now.";
                }
            }
            default -> "Coding tasks have no valid runner configured.";
        };
    }

    public String runnerName() {
        return runner;
    }

    boolean deploymentProfileActive() {
        for (String profile : environment.getActiveProfiles()) {
            if (DEPLOYMENT_PROFILES.contains(profile.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param ttlSeconds how long the sandbox may live at most (its own backstop reaper)
     * @throws CodingSandboxUnavailableException when this deployment is not set up to run a task
     */
    public CodingSandbox open(int ttlSeconds) {
        return switch (runner) {
            case "local" -> openLocal();
            case "e2b" -> openE2b(ttlSeconds);
            default -> throw new CodingSandboxUnavailableException("Coding tasks have no valid runner configured.");
        };
    }

    private CodingSandbox openLocal() {
        if (!allowLocalRunner) {
            throw new CodingSandboxUnavailableException("The local runner is not allowed on this deployment.");
        }
        if (deploymentProfileActive()) {
            throw new CodingSandboxUnavailableException("The local runner is refused under a deployment profile.");
        }
        String refusal = LocalRunnerGate.refusal(environment).orElse(null);
        if (refusal != null) {
            throw new CodingSandboxUnavailableException(refusal);
        }
        log.warn(LocalRunnerGate.banner());
        return new LocalCodingSandbox(policy, localBash, localWorkspaceDir);
    }

    private CodingSandbox openE2b(int ttlSeconds) {
        E2bAccess access = accessResolver.resolveForCodeExecution();
        if (access.platformManaged() && !allowPlatformE2b) {
            throw new CodingSandboxUnavailableException(
                "Coding tasks need your own E2B key. Add one on the Providers page.");
        }
        String template = templateOverride.isEmpty() ? access.templateId() : templateOverride;
        E2bAccess use = new E2bAccess(access.apiKey(), template, access.source());
        E2bCodeExecutionClient.Session session = e2bClient.openSession(use, Math.max(120, Math.min(3600, ttlSeconds)));
        try {
            // Ownership of the session transfers to the sandbox here: E2bCodingSandbox.close() closes it.
            return new E2bCodingSandbox(new SessionShell(session), policy, networkEnabled, commandTimeoutSeconds);
        } catch (RuntimeException e) {
            // The sandbox never came to own it: kill it now instead of leaving it up until its TTL.
            session.close();
            throw e;
        }
    }

    /** Adapts one persistent E2B session to the sandbox's transport; closing the shell closes the session. */
    private static final class SessionShell implements E2bCodingSandbox.RemoteShell {

        private final E2bCodeExecutionClient.Session session;

        SessionShell(E2bCodeExecutionClient.Session session) {
            this.session = session;
        }

        @Override
        public E2bCodingSandbox.RemoteResult run(String script, int timeoutSeconds, List<SandboxFile> files) {
            RunCodeResponse r = session.run(script, "bash", timeoutSeconds, files);
            return new E2bCodingSandbox.RemoteResult(r.exitCode(), r.timedOut(), r.stdout(), r.stderr());
        }

        @Override
        public void close() {
            session.close();
        }

        @Override
        public String id() {
            return session.sandboxId();
        }
    }
}
