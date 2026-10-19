"use client";

import { forwardRef, useEffect, useImperativeHandle, useMemo, useRef, useState } from "react";
import { api } from "../lib/api";
import RegistryBrowser from "./RegistryBrowser";
import PaginationControls from "./PaginationControls";
import { usePagination } from "../lib/usePagination";
import { Card, CardContent } from "./ui/card";
import { useTranslations } from "../lib/i18n";
import { BUILT_IN_TOOLS } from "./McpPanel/constants";
import { type McpCard, parseStoredArgs } from "./McpPanel/helpers";
import { useToolPinStatuses } from "./McpPanel/ToolPinStatus";
import InspectorPanel from "./McpPanel/InspectorPanel";
import { errorMessage } from "../lib/errors";
import { cardSearchName } from "../lib/mcpCardSearch";
import CustomServerForm from "./mcp/CustomServerForm";
import McpCardTile from "./mcp/McpCardTile";
import { useCardPhases } from "./mcp/useCardPhases";
import { useMcpServerEdit } from "./mcp/useMcpServerEdit";
import { GROUP_LABEL_KEYS, buildMcpCards, countByCategory, groupByCategory } from "./mcp/mcpCards";
import { AddServerTile, McpPanelNotices, McpTabBar } from "./mcp/McpPanelChrome";

/**
 * Tools & MCP marketplace - rebuilt this session (task #546) to pixel-match
 * the AXON design handoff (Tools.dc.html): a left tools grid (built-ins +
 * connected Google Workspace tools + registered MCP servers + an "add new"
 * tile) and a right-side inspector for whichever card is selected. Restyled
 * (2026-08-04) onto the app's shared ShadCN component library (Card/Badge/
 * Button/Alert/Tabs) to match the already-migrated sibling screens
 * (/tools/servers, /tools/credentials) instead of the old bespoke ax-tl-*
 * class system - presentation only, every condition/handler below is
 * unchanged. See the big comment above .ax-tl-topbar in globals.css for the
 * full list of honest departures from the mockup (no fabricated call
 * counts/success%/latency/sub-tool lists where the backend genuinely has no
 * such field).
 *
 * `catalog`/`googleConnection`/`query` are fetched/owned by tools/page.js
 * (same "page fetches, panel owns interaction state" split as every other
 * AXON screen) so the topbar breadcrumb's real enabled/available counts and
 * the local search box can live in one place without prop-drilling fetch
 * logic back down.
 */
