package com.agenticai.core.common.replica;

import com.agenticai.core.tenancy.replica.ReplicaModeSettings;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Startup check for {@code app.replica-mode=multi}: lists every per-JVM setting that is unsafe when
 * two core replicas sit behind one load balancer (docs/plans/CORE-TWO-REPLICA-READINESS.md and
 * docs/plans/CORE-STATE-AUDIT.md), each with the property that fixes it.
 *
 * <ul>
 *   <li>Default ({@code single}): nothing is evaluated, nothing is logged. Today's behaviour.
 *   <li>{@code multi}: one WARN per unsafe setting, once, at boot.
 *   <li>{@code multi} + {@code app.replica-mode-strict=true}: the same findings are logged as ERROR
 *       and the ones marked fatal abort boot with a single {@link IllegalStateException} that lists
 *       them all.
 * </ul>
 *
 * <p>Reads other modules' properties through the {@link Environment} by key (with the same defaults
 * those modules use in their {@code @Value}s) so this class adds no module dependency and cannot
 * drift into checking a bean instead of the effective configuration. A failure inside the check
 * itself never blocks boot (it is logged and skipped); only a deliberate strict-mode finding does.
 */
@Component
public class ReplicaModeSafetyCheck implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ReplicaModeSafetyCheck.class);

    private final Environment environment;
    private final ReplicaModeSettings settings;

    public ReplicaModeSafetyCheck(Environment environment, ReplicaModeSettings settings) {
        this.environment = environment;
        this.settings = settings;
    }

    /**
     * One unsafe setting: what is wrong, how to fix it, and whether strict mode refuses to boot on
     * it.
     */
    record Finding(String message, String fix, boolean fatalWhenStrict) {}

    @Override
    public void afterPropertiesSet() {
        if (!settings.isMulti()) {
            return;
        }
        List<Finding> findings;
        try {
            findings = findings();
        } catch (RuntimeException e) {
            log.warn(
                    "app.replica-mode={}: the multi-replica safety check itself failed and was"
                            + " skipped",
                    settings.raw(),
                    e);
            return;
        }
        if (findings.isEmpty()) {
            log.info("app.replica-mode=multi: no unsafe per-JVM settings found");
            return;
        }
        boolean strict = settings.isStrict();
        List<String> fatal = new ArrayList<>();
        for (Finding f : findings) {
            String line = "app.replica-mode=multi: " + f.message() + " Fix: " + f.fix();
            if (strict) {
                log.error(line);
            } else {
                log.warn(line);
            }
            if (f.fatalWhenStrict()) {
                fatal.add(f.message());
            }
        }
        if (strict && !fatal.isEmpty()) {
            throw new IllegalStateException(
                    "app.replica-mode=multi with app.replica-mode-strict=true refuses to start with"
                            + " "
                            + fatal.size()
                            + " unsafe per-JVM setting(s): "
                            + String.join(" | ", fatal));
        }
    }

    /** Package-private so the unit test can drive it with a mock environment. */
    List<Finding> findings() {
        List<Finding> out = new ArrayList<>();
        if (!settings.isMulti()) {
            return out;
        }
        if (bool("app.a2a.enabled", true)) {
            if (bool("app.a2a.signing.enabled", true)
                    && !hasText("app.a2a.signing.private-key-pem")) {
                out.add(
                        new Finding(
                                "A2A card signing is on but no private key is pinned, so every"
                                    + " replica (and every restart) signs with its own ephemeral"
                                    + " Ed25519 key and cards will not verify across replicas.",
                                "set APP_A2A_SIGNING_PRIVATE_KEY and APP_A2A_SIGNING_PUBLIC_KEY.",
                                true));
            }
            if (!isRedis("app.a2a.task-store")) {
                out.add(
                        new Finding(
                                "app.a2a.task-store is in-memory, so GetTask/CancelTask 404 when"
                                        + " the request lands on the other replica.",
                                "set APP_A2A_TASK_STORE=redis.",
                                true));
            }
        }
        if (!isRedis("app.abuse.throttle-store")) {
            out.add(
                    new Finding(
                            "app.abuse.throttle-store is in-memory, so a throttle set on one"
                                    + " replica is not seen by the other.",
                            "set app.abuse.throttle-store=redis.",
                            true));
        }
        if (!isRedis("app.mcp.registry-store")) {
            out.add(
                    new Finding(
                            "app.mcp.registry-store is memory, so each replica connects, retries"
                                + " and health-checks MCP servers independently (retry counters"
                                + " advance per replica; a disconnect on one replica does not reach"
                                + " the other).",
                            "set app.mcp.registry-store=redis.",
                            true));
        }
        // app.mcp.oauth.store is read with redisStore(...) - NOT isRedis - on purpose: it is
        // only unsafe while OAuth is on, so it is inspected inside these gated findings instead of
        // unconditionally like the other stores. ReplicaStoreCoverageTest maps every isRedis
        // key to a Helm _core.tpl env var, and the chart does not switch this one yet; the store is
        // allowlisted in replica-store-allowlist.txt with that reason.
        boolean oauthShared = redisStore("app.mcp.oauth.store");
        if (bool("app.mcp.oauth.enabled", false) && !oauthShared) {
            out.add(
                    new Finding(
                            "app.mcp.oauth.enabled is on but app.mcp.oauth.store is memory, so the"
                                + " single-use OAuth state set and the access-token cache are per"
                                + " JVM (the connection store itself is durable since V424): a"
                                + " callback replayed on the other replica is not rejected and two"
                                + " replicas can refresh the same rotating refresh token at once.",
                            "set app.mcp.oauth.store=redis (MCP_OAUTH_STORE=redis).",
                            true));
        }
        if (bool("app.mcp.oauth.providers-enabled", false) && !oauthShared) {
            // Not fatal: worst case is a redundant refresh, but providers whose refresh tokens
            // rotate (Zoom, Linear, Notion) can burn a token when two replicas refresh at once.
            out.add(
                    new Finding(
                            "app.mcp.oauth.providers-enabled is on but app.mcp.oauth.store is"
                                + " memory, so the OAuth refresh single-flight and single-use state"
                                + " set are per JVM and two replicas can refresh the same rotating"
                                + " refresh token at once.",
                            "set app.mcp.oauth.store=redis (MCP_OAUTH_STORE=redis).",
                            false));
        }
        if (bool("app.sandbox.data-analysis.enabled", false)
                && !isRedis("app.sandbox.data-analysis.store")) {
            out.add(
                    new Finding(
                            "the data-analysis sandbox keeps uploaded files in this JVM only, so a"
                                + " follow-up turn on the other replica cannot find the files it"
                                + " should mount.",
                            "set app.sandbox.data-analysis.store=redis.",
                            true));
        }
        if (bool("app.sandbox.platform-e2b.enabled", false)
                && !isRedis("app.sandbox.platform-e2b.quota-store")) {
            out.add(
                    new Finding(
                            "the platform-paid E2B daily run quota is counted per JVM, so the"
                                    + " effective cap is multiplied by the replica count.",
                            "set app.sandbox.platform-e2b.quota-store=redis.",
                            true));
        }
        if (bool("app.chat.resumable-streams.enabled", false)
                && !isRedis("app.chat.resumable-streams.store")) {
            // Not fatal: a resume that lands on the wrong replica answers 404 and the client falls
            // back to the final message.
            out.add(
                    new Finding(
                            "app.chat.resumable-streams.store is in-memory, so a stream resume that"
                                    + " lands on the replica not generating the turn answers 404"
                                    + " instead of replaying it.",
                            "set APP_CHAT_RESUMABLE_STREAMS_STORE=redis.",
                            false));
        }
        if (!isRedis("app.guardrails.procedures.session-calls-store")) {
            out.add(
                    new Finding(
                            "procedure guard rules (REQUIRE_TOOL_BEFORE) remember executed tools"
                                + " per JVM, so a turn on the other replica can wrongly deny a tool"
                                + " whose prerequisite already ran (fails closed, not open).",
                            "set app.guardrails.procedures.session-calls-store=redis if procedures"
                                    + " with ordering rules are used.",
                            false));
        }
        if (bool("app.tools.effect-journal.enabled", true)
                && !isRedis("app.tools.effect-journal.store")) {
            // Not fatal: the journal only suppresses a repeated write/send inside one turn's retry
            // window, and a
            // turn runs on one replica. It matters when a retry or a resumed job lands on a
            // different replica.
            out.add(
                    new Finding(
                            "the tool effect journal keeps its records in this JVM, so a durable"
                                + " (write/send) tool call retried or resumed on the other replica"
                                + " is not recognised as a repeat and runs again.",
                            "set TOOL_EFFECT_JOURNAL_STORE=redis (app.tools.effect-journal.store).",
                            false));
        }
        if (!isRedis("app.demo.studio.approval-store")) {
            out.add(
                    new Finding(
                            "the /try Agent Studio keeps approval waits in this JVM, so an"
                                + " approve/reject POST that lands on the other replica is lost and"
                                + " the parked run times out after ten minutes.",
                            "set app.demo.studio.approval-store=redis.",
                            false));
        }
        String sweep = environment.getProperty("app.quality.boot-orphan-sweep", "all").strip();
        if (sweep.equalsIgnoreCase("all")) {
            out.add(
                    new Finding(
                            "app.quality.boot-orphan-sweep=all fails EVERY queued/running eval and"
                                + " simulation run at boot, including runs the other replica (or"
                                + " the previous revision during a rolling deploy) is still"
                                + " executing.",
                            "set app.quality.boot-orphan-sweep=stale (only runs older than"
                                    + " app.quality.boot-orphan-min-age).",
                            false));
        }
        return out;
    }

    private boolean bool(String key, boolean defaultValue) {
        String v = environment.getProperty(key);
        return v == null || v.isBlank() ? defaultValue : Boolean.parseBoolean(v.strip());
    }

    private boolean hasText(String key) {
        String v = environment.getProperty(key);
        return v != null && !v.isBlank();
    }

    private boolean isRedis(String key) {
        return "redis".equalsIgnoreCase(environment.getProperty(key, "memory").strip());
    }

    /**
     * Same read as {@link #isRedis} but deliberately under a different name: {@code
     * ReplicaStoreCoverageTest} scans this file for literal {@code isRedis(...)} call sites and
     * requires each key to be switched to {@code redis} by {@code
     * deploy/helm/charts/vegaduta-lib/templates/_core.tpl}. Stores checked here inside
     * feature-gated findings (only unsafe while the feature is on) use this helper and are
     * allowlisted in {@code replica-store-allowlist.txt} instead.
     */
    private boolean redisStore(String key) {
        return "redis".equalsIgnoreCase(environment.getProperty(key, "memory").strip());
    }
}
