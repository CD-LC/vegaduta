package com.agenticai.core.mcp;

import com.agenticai.core.mcp.dto.McpCatalogEntry;
import java.util.List;
import java.util.Locale;

/**
 * Catalog entries for the Activepieces community pieces served by the
 * piece host (top-level {@code piece-host/}). Not a Spring bean: {@link
 * McpCatalogService} appends these only when
 * {@code app.mcp.piece-host.enabled=true} (default false), so with the flag
 * off the catalog is exactly what it was before this class existed.
 *
 * <p>Every entry is a dynamic-connect STDIO entry that submits
 * {@code node <entrypoint> --pieces <piece>} (default entrypoint
 * {@code /opt/piece-host/server.js}, provided by the mcp-stdio-runner image
 * layered with piece-host/Dockerfile - it does NOT exist in core's own
 * container, so these cannot be wired as static YAML and {@code yamlSnippet}
 * is null). One connection loads exactly one piece and receives only that
 * piece's credentials.
 *
 * <p><b>Credentials.</b> The host reads them only from the environment as
 * {@code PIECE_AUTH_<PIECE>} (single token / API key) or, for pieces whose
 * auth has several fields, {@code PIECE_AUTH_<PIECE>_JSON} (a JSON object of
 * the piece's auth fields) - see piece-host/src/auth.js. The env var name is
 * derived from the piece key exactly as the host derives it (upper-case, dash
 * to underscore), and PieceHostCatalogTest pins that.
 *
 * <p><b>Honesty about what is verified</b> (2026-10-04): that each package
 * exists on npm and lives in the MIT part of the Activepieces repository was
 * checked; each piece's <i>actual</i> auth type and field names were NOT
 * (the published bundles are minified and nothing was installed or run). The
 * token-vs-JSON split below and every "credentials you must supply" sentence
 * is therefore best knowledge of the product's API, not read from the piece.
 * Because the connect form shows only the one variable named here, the host
 * also accepts a JSON object of auth fields pasted into a plain
 * {@code PIECE_AUTH_<PIECE>} variable (piece-host/src/auth.js), so a wrong
 * token-vs-JSON guess does not strand the user; the description says so.
 * Entries carry {@code verified=false} until someone has run the piece against
 * the real service; the authoritative per-piece credential list is
 * {@code node /opt/piece-host/server.js --describe --pieces <piece>}, and a
 * wrong or missing credential fails the call with an error naming the exact
 * variables the piece wants.
 *
 * <p>OAuth2-based services are supported only as "paste an access token you
 * obtained yourself": the host runs no OAuth flow and never refreshes a token,
 * so short-lived tokens (Google, Microsoft, Zoom) stop working when they expire.
 * Entries say so.
 */
final class PieceHostCatalog {

    /** Where the piece host lives inside the mcp-stdio-runner image (piece-host/Dockerfile). */
    static final String DEFAULT_ENTRYPOINT = "/opt/piece-host/server.js";

    private static final String SOURCE_URL_PREFIX =
        "https://github.com/activepieces/activepieces/tree/main/packages/pieces/community/";

    /** A single secret/token vs. a JSON object holding several auth fields. */
    enum Credential { TOKEN, JSON }

    /** One piece: package key (the part after {@code @activepieces/piece-}), UI strings, credential shape. */
    record Spec(String key, String title, String icon, String summary, Credential credential, String credentialNote) {}

    private static final String TOKEN_CAVEAT_SHORT_LIVED =
        " The host does not run OAuth or refresh tokens, so this token stops working when it expires.";