const McpPanel = forwardRef(function McpPanel({ servers, catalog, googleConnection, query, onChanged }: any, ref) {
  const t = useTranslations("mcpPanel");
  const [agents, setAgents] = useState([]);
  const [selected, setSelected] = useState(null);
  const [activeTab, setActiveTab] = useState("all");
  const [showAdd, setShowAdd] = useState(false);
  const [addingKey, setAddingKey] = useState(null);
  const [connectingKey, setConnectingKey] = useState(null);
  const [toggleBusyId, setToggleBusyId] = useState(null);
  const [retryBusyId, setRetryBusyId] = useState(null);
  const [error, setError] = useState(null);
  const [success, setSuccess] = useState(null);
  const [deleteBusyId, setDeleteBusyId] = useState(null);
  // Two-click confirm (same shape as ChannelsPanel's device-revoke pattern) -
  // a delete is irreversible (no undo, no "disabled" fallback state), so it
  // never fires on a single accidental click.
  const [confirmDeleteId, setConfirmDeleteId] = useState(null);
  // register()'s transient, request-scoped-only signals (McpServerRegistrationResponse
  // .warnings/.wasUpdate, 2026-08-05) - soft-validation notices (e.g. "missing
  // recommended credential FOO_API_KEY") surfaced immediately here rather than
  // only discoverable later via a failed connection attempt. Cleared at the
  // start of every register-shaped action, same as error/success above.
  const [registerWarnings, setRegisterWarnings] = useState<string[]>([]);
  // Warn-before-enable step: the id of the OFF server whose toggle was
  // clicked - the inspector renders EnableConfirmCard for it instead of the
  // toggle flipping silently. Cleared on confirm/cancel.
  const [pendingEnableId, setPendingEnableId] = useState(null);
  const [enableBusy, setEnableBusy] = useState(false);
  // Enable/connect kicks off a server-side connection test whose outcome
  // lands on the registration a few seconds later - these timers re-fetch
  // (~2s, ~6s) so the fresh status/error/hint appears without a manual
  // refresh. Cleared on unmount.
  const autoRefreshTimers = useRef<any[]>([]);
  useEffect(() => () => autoRefreshTimers.current.forEach(clearTimeout), []);
  // Whether the running core gates agent use on the new usageStatus field
  // (UNTESTED/READY/FAILED/ACKNOWLEDGED). Absent field = older core that
  // does NOT gate - the confirm copy drops its "agents won't use this until
  // tested" sentence there, because it wouldn't be true.
  const usageGated = servers.some((s: any) => typeof s?.usageStatus === "string");

  // --- Immediate feedback UX (MCP-UX follow-up, 2026-07-26) ---------------
  // Grid-card DOM nodes, keyed by selKey, so an Add/Activate/Connect click
  // can scroll the specific card into view synchronously - on a long catalog
  // grid there was previously no indication of WHERE the thing you just
  // clicked actually lives once the click fires.
  const cardRefs = useRef<Map<string, HTMLElement>>(new Map());
  // Below `lg` the inspector renders stacked well below the grid/tabs/
  // pagination - selecting a card gave zero indication that anything
  // happened unless you scrolled down yourself. Scroll it into view on
  // selection, desktop (>=1024px, matches Tailwind's `lg`) excluded since
  // the inspector already sits beside the grid there.
  const inspectorRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!selected) return;
    if (typeof window !== "undefined" && window.innerWidth >= 1024) return;
    inspectorRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
  }, [selected]);
  const [fixBusyId, setFixBusyId] = useState<string | null>(null);
  useImperativeHandle(ref, () => ({
    openAdd: () => setShowAdd(true),
  }));

  useEffect(() => {
    api
      .listAgents()
      .then(setAgents)
      .catch(() => setAgents([]));
  }, []);
  // Real card inventory - see buildMcpCards (mcp/mcpCards.ts) for which
  // cards exist and why a catalog entry keeps its selKey across activation.
  const cards = useMemo(
    () => buildMcpCards(servers, catalog, googleConnection),
    [servers, catalog, googleConnection]
  );

  const { cardPhase, markPhase, clearPhase, markTesting } = useCardPhases(cards, servers, catalog);
  const edit = useMcpServerEdit(setError, setSuccess, onChanged);

  // Selects a card AND scrolls it into view, synchronously in the click
  // handler (before the await) - immediate feedback on WHERE the thing you
  // just clicked lives, not just that something is happening.
  function focusCard(selKey: string | null | undefined) {
    if (!selKey) return;
    setSelected(selKey);
    requestAnimationFrame(() => {
      cardRefs.current.get(selKey)?.scrollIntoView({ behavior: "smooth", block: "nearest" });
    });
  }
  useEffect(() => {
    if (!selected && cards.length > 0) setSelected(cards[0].selKey);
  }, [cards, selected]);

  const filteredCards = cards.filter((c) => {
    if (activeTab !== "all" && c.category !== activeTab) return false;
    const q = (query || "").trim().toLowerCase();
    if (!q) return true;
    return cardSearchName(c, t).toLowerCase().includes(q);
  });

  // Paginate the marketplace grid; selection (selKey) lives outside the page so
  // the inspector keeps its card when the page changes.
  const {
    page: mcpPage,
    pageSize: mcpPageSize,
    pageItems: pagedCards,
    totalPages: mcpTotalPages,
    totalItems: mcpTotalItems,
    setPage: setMcpPage,
    setPageSize: setMcpPageSize,
  } = usePagination(filteredCards);

  const selectedCard = cards.find((c) => c.selKey === selected) || null;
  const pins = useToolPinStatuses(servers.map((s: any) => String(s.id)));

  // register()'s wasUpdate/warnings (McpServerRegistrationResponse) exist only on a
  // register() response, so surface them right away (inline banner + review note).
  function noteRegisterResponse(res: any, baseMessage: string): string {
    setRegisterWarnings(Array.isArray(res?.warnings) ? res.warnings : []);
    let message = baseMessage;
    if (res?.wasUpdate) message += ` ${t("registerWasUpdateSuffix")}`;
    if (res?.reviewStatus === "PENDING") message += ` ${t("registerReviewPendingSuffix")}`;
    return message;
  }

  async function addFromCatalog(entry: any) {
    setError(null);
    setSuccess(null);
    setRegisterWarnings([]);
    setAddingKey(entry.key);
    const selKey = `mcp:${entry.key}`;
    // Immediate feedback: select + scroll the card BEFORE the await, not
    // after - the point is showing the user where to look the instant they
    // click, not once the network round-trip finishes.
    focusCard(selKey);
    markPhase(selKey, "adding");
    try {
      const res = await api.registerMcpServer({
        name: entry.name,
        type: entry.type,
        description: entry.description,
        enabled: true,
      });
      setSuccess(noteRegisterResponse(res, t("activatedSuccess", { name: entry.name })));
      onChanged();
      // A static (non-dynamic) catalog activation has no async connection
      // attempt to wait on - the steady-state badge is accurate the moment
      // the refetch lands, so there's no "testing" phase to show.
      clearPhase(selKey);
    } catch (err) {
      setError(errorMessage(err, t("addServerError")));
      clearPhase(selKey);
    } finally {
      setAddingKey(null);
    }
  }

  // #424 - the actual "how client can configure and use it during runtime"
  // action: registers a dynamic connection (command/args/env, encrypted at
  // rest) and kicks off a real connect attempt server-side in the same
  // request. Distinct from addFromCatalog above (which only ever registers
  // a reference to something already wired in application-mcp.yml).
  async function connectDynamic(entry: any, command: any, args: any, env: any, url?: any) {
    setError(null);
    setSuccess(null);
    setRegisterWarnings([]);
    setConnectingKey(entry.key);
    const selKey = `mcp:${entry.key}`;
    focusCard(selKey);
    markPhase(selKey, "connecting");
    try {
      const payload: any = { name: entry.name, type: entry.type, description: entry.description, enabled: true, env };
      if (command) {
        payload.command = command;
        payload.args = args;
      } else if (url) {
        payload.url = url;
      }
      const res = await api.registerMcpServer(payload);
      setSuccess(noteRegisterResponse(res, t("connectingSuccess", { name: entry.name })));
      onChanged();
      scheduleAutoTestRefresh();
      // Real connection attempt is now in flight server-side - show
      // "Testing connection..." until the next refetch shows the outcome.
      markTesting(selKey);
    } catch (err) {
      setError(errorMessage(err, t("saveConnectionError")));
      clearPhase(selKey);
    } finally {
      setConnectingKey(null);
    }
  }

  // OAuth-backed catalog entries (2026-08-15, currently only atlassian - see
  // McpCatalogEntry.requiresOAuth's javadoc) have no credential to submit at
  // all - a real browser-redirect consent flow replaces both addFromCatalog
  // and connectDynamic above. This full-page-navigates away, same idiom as
  // GoogleIntegrationPanel/SocialChannelsPanel's own "Connect" buttons - if
  // it succeeds, this component unmounts; the error branch below only ever
  // fires if fetching the authorize URL itself failed before the redirect
  // could happen.
  async function connectOAuth(entry: any) {
    setError(null);
    setSuccess(null);
    setConnectingKey(entry.key);
    try {
      // A catalog entry backed by the one-click provider registry (entry.oauthProviderId) uses that flow;
      // every other OAuth entry is Atlassian's hard-wired one.
      const { url } = entry.oauthProviderId
        ? await api.startMcpOAuth(entry.oauthProviderId)
        : await api.getAtlassianAuthorizeUrl();
      window.location.href = url;
    } catch (err) {
      setError(errorMessage(err, t("saveConnectionError")));
      setConnectingKey(null);
    }
  }

  // Enable/connect/retry all kick off a server-side connection attempt (and,
  // on the new core, its auto-test) whose outcome lands on the registration
  // AFTER the triggering call returned - re-fetch shortly after so the fresh
  // status + enriched error/hint shows up without a manual refresh.
  function scheduleAutoTestRefresh() {
    autoRefreshTimers.current.push(setTimeout(onChanged, 2000), setTimeout(onChanged, 6000));
  }

  // Warn-before-enable: flipping an OFF server on routes here instead of
  // straight to toggleServer - selects the card and opens the confirm step
  // (what enabling means + credential prompt) in the inspector.
  function requestEnable(server: any, selKey?: any) {
    setError(null);
    setSuccess(null);
    if (selKey) setSelected(selKey);
    setPendingEnableId(server.id);
  }

  // The confirm step's submit. With credential entries on a dynamic server,
  // enabling has to go through POST register (an upsert by name, carrying
  // the stored command/args/url back plus the new env) because PATCH only
  // accepts {enabled} - without entries it's the plain toggle.
  // Resolves a live registration back to its grid selKey (mcp:<catalogKey>
  // or custom:<id>) so an action that only has the server object (confirmEnable,
  // retryConnect, applySuggestedFix) can still drive the same card-level
  // focus/phase-badge affordance the catalog Add/Connect paths use.
  function selKeyForServer(server: any): string | null {
    const card = cards.find((c: any) => c.kind === "mcp" && c.data?.id === server?.id);
    if (card) return card.selKey;
    return server?.id ? `custom:${server.id}` : null;
  }

  async function confirmEnable(server: any, entries: any) {
    if (enableBusy) return;
    setEnableBusy(true);
    setError(null);
    setSuccess(null);
    setRegisterWarnings([]);
    const selKey = selKeyForServer(server);
    focusCard(selKey);
    markPhase(selKey, "enabling");
    try {
      const env: Record<string, string> = {};
      (entries || []).forEach((r: any) => {
        if (r.key.trim()) env[r.key.trim()] = r.value;
      });
      let baseMessage = t("serverToggledSuccess", { name: server.name, state: t("enabledState") });
      if (Object.keys(env).length > 0 && (server.command || server.url)) {
        const payload: any = {
          name: server.name,
          type: server.type,
          description: server.description,
          enabled: true,
          env,
        };
        if (server.command) {
          payload.command = server.command;
          payload.args = parseStoredArgs(server.args);
        }
        if (server.url) payload.url = server.url;
        const res = await api.registerMcpServer(payload);
        baseMessage = `${server.name} enabled — checking the connection now; status will update here shortly.`;
        baseMessage = noteRegisterResponse(res, baseMessage);
      } else {
        await api.toggleMcpServer(server.id, true);
      }
      setSuccess(baseMessage);
      setPendingEnableId(null);
      onChanged();
      scheduleAutoTestRefresh();
      markTesting(selKey);
    } catch (err) {
      setError(errorMessage(err, t("toggleServerError")));
      clearPhase(selKey);
    } finally {
      setEnableBusy(false);
    }
  }

  async function retryConnect(server: any) {
    if (retryBusyId === server.id) return;
    setRetryBusyId(server.id);
    setError(null);
    setSuccess(null);
    const selKey = selKeyForServer(server);
    focusCard(selKey);
    markPhase(selKey, "connecting");
    try {
      await api.connectMcpServer(server.id);
      setSuccess(t("retryingConnection", { name: server.name }));
      onChanged();
      scheduleAutoTestRefresh();
      markTesting(selKey);
    } catch (err) {
      setError(errorMessage(err, t("retryConnectionError")));
      clearPhase(selKey);
    } finally {
      setRetryBusyId(null);
    }
  }

  // suggestedFix "Apply" action (see SuggestedFixBlock) - re-registers the
  // server with command/args overridden from the fix, same payload shape
  // confirmEnable/connectDynamic already build. The read DTO never echoes
  // stored env (it's the secret), so this necessarily can't carry forward
  // whatever credentials were there before - SuggestedFixBlock's own copy
  // says so, this isn't silently dropping something the user would expect
  // to survive.
  async function applySuggestedFix(server: any, fix: any) {
    if (!fix || typeof fix.command !== "string" || !fix.command.trim() || fixBusyId) return;
    setFixBusyId(server.id);
    setError(null);
    setSuccess(null);
    const selKey = selKeyForServer(server);
    focusCard(selKey);
    markPhase(selKey, "connecting");
    try {
      const payload: any = {
        name: server.name,
        type: server.type,
        description: server.description,
        enabled: true,
        command: fix.command,
        args: Array.isArray(fix.args) ? fix.args : parseStoredArgs(fix.args),
      };
      if (server.url) payload.url = server.url;
      await api.registerMcpServer(payload);
      setSuccess(`Applied the suggested fix for ${server.name} — reconnecting now.`);
      onChanged();
      scheduleAutoTestRefresh();
      markTesting(selKey);
    } catch (err: any) {
      setError(err.message || "Couldn't apply the suggested fix.");
      clearPhase(selKey);
    } finally {
      setFixBusyId(null);
    }
  }

  async function toggleServer(server: any) {
    if (toggleBusyId === server.id) return;
    setToggleBusyId(server.id);
    setError(null);
    setSuccess(null);
    try {
      await api.toggleMcpServer(server.id, !server.enabled);
      setSuccess(
        t("serverToggledSuccess", { name: server.name, state: server.enabled ? t("disabledState") : t("enabledState") })
      );
      onChanged();
    } catch (err) {
      setError(errorMessage(err, t("toggleServerError")));
    } finally {
      setToggleBusyId(null);
    }
  }

  async function handleDisconnectGoogle() {
    setError(null);
    setSuccess(null);
    try {
      await api.disconnectGoogle();
      setSuccess(t("googleDisconnectedSuccess"));
      onChanged();
    } catch (err) {
      setError(errorMessage(err, t("disconnectGoogleError")));
    }
  }
  async function deleteServer(server: any) {
    if (confirmDeleteId !== server.id) {
      setConfirmDeleteId(server.id);
      return;
    }
    setConfirmDeleteId(null);
    setDeleteBusyId(server.id);
    setError(null);
    setSuccess(null);
    try {
      await api.deleteMcpServer(server.id);
      setSuccess(t("serverDeletedSuccess", { name: server.name }));
      if (selected === server.id) setSelected(null);
      onChanged();
    } catch (err: any) {
      setError(err.message || t("deleteServerError"));
    } finally {
      setDeleteBusyId(null);
    }
  }
  // QA-07: one heading per origin (see GROUP_LABEL_KEYS in mcp/mcpCards.ts).
  const groupTotals = useMemo(() => countByCategory(filteredCards), [filteredCards]);
  const pageGroups = useMemo(() => groupByCategory(pagedCards), [pagedCards]);
  const addTile = <AddServerTile onOpen={() => setShowAdd(true)} />;

  return (
    <div className="flex flex-col gap-6 lg:flex-row lg:items-start" data-tour="mcp-connect">
      <div className="min-w-0 flex-1 space-y-4">
        <McpPanelNotices error={error} success={success} registerWarnings={registerWarnings} />

        <McpTabBar activeTab={activeTab} setActiveTab={setActiveTab} />

        {activeTab === "browse" && (
          <div role="tabpanel" id="mcp-tabpanel-browse" aria-labelledby="mcp-tab-browse">
            <RegistryBrowser onChanged={onChanged} />
          </div>
        )}

        {activeTab !== "browse" && (
          <div
            role="tabpanel"
            id={`mcp-tabpanel-${activeTab}`}
            aria-labelledby={`mcp-tab-${activeTab}`}
            className="space-y-5"
          >
            {filteredCards.length === 0 && cards.length > 0 ? (
              <Card>
                <CardContent className="py-8 text-center text-sm text-muted-foreground">
                  {t("noToolsMatchFilter")}
                </CardContent>
              </Card>
            ) : pageGroups.length === 0 ? (
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3">{addTile}</div>
            ) : (
              pageGroups.map((group, gi) => (
                <section
                  key={group.category}
                  className="space-y-2"
                  aria-label={t(GROUP_LABEL_KEYS[group.category] || "tabAll")}
                >
                  <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                    {t(GROUP_LABEL_KEYS[group.category] || "tabAll")} ·{" "}
                    {groupTotals[group.category] ?? group.items.length}
                  </div>
                  <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3">
                    {group.items.map((card: McpCard) => (
                      <McpCardTile
                        key={card.selKey}
                        card={card}
                        isSelected={selected === card.selKey}
                        phase={cardPhase[card.selKey]}
                        pins={pins}
                        addingKey={addingKey}
                        connectingKey={connectingKey}
                        registerRef={(selKey, el) => {
                          if (el) cardRefs.current.set(selKey, el);
                          else cardRefs.current.delete(selKey);
                        }}
                        onSelect={setSelected}
                        onToggleServer={toggleServer}
                        onRequestEnable={requestEnable}
                        onAddFromCatalog={addFromCatalog}
                        onConnectOAuth={connectOAuth}
                      />
                    ))}
                    {/* The "register a custom server" tile closes the last group on the page. */}
                    {gi === pageGroups.length - 1 && addTile}
                  </div>
                </section>
              ))
            )}
            <PaginationControls
              page={mcpPage}
              totalPages={mcpTotalPages}
              totalItems={mcpTotalItems}
              pageSize={mcpPageSize}
              onPageChange={setMcpPage}
              onPageSizeChange={setMcpPageSize}
              itemLabel={t("toolsItemLabel")}
            />
          </div>
        )}

        {showAdd && (
          <CustomServerForm
            catalogCount={catalog.length}
            onClose={() => setShowAdd(false)}
            onChanged={onChanged}
            setError={setError}
            setSuccess={setSuccess}
            setRegisterWarnings={setRegisterWarnings}
            noteRegisterResponse={noteRegisterResponse}
            scheduleAutoTestRefresh={scheduleAutoTestRefresh}
          />
        )}
      </div>

      <div
        ref={inspectorRef}
        className="w-full shrink-0 space-y-4 lg:sticky lg:top-6 lg:max-h-[calc(100vh-3rem)] lg:w-[380px] lg:overflow-y-auto"
      >
        {!selectedCard ? (
          <div className="flex min-h-[200px] items-center justify-center rounded-xl border border-dashed border-border p-8 text-center text-sm text-muted-foreground">
            {t("selectToolOrServerHint")}
          </div>
        ) : (
          <InspectorPanel
            key={selectedCard.selKey}
            card={selectedCard}
            agents={agents}
            googleConnection={googleConnection}
            onToggleServer={toggleServer}
            onDisconnectGoogle={handleDisconnectGoogle}
            onRetryConnect={retryConnect}
            onAddCatalog={addFromCatalog}
            onConnectCatalog={connectDynamic}
            onConnectOAuth={connectOAuth}
            addingKey={addingKey}
            connectingKey={connectingKey}
            toggleBusyId={toggleBusyId}
            retryBusyId={retryBusyId}
            pendingEnableId={pendingEnableId}
            enableBusy={enableBusy}
            usageGated={usageGated}
            onRequestEnable={requestEnable}
            onConfirmEnable={confirmEnable}
            onCancelEnable={() => setPendingEnableId(null)}
            onRefresh={onChanged}
            onApplySuggestedFix={applySuggestedFix}
            fixBusyId={fixBusyId}
            editingId={edit.editingId}
            editCommand={edit.editCommand}
            setEditCommand={edit.setEditCommand}
            editArgsText={edit.editArgsText}
            setEditArgsText={edit.setEditArgsText}
            editEnvText={edit.editEnvText}
            setEditEnvText={edit.setEditEnvText}
            editUrl={edit.editUrl}
            setEditUrl={edit.setEditUrl}
            editBusy={edit.editBusy}
            onStartEdit={edit.startEditServer}
            onCancelEdit={edit.cancelEditServer}
            onSubmitEdit={edit.submitEditServer}
            deleteBusyId={deleteBusyId}
            confirmDeleteId={confirmDeleteId}
            onDelete={deleteServer}
            onCancelDelete={() => setConfirmDeleteId(null)}
          />
        )}
      </div>
    </div>
  );
});

export default McpPanel;
// Re-exported so tools/page.tsx keeps importing it from this module.
export { BUILT_IN_TOOLS };
