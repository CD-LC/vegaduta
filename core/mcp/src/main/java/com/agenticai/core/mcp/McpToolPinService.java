package com.agenticai.core.mcp;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped persistence and policy for tool-definition pinning (see {@link McpToolPin}).
 * Reads and writes whatever schema the ambient {@code TenantContext} points at, like every
 * service in this leaf module; the caller supplies the tenant.
 *
 * <p>{@code app.mcp.tool-pinning.mode}:
 * <ul>
 *   <li>{@code off} - nothing is observed or stored;
 *   <li>{@code warn} (default) - changes are recorded and reported (audit event, API) but agents
 *       keep using the server, so shipping this changes no existing behaviour;
 *   <li>{@code enforce} - a registration whose tool list differs from the approved baseline
 *       contributes no tools to agents until an administrator approves the new list.
 * </ul>
 */
@Service
public class McpToolPinService {

    public enum Mode {
        OFF,
        WARN,
        ENFORCE;

        static Mode parse(String raw) {
            if (raw == null) {
                return WARN;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "off", "false" -> OFF;
                case "enforce", "block" -> ENFORCE;
                default -> WARN;
            };
        }
    }

    /** Outcome of one observation. {@code diff} is empty unless {@code newlyChanged}. */
    public record Observation(
            String serverName, boolean firstPin, boolean newlyChanged, boolean changed, String hash, McpToolPin.Diff diff) {}

    /** API view of one registration's pin state; no entity leaks to controllers. */
    public record PinStatus(
            UUID registrationId,
            String name,
            String mode,
            boolean pinned,
            boolean changed,
            String pinnedHash,
            String observedHash,
            java.time.Instant pinnedAt,
            String pinnedBy,
            java.time.Instant changedAt,
            List<String> added,
            List<String> removed,
            List<String> changedTools) {}

    /** Result of an API approval: the new state plus what the admin just accepted (for the audit event). */
    public record Approval(PinStatus status, McpToolPin.Diff approvedDiff) {}

    private final McpServerRegistrationRepository repository;
    private final Mode mode;

    public McpToolPinService(
            McpServerRegistrationRepository repository,
            @Value("${app.mcp.tool-pinning.mode:warn}") String mode) {
        this.repository = repository;
        this.mode = Mode.parse(mode);
    }

    public Mode mode() {
        return mode;
    }

    @Transactional
    public Optional<Observation> observe(UUID registrationId, List<McpToolPin.ToolDescriptor> tools) {
        if (mode == Mode.OFF) {
            return Optional.empty();
        }
        return repository.findById(registrationId).map(entity -> {
            McpToolPin.Manifest observed = McpToolPin.manifest(tools);
            boolean firstPin = entity.getToolsPinnedHash() == null;
            String previousObserved = entity.getToolsObservedHash();
            boolean newlyChanged =
                    entity.observeTools(observed.aggregateHash(), observed.toJson(), "system:first-use");
            McpToolPin.Diff diff = new McpToolPin.Diff(List.of(), List.of(), List.of());
            if (newlyChanged) {
                diff = McpToolPin.diff(McpToolPin.Manifest.fromJson(entity.getToolsPinnedManifest()), observed);
            }
            if (firstPin || newlyChanged || !observed.aggregateHash().equals(previousObserved)) {
                repository.save(entity);
            }
            return new Observation(entity.getName(), firstPin, newlyChanged, entity.isToolsChanged(), observed.aggregateHash(), diff);
        });
    }

    /** Accepts the last observed tool list as the approved baseline. 404 if the id is unknown. */
    @Transactional
    public McpServerRegistration approve(UUID registrationId, String actor) {
        McpServerRegistration entity = repository
                .findById(registrationId)
                .orElseThrow(() -> new McpServerRegistrationNotFoundException(registrationId));
        if (entity.getToolsObservedHash() != null) {
            entity.pinObservedTools(actor);
            repository.save(entity);
        }
        return entity;
    }

    @Transactional(readOnly = true)
    public McpServerRegistration get(UUID registrationId) {
        return repository
                .findById(registrationId)
                .orElseThrow(() -> new McpServerRegistrationNotFoundException(registrationId));
    }

    /** Diff between baseline and last observation, for the review UI. Empty when unchanged. */
    @Transactional(readOnly = true)
    public McpToolPin.Diff pendingDiff(UUID registrationId) {
        McpServerRegistration entity = repository
                .findById(registrationId)
                .orElseThrow(() -> new McpServerRegistrationNotFoundException(registrationId));
        if (!entity.isToolsChanged()) {
            return new McpToolPin.Diff(List.of(), List.of(), List.of());
        }
        return McpToolPin.diff(
                McpToolPin.Manifest.fromJson(entity.getToolsPinnedManifest()),
                McpToolPin.Manifest.fromJson(entity.getToolsObservedManifest()));
    }

    @Transactional(readOnly = true)
    public List<McpServerRegistration> changedRegistrations() {
        return repository.findAll().stream().filter(McpServerRegistration::isToolsChanged).toList();
    }

    /**
     * Registrations agents must not use right now. Empty unless the mode is {@code enforce}, so
     * {@code warn} never changes what agents can call.
     */
    @Transactional(readOnly = true)
    public Set<UUID> blockedRegistrationIds() {
        if (mode != Mode.ENFORCE) {
            return Set.of();
        }
        return changedRegistrations().stream().map(McpServerRegistration::getId).collect(Collectors.toSet());
    }

    @Transactional(readOnly = true)
    public List<PinStatus> changedStatuses() {
        return changedRegistrations().stream().map(this::statusOf).toList();
    }

    @Transactional(readOnly = true)
    public PinStatus status(UUID registrationId) {
        return statusOf(get(registrationId));
    }

    /** Approve and report in one transaction: the diff is captured before the baseline moves. */
    @Transactional
    public Approval approveWithDiff(UUID registrationId, String actor) {
        McpToolPin.Diff diff = pendingDiff(registrationId);
        return new Approval(statusOf(approve(registrationId, actor)), diff);
    }

    private PinStatus statusOf(McpServerRegistration e) {
        McpToolPin.Diff diff = e.isToolsChanged()
                ? McpToolPin.diff(
                        McpToolPin.Manifest.fromJson(e.getToolsPinnedManifest()),
                        McpToolPin.Manifest.fromJson(e.getToolsObservedManifest()))
                : new McpToolPin.Diff(List.of(), List.of(), List.of());
        return new PinStatus(
                e.getId(), e.getName(), mode.name().toLowerCase(Locale.ROOT), e.getToolsPinnedHash() != null,
                e.isToolsChanged(), e.getToolsPinnedHash(), e.getToolsObservedHash(), e.getToolsPinnedAt(),
                e.getToolsPinnedBy(), e.getToolsChangedAt(), diff.added(), diff.removed(), diff.changed());
    }
}
