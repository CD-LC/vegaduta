"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api } from "../../../lib/api";
import { useTranslations } from "../../../lib/i18n";
import { Button } from "../../ui/button";
import { Input } from "../../ui/input";
import { cn } from "../../../lib/utils";
import { Lock, Search, Wrench } from "lucide-react";
import type { ResolvedTool } from "./types";

// MCP-REVIEW (2026-08-05) - GET /api/agents/{id}/available-tools
// (AgentToolProvider#listAvailableToolsFor) only ever resolves tools from a
// server that actually produced a live MCP client - a server blocked on a
// pending/rejected platform review or unreadable credentials silently omits
// its whole tool set, with no path back to "why isn't this here" (see
// DynamicMcpConnectionManager's review-gate javadoc: the review check runs
// FIRST, before any other guard, whenever a dynamic server would otherwise
// connect). ToolDescriptor (the available-tools response shape) carries no
// per-server status field to extend, so ToolsGrid cross-references the
// tenant's full MCP registration list (the same GET /api/mcp/registry
// McpPanel/tools/servers already fetch) against the small set of reasons a
// registered, enabled dynamic server would produce zero tools despite being
// "on" - not a perfect reconstruction of exactly which tool names it WOULD
// have exposed (the backend never told us that), but enough to synthesize a
// "needs setup" row instead of the server just not existing.
function isBlockedMcpServer(server: any): boolean {
  if (!server?.dynamic || !server?.enabled) return false;
  if (server.reviewStatus === "PENDING" || server.reviewStatus === "REJECTED") return true;
  if (server.credentialsCorrupted) return true;
  if (server.connectionStatus === "ERROR") return true;
  if (server.usageStatus === "FAILED") return true;
  return false;
}

function blockedMcpServerReason(server: any, t: (key: string) => string): string {
  if (server.reviewStatus === "PENDING") return t("blockedServerAwaitingReview");
  if (server.reviewStatus === "REJECTED") return t("blockedServerReviewRejected");
  if (server.credentialsCorrupted) return t("blockedServerCredentialsCorrupted");
  if (server.usageStatus === "FAILED") return t("blockedServerConnectionFailed");
  return t("blockedServerConnectionError");
}

// One summary row per blocked server (not per-tool - the backend never told
// us which tool names a broken server would have exposed), greyed out and
// linking back to Tools where the actual fix (re-enter credentials, wait for
// review, retry the connection) lives - replaces the previous "tool just
// isn't in the list, no explanation" experience.
function BlockedServerRows({ servers }: { servers: any[] }) {
  const t = useTranslations("agentWorkbench");
  if (!servers || servers.length === 0) return null;
  return (
    <div className="space-y-2">
      {servers.map((s) => (
        <div
          key={s.id}
          className="elev-sunken flex items-center gap-3 rounded-lg border border-dashed border-border p-3 opacity-70"
        >
          <span className="cat-chip cat-chip-sm shrink-0" style={{ ["--cat" as any]: "var(--state-idle)" } as any}>
            <Lock size={13} aria-hidden="true" />
          </span>
          <div className="min-w-0 flex-1">
            <div className="truncate text-sm font-medium text-foreground">{s.name}</div>
            <div className="truncate text-xs text-muted-foreground">
              {blockedMcpServerReason(s, t)} — {t("blockedServerNeedsSetupSuffix")}
            </div>
          </div>
          <Link href="/tools" className="shrink-0 text-xs font-medium text-primary underline-offset-4 hover:underline">
            {t("configureInTools")}
          </Link>
        </div>
      ))}
    </div>
  );
}

/**
 * The design's 2-column tool grid with per-tool status + live toggle -
 * `resolved` is GET /api/agents/{id}/available-tools's real, live-resolved
 * list (built-ins + whatever MCP servers are enabled) - see
 * AgentToolProvider's javadoc for why this can't be a static frontend list.
 * An empty `selected` set means unrestricted (every resolved tool usable) -
 * shown as every card in its "on" state, matching AgentsPanel's own
 * semantics for this exact field. `agentId` is only used to fetch the
 * blocked-server cross-reference above (BlockedServerRows) - it plays no
 * part in resolving `resolved` itself, that's still the caller's job.
 */
