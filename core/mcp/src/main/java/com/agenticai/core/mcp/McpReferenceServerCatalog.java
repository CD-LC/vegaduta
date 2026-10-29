package com.agenticai.core.mcp;

import com.agenticai.core.mcp.dto.McpCatalogEntry;
import java.util.List;

/**
 * The curated official reference MCP servers (github.com/modelcontextprotocol/servers) in {@link
 * McpCatalogService}'s catalog, in gallery order. See {@link McpCatalogService}'s class javadoc for
 * why the list is hand-maintained and why {@code fetch}/{@code time} are absent.
 */
final class McpReferenceServerCatalog {

    private McpReferenceServerCatalog() {}

    static final List<McpCatalogEntry> ENTRIES =
            List.of(
                    new McpCatalogEntry(
                            "filesystem",
                            "filesystem",
                            McpServerType.STDIO,
                            "Read, write, and list files in a sandboxed directory on the server (core/mcp-data/ by default).",
                            "filesystem",
                            "https://github.com/modelcontextprotocol/servers/tree/main/src/filesystem",
                            true,
                            List.of(),
                            null),
                    new McpCatalogEntry(
                            "github",
                            "github",
                            McpServerType.STDIO,
                            "Search repos, read files, open issues/PRs, and review diffs on GitHub.",
                            "github",
                            "https://github.com/modelcontextprotocol/servers/tree/main/src/github",
                            false,
                            List.of("GITHUB_PERSONAL_ACCESS_TOKEN"),
                            """
            spring:
              ai:
                mcp:
                  client:
                    stdio:
                      connections:
                        github:
                          command: npx
                          args: ["-y", "@modelcontextprotocol/server-github"]
                          env:
                            GITHUB_PERSONAL_ACCESS_TOKEN: "${GITHUB_PERSONAL_ACCESS_TOKEN}"
            """,
                            "npx",
                            List.of("-y", "@modelcontextprotocol/server-github")),
                    new McpCatalogEntry(
                            "postgres",
                            "postgres",
                            McpServerType.STDIO,
                            "Run read-only queries and inspect schemas on a Postgres database you point it at.",
                            "database",
                            "https://github.com/modelcontextprotocol/servers/tree/main/src/postgres",
                            false,
                            List.of("MCP_POSTGRES_CONNECTION_STRING"),
                            """
            spring:
              ai:
                mcp:
                  client:
                    stdio:
                      connections:
                        postgres:
                          command: npx
                          args: ["-y", "@modelcontextprotocol/server-postgres", "${MCP_POSTGRES_CONNECTION_STRING}"]
            """,
                            "npx",
                            List.of(
                                    "-y",
                                    "@modelcontextprotocol/server-postgres",
                                    "{{MCP_POSTGRES_CONNECTION_STRING}}")),
                    new McpCatalogEntry(
                            "brave-search",
                            "brave-search",
                            McpServerType.STDIO,
                            "Web search for agents that need current information beyond their training data.",
                            "search",
                            "https://github.com/modelcontextprotocol/servers/tree/main/src/brave-search",
                            false,
                            List.of("BRAVE_API_KEY"),
                            """
            spring:
              ai:
                mcp:
                  client:
                    stdio:
                      connections:
                        brave-search:
                          command: npx
                          args: ["-y", "@modelcontextprotocol/server-brave-search"]
                          env:
                            BRAVE_API_KEY: "${BRAVE_API_KEY}"
            """,
                            "npx",
                            List.of("-y", "@modelcontextprotocol/server-brave-search")),
                    new McpCatalogEntry(
                            "slack",
                            "slack",
                            McpServerType.STDIO,
                            "Read and post messages, list channels, and search Slack workspace history.",
                            "message-square",
                            "https://github.com/modelcontextprotocol/servers/tree/main/src/slack",
                            false,
                            List.of("SLACK_BOT_TOKEN", "SLACK_TEAM_ID"),
                            """
            spring:
              ai:
                mcp:
                  client:
                    stdio:
                      connections:
                        slack:
                          command: npx
                          args: ["-y", "@modelcontextprotocol/server-slack"]
                          env:
                            SLACK_BOT_TOKEN: "${SLACK_BOT_TOKEN}"
                            SLACK_TEAM_ID: "${SLACK_TEAM_ID}"
            """,
                            "npx",
                            List.of("-y", "@modelcontextprotocol/server-slack")),
                    new McpCatalogEntry(
                            "puppeteer",
                            "puppeteer",
                            McpServerType.STDIO,
                            "Browse the web, click, fill forms, and take screenshots via a real headless browser (DOM-selector driven - see playwright-vision below for the vision-loop alternative).",
                            "globe",
                            "https://github.com/modelcontextprotocol/servers/tree/main/src/puppeteer",
                            true,
                            List.of(),
                            """
            spring:
              ai:
                mcp:
                  client:
                    stdio:
                      connections:
                        puppeteer:
                          command: npx
                          args: ["-y", "@modelcontextprotocol/server-puppeteer"]
            """,
                            // The per-tenant fallback spec
                            // McpRegistryService#upgradeUnservedStaticReference uses
                            // when this deployment's shared static connection can't serve the
                            // tenant (no "mcp"
                            // profile, or the stateful server hidden by the exposure policy) -
                            // enabling still
                            // connects something real.
                            "npx",
                            List.of("-y", "@modelcontextprotocol/server-puppeteer")),
                    // Master-Roadmap-2026.md item #10 ("Vision-driven browser automation
                    // upgrade") - "@playwright/mcp" is Microsoft's official, actively
                    // maintained MCP server; running it with the --caps=vision flag
                    // unlocks browser_take_screenshot + coordinate-based
                    // browser_mouse_click_xy/browser_mouse_drag_xy tools, i.e. the exact
                    // "agent looks at a screenshot, clicks a pixel coordinate" loop the
                    // browser-use/Browserbase/Skyvern pattern the roadmap calls for -
                    // rather than puppeteer's DOM-selector-only approach above, which
                    // breaks the moment a site's markup or a11y tree doesn't map cleanly
                    // (canvas UIs, custom-drawn widgets, aggressive bot-detection sites
                    // that scramble selectors). Kept as an ADDITIONAL ready entry, not a
                    // replacement for puppeteer - the accessibility-tree-based default
                    // mode (no --caps flag) actually resolves the vast majority of real
                    // web automation faster and more reliably than vision/coordinates
                    // ever will (fewer LLM round-trips, no screenshot-analysis latency),
                    // so an agent still wants puppeteer/plain-DOM tools as the default
                    // and this as the fallback for the awkward remainder. Zero
                    // credentials needed, so `ready: true` here too - wired the same way
                    // in application-mcp.yml.
                    new McpCatalogEntry(
                            "playwright-vision",
                            "playwright-vision",
                            McpServerType.STDIO,
                            "Vision-driven browser automation: takes a screenshot, has the agent identify pixel coordinates, then clicks/drags there - for sites where normal selector-based automation breaks (canvas UIs, custom widgets, anti-bot markup scrambling).",
                            "globe",
                            "https://playwright.dev/mcp/vision-mode",
                            true,
                            List.of(),
                            """
            spring:
              ai:
                mcp:
                  client:
                    stdio:
                      connections:
                        playwright-vision:
                          command: npx
                          args: ["-y", "@playwright/mcp@0.0.83", "--caps=vision", "--browser=chromium", "--isolated"]
            """,
                            // Same pin as application-mcp.yml's playwright-vision connection (bump
                            // them together -
                            // the runner/core-full images install this version's browser at build
                            // time; @latest
                            // there caused the 2026-08-09 browser-not-found incident the yml
                            // comment documents).
                            "npx",
                            List.of(
                                    "-y",
                                    "@playwright/mcp@0.0.83",
                                    "--caps=vision",
                                    "--browser=chromium",
                                    "--isolated")),
                    // The following are further official Anthropic reference servers
                    // (github.com/modelcontextprotocol/servers) - added per the "should
                    // provide all the curated tools" request. Package/argument names are
                    // accurate as of this platform's last verification pass; double-check
                    // against the registry before relying on one, the same caveat the
                    // Twilio SDK version comment elsewhere in this codebase makes for any
                    // fast-moving external dependency.
                    new McpCatalogEntry(
                            "gitlab",
                            "gitlab",
                            McpServerType.STDIO,
                            "Search projects, read files, open merge requests, and review issues on GitLab.",
                            "git-branch",
                            "https://github.com/modelcontextprotocol/servers/tree/main/src/gitlab",
                            false,
                            List.of("GITLAB_PERSONAL_ACCESS_TOKEN"),
                            """
            spring:
              ai:
                mcp:
                  client:
                    stdio:
                      connections:
                        gitlab:
                          command: npx
                          args: ["-y", "@modelcontextprotocol/server-gitlab"]
                          env:
                            GITLAB_PERSONAL_ACCESS_TOKEN: "${GITLAB_PERSONAL_ACCESS_TOKEN}"
            """,
                            "npx",
                            List.of("-y", "@modelcontextprotocol/server-gitlab")),
                    new McpCatalogEntry(
                            "google-maps",
                            "google-maps",
                            McpServerType.STDIO,
                            "Geocode addresses, get directions, and search places for location-aware agents.",
                            "map-pin",
                            "https://github.com/modelcontextprotocol/servers/tree/main/src/google-maps",
                            false,
                            List.of("GOOGLE_MAPS_API_KEY"),
                            """
            spring:
              ai:
                mcp:
                  client:
                    stdio:
                      connections:
                        google-maps:
                          command: npx
                          args: ["-y", "@modelcontextprotocol/server-google-maps"]
                          env:
                            GOOGLE_MAPS_API_KEY: "${GOOGLE_MAPS_API_KEY}"
            """,
                            "npx",
                            List.of("-y", "@modelcontextprotocol/server-google-maps")),
                    new McpCatalogEntry(
                            "google-drive",
                            "google-drive",
                            McpServerType.STDIO,
                            "Search, read, and export files from a connected Google Drive.",
                            "folder-input",
                            "https://github.com/modelcontextprotocol/servers/tree/main/src/gdrive",
                            false,
                            List.of("GDRIVE_OAUTH_CREDENTIALS_PATH"),
                            """
            spring:
              ai:
                mcp:
                  client:
                    stdio:
                      connections:
                        gdrive:
                          command: npx
                          args: ["-y", "@modelcontextprotocol/server-gdrive"]
                          env:
                            GDRIVE_OAUTH_CREDENTIALS_PATH: "${GDRIVE_OAUTH_CREDENTIALS_PATH}"
            """,
                            "npx",
                            List.of("-y", "@modelcontextprotocol/server-gdrive")),
                    new McpCatalogEntry(
                            "sqlite",
                            "sqlite",
                            McpServerType.STDIO,
                            "Query and inspect a local SQLite database file.",
                            "database",
                            "https://github.com/modelcontextprotocol/servers/tree/main/src/sqlite",
                            false,
                            List.of("MCP_SQLITE_DB_PATH"),
                            """
            spring:
              ai:
                mcp:
                  client:
                    stdio:
                      connections:
                        sqlite:
                          command: npx
                          args: ["-y", "@modelcontextprotocol/server-sqlite", "--db-path", "${MCP_SQLITE_DB_PATH}"]
            """,
                            "npx",
                            List.of(
                                    "-y",
                                    "@modelcontextprotocol/server-sqlite",
                                    "--db-path",
                                    "{{MCP_SQLITE_DB_PATH}}")),
                    new McpCatalogEntry(
                            "memory",
                            "memory",
                            McpServerType.STDIO,
                            "A persistent knowledge-graph scratchpad the model can write to and query across a session.",
                            "brain",
                            "https://github.com/modelcontextprotocol/servers/tree/main/src/memory",
                            true,
                            List.of(),
                            """
            spring:
              ai:
                mcp:
                  client:
                    stdio:
                      connections:
                        memory:
                          command: npx
                          args: ["-y", "@modelcontextprotocol/server-memory"]
            """,
                            // Per-tenant copy the registry falls back to when the shared static
                            // server is hidden
                            // (memory is stateful - its own knowledge graph per tenant is the
                            // correct isolation
                            // anyway, unlike a stateless shared server).
                            "npx",
                            List.of("-y", "@modelcontextprotocol/server-memory")),
                    new McpCatalogEntry(
                            "sequential-thinking",
                            "sequential-thinking",
                            McpServerType.STDIO,
                            "Structured, revisable step-by-step reasoning for harder multi-step problems.",
                            "list-checks",
                            "https://github.com/modelcontextprotocol/servers/tree/main/src/sequentialthinking",
                            true,
                            List.of(),
                            """
            spring:
              ai:
                mcp:
                  client:
                    stdio:
                      connections:
                        sequential-thinking:
                          command: npx
                          args: ["-y", "@modelcontextprotocol/server-sequential-thinking"]
            """,
                            "npx",
                            List.of("-y", "@modelcontextprotocol/server-sequential-thinking")),
                    // "fetch" and "time" catalog entries were removed here - see this
                    // class's javadoc above for why (npm packages that never existed,
                    // and no clean UI path to half-support them once corrected to uvx).
                    new McpCatalogEntry(
                            "sentry",
                            "sentry",
                            McpServerType.STDIO,
                            "Look up and triage error reports from a connected Sentry project.",
                            "bug",
                            "https://github.com/modelcontextprotocol/servers/tree/main/src/sentry",
                            false,
                            List.of("SENTRY_AUTH_TOKEN"),
                            """
            spring:
              ai:
                mcp:
                  client:
                    stdio:
                      connections:
                        sentry:
                          command: npx
                          args: ["-y", "@modelcontextprotocol/server-sentry"]
                          env:
                            SENTRY_AUTH_TOKEN: "${SENTRY_AUTH_TOKEN}"
            """,
                            "npx",
                            List.of("-y", "@modelcontextprotocol/server-sentry")),
                    // Non-Anthropic-server additions: each is version-pinned like
                    // McpStarterCatalog (npm dist.integrity recorded above the list for
                    // whoever bumps it).
                    new McpCatalogEntry(
                            "context7",
                            "context7",
                            McpServerType.STDIO,
                            "Pulls current documentation and code examples for a library or framework into the prompt - stops the model answering from stale training-data API knowledge.",
                            "book",
                            "https://github.com/upstash/context7",
                            false,
                            List.of("CONTEXT7_API_KEY"),
                            """
            spring:
              ai:
                mcp:
                  client:
                    stdio:
                      connections:
                        context7:
                          command: npx
                          args: ["-y", "@upstash/context7-mcp@4.4.4", "--api-key", "${CONTEXT7_API_KEY}"]
            """,
                            "npx",
                            List.of(
                                    "-y",
                                    "@upstash/context7-mcp@4.4.4",
                                    "--api-key",
                                    "{{CONTEXT7_API_KEY}}")),
                    new McpCatalogEntry(
                            "exa",
                            "exa",
                            McpServerType.STDIO,
                            "Semantic web search, crawling and deep research via Exa's API - pairs with the built-in web_search providers for agent research.",
                            "search",
                            "https://docs.exa.ai/reference/exa-mcp",
                            false,
                            List.of("EXA_API_KEY"),
                            """
            spring:
              ai:
                mcp:
                  client:
                    stdio:
                      connections:
                        exa:
                          command: npx
                          args: ["-y", "exa-mcp-server@3.4.2"]
                          env:
                            EXA_API_KEY: "${EXA_API_KEY}"
            """,
                            "npx",
                            List.of("-y", "exa-mcp-server@3.4.2")),
                    new McpCatalogEntry(
                            "firecrawl",
                            "firecrawl",
                            McpServerType.STDIO,
                            "Crawl and scrape websites into clean Markdown - useful feeding pages into add_to_knowledge_base (which itself takes a url argument).",
                            "flame",
                            "https://github.com/mendableai/firecrawl-mcp-server",
                            false,
                            List.of("FIRECRAWL_API_KEY"),
                            """
            spring:
              ai:
                mcp:
                  client:
                    stdio:
                      connections:
                        firecrawl:
                          command: npx
                          args: ["-y", "firecrawl-mcp@3.35.0"]
                          env:
                            FIRECRAWL_API_KEY: "${FIRECRAWL_API_KEY}"
            """,
                            "npx",
                            List.of("-y", "firecrawl-mcp@3.35.0")));
}
