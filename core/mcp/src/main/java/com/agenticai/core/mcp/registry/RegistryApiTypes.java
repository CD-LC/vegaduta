package com.agenticai.core.mcp.registry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Package-private records mirroring the JSON shape of the official MCP
 * Registry's {@code GET /v0/servers} response
 * (registry.modelcontextprotocol.io) - re-verified against a LIVE call
 * 2026-08-03 ({@code curl "https://registry.modelcontextprotocol.io/v0/servers
 * ?search=github&limit=2"}), which is what caught {@link Envelope}'s javadoc
 * bug: an earlier version of this class assumed a top-level {@code verified}
 * boolean directly on {@code Server} that does not exist anywhere in the real
 * payload (it always deserialized to {@code null}), and separately missed
 * that {@code _meta} is a SIBLING of {@code server} inside each list element,
 * not nested inside it. Kept deliberately separate from the platform's own
 * outward-facing {@link RegistrySearchResult}: this is the untrusted external
 * schema, {@link RegistryServerMapper} is the seam that turns it into
 * something this platform can actually launch. Every record is {@code
 * @JsonIgnoreProperties(ignoreUnknown = true)} because the registry schema
 * evolves independently of this code and MUST NOT break parsing when it adds
 * a field.
 *
 * <p>The registry's JSON is camelCase ({@code registryType}, {@code
 * runtimeHint}, {@code environmentVariables}, {@code isRequired}) so Jackson's
 * default naming maps most fields directly; the exceptions needing an
 * explicit {@link JsonProperty} are reserved-word-ish keys ({@code default})
 * and {@code _meta}'s namespaced sub-keys ({@code
 * io.modelcontextprotocol.registry/official}, not a valid Java identifier).
 */
final class RegistryApiTypes {

    private RegistryApiTypes() {
    }

    /** Top-level {@code GET /v0/servers} body: {@code { servers: [...], metadata: {...} }}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ListResponse(List<Envelope> servers, Metadata metadata) {
    }

    /**
     * Each element of {@code servers} wraps the actual server under a {@code
     * server} key, plus registry {@code _meta} - confirmed 2026-08-03 against
     * a live call ({@code GET /v0/servers?search=github}): {@code _meta} sits
     * as a SIBLING of {@code server}, not nested inside it. There is no
     * {@code verified} field anywhere in the real payload (an earlier version
     * of {@link Server} guessed one existed and always read {@code null} -
     * see git history for the fix). The only genuine per-server signal the
     * registry exposes is lifecycle {@link OfficialMeta#status}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope(Server server, Meta meta) {
    }

    /** {@code _meta} - a namespaced-key bag; this platform only reads the official registry's own namespace. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Meta(
        @JsonProperty("io.modelcontextprotocol.registry/official") OfficialMeta official
    ) {
    }

    /**
     * @param status one of {@code "active"} (maintained), {@code "deprecated"}
     *     (no longer recommended), {@code "deleted"} (removed) - per the
     *     registry's own API reference. Absent/unrecognized values are
     *     treated as {@code "active"} by {@link RegistryServerMapper} (fail
     *     open: an unrecognized status is more likely a schema addition than
     *     a reason to hide a result).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record OfficialMeta(String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Server(
        String name,
        String description,
        String title,
        String version,
        Repository repository,
        List<Package> packages,
        List<Remote> remotes
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Repository(String url, String source, String subfolder) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Package(
        String registryType,     // npm | pypi | oci | nuget | mcpb ...
        String registryBaseUrl,
        String identifier,
        String version,
        String runtimeHint,      // npx | uvx | docker ...
        Transport transport,
        List<Argument> runtimeArguments,
        List<Argument> packageArguments,
        List<EnvVar> environmentVariables
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Transport(String type, String url) {
    }

    /**
     * A runtime/package argument. Positional args carry {@code value}; named
     * args carry {@code name} (and optionally {@code value}). {@code valueHint}
     * is a human placeholder shown when neither is fixed.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Argument(
        String type,             // "positional" | "named"
        String name,
        String value,
        String valueHint,
        @JsonProperty("default") String defaultValue,
        Boolean isRequired,
        Boolean isRepeated
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EnvVar(
        String name,
        String description,
        Boolean isRequired,
        Boolean isSecret,
        @JsonProperty("default") String defaultValue
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Remote(String type, String url) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Metadata(String nextCursor, Integer count) {
    }
}
