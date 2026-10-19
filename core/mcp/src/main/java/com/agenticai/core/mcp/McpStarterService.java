package com.agenticai.core.mcp;

import com.agenticai.core.mcp.dto.McpCatalogEntry;
import com.agenticai.core.mcp.dto.McpServerRegistrationRequest;
import com.agenticai.core.mcp.dto.McpServerRegistrationResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Registers the {@link McpStarterCatalog} connectors for the CURRENT tenant, once each.
 *
 * <p><b>Idempotent and never destructive.</b> A starter is offered at most once per tenant: after it
 * has been offered, {@code mcp_starter_seed} remembers that, so deleting it, switching it off or
 * editing it sticks. A registration that already carries the starter's name (the owner made one by
 * hand) is left exactly as it is and counts as offered. Nothing here updates or deletes a row.
 *
 * <p><b>Same path as the console.</b> The row is created through {@link McpRegistryService#register},
 * so the STDIO launcher guard, the plan gate, env encryption and the review-status decision all run
 * as for a hand-added server. The launch spec is the catalog's exact one, which {@link
 * McpCatalogService#findStrictMatch} accepts as vetted, so no review is queued.
 *
 * <p>The caller owns tenant routing ({@code TenantContext} must be set to the tenant's schema) and
 * connecting: this class only writes rows. It is deliberately not {@code @Transactional}, so one
 * failed registration cannot mark the others rollback-only.
 */
@Service
public class McpStarterService {

    private static final Logger log = LoggerFactory.getLogger(McpStarterService.class);

    /** One registration this pass created. {@code enabled} false = added switched off. */
    public record Seeded(UUID id, String name, boolean enabled) {}

    private final McpRegistryService registry;
    private final McpServerRegistrationRepository registrations;
    private final McpStarterSeedRepository seeds;

    public McpStarterService(
            McpRegistryService registry,
            McpServerRegistrationRepository registrations,
            McpStarterSeedRepository seeds) {
        this.registry = registry;
        this.registrations = registrations;
        this.seeds = seeds;
    }

    /** Names of every starter, in registration order. */
    public static List<String> starterNames() {
        return McpStarterCatalog.STARTERS.stream().map(McpStarterCatalog.Starter::name).toList();
    }

    /** Registers each starter this tenant has not been offered yet; returns what it created. */
    public List<Seeded> seedMissing() {
        List<Seeded> created = new ArrayList<>();
        for (McpStarterCatalog.Starter starter : McpStarterCatalog.STARTERS) {
            String key = starter.name();
            try {
                if (seeds.existsById(key)) {
                    continue;
                }
                if (registrations.findByName(key).isPresent()) {
                    // The owner already has a connector by this name: theirs, never touched.
                    markOffered(key);
                    continue;
                }
                McpCatalogEntry entry = starter.entry();
                McpServerRegistrationResponse saved =
                        registry.register(
                                new McpServerRegistrationRequest(
                                        entry.name(),
                                        entry.type(),
                                        entry.description(),
                                        starter.enabledByDefault(),
                                        entry.dynamicCommand(),
                                        entry.dynamicArgs(),
                                        null));
                if (saved.reviewStatus() != McpReviewStatus.NOT_REQUIRED) {
                    // The catalog and the guard disagree: a bug, not a state to ship quietly.
                    log.warn(
                            "Starter MCP connector '{}' was registered with review status {} instead of NOT_REQUIRED;"
                                    + " it will not connect until reviewed",
                            key,
                            saved.reviewStatus());
                }
                markOffered(key);
                created.add(new Seeded(saved.id(), entry.name(), starter.enabledByDefault()));
                log.info(
                        "Registered starter MCP connector '{}' ({})",
                        key,
                        starter.enabledByDefault() ? "on" : "off");
            } catch (RuntimeException e) {
                // Not marked offered: the next start tries again (a plan cap, a transient database error).
                log.warn("Could not register starter MCP connector '{}': {}", key, e.toString());
            }
        }
        return created;
    }

    /**
     * Names of the starters this tenant has a registration for that still carries the catalog's exact
     * launch spec (command and arguments). Lets a caller finish per-starter work that a failed start
     * left undone without touching a connector the owner made by hand under the same name.
     */
    public List<String> registeredStarterNames() {
        List<String> names = new ArrayList<>();
        for (McpStarterCatalog.Starter starter : McpStarterCatalog.STARTERS) {
            try {
                McpCatalogEntry entry = starter.entry();
                boolean ours =
                        registrations
                                .findByName(starter.name())
                                .filter(r -> entry.dynamicCommand() != null && entry.dynamicCommand().equals(r.getCommand()))
                                .filter(r -> entry.dynamicArgs().equals(McpRegistrationEnv.readJsonList(r.getArgs())))
                                .isPresent();
                if (ours) {
                    names.add(starter.name());
                }
            } catch (RuntimeException e) {
                log.warn("Could not look up starter MCP connector '{}': {}", starter.name(), e.toString());
            }
        }
        return names;
    }

    private void markOffered(String key) {
        seeds.save(new McpStarterSeed(key, Instant.now()));
    }
}
