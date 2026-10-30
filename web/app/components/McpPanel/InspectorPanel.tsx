"use client";

import { ToolPinServerBadge } from "./ToolPinStatus";
import { Wrench, Loader2, AlertTriangle, RefreshCw, Power, PowerOff, Pencil, Trash2 } from "lucide-react";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Textarea } from "../ui/textarea";
import { Card, CardContent } from "../ui/card";
import { Alert, AlertDescription } from "../ui/alert";
import { cn } from "../../lib/utils";
import { useTranslations } from "../../lib/i18n";
import Link from "next/link";
import { CATALOG_ICONS } from "./constants";
import { reviewStatusOf } from "./helpers";
import {
  ProvenanceReviewBadge,
  CatalogVerifiedBadge,
  CredentialsCorruptedAlert,
  ReviewStatusAlert,
  ReadinessBadge,
} from "./parts";
import { ConnectionErrorBlock, SuggestedFixBlock, TestConnectionSection, AcknowledgeControl } from "./connectionParts";
import { EnableConfirmCard, CatalogCardInspector } from "./catalogParts";
import { BuiltinInspector, GoogleConnectInspector, GoogleToolInspector } from "./IntegrationInspectors";

export default function InspectorPanel({
  card,
  agents,
  googleConnection,
  onToggleServer,
  onDisconnectGoogle,
  onRetryConnect,
  onAddCatalog,
  onConnectCatalog,
  onConnectOAuth,
  addingKey,
  connectingKey,
  toggleBusyId,
  retryBusyId,
  pendingEnableId,
  enableBusy,
  usageGated,
  onRequestEnable,
  onConfirmEnable,
  onCancelEnable,
  onRefresh,
  onApplySuggestedFix,
  fixBusyId,
  editingId,
  editCommand,
  setEditCommand,
  editArgsText,
  setEditArgsText,
  editEnvText,
  setEditEnvText,
  editUrl,
  setEditUrl,
  editBusy,
  onStartEdit,
  onCancelEdit,
  onSubmitEdit,
  deleteBusyId,
  confirmDeleteId,
  onDelete,
  onCancelDelete,
}: {
  card: any;
  agents: any;
  googleConnection: any;
  onToggleServer: any;
  onDisconnectGoogle: any;
  onRetryConnect: any;
  onAddCatalog: any;
  onConnectCatalog: any;
  onConnectOAuth: any;
  addingKey: any;
  connectingKey: any;
  toggleBusyId: any;
  retryBusyId: any;
  pendingEnableId: any;
  enableBusy: any;
  usageGated: boolean;
  onRequestEnable: any;
  onConfirmEnable: any;
  onCancelEnable: any;
  onRefresh: any;
  onApplySuggestedFix?: any;
  fixBusyId?: string | null;
  editingId?: any;
  editCommand?: string;
  setEditCommand?: any;
  editArgsText?: string;
  setEditArgsText?: any;
  editEnvText?: string;
  setEditEnvText?: any;
  editUrl?: string;
  setEditUrl?: any;
  editBusy?: boolean;
  onStartEdit?: any;
  onCancelEdit?: any;
  onSubmitEdit?: any;
  deleteBusyId?: any;
  confirmDeleteId?: any;
  onDelete?: any;
  onCancelDelete?: any;
}) {
    const tHC = useTranslations("hcComponentsMcpPanelInspectorPanel");
  const t = useTranslations("mcpPanel");
  if (card.kind === "catalog") {
    const entry = card.data;
    const Icon = CATALOG_ICONS[entry.iconKey] || Wrench;
    return (
      <div className="space-y-4">
        <div className="flex items-center gap-3">
          <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-muted text-muted-foreground">
            <Icon size={18} />
          </span>
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-1.5">
              <div className="truncate text-base font-semibold">{entry.name}</div>
              <CatalogVerifiedBadge compact />
            </div>
            <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
              <span className="size-1.5 rounded-full bg-muted-foreground/50" />
              {t("notAddedYetLabel")}
              {entry.ready ? t("noKeyNeededSuffix") : t("needsKeySuffix")}
            </div>
          </div>
        </div>
        <CatalogCardInspector
          entry={entry}
          onAdd={onAddCatalog}
          addBusy={addingKey === entry.key}
          onConnect={onConnectCatalog}
          connecting={connectingKey === entry.key}
          onConnectOAuth={onConnectOAuth}
        />
      </div>
    );
  }

  if (card.kind === "builtin") return <BuiltinInspector card={card} />;

  if (card.kind === "google-connect") return <GoogleConnectInspector />;

  if (card.kind === "google") {
    return (
      <GoogleToolInspector
        card={card}
        agents={agents}
        googleConnection={googleConnection}
        onDisconnectGoogle={onDisconnectGoogle}
      />
    );
  }

  // kind === "mcp"
  const server = card.data;
  const Icon = CATALOG_ICONS[card.catalogEntry?.iconKey] || Wrench;
  // A row that exists (was registered at some point, possibly before this
  // catalog entry ever offered a Connect-now form) but is neither a real
  // static YAML connection (catalogEntry.ready) nor a live dynamic one
  // (server.dynamic) - it looks "registered · enabled" but produces zero
  // real tools, because nothing ever actually told a client how to reach
  // it. Distinguishing this from a genuinely-working static entry
  // (filesystem, puppeteer, etc., where catalogEntry.ready is true) is
  // exactly the gap that made google-maps look "enabled" with no way to
  // add the credential it actually needs.
  const isPhantom = !server.dynamic && card.catalogEntry && !card.catalogEntry.ready;
  // MCP-STDIO-SANDBOXING - while PENDING/REJECTED, DynamicMcpConnectionManager
  // refuses to ever spawn this server regardless of what its connectionStatus/
  // usageStatus last said (see McpReviewStatus's javadoc) - so the reconnect/
  // test/trust actions below would just surface that refusal. Hiding them
  // here (in favor of ReviewStatusAlert's explanation) is more honest than
  // offering a button that can't succeed yet.
  const reviewBlocked = reviewStatusOf(server) === "PENDING" || reviewStatusOf(server) === "REJECTED";
  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <span
          className={cn(
            "flex size-10 shrink-0 items-center justify-center rounded-full",
            server.enabled && !isPhantom ? "bg-primary/10 text-primary" : "bg-muted text-muted-foreground"
          )}
        >
          <Icon size={18} />
        </span>
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-1.5">
            <div className="truncate text-base font-semibold">{server.name}</div>
            <ProvenanceReviewBadge server={server} compact />
            {server.dynamic && <ToolPinServerBadge serverId={server.id} />}
          </div>
          <div
            className={cn(
              "flex items-center gap-1.5 text-xs font-medium",
              isPhantom
                ? "text-[var(--state-warning)]"
                : server.enabled
                  ? "text-[var(--state-success)]"
                  : "text-muted-foreground"
            )}
          >
            <span
              className={cn(
                "size-1.5 rounded-full",
                isPhantom
                  ? "bg-[var(--state-warning)]"
                  : server.enabled
                    ? "bg-[var(--state-success)]"
                    : "bg-muted-foreground/50"
              )}
            />
            {isPhantom
              ? t("registeredNotConnected")
              : t("registeredEnabledStatus", { state: server.enabled ? t("enabledState") : t("disabledState") })}
          </div>
          <ReadinessBadge server={server} />
        </div>
      </div>

      {/* Decrypt-failure visibility - distinct from "never configured": the
       * credentials ARE there, they just can't be read anymore. */}
      {server.credentialsCorrupted && <CredentialsCorruptedAlert onReEnter={() => onRequestEnable(server)} />}

      {/* MCP-STDIO-SANDBOXING review-state explanation - PENDING/REJECTED,
       * shown ahead of the live-connection card since it's the reason
       * nothing there will move yet. */}
      <ReviewStatusAlert server={server} />

      {pendingEnableId === server.id && (
        <EnableConfirmCard
          server={server}
          busy={enableBusy}
          usageGated={usageGated}
          onConfirm={onConfirmEnable}
          onCancel={onCancelEnable}
        />
      )}

      <Card>
        <CardContent className="space-y-2 pt-6">
          <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            {t("serverDetailsLabel")}
          </div>
          <p className="text-sm text-muted-foreground">{server.description || t("noDescription")}</p>
          <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 text-sm">
            <span className="text-muted-foreground">{t("typeLabel")}</span>
            <span className="min-w-0 break-words font-medium">{server.type}</span>
          </div>
          <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 text-sm">
            <span className="text-muted-foreground">{t("registeredLabel")}</span>
            <span className="min-w-0 break-words font-medium">{new Date(server.createdAt).toLocaleString()}</span>
          </div>
          <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 text-sm">
            <span className="text-muted-foreground">{t("updatedLabel")}</span>
            <span className="min-w-0 break-words font-medium">{new Date(server.updatedAt).toLocaleString()}</span>
          </div>
          <p className="pt-1 text-xs text-muted-foreground">
            {tHC("s1")}
          </p>
        </CardContent>
      </Card>

      {isPhantom && (
        <div className="space-y-4">
          <Alert variant="destructive">
            <AlertTriangle aria-hidden="true" />
            <AlertDescription>{t("phantomWarning")}</AlertDescription>
          </Alert>
          <CatalogCardInspector
            entry={card.catalogEntry}
            onAdd={onAddCatalog}
            addBusy={addingKey === card.catalogEntry.key}
            onConnect={onConnectCatalog}
            connecting={connectingKey === card.catalogEntry.key}
            onConnectOAuth={onConnectOAuth}
          />
        </div>
      )}

      {server.dynamic && (
        <Card>
          <CardContent className="space-y-3 pt-6">
            <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              {t("connectionLiveLabel")}
            </div>
            <p className="text-sm text-muted-foreground">{t("dynamicConnectionHint")}</p>
            <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 text-sm">
              <span className="text-muted-foreground">{t("statusLabel")}</span>
              <span
                className={cn(
                  "font-medium",
                  server.connectionStatus === "CONNECTED"
                    ? "text-[var(--state-success)]"
                    : server.connectionStatus === "ERROR"
                      ? "text-destructive"
                      : "text-muted-foreground"
                )}
              >
                {server.connectionStatus}
              </span>
            </div>
            {/* Enriched error rendering: actionable hint emphasized, raw
             * multi-line detail behind a disclosure - replaces the old
             * stack-trace-looking red text blob. */}
            <ConnectionErrorBlock server={server} label={t("lastErrorLabel")} />
            {/* suggestedFix read from the registration itself (server.suggestedFix)
             * - the other plausible location, on a fresh test response, is
             * handled inside TestConnectionSection below. */}
            {onApplySuggestedFix && (
              <SuggestedFixBlock
                fix={server.suggestedFix}
                onApply={() => onApplySuggestedFix(server, server.suggestedFix)}
                busy={fixBusyId === server.id}
              />
            )}
            {server.connectedAt && (
              <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 text-sm">
                <span className="text-muted-foreground">{t("connectedAtLabel")}</span>
                <span className="min-w-0 break-words font-medium">{new Date(server.connectedAt).toLocaleString()}</span>
              </div>
            )}
            {/* Connect/test/trust are all refused server-side while a review
             * is pending or rejected (see reviewBlocked's comment above) -
             * ReviewStatusAlert already explained why, so offering these
             * would just be a button that can't succeed yet. */}
            {!reviewBlocked && (
              <>
                <div className="flex gap-2">
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    className="flex-1"
                    disabled={retryBusyId === server.id}
                    onClick={() => onRetryConnect(server)}
                  >
                    {retryBusyId === server.id ? (
                      <Loader2 size={13} className="animate-spin" aria-hidden="true" />
                    ) : (
                      <RefreshCw size={13} aria-hidden="true" />
                    )}
                    {retryBusyId === server.id
                      ? t("connectingEllipsis")
                      : server.connectionStatus === "CONNECTED"
                        ? t("reconnectButton")
                        : t("tryConnectingAgainButton")}
                  </Button>
                  {/* A broken server's very next question is "how do I stop it
                   * bothering me" - previously the only disable control lived at
                   * the bottom of the whole panel, past the details/tools cards,
                   * easy to miss entirely on a failed connection. Surfacing it
                   * here too, right next to the error, only when there's
                   * actually something wrong to disable (a healthy CONNECTED
                   * server doesn't need this shortcut - the bottom toggle still
                   * covers that case). */}
                  {server.enabled && server.connectionStatus === "ERROR" && (
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      className="flex-1 border-destructive/40 text-destructive hover:bg-destructive/10"
                      disabled={toggleBusyId === server.id}
                      onClick={() => onToggleServer(server)}
                    >
                      {toggleBusyId === server.id ? t("disablingEllipsis") : t("disableButton")}
                    </Button>
                  )}
                </div>
                {/* Diagnostic sibling of the reconnect button above: reconnect
                 * re-establishes the live client, the test explains WHY a
                 * connection fails (phase + detail + hint). Self-hides for the
                 * whole page load if the endpoint 404s (older core). */}
                <TestConnectionSection
                  server={server}
                  onRefresh={onRefresh}
                  onApplySuggestedFix={onApplySuggestedFix}
                  fixBusy={fixBusyId === server.id}
                />
                <AcknowledgeControl server={server} onRefresh={onRefresh} />
              </>
            )}
          </CardContent>
        </Card>
      )}

      {/* A non-dynamic registration has no live-connection card, but can
       * still carry a usageStatus on the new core - keep the deliberate
       * "use anyway" override reachable for it too (renders nothing when
       * the field is absent or already READY/ACKNOWLEDGED). */}
      {!server.dynamic && <AcknowledgeControl server={server} onRefresh={onRefresh} />}

      <Card>
        <CardContent className="space-y-2 pt-6">
          <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            {t("toolsExposedLabel")}
          </div>
          <p className="text-sm text-muted-foreground">{t("toolsExposedDynamicHint")}</p>
          <Button variant="outline" size="sm" className="w-full" asChild>
            <Link href="/agents">{t("openAgentsLink")}</Link>
          </Button>
        </CardContent>
      </Card>

      <Button
        className="w-full"
        variant={server.enabled ? "outline" : "default"}
        disabled={toggleBusyId === server.id || (!server.enabled && pendingEnableId === server.id)}
        onClick={() => (server.enabled ? onToggleServer(server) : onRequestEnable(server))}
      >
        {toggleBusyId === server.id ? (
          <Loader2 size={13} className="animate-spin" aria-hidden="true" />
        ) : server.enabled ? (
          <PowerOff size={13} aria-hidden="true" />
        ) : (
          <Power size={13} aria-hidden="true" />
        )}
        {toggleBusyId === server.id
          ? server.enabled
            ? t("disablingEllipsis")
            : t("enablingEllipsis")
          : server.enabled
            ? t("disableButton")
            : t("enableButton")}
      </Button>

      {/* #edit-delete-gap: rotating a bad/expired credential previously meant
       * registering a whole new server under a new name and leaving the
       * broken one behind - there was no edit affordance anywhere. Only
       * offered for dynamic (command/url-bearing) registrations - a static,
       * YAML-wired reference has nothing here to edit. */}
      {server.dynamic && (
        <Card>
          <CardContent className="space-y-2 pt-6">
            {editingId === server.id ? (
              <form className="space-y-2" onSubmit={(e) => onSubmitEdit(server, e)}>
                <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                  {t("editCredentialsLabel")}
                </div>
                <p className="text-xs text-muted-foreground">{t("editCredentialsHint")}</p>
                {server.command ? (
                  <>
                    <Input
                      type="text"
                      placeholder={t("commandPlaceholder")}
                      value={editCommand}
                      onChange={(e) => setEditCommand(e.target.value)}
                    />
                    <Textarea
                      className="resize-y"
                      placeholder={t("argsPlaceholder")}
                      value={editArgsText}
                      onChange={(e) => setEditArgsText(e.target.value)}
                      rows={3}
                    />
                  </>
                ) : (
                  <Input type="text" placeholder="URL" value={editUrl} onChange={(e) => setEditUrl(e.target.value)} />
                )}
                <Textarea
                  className="resize-y"
                  placeholder={t("envPlaceholder")}
                  value={editEnvText}
                  onChange={(e) => setEditEnvText(e.target.value)}
                  rows={3}
                />
                <div className="flex gap-2">
                  <Button type="submit" size="sm" className="flex-1" disabled={editBusy}>
                    {editBusy ? <Loader2 size={13} className="animate-spin" aria-hidden="true" /> : t("saveButton")}
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    className="flex-1"
                    disabled={editBusy}
                    onClick={onCancelEdit}
                  >
                    {t("cancelButton")}
                  </Button>
                </div>
              </form>
            ) : (
              <Button type="button" variant="outline" size="sm" className="w-full" onClick={() => onStartEdit(server)}>
                <Pencil size={13} aria-hidden="true" />
                {t("editCredentialsButton")}
              </Button>
            )}
          </CardContent>
        </Card>
      )}

      <Button
        type="button"
        variant={confirmDeleteId === server.id ? "destructive" : "outline"}
        size="sm"
        className="w-full"
        disabled={deleteBusyId === server.id}
        onClick={() => onDelete(server)}
      >
        {deleteBusyId === server.id ? (
          <Loader2 size={13} className="animate-spin" aria-hidden="true" />
        ) : (
          <Trash2 size={13} aria-hidden="true" />
        )}
        {deleteBusyId === server.id
          ? t("deletingEllipsis")
          : confirmDeleteId === server.id
            ? t("confirmDeleteButton")
            : t("deleteButton")}
      </Button>
      {confirmDeleteId === server.id && (
        <Button type="button" variant="ghost" size="sm" className="w-full" onClick={onCancelDelete}>
          {t("cancelButton")}
        </Button>
      )}
    </div>
  );
}
