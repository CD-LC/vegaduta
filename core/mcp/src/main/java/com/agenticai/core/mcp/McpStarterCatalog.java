package com.agenticai.core.mcp;

import com.agenticai.core.mcp.dto.McpCatalogEntry;
import java.util.List;
import java.util.Optional;

/**
 * The MCP servers a personal install registers for its owner on first start (see {@link
 * McpStarterService}): well-known reference servers that need no account and no secret, each pinned
 * to an EXACT package version.
 *
 * <p><b>Why a second list and not the gallery.</b> {@link McpReferenceServerCatalog} is the gallery
 * every shape shows; an entry there with no credential gets a plain "Add" button that registers a
 * name with no launch command (the bug its javadoc describes). These entries are launch specs the
 * platform registers itself, so they are NOT offered by {@link McpCatalogService#list()}. They are
 * still curated: {@link McpCatalogService#findStrictMatch} accepts the exact spec below as
 * catalog-sourced, so a starter registration skips the STDIO review queue and a changed spec does
 * not.
 *
 * <p><b>Pinned means exact.</b> Each version below was read from the package registry on
 * 2026-10-19 and is at least two weeks old at that date. The registry-published digests are kept
 * next to the entry so a reviewer can compare them with what the runner downloads. The runner does
 * not enforce them: {@code npx} and {@code uvx} resolve a version, not a hash (a follow-up, see
 * docs/architecture/OUT-OF-THE-BOX-PERSONAL-2026-10-19.md, "Verified means pinned"). Bump a version
 * deliberately, in this file, with the new digest; never float it.
 *
 * <p><b>What is deliberately absent.</b>
 *
 * <ul>
 *   <li>{@code memory}: the runner gives every spawned server a private temp HOME that is deleted
 *       when it exits, so the knowledge graph would be lost on every reconnect, and the platform
 *       already has {@code remember_fact}.
 *   <li>{@code filesystem}: the Assistant already has {@code workspace_*} tools for the projects
 *       folder, and mounting that folder into the runner would let any later, reviewed third-party
 *       server read it and send it out over the runner's open network.
 *   <li>{@code brave-search}: its package is deprecated on npm ("no longer supported"), and native
 *       {@code web_search} already takes a Brave key.
 * </ul>
 */
final class McpStarterCatalog {

    private McpStarterCatalog() {}

    /**
     * One starter: the catalog entry (the exact launch spec) and whether it is switched on when
     * registered.
     *
     * @param enabledByDefault false = registered but off; the owner turns it on from the Tools page
     */
    record Starter(McpCatalogEntry entry, boolean enabledByDefault) {
        String name() {
            return entry.name();
        }
    }

    // mcp-server-fetch 2026.8.18 (PyPI, uploaded 2026-08-18, MIT, Anthropic, PBC)
    //   mcp_server_fetch-2026.8.18-py3-none-any.whl sha256 6642df733a1032e7f37d0f13849af8a944d46c02420d2c070cc14e0948f8fcc2
    //   mcp_server_fetch-2026.8.18.tar.gz           sha256 da12533c8218c7a4ee2eb9d789170a317cf758c07cb2cc0211a91bdb0d4b7254
    // mcp-server-time 2026.8.18 (PyPI, uploaded 2026-08-18, MIT, modelcontextprotocol/servers src/time)
    //   mcp_server_time-2026.8.18-py3-none-any.whl  sha256 1407583af42dc0163909d855c9ef20114a12b4981c3975033721a7906cdd212a
    //   mcp_server_time-2026.8.18.tar.gz            sha256 ec1313feb558a848838217d922bd218e9f7ca217cb0f7b890ee75ab94bf962e0
    // @modelcontextprotocol/server-sequential-thinking 2026.8.31 (npm, published 2026-08-31)
    //   dist.integrity sha512-/EMH++1j2e+VmHwpibw1IUPHxA0tcQY09Yo5/OIPwW/8irEKLAufJNGSZQhCK18n8TUvV6WwZyQOuaDKxe/CeQ==
    static final List<Starter> STARTERS =
            List.of(
                    new Starter(
                            new McpCatalogEntry(
                                    "fetch",
                                    "fetch",
                                    McpServerType.STDIO,
                                    "Reads a web page and returns it as clean text or Markdown. Obeys"
                                            + " the site's robots.txt. Runs in the isolated MCP runner,"
                                            + " not inside core. Off by default: the runner's network has"
                                            + " no egress filter, so unlike the built-in web page tool"
                                            + " (which refuses private and loopback addresses) this one"
                                            + " can reach services on your laptop or LAN. Turn it on only"
                                            + " if you want that.",
                                    "globe",
                                    "https://github.com/modelcontextprotocol/servers/tree/main/src/fetch",
                                    false,
                                    List.of(),
                                    null,
                                    "uvx",
                                    List.of("mcp-server-fetch@2026.8.18")),
                            false),
                    new Starter(
                            new McpCatalogEntry(
                                    "time",
                                    "time",
                                    McpServerType.STDIO,
                                    "Tells an agent the current time in any time zone and converts a"
                                            + " time between zones. Runs in the isolated MCP runner,"
                                            + " not inside core.",
                                    "clock",
                                    "https://github.com/modelcontextprotocol/servers/tree/main/src/time",
                                    false,
                                    List.of(),
                                    null,
                                    "uvx",
                                    List.of("mcp-server-time@2026.8.18")),
                            true),
                    new Starter(
                            new McpCatalogEntry(
                                    "sequential-thinking",
                                    "sequential-thinking",
                                    McpServerType.STDIO,
                                    "A scratchpad for working through a hard problem step by step and"
                                            + " revising earlier steps. Off by default: it adds a tool"
                                            + " every turn, which a small local model can do without."
                                            + " Turn it on if your model is a large one.",
                                    "list-checks",
                                    "https://github.com/modelcontextprotocol/servers/tree/main/src/sequentialthinking",
                                    false,
                                    List.of(),
                                    null,
                                    "npx",
                                    List.of(
                                            "-y",
                                            "@modelcontextprotocol/server-sequential-thinking@2026.8.31")),
                            false));

    /** The curated entries as catalog entries, for trust decisions only (never listed in the gallery). */
    static List<McpCatalogEntry> entries() {
        return STARTERS.stream().map(Starter::entry).toList();
    }

    /** Case-insensitive lookup by registration name. */
    static Optional<McpCatalogEntry> find(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return STARTERS.stream()
                .map(Starter::entry)
                .filter(e -> e.name().equalsIgnoreCase(name))
                .findFirst();
    }
}
