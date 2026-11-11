"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Search, Plus, ListChecks, AlertTriangle, Code2, FileJson2 } from "lucide-react";
import { api } from "../lib/api";
import AppShell from "../components/AppShell";
import PageHeader from "../components/ax/PageHeader";
import { usePageReady } from "../components/RouteTransition";
import McpPanel, { BUILT_IN_TOOLS } from "../components/McpPanel";
import ToolPinReview from "../components/McpPanel/ToolPinReview";
import { ToolPinSummary, useToolPinStatuses } from "../components/McpPanel/ToolPinStatus";
import { GOOGLE_TOOLS } from "../components/McpPanel/constants";
import { toolCardTotals } from "../components/McpPanel/helpers";
import McpOAuthPanel from "../components/McpOAuthPanel";
import {
  noticeForRemoteReturn,
  parseRemoteOAuthReturn,
  serverLabel,
  stripRemoteReturnParams,
} from "../lib/mcpRemoteOAuth";
import UserToolScriptsPanel from "../components/UserToolScriptsPanel";
import OpenApiToolSourcesPanel from "../components/OpenApiToolSourcesPanel";
import ToolClassificationPanel from "../components/tools/ToolClassificationPanel";
import { useTranslations } from "../lib/i18n";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Alert, AlertDescription, AlertTitle } from "../components/ui/alert";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "../components/ui/tabs";
import CoachButton from "../components/brief/CoachDrawer";

/**
 * Tools & MCP - rebuilt this session (task #546) to pixel-match the AXON
 * design handoff (Tools.dc.html). Page owns the fetch (servers/catalog/
 * Google connection) and the topbar's breadcrumb/search/"+ Add MCP server"
 * button, same "page fetches, panel owns interaction state" split as every
 * other AXON screen (see Knowledge.js) - the panel needs catalog and the
 * Google connection status too (for its own grid + inspector), so both are
 * fetched once here and passed down rather than duplicated.
 */
