package com.agenticai.core.mcp.registry;

import com.agenticai.core.mcp.McpServerType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * The seam between the official registry's raw server schema ({@link
 * RegistryApiTypes}) and something this platform can actually register and
 * launch ({@link RegistrySearchResult}, consumed by the same {@code
 * McpServerRegistrationRequest} path the curated catalog already uses). Pure
 * and deterministic - no I/O, no Spring state - so the tricky part (turning a
 * registry {@code package} into a real {@code command}/{@code args}/env spec
 * for four different runtimes) is exhaustively unit-testable without standing
 * up an HTTP client or a subprocess. {@code @Component} only so it can be
 * injected; it holds nothing.
 *
 * <p><b>Runtime coverage</b> (the "everything" scope): a registry server can
 * ship one or more {@code packages} (locally-launched) and/or {@code remotes}
 * (a hosted endpoint). This maps:
 * <ul>
 *   <li>{@code npm} package -&gt; {@code npx} (Node) - always launchable here.</li>
 *   <li>{@code pypi} package -&gt; {@code uvx} (uv) - launchable where {@code uv}
 *       is installed; the live connect surfaces a clean ERROR if it isn't.</li>
 *   <li>{@code oci} package -&gt; {@code docker run} - launchable where Docker is.</li>
 *   <li>{@code remotes[]} -&gt; a remote SSE / streamable-HTTP registration (no
 *       local subprocess at all) when no launchable package is present.</li>
 *   <li>anything else ({@code nuget}, {@code mcpb}, unknown) -&gt; surfaced but
 *       marked {@link RegistrySearchResult#runnable() not runnable}, so the UI
 *       shows it honestly and disables Add rather than registering something
 *       that can never connect.</li>
 * </ul>
 */
@Component
public class RegistryServerMapper {

    private static final String RUNTIME_NPX = "npx";
    private static final String RUNTIME_UVX = "uvx";
    private static final String RUNTIME_DOCKER = "docker";
    private static final String RUNTIME_REMOTE = "remote";
    private static final String RUNTIME_UNSUPPORTED = "unsupported";

    /**
     * @param rawStatus the sibling {@code _meta} envelope's {@code
     *     io.modelcontextprotocol.registry/official.status}, or null if the
     *     registry omitted {@code _meta} entirely - see {@link
     *     RegistryApiTypes.Envelope}'s javadoc for why this is a caller-
     *     supplied parameter rather than read off {@code server} itself.
     */
    RegistrySearchResult map(RegistryApiTypes.Server server, String rawStatus) {
        String name = server.name();
        String displayName = displayNameOf(server);
        String description = server.description() == null ? "" : server.description();
        String repoUrl = server.repository() == null ? null : server.repository().url();
        // Fail open: an unrecognized/missing status is more likely a schema
        // addition upstream than a real reason to hide or warn about a result.
        String status = blank(rawStatus) ? "active" : rawStatus;

        RegistryApiTypes.Package pkg = selectPackage(server.packages());
        if (pkg != null) {
            return mapPackage(server, pkg, name, displayName, description, repoUrl, status);
        }
        RegistryApiTypes.Remote remote = selectRemote(server.remotes());
        if (remote != null) {
            return mapRemote(server, remote, name, displayName, description, repoUrl, status);
        }
        // No package and no remote we understand - visible but not addable.
        // reviewRequired is a placeholder (STDIO type) - McpRegistryController
        // overwrites it with the real McpCatalogService-based value; see
        // RegistrySearchResult#reviewRequired's javadoc.
        return new RegistrySearchResult(
            name, displayName, description, server.version(), repoUrl,
            McpServerType.STDIO, RUNTIME_UNSUPPORTED, false,
            "This registry entry has no launchable package or remote endpoint this platform supports yet.",
            null, List.of(), null, List.of(), status, true
        );
    }

    // --- package (locally-launched) path -----------------------------------

    private RegistrySearchResult mapPackage(
        RegistryApiTypes.Server server, RegistryApiTypes.Package pkg,
        String name, String displayName, String description, String repoUrl, String status
    ) {
        String registryType = lower(pkg.registryType());
        String runtime = runtimeFor(registryType, pkg.runtimeHint());
        String version = pkg.version() != null ? pkg.version() : server.version();

        if (RUNTIME_UNSUPPORTED.equals(runtime)) {
            // reviewRequired is a placeholder (STDIO type) - see the note above.
            return new RegistrySearchResult(
                name, displayName, description, version, repoUrl,
                McpServerType.STDIO, RUNTIME_UNSUPPORTED, false,
                "Runtime '" + (pkg.registryType() == null ? "unknown" : pkg.registryType())
                    + "' isn't supported on this deployment (supported: npm/npx, pypi/uvx, oci/docker, remote).",
                null, List.of(), null, List.of(), status, true
            );
        }

        List<RegistryEnvVarSpec> envVars = new ArrayList<>();
        for (RegistryApiTypes.EnvVar e : nullSafe(pkg.environmentVariables())) {
            if (e == null || blank(e.name())) {
                continue;
            }
            envVars.add(new RegistryEnvVarSpec(
                e.name(), e.description(),
                Boolean.TRUE.equals(e.isRequired()),
                Boolean.TRUE.equals(e.isSecret()) || looksSecret(e.name()),
                e.defaultValue(), false
            ));
        }

        List<String> args = new ArrayList<>();
        String command;
        switch (runtime) {
            case RUNTIME_NPX -> {
                command = RUNTIME_NPX;
                List<String> runtimeArgs = resolveArgs(pkg.runtimeArguments(), envVars);
                // A subprocess must never block on npx's interactive "install
                // this package? (y)" prompt - force -y if the registry entry
                // didn't already include it.
                if (runtimeArgs.stream().noneMatch(a -> a.equals("-y") || a.equals("--yes"))) {
                    args.add("-y");
                }
                args.addAll(runtimeArgs);
                args.add(npmToken(pkg.identifier(), pkg.version()));
                args.addAll(resolveArgs(pkg.packageArguments(), envVars));
            }
            case RUNTIME_UVX -> {
                command = RUNTIME_UVX;
                args.addAll(resolveArgs(pkg.runtimeArguments(), envVars));
                // uvx pins with 'package@version'; bare identifier if unversioned.
                args.add(blank(pkg.version()) ? pkg.identifier() : pkg.identifier() + "@" + pkg.version());
                args.addAll(resolveArgs(pkg.packageArguments(), envVars));
            }
            case RUNTIME_DOCKER -> {
                command = RUNTIME_DOCKER;
                args.add("run");
                args.add("-i");
                args.add("--rm");
                // Forward each declared env var into the container. `-e NAME`
                // (no =value) makes docker pass NAME through from the launcher's
                // own environment, which is exactly where the connector sets the
                // tenant's decrypted value - so secrets never appear in argv.
                for (RegistryEnvVarSpec e : envVars) {
                    args.add("-e");
                    args.add(e.name());
                }
                args.addAll(resolveArgs(pkg.runtimeArguments(), envVars));
                args.add(dockerImage(pkg.identifier(), pkg.version()));
                args.addAll(resolveArgs(pkg.packageArguments(), envVars));
            }
            default -> {
                // Unreachable (guarded above) - defensive.
                command = null;
            }
        }

        // reviewRequired is a placeholder (STDIO type) - see the note above.
        return new RegistrySearchResult(
            name, displayName, description, version, repoUrl,
            McpServerType.STDIO, runtime, true, null,
            command, List.copyOf(args), null, List.copyOf(envVars), status, true
        );
    }

    // --- remote (hosted endpoint) path -------------------------------------

    private RegistrySearchResult mapRemote(
        RegistryApiTypes.Server server, RegistryApiTypes.Remote remote,
        String name, String displayName, String description, String repoUrl, String status
    ) {
        McpServerType type = "sse".equalsIgnoreCase(remote.type())
            ? McpServerType.SSE : McpServerType.STREAMABLE_HTTP;
        // Remote registrations are always McpReviewStatus.NOT_REQUIRED - no
        // subprocess spawned, so this is the real final value, not a
        // placeholder (unlike the STDIO branches above).
        return new RegistrySearchResult(
            name, displayName, description, server.version(), repoUrl,
            type, RUNTIME_REMOTE, true, null,
            null, List.of(), remote.url(), List.of(), status, false
        );
    }

    // --- selection ---------------------------------------------------------

    /**
     * Picks the best package to launch: prefer a runtime this platform runs
     * natively/most reliably (npm, then oci, then pypi); fall back to the first
     * package so an all-unsupported entry still maps (to a not-runnable result
     * that explains why) rather than silently vanishing.
     */
    private RegistryApiTypes.Package selectPackage(List<RegistryApiTypes.Package> packages) {
        List<RegistryApiTypes.Package> list = nullSafe(packages).stream().filter(p -> p != null).toList();
        if (list.isEmpty()) {
            return null;
        }
        for (String preferred : List.of("npm", "oci", "pypi")) {
            for (RegistryApiTypes.Package p : list) {
                if (preferred.equals(lower(p.registryType()))) {
                    return p;
                }
            }
        }
        return list.get(0);
    }

    private RegistryApiTypes.Remote selectRemote(List<RegistryApiTypes.Remote> remotes) {
        for (RegistryApiTypes.Remote r : nullSafe(remotes)) {
            if (r != null && !blank(r.url())) {
                return r;
            }
        }
        return null;
    }

    // --- argument resolution ----------------------------------------------

    /**
     * Turns registry {@code Argument}s into a flat arg list. A fixed {@code
     * value} is emitted literally; a required arg with no fixed value becomes a
     * {@code {{KEY}}} placeholder plus a positional {@link RegistryEnvVarSpec}
     * the Add form will collect and splice in; an optional valueless arg is
     * dropped.
     */
    private List<String> resolveArgs(List<RegistryApiTypes.Argument> args, List<RegistryEnvVarSpec> collectedSpecs) {
        List<String> out = new ArrayList<>();
        for (RegistryApiTypes.Argument a : nullSafe(args)) {
            if (a == null) {
                continue;
            }
            boolean named = "named".equalsIgnoreCase(a.type()) && !blank(a.name());
            if (named) {
                out.add(a.name());
            }
            if (!blank(a.value())) {
                out.add(a.value());
            } else if (Boolean.TRUE.equals(a.isRequired())) {
                String key = argKey(a);
                out.add("{{" + key + "}}");
                collectedSpecs.add(new RegistryEnvVarSpec(
                    key, a.valueHint(), true, looksSecret(key), a.defaultValue(), true
                ));
            } else if (!blank(a.defaultValue())) {
                out.add(a.defaultValue());
            }
            // else: optional arg with no supplied/registry value -> omit.
        }
        return out;
    }

    // --- helpers -----------------------------------------------------------

    private static String runtimeFor(String registryType, String runtimeHint) {
        if (registryType == null) {
            return RUNTIME_UNSUPPORTED;
        }
        return switch (registryType) {
            case "npm" -> RUNTIME_NPX;
            case "pypi" -> RUNTIME_UVX;
            case "oci" -> RUNTIME_DOCKER;
            default -> RUNTIME_UNSUPPORTED;
        };
    }

    private static String npmToken(String identifier, String version) {
        return blank(version) ? identifier : identifier + "@" + version;
    }

    private static String dockerImage(String identifier, String version) {
        // Don't append a tag if the identifier already carries one (a ':' after
        // any registry-host ':port' - checked on the last path segment).
        String lastSegment = identifier.contains("/") ? identifier.substring(identifier.lastIndexOf('/') + 1) : identifier;
        if (lastSegment.contains(":") || blank(version)) {
            return identifier;
        }
        return identifier + ":" + version;
    }

    private static String argKey(RegistryApiTypes.Argument a) {
        String base = !blank(a.name()) ? a.name() : (!blank(a.valueHint()) ? a.valueHint() : "arg");
        String cleaned = base.replaceAll("^-+", "").replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return cleaned.isEmpty() ? "arg" : cleaned.toUpperCase(Locale.ROOT);
    }

    private static String displayNameOf(RegistryApiTypes.Server server) {
        if (!blank(server.title())) {
            return server.title();
        }
        String name = server.name() == null ? "" : server.name();
        int slash = name.lastIndexOf('/');
        return slash >= 0 && slash < name.length() - 1 ? name.substring(slash + 1) : name;
    }

    private static boolean looksSecret(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("token") || k.contains("key") || k.contains("secret")
            || k.contains("password") || k.contains("passwd") || k.contains("pat")
            || k.contains("credential") || k.contains("auth");
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