export function ToolsGrid({
  agentId,
  resolved,
  selected,
  busyName,
  onToggle,
}: {
  // Nullable only as belt-and-suspenders for the fetch guard below - the one
  // current call site always passes a real, existing agent's id.
  agentId: string | null;
  // Same three-state sentinel shape as the caller's toolsResolved state.
  resolved: undefined | "loading" | "error" | ResolvedTool[];
  selected: Set<string>;
  busyName: string | null;
  onToggle: (toolName: string) => void;
}) {
  const t = useTranslations("agentWorkbench");
  // Client-side search (by tool name/description) + pagination over the
  // filtered results; page resets to 0 whenever the query changes.
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  // Hooks run unconditionally, ahead of the loading/error/empty early
  // returns below, so this fetch (and BlockedServerRows further down) stays
  // consistent across every one of those branches - including the empty
  // one, which is exactly the case where every tool being blocked matters
  // most.
  const [blockedServers, setBlockedServers] = useState<any[]>([]);
  // An empty array here is ambiguous - it means both "genuinely zero blocked
  // servers" (the common, fine case) and "the fetch failed" (exactly the
  // case where BlockedServerRows's explanation is needed most, per the
  // comment above). This flag keeps those apart so a fetch failure gets a
  // distinguishable, retry-capable notice instead of silently looking like
  // "nothing is blocked".
  const [blockedServersError, setBlockedServersError] = useState(false);
  const [blockedServersRetryToken, setBlockedServersRetryToken] = useState(0);
  useEffect(() => {
    if (!agentId) {
      setBlockedServers([]);
      setBlockedServersError(false);
      return undefined;
    }
    let cancelled = false;
    setBlockedServersError(false);
    api
      .listMcpServers()
      .then((servers) => {
        if (cancelled) return;
        setBlockedServers(Array.isArray(servers) ? servers.filter(isBlockedMcpServer) : []);
      })
      .catch(() => {
        if (cancelled) return;
        setBlockedServers([]);
        setBlockedServersError(true);
      });
    return () => {
      cancelled = true;
    };
  }, [agentId, blockedServersRetryToken]);
  if (resolved === "loading" || resolved === undefined) {
    // Skeleton shaped like the grid it becomes (see the reliability panel for
    // the same reasoning) instead of a bare line of grey text.
    return (
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-2" aria-busy="true" aria-label={t("loadingTools")}>
        {[0, 1, 2, 3].map((i) => (
          <div key={i} className="elev-sunken flex items-center gap-3 rounded-lg p-3">
            <span className="size-8 shrink-0 animate-pulse rounded-md bg-muted" />
            <span className="flex min-w-0 flex-1 flex-col gap-1.5">
              <span className="h-3 w-1/2 animate-pulse rounded bg-muted" />
              <span className="h-2.5 w-3/4 animate-pulse rounded bg-muted" />
            </span>
          </div>
        ))}
      </div>
    );
  }
  if (resolved === "error") {
    return (
      <p className="text-sm text-muted-foreground">
        {t("toolsLoadErrorPrefix")}{" "}
        <Link href="/tools" className="font-medium text-primary underline-offset-4 hover:underline">
          {t("browseMcpMarketplace")}
        </Link>
      </p>
    );
  }
  if (resolved.length === 0) {
    return (
      <div className="space-y-3">
        <p className="text-sm text-muted-foreground">
          {t("noToolsAvailablePrefix")}
          <Link href="/tools" className="font-medium text-primary underline-offset-4 hover:underline">
            {t("mcpMarketplace")}
          </Link>
          .
        </p>
        {/* This is exactly the case where a blocked-server explanation
         * matters most - zero usable tools could mean nothing is enabled
         * at all, or it could mean every enabled server is stuck on a
         * pending review / bad credentials with no other clue why. */}
        {blockedServersError ? (
          <p className="text-sm text-muted-foreground">
            {t("blockedServersCheckFailed")}{" "}
            <button
              type="button"
              onClick={() => setBlockedServersRetryToken((n) => n + 1)}
              className="font-medium text-primary underline-offset-4 hover:underline"
            >
              {t("retry")}
            </button>
          </p>
        ) : (
          <BlockedServerRows servers={blockedServers} />
        )}
      </div>
    );
  }
  const unrestricted = selected.size === 0;
  const query = search.trim().toLowerCase();
  const filtered = query
    ? resolved.filter(
        (tool) =>
          (tool.name || "").toLowerCase().includes(query) || (tool.description || "").toLowerCase().includes(query)
      )
    : resolved;
  const PER_PAGE = 8;
  const pageCount = Math.max(1, Math.ceil(filtered.length / PER_PAGE));
  const pageClamped = Math.min(page, pageCount - 1);
  const shown = filtered.slice(pageClamped * PER_PAGE, pageClamped * PER_PAGE + PER_PAGE);
  return (
    <div className="space-y-3">
      <div className="relative">
        <Search
          size={13}
          className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground"
          aria-hidden="true"
        />
        <Input
          type="text"
          value={search}
          onChange={(e) => {
            setSearch(e.target.value);
            setPage(0);
          }}
          placeholder={t("toolsSearchPlaceholder")}
          className="h-9 pl-8"
        />
      </div>
      {filtered.length === 0 ? (
        <div className="elev-sunken flex items-center gap-2.5 rounded-lg px-3 py-4">
          <span className="cat-chip cat-chip-sm" style={{ ["--cat" as any]: "var(--cat-automation)" } as any}>
            <Search size={12} aria-hidden="true" />
          </span>
          <span className="text-sm text-muted-foreground">{t("noToolsMatchSearch")}</span>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          {/* Tool cards: .card-interactive for real hover feedback (the old
              cards were completely inert), a proper .cat-chip carrying the
              automation hue + a Wrench icon instead of a "⚙" text glyph, and
              a .state-dot that animates only while that tool's PATCH is
              actually in flight — so "off / on / saving" are three distinct
              signals rather than one border-colour change. */}
          {shown.map((tool) => {
            const on = unrestricted || selected.has(tool.name);
            const saving = busyName === tool.name;
            return (
              <div
                key={tool.name}
                className={cn(
                  "card-interactive flex items-center gap-3 rounded-lg border p-3",
                  on ? "elev-base" : "elev-sunken opacity-80"
                )}
                style={{ ["--cat" as any]: on ? "var(--cat-automation)" : "var(--state-idle)" } as any}
              >
                <span className={cn("cat-chip", !on && "opacity-70")}>
                  <Wrench size={14} strokeWidth={1.9} aria-hidden="true" />
                </span>
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-1.5">
                    <span className="truncate text-sm font-medium text-foreground">{tool.name}</span>
                    <span
                      className={cn("state-dot", saving && "state-dot-live")}
                      style={
                        {
                          ["--state" as any]: saving
                            ? "var(--state-running)"
                            : on
                              ? "var(--state-success)"
                              : "var(--state-idle)",
                        } as any
                      }
                      title={
                        saving ? t("toolStatusSaving") : on ? t("toolStatusAvailable") : t("toolStatusUnavailable")
                      }
                    />
                  </div>
                  <div className="truncate text-xs text-muted-foreground">
                    {tool.description ? tool.description.slice(0, 34) : t("toolFallback")}
                  </div>
                </div>
                <button
                  type="button"
                  className={`ax-toggle${on ? " ax-toggle-on" : ""}`}
                  role="switch"
                  aria-checked={on}
                  aria-label={t("toggleToolAriaLabel", { name: tool.name })}
                  disabled={saving || busyName != null}
                  onClick={() => onToggle(tool.name)}
                />
              </div>
            );
          })}
        </div>
      )}
      {pageCount > 1 && (
        <div className="flex items-center justify-between gap-2">
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={pageClamped <= 0}
            onClick={() => setPage(pageClamped - 1)}
          >
            {t("prevPage")}
          </Button>
          <span className="text-xs tabular-nums text-muted-foreground">
            {pageClamped + 1} / {pageCount}
          </span>
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={pageClamped >= pageCount - 1}
            onClick={() => setPage(pageClamped + 1)}
          >
            {t("nextPage")}
          </Button>
        </div>
      )}
      {/* Servers blocked on a pending review, unreadable credentials, or a
       * connection failure - greyed-out rows below the real, usable tools,
       * not silently absent (see isBlockedMcpServer's javadoc-style comment
       * above). */}
      {blockedServersError ? (
        <p className="text-sm text-muted-foreground">
          {t("blockedServersCheckFailed")}{" "}
          <button
            type="button"
            onClick={() => setBlockedServersRetryToken((n) => n + 1)}
            className="font-medium text-primary underline-offset-4 hover:underline"
          >
            {t("retry")}
          </button>
        </p>
      ) : (
        <BlockedServerRows servers={blockedServers} />
      )}
    </div>
  );
}