export default function ToolsPage() {
  const tHC = useTranslations("hcToolsPage");
  const t = useTranslations("toolsPage");
  const tOpenApi = useTranslations("openApiTools");
  const tOAuth = useTranslations("mcpOAuth");
  const router = useRouter();
  const searchParams = useSearchParams();
  const [servers, setServers] = useState([]);
  const [catalog, setCatalog] = useState([]);
  const [googleConnection, setGoogleConnection] = useState(null);
  const [query, setQuery] = useState("");
  const [loaded, setLoaded] = useState(false);
  // Which panel tab is active. Panels are unchanged verbatim - this just
  // groups the four stacked sections (servers/classification/custom code/
  // OpenAPI sources) into tabs instead of one long scroll (task #reorg-tools).
  // Returning from a provider's consent screen (?mcp_oauth=...) lands on the "Connect apps" tab, which announces the result.
  // A return from the generic remote-server "Connect with OAuth" (provider=remote) belongs to the Servers tab instead.
  const isRemoteReturn = parseRemoteOAuthReturn(searchParams) !== null;
  const [activeTab, setActiveTab] = useState(searchParams.get("mcp_oauth") && !isRemoteReturn ? "oauth" : "servers");
  // The "Connect apps" tab exists only when the deployment switched one-click provider OAuth on
  // (GET /api/mcp/oauth/providers -> enabled), so a flag-off deployment's Integrations page is unchanged.
  // Returning from a consent screen (?mcp_oauth=...) always shows it so the result banner has a home.
  const [oauthTabVisible, setOauthTabVisible] = useState(Boolean(searchParams.get("mcp_oauth")) && !isRemoteReturn);
  useEffect(() => {
    api
      .listMcpOAuthProviders()
      .then((r) => {
        if (r?.enabled) setOauthTabVisible(true);
      })
      .catch(() => {});
  }, []);
  // Atlassian OAuth callback result (AtlassianOAuthController#callback lands
  // here with ?atlassian=connected|error&reason=...) - same
  // read-param/show-banner/strip-param pattern GoogleIntegrationPanel.tsx
  // uses for its own OAuth callback.
  const [oauthNotice, setOauthNotice] = useState(null);
  const tRemote = useTranslations("mcpRemoteOAuth");
  const panelRef = useRef(null);
  // "Import from OpenAPI" lives in the topbar next to the other two
  // add-a-tool entry points; the panel below owns the wizard, same
  // ref-and-open split as McpPanel's openAdd().
  const openApiPanelRef = useRef<{ openImport: () => void } | null>(null);

  // "Couldn't load" is NOT "you have none enabled" (UX audit, 2026-07-29).
  // A failed listMcpServers() used to fall through to `setServers([])`, which
  // on this screen produces an actively dangerous reading: every connected
  // MCP server silently disappears from the grid and the header's own
  // "N enabled / M available" counter - a real, deliberately-not-rounded
  // number, see the comment below it - reports a smaller N as fact. Someone
  // could reasonably conclude their integrations had been disabled and go
  // re-add them. Tracked separately so the banner can say what happened.
  const [loadFailed, setLoadFailed] = useState(false);

  const refresh = useCallback(() => {
    const servers$ = api
      .listMcpServers()
      .then((list) => {
        setServers(list);
        setLoadFailed(false);
      })
      .catch(() => {
        setServers([]);
        setLoadFailed(true);
      });
    const catalog$ = api
      .listMcpCatalog()
      .then(setCatalog)
      .catch(() => setCatalog([]));
    api
      .getGoogleConnection()
      .then(setGoogleConnection)
      .catch(() => setGoogleConnection(null));
    // Hold the transition cover until the servers + catalog lists resolve (the
    // data the grid renders); the Google connection is secondary chrome.
    void Promise.allSettled([servers$, catalog$]).finally(() => setLoaded(true));
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);
  usePageReady(loaded);

  // Result of the generic remote-server OAuth callback (core/mcp McpOAuthController#callback lands here with
  // ?mcp_oauth=connected|error&provider=remote&reason=&mcp=&server=): announce it once, refresh the server list (the
  // callback re-connected the registrations) and strip the params so a reload does not repeat it.
  useEffect(() => {
    const ret = parseRemoteOAuthReturn(searchParams);
    if (!ret) return;
    const n = noticeForRemoteReturn(ret);
    setOauthNotice({ variant: n.variant, text: tRemote(n.key, { server: serverLabel(ret.server) }) });
    if (ret.result === "connected") refresh();
    router.replace(stripRemoteReturnParams("/tools", searchParams.toString()));
  }, [searchParams, router, refresh, tRemote]);

  useEffect(() => {
    const atlassianParam = searchParams.get("atlassian");
    if (!atlassianParam) return;
    if (atlassianParam === "connected") {
      setOauthNotice({ variant: "success", text: t("atlassianConnectedNotice") });
      refresh();
    } else if (atlassianParam === "error") {
      const reason = searchParams.get("reason");
      setOauthNotice({
        variant: "destructive",
        text: reason === "consent_declined" ? t("atlassianCancelledNotice") : t("atlassianConnectErrorNotice"),
      });
    }
    router.replace("/tools");
  }, [searchParams, router, refresh, t]);

  // Real counts (see globals.css's big comment above .ax-tl-topbar for why
  // this is never the mockup's rounded "40+") - built-ins are always
  // available, Google's four tools only count once actually connected, and
  // catalog.length is McpCatalogService's real static list size.
  // Tool pinning is a deployment-wide mode, so one registration's status is enough to show it persistently.
  const pinProbeId = (servers as Array<{ id: string; dynamic?: boolean; enabled?: boolean }>).find(
    (s) => s.dynamic && s.enabled
  )?.id;
  const pinProbe = useToolPinStatuses(pinProbeId ? [pinProbeId] : [], servers);

  const { enabled: enabledCount, total: totalCount } = toolCardTotals({
    builtInCount: BUILT_IN_TOOLS.length,
    googleToolCount: GOOGLE_TOOLS.length,
    googleConnected: !!googleConnection,
    catalog,
    servers,
  });

  return (
    <AppShell eyebrow={t("pageEyebrow")}>
      <PageHeader
        title={t("pageTitle")}
        subtitle={
          loadFailed ? (
            // Suppress the counter rather than print a wrong one - see the
            // loadFailed comment above.
            <>
              {tHC("s7")} <span className="text-muted-foreground">{tHC("s8")}</span>
            </>
          ) : (
            <>
              {tHC("s7")}{" "}
              <span className="whitespace-nowrap">
                <span className="font-medium text-foreground tabular-nums">{enabledCount}</span> {t("enabledSuffix")}{" "}
                <span className="tabular-nums">/ {totalCount}</span> {t("availableSuffix")}
              </span>
            </>
          )
        }
        actions={
          <div className="flex min-w-0 flex-wrap items-center gap-2 sm:justify-end">
            <div className="relative">
              <Search
                size={14}
                className="pointer-events-none absolute start-3 top-1/2 -translate-y-1/2 text-muted-foreground"
                aria-hidden="true"
              />
              <Input
                type="text"
                placeholder={t("searchPlaceholder")}
                aria-label={t("searchPlaceholder")}
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                className="h-8 w-48 ps-8 text-xs sm:w-56"
              />
            </div>
            {/* Follow-through screen (MCP-UX task, 2026-07-26): every enabled
             * server in one flat list with per-server Test/Trust/Disable
             * actions - complements this page's grid+inspector, doesn't
             * replace it. */}
            <Button variant="outline" size="sm" asChild>
              <Link href="/tools/servers">
                <ListChecks size={14} aria-hidden="true" /> {tHC("s6")}
              </Link>
            </Button>
            <CoachButton />
            {/* Custom Code Tools - admin-only, sandbox-gated, and needs a
             * full-width editor, so it gets its own screen rather than a
             * third panel below. Discoverable from here because "a tool an
             * agent can call" is what someone is already looking for when
             * they land on this page. */}
            <Button variant="outline" size="sm" asChild>
              <Link href="/tools/code">
                <Code2 size={14} aria-hidden="true" /> {t("codeToolsButton")}
              </Link>
            </Button>
            {/* Import from OpenAPI - the no-code path: every operation of
             * an API document becomes a tool, no script or program written.
             * Opens the wizard in OpenApiToolSourcesPanel below. */}
            <Button
              variant="outline"
              size="sm"
              onClick={() => {
                // The panel only exists in the DOM (and the ref only
                // resolves) while its own tab is mounted - TabsContent
                // unmounts inactive tabs. Switch first, then let the
                // now-mounted panel's own open-on-tab-switch effect pick it
                // up next tick rather than calling the ref synchronously in
                // the same click, which would still be null on first press.
                setActiveTab("openapi");
                requestAnimationFrame(() => openApiPanelRef.current?.openImport());
              }}
            >
              <FileJson2 size={14} aria-hidden="true" /> {tOpenApi("importButton")}
            </Button>
            <Button
              size="sm"
              onClick={() => {
                setActiveTab("servers");
                requestAnimationFrame(() => panelRef.current?.openAdd());
              }}
            >
              <Plus size={14} aria-hidden="true" /> {t("addMcpServerButton")}
            </Button>
          </div>
        }
      />

      {oauthNotice && (
        <Alert variant={oauthNotice.variant} className="mb-6">
          <AlertDescription>{oauthNotice.text}</AlertDescription>
        </Alert>
      )}

      {/* Load failure, stated as a load failure - so an empty grid below can't
          be misread as "my integrations were turned off". */}
      {loadFailed && (
        <Alert variant="destructive" className="mb-6">
          <AlertTriangle aria-hidden="true" />
          <AlertTitle>{tHC("s5")}</AlertTitle>
          <AlertDescription>
            <p>{tHC("s4")}</p>
            <Button variant="outline" size="sm" className="mt-2" onClick={refresh}>
              {tHC("s3")}
            </Button>
          </AlertDescription>
        </Alert>
      )}

      {/* Four sections that used to stack with space-y-8, now grouped into
          tabs (task #reorg-tools) - same panels, unchanged, just one visible
          at a time instead of one long scroll. Each panel keeps its own
          fetch/props exactly as before; only the wrapper changed.

          NOTE (ref caveat, fixed at the topbar buttons above): TabsContent
          unmounts the inactive panels, so `panelRef` (McpPanel.openAdd) and
          `openApiPanelRef` (OpenApiToolSourcesPanel.openImport) only resolve
          once their own tab is mounted - both buttons now switch tabs first
          and invoke the ref on the next frame, instead of calling a
          possibly-null ref synchronously. */}
      <Tabs value={activeTab} onValueChange={setActiveTab}>
        <TabsList className="h-auto w-full flex-wrap justify-start gap-1 lg:w-auto">
          <TabsTrigger value="servers" className="flex-none gap-1.5 px-3 py-1.5">
            Servers
          </TabsTrigger>
          {oauthTabVisible && (
            <TabsTrigger value="oauth" className="flex-none gap-1.5 px-3 py-1.5">
              {tOAuth("tabLabel")}
            </TabsTrigger>
          )}
          <TabsTrigger value="classification" className="flex-none gap-1.5 px-3 py-1.5">
            Classification
          </TabsTrigger>
          <TabsTrigger value="custom-code" className="flex-none gap-1.5 px-3 py-1.5">
            {tHC("s2")}
          </TabsTrigger>
          <TabsTrigger value="openapi" className="flex-none gap-1.5 px-3 py-1.5">
            {tHC("s1")}
          </TabsTrigger>
        </TabsList>

        <TabsContent value="servers">
          <ToolPinReview onChanged={refresh} />
          <ToolPinSummary statuses={pinProbe.statuses} />
          <McpPanel
            ref={panelRef}
            servers={servers}
            catalog={catalog}
            googleConnection={googleConnection}
            query={query}
            onChanged={refresh}
          />
        </TabsContent>

        <TabsContent value="oauth">
          {/* One-click OAuth connections (Google, GitHub, Slack, Notion, Linear, HubSpot, Microsoft, Asana, Zoom).
           * Owns its own fetch (GET /api/mcp/oauth/providers) and the ?mcp_oauth= return banner; refreshes this
           * page's server list when a connection adds or removes an MCP server. */}
          <McpOAuthPanel onChanged={refresh} />
        </TabsContent>

        <TabsContent value="classification">
          {/* Tool risk classification (build-agenda item 3) - trust x
           * sensitivity x determinism for every tool an agent can call, with
           * `unclassified` shown as the blocked state it is. Sits between the
           * MCP grid (what tools exist / how to connect them) and the custom
           * code tools (what tools you wrote) because it is the cross-cutting
           * answer about ALL of them: this is where someone looks to find out
           * that one of their connected tools acts with a stored credential or
           * sends data out of the platform.
           *
           * Owns its own fetch (like UserToolScriptsPanel, unlike McpPanel):
           * the classification registry is a separate endpoint from
           * /api/mcp/registry, and the panel has to distinguish "core doesn't
           * expose it yet" from "request failed" from "zero tools" on its own.
           * Shares this page's topbar search box via `query`. */}
          <ToolClassificationPanel query={query} />
        </TabsContent>

        <TabsContent value="custom-code">
          <UserToolScriptsPanel />
        </TabsContent>

        <TabsContent value="openapi">
          {/* Imported OpenAPI sources - each one is a whole API turned into
           * tools. Below the hand-written tools because it's the coarser
           * unit (one import = many tools) and its own fetch, like
           * UserToolScriptsPanel. */}
          <OpenApiToolSourcesPanel ref={openApiPanelRef} />
        </TabsContent>
      </Tabs>
    </AppShell>
  );
}