    /** The ~30 popular pieces. Keys must also be listed (pinned) in piece-host/pieces.txt - PieceHostCatalogTest checks. */
    static final List<Spec> SPECS = List.of(
        new Spec("google-sheets", "Google Sheets", "database",
            "Read, add and update rows in Google Sheets spreadsheets.",
            Credential.TOKEN, "a Google OAuth2 access token with the Sheets scope." + TOKEN_CAVEAT_SHORT_LIVED),
        new Spec("gmail", "Gmail", "message-square",
            "Send and search email in a Gmail mailbox.",
            Credential.TOKEN, "a Google OAuth2 access token with the Gmail scope." + TOKEN_CAVEAT_SHORT_LIVED),
        new Spec("slack", "Slack", "message-square",
            "Post messages, look up users and channels, and manage conversations in a Slack workspace.",
            Credential.TOKEN, "a Slack bot (xoxb-) or user (xoxp-) token from a Slack app you create in your workspace."),
        new Spec("notion", "Notion", "database",
            "Create and update pages and database items in Notion.",
            Credential.TOKEN, "a Notion integration token, with the integration shared on the pages or databases it should reach."),
        new Spec("airtable", "Airtable", "database",
            "Create, find and update records in Airtable bases.",
            Credential.TOKEN, "an Airtable personal access token with the scopes and bases you want to expose."),
        new Spec("hubspot", "HubSpot", "globe",
            "Work with HubSpot CRM contacts, companies and deals.",
            Credential.TOKEN, "a HubSpot private-app access token."),
        new Spec("trello", "Trello", "list-checks",
            "Create and move Trello cards and manage boards and lists.",
            Credential.JSON, "a JSON object with your Trello API key and token (field names via --describe)."),
        new Spec("asana", "Asana", "list-checks",
            "Create and update Asana tasks and projects.",
            Credential.TOKEN, "an Asana personal access token."),
        new Spec("jira-cloud", "Jira Cloud", "bug",
            "Create, search and update Jira Cloud issues.",
            Credential.JSON, "a JSON object with your Jira site URL, account email and API token (field names via --describe)."),
        new Spec("github", "GitHub (Activepieces piece)", "github",
            "Create issues and comments and look up repositories on GitHub. Overlaps the built-in 'github' MCP entry; this one runs as a piece.",
            Credential.TOKEN, "a GitHub personal access token."),
        new Spec("stripe", "Stripe", "globe",
            "Look up and create Stripe customers, payments and invoices. Some actions move real money.",
            Credential.TOKEN, "a Stripe secret key - use a restricted key limited to what the agent needs; live keys act on real money."),
        new Spec("shopify", "Shopify", "globe",
            "Manage Shopify products, orders and customers.",
            Credential.JSON, "a JSON object with your shop name and Admin API access token (field names via --describe)."),
        new Spec("twilio", "Twilio", "message-square",
            "Send SMS and look up messages and calls through Twilio.",
            Credential.JSON, "a JSON object with your Twilio Account SID and auth token (field names via --describe). Sends cost money."),
        new Spec("discord", "Discord", "message-square",
            "Post messages and manage channels and members in a Discord server.",
            Credential.TOKEN, "a Discord bot token, with the bot added to your server."),
        new Spec("telegram-bot", "Telegram Bot", "message-square",
            "Send messages and media through a Telegram bot.",
            Credential.TOKEN, "a Telegram bot token from @BotFather."),
        new Spec("zoho-crm", "Zoho CRM", "globe",
            "Create and update Zoho CRM leads, contacts and deals.",
            Credential.JSON, "a JSON object with a Zoho OAuth access token and your data-centre location (field names via --describe)." + TOKEN_CAVEAT_SHORT_LIVED),
        new Spec("salesforce", "Salesforce", "globe",
            "Create, query and update Salesforce records.",
            Credential.JSON, "a JSON object with a Salesforce OAuth access token and any instance details the piece asks for (field names via --describe)." + TOKEN_CAVEAT_SHORT_LIVED),
        new Spec("mailchimp", "Mailchimp", "message-square",
            "Add and update Mailchimp audience members and manage campaigns.",
            Credential.TOKEN, "a Mailchimp API key."),
        new Spec("calendly", "Calendly", "clock",
            "Look up Calendly events, invitees and event types.",
            Credential.TOKEN, "a Calendly personal access token."),
        new Spec("typeform", "Typeform", "list-checks",
            "Read Typeform forms and responses.",
            Credential.TOKEN, "a Typeform personal access token."),
        new Spec("woocommerce", "WooCommerce", "globe",
            "Manage WooCommerce products, orders and customers.",
            Credential.JSON, "a JSON object with your store URL, consumer key and consumer secret (field names via --describe)."),
        new Spec("clickup", "ClickUp", "list-checks",
            "Create and update ClickUp tasks, lists and comments.",
            Credential.TOKEN, "a ClickUp personal API token."),
        new Spec("linear", "Linear", "list-checks",
            "Create and update Linear issues and comments.",
            Credential.TOKEN, "a Linear API key."),
        new Spec("zendesk", "Zendesk", "message-square",
            "Create and update Zendesk tickets and users.",
            Credential.JSON, "a JSON object with your Zendesk subdomain, agent email and API token (field names via --describe)."),
        new Spec("freshdesk", "Freshdesk", "message-square",
            "Create and update Freshdesk tickets and contacts.",
            Credential.JSON, "a JSON object with your Freshdesk domain and API key (field names via --describe)."),
        new Spec("pipedrive", "Pipedrive", "globe",
            "Create and update Pipedrive deals, persons and organizations.",
            Credential.TOKEN, "a Pipedrive API token."),
        new Spec("dropbox", "Dropbox", "folder-input",
            "Upload, download, move and share files in Dropbox.",
            Credential.TOKEN, "a Dropbox access token." + TOKEN_CAVEAT_SHORT_LIVED),
        new Spec("microsoft-onedrive", "Microsoft OneDrive", "folder-input",
            "Upload, download, list and share files in OneDrive.",
            Credential.TOKEN, "a Microsoft Graph OAuth2 access token with file scopes." + TOKEN_CAVEAT_SHORT_LIVED),
        new Spec("zoom", "Zoom", "clock",
            "Create and look up Zoom meetings and registrants.",
            Credential.TOKEN, "a Zoom OAuth or Server-to-Server OAuth access token." + TOKEN_CAVEAT_SHORT_LIVED),
        new Spec("intercom", "Intercom", "message-square",
            "Look up and update Intercom contacts and conversations.",
            Credential.TOKEN, "an Intercom access token.")
    );

