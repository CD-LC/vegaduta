package com.agenticai.core.mcp.registry;

import com.agenticai.core.mcp.McpCatalogService;
import com.agenticai.core.mcp.McpReviewStatus;
import com.agenticai.core.mcp.McpServerType;
import java.util.List;

/**
 * One official-registry server, mapped into everything this platform needs to
 * (a) display it in the "Browse registry" search and (b) register it for a
 * tenant through the existing dynamic-connection path ({@code
 * McpServerRegistrationRequest} -> {@code DynamicMcpConnectionManager}) with
 * no further lookup. The mapping from the registry's raw
 * packages/remotes/environmentVariables happens once, server-side, in {@link
 * RegistryServerMapper} - the frontend never parses the registry schema
 * itself.
 *
 * @param name         the registry's canonical reverse-DNS server name (e.g.
 *                     {@code io.github.acme/weather}) - unique, and what gets
 *                     stored as the registration name
 * @param displayName  a friendlier label (registry {@code title}, else the
 *                     last path segment of {@code name})
 * @param description  short human description from the registry
 * @param version      the server version this mapping was resolved from
 * @param repositoryUrl source repo link, or null - shown as a "view source"
 * @param type         transport: STDIO for a locally-launched package, or
 *                     SSE / STREAMABLE_HTTP for a remote endpoint
 * @param runtime      coarse runtime label for a UI badge and for the operator
 *                     to understand what will run: {@code npx} / {@code uvx} /
 *                     {@code docker} / {@code remote} / {@code unsupported}
 * @param runnable     whether THIS deployment can actually launch it - false
 *                     for registry entries whose only packages use a runtime
 *                     this platform doesn't wire (e.g. nuget, mcpb); the UI
 *                     shows these but disables "Add"
 * @param notRunnableReason  null when {@link #runnable}; otherwise a short,
 *                     honest reason to show instead of an Add button
 * @param command      resolved launch command for a STDIO server ({@code npx}
 *                     / {@code uvx} / {@code docker}), null for a remote server
 * @param args         resolved launch args. An element written {@code
 *                     "{{NAME}}"} is a placeholder the frontend replaces with
 *                     the value collected for the {@link #envVars} entry named
 *                     {@code NAME} (a positional secret), instead of sending it
 *                     as an env var - identical convention to the curated
 *                     catalog's {@code dynamicArgs}
 * @param url          remote endpoint URL for a SSE / STREAMABLE_HTTP server,
 *                     null for a STDIO server
 * @param envVars      every credential/config input the server needs - drives
 *                     the Add form
 * @param status       the registry's own lifecycle status for this server -
 *                     {@code "active"}, {@code "deprecated"}, or {@code
 *                     "deleted"} (see {@code RegistryApiTypes.OfficialMeta}).
 *                     Defaults to {@code "active"} when the registry omits it.
 *                     There is no separate "verified" concept: the registry's
 *                     real API has no such field (an earlier version of this
 *                     record had one that always read {@code false} for every
 *                     result - see git history, 2026-08-03 fix) - EVERY name
 *                     published to the official registry already proved
 *                     namespace ownership at publish time (GitHub OAuth for
 *                     {@code io.github.*}, DNS TXT record for a domain
 *                     namespace), so a "verified" badge would be near-
 *                     universal and would not actually distinguish anything.
 *                     {@code status} is the one signal that IS real and
 *                     useful: it tells a tenant whether the maintainer still
 *                     recommends this server.
 * @param reviewRequired whether adding this result today would start life as
 *                     {@link McpReviewStatus#PENDING} - blocked from actually
 *                     connecting until platform staff manually approve it -
 *                     rather than {@link McpReviewStatus#NOT_REQUIRED} (usable
 *                     immediately). This is NOT a "verified"/"safe" signal
 *                     (see the {@link #status} note above for why that concept
 *                     was deliberately removed 2026-08-03) - it is the
 *                     existing, already-enforced {@link McpReviewStatus} gate
 *                     from {@code McpRegistryService#register}, computed here
 *                     ahead of time so the UI can tell a tenant honestly
 *                     whether "Add" means "usable now" or "usable once staff
 *                     sign off": {@code false} for every remote (SSE /
 *                     streamable-HTTP) result and for any STDIO result whose
 *                     {@link #name} matches a {@link McpCatalogService}-curated
 *                     entry, {@code true} for every other STDIO result.
 */
public record RegistrySearchResult(
    String name,
    String displayName,
    String description,
    String version,
    String repositoryUrl,
    McpServerType type,
    String runtime,
    boolean runnable,
    String notRunnableReason,
    String command,
    List<String> args,
    String url,
    List<RegistryEnvVarSpec> envVars,
    String status,
    boolean reviewRequired
) {
    /**
     * Copy with {@link #reviewRequired} replaced - used by {@code
     * McpRegistryController#search} to overwrite {@link RegistryServerMapper}'s
     * STDIO placeholder with the real, {@link McpCatalogService}-aware value
     * once the results leave the mapper (which is deliberately catalog-blind -
     * see its class javadoc). A no-op copy for the REMOTE case, whose mapper
     * output is already final.
     */
    public RegistrySearchResult withReviewRequired(boolean newReviewRequired) {
        return new RegistrySearchResult(
            name, displayName, description, version, repositoryUrl,
            type, runtime, runnable, notRunnableReason,
            command, args, url, envVars, status, newReviewRequired
        );
    }
}
