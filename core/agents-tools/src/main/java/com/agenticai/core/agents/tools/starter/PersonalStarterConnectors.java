package com.agenticai.core.agents.tools.starter;

import com.agenticai.core.agents.tools.McpStdioSidecarProperties;
import com.agenticai.core.agents.tools.classification.ToolClassification;
import com.agenticai.core.agents.tools.classification.ToolClassificationEntity;
import com.agenticai.core.agents.tools.classification.ToolClassificationRegistry;
import com.agenticai.core.agents.tools.classification.ToolDeterminism;
import com.agenticai.core.agents.tools.classification.ToolSensitivity;
import com.agenticai.core.agents.tools.classification.ToolTrustTier;
import com.agenticai.core.mcp.McpStarterService;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Registers the personal install's starter MCP connectors ({@link McpStarterService}) for every
 * tenant at start-up and gives each its risk classification, so a fresh install has verified tools
 * without the person touching infrastructure.
 *
 * <p><b>Only on a personal install.</b> Active only with {@code app.mcp.starter.enabled=true}, which
 * {@code application-personal.yml} sets; every other shape never loads this class.
 *
 * <p><b>Only when there is somewhere to run them.</b> The slim core image has no Node or uv, so a
 * STDIO server can only run in the {@code mcp-stdio-runner} container ({@code
 * mcp-stdio-sidecar.enabled}). Without it nothing is registered, instead of leaving rows that fail
 * to start; the next start with the runner on registers them.
 *
 * <p><b>Order.</b> Runs before {@code DynamicMcpConnectionManager.reconnectEnabledOnStartup} (same
 * event, default order), which then connects the enabled rows created here on its own tenant-scoped
 * threads. This class neither hands work to another thread nor connects anything itself, so it
 * carries no tenant across a hop (AGENTS.md rule 12): the iterator sets the tenant for each call and
 * clears it after.
 *
 * <p><b>Risk classification.</b> Each starter's tools are declared under {@code mcp__<name>__*} when
 * the starter is registered, and on every later start for as long as the registration is still the
 * catalog's own (so a write that failed once is retried), unless the tenant already holds a row for
 * that pattern. All
 * three are read-only and cannot change anything, so none needs an approval; the output is third-party
 * content, so it taints the turn like every MCP server's ({@link ToolTrustTier#UNTRUSTED_EXTERNAL}),
 * which is what makes a later write in the same turn ask first. An unclassified MCP tool would be both
 * a taint source and a taint sink.
 */
@Component
@ConditionalOnProperty(name = "app.mcp.starter.enabled", havingValue = "true")
public class PersonalStarterConnectors {

    private static final Logger log = LoggerFactory.getLogger(PersonalStarterConnectors.class);

    /** Provenance recorded on the rows this class writes (the tool_classification source CHECK). */
    static final String SOURCE = "MCP_IMPORT";

    static final String ACTOR = "system:starter-connectors";

    /**
     * What each starter's tools are. Keep in step with the starter catalog: a test fails when a
     * starter has no entry here.
     */
    static final Map<String, ToolClassification> CLASSIFICATIONS =
            Map.of(
                    "fetch", readOnlyThirdParty("fetch"),
                    "time", readOnlyThirdParty("time"),
                    "sequential-thinking", readOnlyThirdParty("sequential-thinking"));

    private static ToolClassification readOnlyThirdParty(String server) {
        return new ToolClassification(
                pattern(server),
                ToolTrustTier.UNTRUSTED_EXTERNAL,
                ToolSensitivity.READ_PUBLIC,
                ToolDeterminism.SIDE_EFFECT,
                null,
                false,
                false);
    }

    static String pattern(String server) {
        return "mcp__" + server + "__*";
    }

    private final TenantSchemaIterator tenants;
    private final McpStarterService starters;
    private final ToolClassificationRegistry classifications;
    private final McpStdioSidecarProperties sidecar;

    public PersonalStarterConnectors(
            TenantSchemaIterator tenants,
            McpStarterService starters,
            ToolClassificationRegistry classifications,
            McpStdioSidecarProperties sidecar) {
        this.tenants = tenants;
        this.starters = starters;
        this.classifications = classifications;
        this.sidecar = sidecar;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE + 100)
    public void seedOnStartup() {
        if (!sidecar.isEnabled()) {
            log.info(
                    "Starter MCP connectors not registered: the MCP runner is off"
                            + " (mcp-stdio-sidecar.enabled=false). They are added on a start with it on.");
            return;
        }
        tenants.forEachActiveTenantSchema(schema -> seedCurrentTenant());
    }

    /** One tenant, with {@code TenantContext} already set by the caller. */
    void seedCurrentTenant() {
        // Newly registered starters, plus any whose registration is still ours from an earlier start:
        // a classification that failed to write then (a transient database error) is retried now
        // instead of leaving the tools unclassified for good.
        Set<String> pending = new LinkedHashSet<>();
        for (McpStarterService.Seeded starter : starters.seedMissing()) {
            pending.add(starter.name());
        }
        pending.addAll(starters.registeredStarterNames());
        if (pending.isEmpty()) {
            return;
        }
        Set<String> held =
                classifications.storedClassifications().stream()
                        .map(ToolClassificationEntity::getToolName)
                        .collect(Collectors.toSet());
        for (String starter : pending) {
            ToolClassification declared = CLASSIFICATIONS.get(starter);
            if (declared == null || held.contains(declared.toolName())) {
                continue;
            }
            try {
                classifications.declare(
                        declared,
                        SOURCE,
                        "Starter connector '"
                                + starter
                                + "': a pinned reference server that only reads. Its output is"
                                + " third-party content, so it is treated as untrusted.",
                        ACTOR);
            } catch (RuntimeException e) {
                log.warn(
                        "Could not classify starter MCP connector '{}' (its tools stay unclassified): {}",
                        starter,
                        e.toString());
            }
        }
    }
}
