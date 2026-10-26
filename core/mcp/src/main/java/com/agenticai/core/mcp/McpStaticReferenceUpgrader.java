package com.agenticai.core.mcp;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.mcp.dto.McpCatalogEntry;
import com.agenticai.core.mcp.dto.McpServerRegistrationResponse;
import com.agenticai.core.secrets.SecretReferenceResolver;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bug fix ("enabled puppeteer/playwright, agent still has no tools"): a name-only ("ready"
 * catalog) registration is a REFERENCE to a statically wired shared connection, not a connection
 * of its own. When the deployment can't serve that reference - no "mcp" Spring profile (lite and
 * personal tiers wire none), or the server is stateful and hidden by the static-exposure policy -
 * enabling it used to silently connect nothing: no dynamic config, no live client, no tools. In
 * that case the registration upgrades itself to the catalog entry's own per-tenant launch spec
 * (the same curated spec the dynamic-connect form submits), becoming an ordinary dynamic
 * connection the stdio runner can spawn and mark READY.
 *
 * <p>A registration the static path CAN serve stays a reference - one shared process beats a
 * duplicate per-tenant spawn for a stateless server. A spec containing {@code {{VAR}}}
 * placeholders can't be auto-filled (no tenant value exists yet), so entries like filesystem are
 * never upgraded here - a tenant still registers them with its own path/credentials.
 *
 * <p>Extracted from {@code McpRegistryService} (file-size cap): this class owns the whole
 * "is the reference servable -> upgrade it" concern, plus {@link #ensureConnectable} for the
 * explicit-connect path.
 */
@Component
class McpStaticReferenceUpgrader {

    private static final Logger log = LoggerFactory.getLogger(McpStaticReferenceUpgrader.class);

    private final McpServerRegistrationRepository repository;
    private final McpCatalogService catalogService;
    private final McpStdioCommandGuard stdioCommandGuard;
    private final McpRegistrationEnv envCodec;
    // agents-tools' StaticMcpServerAvailabilityAdapter (the only module that can see the static
    // client list + exposure policy). An unresolved provider = "cannot tell whether the static
    // wiring serves it" - treated as NOT served, because upgrading to a per-tenant launch spec is
    // strictly better than leaving the registration dead (a dynamic copy is the correct isolation
    // even when a shared static client would have worked).
    private final Supplier<McpStaticServerAvailability> availability;

    @org.springframework.beans.factory.annotation.Autowired
    McpStaticReferenceUpgrader(
            McpServerRegistrationRepository repository,
            McpCatalogService catalogService,
            McpStdioCommandGuard stdioCommandGuard,
            EncryptionService encryptionService,
            SecretReferenceResolver secretReferenceResolver,
            ObjectProvider<McpStaticServerAvailability> staticServerAvailability) {
        this(repository, catalogService, stdioCommandGuard, encryptionService,
                secretReferenceResolver,
                () -> staticServerAvailability == null
                        ? null
                        : staticServerAvailability.getIfAvailable());
    }

    /** Convenience for tests: the availability lookup directly (null/empty = "not served"). */
    McpStaticReferenceUpgrader(
            McpServerRegistrationRepository repository,
            McpCatalogService catalogService,
            McpStdioCommandGuard stdioCommandGuard,
            EncryptionService encryptionService,
            SecretReferenceResolver secretReferenceResolver,
            Supplier<McpStaticServerAvailability> availability) {
        this.repository = repository;
        this.catalogService = catalogService;
        this.stdioCommandGuard = stdioCommandGuard;
        this.envCodec = new McpRegistrationEnv(encryptionService, secretReferenceResolver);
        this.availability = availability;
    }

    /**
     * Upgrades {@code entity} in place when it is an unservable static reference; a no-op for a
     * dynamic row, a name with no catalog spec, a placeholder spec, or a reference the static
     * wiring does serve. Called inside the caller's transaction ({@code register},
     * {@code setEnabled}).
     */
    void upgradeIfUnserved(McpServerRegistration entity) {
        if (entity.isDynamic()) {
            return;
        }
        McpCatalogEntry entry = catalogService.find(entity.getName()).orElse(null);
        if (entry == null
                || entry.dynamicCommand() == null
                || hasPlaceholderArgs(entry.dynamicArgs())) {
            return;
        }
        McpStaticServerAvailability check = availability.get();
        if (check != null && check.usable(entity.getName())) {
            return;
        }
        // Same guard + review-cleared posture as the catalogSourced dynamic branch in
        // McpRegistryService#register: the launch spec is the platform's own curated one.
        stdioCommandGuard.requireAllowed(entry.dynamicCommand(), entry.dynamicArgs());
        entity.configureDynamic(
                entry.dynamicCommand(),
                McpRegistrationEnv.writeJson(entry.dynamicArgs()),
                envCodec.encrypt(java.util.Map.of()));
        entity.markReviewNotRequired();
        log.info(
                "MCP registration '{}' upgraded to a per-tenant dynamic connection (the shared"
                        + " static server is unavailable or hidden in this deployment)",
                entity.getName());
    }

    /**
     * The explicit-connect path's version of the repair: a static-reference row whose shared
     * server this deployment can't serve upgrades to its catalog launch spec first, so the {@code
     * connect} the controller then dispatches has a real dynamic config to open. A no-op for
     * dynamic rows and for references the static wiring does serve.
     */
    @Transactional
    public McpServerRegistrationResponse ensureConnectable(UUID id) {
        McpServerRegistration entity =
                repository
                        .findById(id)
                        .orElseThrow(() -> new McpServerRegistrationNotFoundException(id));
        upgradeIfUnserved(entity);
        return McpServerRegistrationResponse.from(repository.save(entity));
    }

    private static boolean hasPlaceholderArgs(List<String> args) {
        if (args == null) {
            return false;
        }
        return args.stream().anyMatch(a -> a != null && a.matches("^\\{\\{[^}]+}}$"));
    }
}