    private PieceHostCatalog() {
    }

    /** The env var the host reads this piece's credential from - mirrors piece-host/src/auth.js authEnvBase(). */
    static String credentialEnvVar(Spec spec) {
        String base = "PIECE_AUTH_" + spec.key().toUpperCase(Locale.ROOT).replace('-', '_');
        return spec.credential() == Credential.JSON ? base + "_JSON" : base;
    }

    /** Catalog key and registration name for a piece: {@code piece-<key>}. */
    static String entryName(Spec spec) {
        return "piece-" + spec.key();
    }

    /** All piece entries for the given launcher script path (blank -> {@link #DEFAULT_ENTRYPOINT}). */
    static List<McpCatalogEntry> entries(String entrypoint) {
        String script = entrypoint == null || entrypoint.isBlank() ? DEFAULT_ENTRYPOINT : entrypoint.trim();
        return SPECS.stream().map(spec -> toEntry(spec, script)).toList();
    }

    private static McpCatalogEntry toEntry(Spec spec, String script) {
        String description = spec.title() + " via an Activepieces community piece (MIT), run inside the sandboxed MCP runner. "
            + spec.summary()
            + " Credentials you must supply: " + spec.credentialNote()
            + (spec.credential() == Credential.TOKEN
                ? " If the piece turns out to need several auth fields instead of one token, paste a JSON object with those fields into the same variable."
                : "")
            + " Not yet verified against the live service.";
        return new McpCatalogEntry(
            entryName(spec),
            entryName(spec),
            McpServerType.STDIO,
            description,
            spec.icon(),
            SOURCE_URL_PREFIX + spec.key(),
            false,
            List.of(credentialEnvVar(spec)),
            null,
            "node",
            List.of(script, "--pieces", spec.key()),
            null,
            false,
            false
        );
    }
}
