package com.agenticai.core.providers.addons;

import com.agenticai.core.providers.localmodels.HardwareProfile;
import java.util.List;
import java.util.Map;

/** The JSON shapes of {@code /api/addons}. Field names are the contract (see the controller). */
public final class AddonApi {

    private AddonApi() {}

    /**
     * One add-on: the catalog entry plus its state. {@code hostCommand} is the manual command for a
     * default install; for the whole wanted set use {@link Overview#apply()}.
     */
    public record View(
            String id,
            String name,
            String description,
            String category,
            String kind,
            String availability,
            int sizeMb,
            int minRamMb,
            boolean needsGpu,
            String license,
            boolean free,
            List<String> requires,
            boolean defaultOn,
            String hostCommand,
            String learnMore,
            List<String> unlocks,
            String model,
            // --- state ---
            boolean desired,
            String status,
            String message,
            Integer progress,
            Boolean installed,
            String desiredAt,
            String reportedAt,
            String fit,
            boolean canEnable,
            String blockedReason,
            List<String> requiredBy) {}

    /**
     * What the host has to do to match the wanted set. {@code command} only ever ADDS: the launcher
     * keeps what it remembers and never removes an add-on because a later command omits it, so
     * turning one off is a separate step (the Setup app, or a {@code --no-*} option).
     */
    public record Apply(
            String command,
            List<String> profiles,
            List<String> flags,
            Map<String, String> envSet,
            List<String> envUnset,
            List<String> pending) {}

    public record HostBridge(boolean configured) {}

    public record OllamaInfo(boolean reachable) {}

    public record Overview(
            int catalogSchema,
            HardwareProfile hardware,
            HostBridge hostBridge,
            OllamaInfo ollama,
            Apply apply,
            List<View> addons) {}

    /**
     * Answer of enable and disable. {@code changed} lists every add-on whose wish changed (an add-on
     * and what it needs). {@code needsHost} is true when the change only takes effect once the host
     * applies {@code apply}.
     */
    public record ChangeResult(View addon, List<String> changed, boolean needsHost, Apply apply) {}

    public record HostItem(String id, boolean wanted, long rev, String status, DefinitionCompose compose) {}

    /** The compose part of a catalog entry, as the installer needs it. */
    public record DefinitionCompose(List<String> profiles, List<String> flags, Map<String, String> env) {}

    /** {@code GET /api/addons/host/wanted}. {@code fingerprint} changes whenever any wish does. */
    public record HostWanted(String generatedAt, String fingerprint, Apply apply, List<HostItem> addons) {}

    public record ReportItem(String id, Long rev, String status, String message) {}

    public record ReportRequest(List<ReportItem> reports) {}

    public record IgnoredReport(String id, String reason) {}

    public record ReportResult(int accepted, List<IgnoredReport> ignored) {}

    /** Body of every error this API produces itself. */
    public record ErrorBody(String error, String message, Map<String, Object> details) {}
}
