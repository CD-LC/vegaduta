"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import {
  AlertTriangle,
  Ban,
  Info,
  KeyRound,
  Loader2,
  RefreshCw,
  ShieldCheck,
  ShieldQuestion,
  Undo2,
} from "lucide-react";
import { api } from "../../lib/api";
import { cn } from "../../lib/utils";
import { EmptyState, Section } from "../ax/page-kit";
import { Alert, AlertDescription, AlertTitle } from "../ui/alert";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Card, CardContent } from "../ui/card";
import { Skeleton } from "../ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../ui/table";
import InfoTip from "../InfoTip";
import PaginationControls from "../PaginationControls";
import { usePagination } from "../../lib/usePagination";
import ToolClassificationLegend from "./ToolClassificationLegend";
import { TierCell, TierPill, UnclassifiedPill } from "./ToolClassificationBadges";
import {
  AXIS_QUESTIONS,
  CLASSIFICATION_FILTERS,
  SEVERITY_STATE_TOKEN,
  determinismMeta,
  isUnclassified,
  originLabel,
  sensitivityMeta,
  sortByRisk,
  trustMeta,
  type ToolClassificationListResponse,
  type ToolClassificationRow,
} from "./toolClassification";

/**
 * Tool classification registry — the console surface for build-agenda item 3
 * (`trust × sensitivity × determinism`, non-nullable, fails closed).
 *
 * What a tenant gets out of this screen: one table that says, per tool an
 * agent can actually call, whether the tool's output came from somewhere
 * untrusted, whether it acts with a stored credential of theirs (and can
 * therefore spend money if that credential can), whether it sends data out of
 * the platform, and whether retrying it after a timeout can do the same thing
 * twice. Those four questions are the entire safety conversation about a
 * tool, and before this screen the only place any of it existed was approval
 * "sensitivity globs" scattered through policy config.
 *
 * Two design rules this panel follows that are worth stating, because both are
 * easy to break in a later edit:
 *
 *   1. An unclassified tool reads as BLOCKED, loudly. The registry fails
 *      closed, so the UI must too. Three blank cells would read as "nothing
 *      to see here"; a solid red "Unclassified — blocked" pill plus a tinted
 *      row reads as the refusal it is. A row with only *some* axes declared
 *      counts as unclassified as well (see `isUnclassified`) — the guard that
 *      runs before a tool call needs all three to decide anything.
 *
 *   2. Nothing on this screen is invented. The backend for this registry is
 *      being built in parallel, so three different "no data" situations are
 *      kept distinct instead of collapsing into one empty table:
 *        - the running core has no classification endpoint at all (HTTP 404)
 *          → say so, and show the legend as reference only;
 *        - the request failed → say it failed, with a retry, because "couldn't
 *          load" is NOT "you have nothing classified" (the same mistake this
 *          page's own server list made and fixed, see ToolsPage's loadFailed
 *          comment);
 *        - the endpoint answered with zero tools → a real empty state.
 *      A tool never gets a tier this component chose for it.
 */

/**
 * The member `web/app/lib/api.ts` will grow for this panel. It is declared
 * here, as optional, rather than assumed: `api.ts` is owned by the integrator
 * this week and the endpoint itself is mid-build, so this component must
 * compile and render sanely both before and after that method lands. The
 * runtime `typeof` check below is what turns "not wired yet" into the
 * unsupported state instead of a TypeError in the console.
 */
type ClassificationCapableApi = {
  listToolClassifications?: () => Promise<ToolClassificationListResponse>;
};

export interface ToolClassificationPanelProps {
  /** Free-text filter, wired to the Tools page's existing topbar search so
   *  one search box filters this table too. Matched against the tool's
   *  literal name, its display name and its owning MCP server. */
  query?: string;
  /** Injection point for the loader. The integrator can pass
   *  `api.listToolClassifications` directly (or a test double) without
   *  touching this file; left unset, the panel looks the method up on `api`
   *  defensively. */
  load?: () => Promise<ToolClassificationListResponse>;
  className?: string;
}

/** Status cell. "Classified" deliberately does NOT say "allowed": a complete
 *  classification means someone declared what the tool can do, not that
 *  policy lets this agent call it. Overstating that would be the one
 *  misreading that gets somebody hurt. */
function StatusCell({ row }: { row: ToolClassificationRow }) {
  if (isUnclassified(row)) {
    return (
      <div className="space-y-1">
        <UnclassifiedPill />
        {row.blockedReason && <p className="text-xs leading-relaxed text-muted-foreground">{row.blockedReason}</p>}
      </div>
    );
  }
  return (
    <Badge
      variant="state"
      state={SEVERITY_STATE_TOKEN.safe}
      className="gap-1.5"
      title="All three axes are declared for this tool. Whether a given agent may call it is still decided by that agent's own tool list and your approval policy."
    >
      <ShieldCheck aria-hidden="true" />
      Classified
    </Badge>
  );
}

function ToolCell({ row }: { row: ToolClassificationRow }) {
  const origin = originLabel(row.origin);
  const name = row.displayName || row.toolName;
  return (
    <div className="min-w-0 space-y-1">
      <div className="truncate font-medium text-foreground">{name}</div>
      {/* The literal tool name is what `Agent.allowedToolNames` contains and
       * what a trace or a support ticket will quote, so it is always shown -
       * monospace, and only suppressed when it IS the display name. */}
      {row.displayName && row.displayName !== row.toolName && (
        <div className="truncate font-mono text-xs text-muted-foreground">{row.toolName}</div>
      )}
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
        {origin && <span>{origin}</span>}
        {row.serverName && (
          <>
            {origin && <span aria-hidden="true">·</span>}
            <span className="truncate">{row.serverName}</span>
          </>
        )}
        {row.requiredScope && (
          <>
            <span aria-hidden="true">·</span>
            <span
              className="inline-flex items-center gap-1"
              title="The permission or OAuth scope this tool needs before it can run."
            >
              <KeyRound className="size-3" aria-hidden="true" />
              <span className="font-mono">{row.requiredScope}</span>
            </span>
          </>
        )}
      </div>
    </div>
  );
}

export default function ToolClassificationPanel({ query = "", load, className }: ToolClassificationPanelProps) {
  const [rows, setRows] = useState<ToolClassificationRow[]>([]);
  const [enforced, setEnforced] = useState<boolean | null>(null);
  const [loading, setLoading] = useState(true);
  // Three distinct non-success states, never merged - see the class javadoc.
  const [unsupported, setUnsupported] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [activeFilter, setActiveFilter] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      let data: ToolClassificationListResponse;
      if (load) {
        data = await load();
      } else {
        const capable = api as unknown as ClassificationCapableApi;
        if (typeof capable.listToolClassifications !== "function") {
          // The running console/core pair has no classification endpoint
          // wired yet. Not an error, and emphatically not "zero tools".
          setRows([]);
          setEnforced(null);
          setUnsupported(true);
          return;
        }
        data = await capable.listToolClassifications();
      }
      if (data?.unsupported) {
        setRows([]);
        setEnforced(null);
        setUnsupported(true);
        return;
      }
      setUnsupported(false);
      setRows(Array.isArray(data?.tools) ? data.tools : []);
      // Only trust an explicit boolean. `undefined` means the core does not
      // report enforcement state, and the panel then says nothing about it
      // rather than guessing in either direction.
      setEnforced(typeof data?.enforced === "boolean" ? data.enforced : null);
    } catch (err: unknown) {
      // Keep whatever we last had on screen rather than blanking the table -
      // a failed refresh must not make a classified tool look unclassified.
      setError(err instanceof Error && err.message ? err.message : "Couldn't load tool classifications.");
    } finally {
      setLoading(false);
    }
  }, [load]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  // Counts come off the full row set, not the filtered view, so a chip's
  // number means "this many tools in your workspace" regardless of what is
  // currently selected.
  const filterCounts = useMemo(() => {
    const counts: Record<string, number> = {};
    for (const filter of CLASSIFICATION_FILTERS) {
      counts[filter.id] = rows.filter((row) => filter.match(row)).length;
    }
    return counts;
  }, [rows]);

  const visibleRows = useMemo(() => {
    const needle = query.trim().toLowerCase();
    const filter = CLASSIFICATION_FILTERS.find((f) => f.id === activeFilter) ?? null;
    const filtered = rows.filter((row) => {
      if (filter && !filter.match(row)) return false;
      if (!needle) return true;
      const haystack = [row.toolName, row.displayName, row.serverName, row.requiredScope]
        .filter((part): part is string => typeof part === "string" && part.length > 0)
        .join(" ")
        .toLowerCase();
      return haystack.includes(needle);
    });
    return sortByRisk(filtered);
  }, [rows, query, activeFilter]);

  const blockedCount = filterCounts.blocked ?? 0;
  // Rows can number in the hundreds once a workspace wires up several MCP
  // servers - the shared client-side pagination keeps the table readable.
  const rowsPagination = usePagination(visibleRows);

  // The destructive alert above already says the load failed. Critically NOT the empty state
  // below it: "the registry answered with zero tools" is a claim we cannot make after a failed
  // request, and it is the exact misreading ("nothing needs classifying, all good") this screen
  // must never produce.
  const loadFailedNoRows = Boolean(error) && rows.length === 0;

  return (
    // Stable anchor so this panel is deep-linkable as /tools#tool-classification
    // (from the nav, the servers screen, or a support reply) without needing a
    // route of its own - the classification of a tool only makes sense next to
    // the tools it classifies. `scroll-mt` keeps the heading clear of the
    // sticky app chrome when jumped to.
    <div id="tool-classification" className="scroll-mt-24">
      <Section
        className={className}
        title="Tool risk classification"
        description="What every tool an agent can call is declared to be — where its answers come from, what it is allowed to touch, and whether running it twice is safe. A tool nobody has classified is blocked."
        actions={
          <Button variant="outline" size="sm" onClick={() => void refresh()} disabled={loading}>
            {loading ? (
              <Loader2 size={13} className="animate-spin" aria-hidden="true" />
            ) : (
              <RefreshCw size={13} aria-hidden="true" />
            )}
            {loading ? "Loading…" : "Refresh"}
          </Button>
        }
      >
        {/* Load failure, stated as a load failure. Same principle as the server
         * list above it: an empty or stale table must never be readable as
         * "everything is classified". */}
        {error && (
          <Alert variant="destructive">
            <AlertTriangle aria-hidden="true" />
            <AlertTitle>Couldn&apos;t load tool classifications</AlertTitle>
            <AlertDescription>
              <p>
                {error} Nothing has changed about which tools are allowed — we just can&apos;t show their classification
                right now. Treat this table as out of date until it loads.
              </p>
              <Button variant="outline" size="sm" className="mt-2 w-fit" onClick={() => void refresh()}>
                Try again
              </Button>
            </AlertDescription>
          </Alert>
        )}

        {/* Enforcement off is the genuinely dangerous state: classifications are
         * being recorded but an unclassified tool still runs. Rendered only when
         * the API says so explicitly. */}
        {enforced === false && (
          <Alert variant="warning">
            <AlertTriangle aria-hidden="true" />
            <AlertTitle>Classification isn&apos;t being enforced yet on this deployment</AlertTitle>
            <AlertDescription>
              Tools are being classified and shown here, but an unclassified tool is not yet refused at call time. Until
              enforcement is switched on, treat the blocked label below as a warning rather than a guarantee.
            </AlertDescription>
          </Alert>
        )}

        {/* The headline number, as an alert rather than a statistics tile,
         * because "3 of your tools will refuse to run" is something to read,
         * not a metric to glance at. */}
        {!loading && !unsupported && blockedCount > 0 && (
          <Alert variant="warning">
            <Ban aria-hidden="true" />
            <AlertTitle>
              {blockedCount} tool{blockedCount === 1 ? "" : "s"} {blockedCount === 1 ? "is" : "are"} blocked until
              classified
            </AlertTitle>
            <AlertDescription>
              {/* Do not promise a refusal the deployment isn't making. When the
               * registry reports enforcement off, the alert above owns that
               * caveat and this one states the intent without the guarantee. */}
              {enforced === false
                ? "These are the tools that would be refused once enforcement is switched on. The registry is designed to fail closed rather than trust a tool nobody has described. Classify them before that happens."
                : "An agent that tries to call one of these gets refused. That is the intended behaviour — the registry fails closed rather than trusting a tool nobody has described. Classify the tool to unblock it."}
            </AlertDescription>
          </Alert>
        )}

        {loading && rows.length === 0 ? (
          <Card>
            <CardContent className="space-y-3">
              {/* Four placeholder rows, sized like the real ones, so the table
               * does not jump when data lands. */}
              {[0, 1, 2, 3].map((i) => (
                <div key={i} className="flex items-center gap-4">
                  <Skeleton className="h-5 w-48" />
                  <Skeleton className="h-5 w-28" />
                  <Skeleton className="h-5 w-32" />
                  <Skeleton className="h-5 w-32" />
                  <Skeleton className="h-5 w-28" />
                </div>
              ))}
            </CardContent>
          </Card>
        ) : unsupported ? (
          <Alert variant="info">
            <Info aria-hidden="true" />
            <AlertTitle>Tool classification isn&apos;t available on this deployment yet</AlertTitle>
            <AlertDescription>
              This workspace&apos;s core doesn&apos;t expose the tool classification registry, so there is nothing to
              show — no tool has been classified here, and none is being reported as blocked. The reference below
              describes the labels this table will use once the registry is live.
            </AlertDescription>
          </Alert>
        ) : loadFailedNoRows ? null : rows.length === 0 ? (
          <EmptyState
            icon={ShieldQuestion}
            title="No tools reported"
            description="The classification registry answered, but it has no tools for this workspace yet. Connect an MCP server or add a custom code tool and its classification will appear here."
          />
        ) : (
          <>
            {/* Filter chips double as the at-a-glance summary: each one is a
             * question an admin arrives with, and its count is the answer. No
             * separate statistics row is invented for the same numbers. */}
            <div className="flex flex-wrap items-center gap-2">
              <Button
                variant={activeFilter === null ? "default" : "outline"}
                size="sm"
                onClick={() => setActiveFilter(null)}
              >
                All <span className="tabular-nums">{rows.length}</span>
              </Button>
              {CLASSIFICATION_FILTERS.map((filter) => {
                const count = filterCounts[filter.id] ?? 0;
                const selected = activeFilter === filter.id;
                return (
                  <Button
                    key={filter.id}
                    variant={selected ? "default" : "outline"}
                    size="sm"
                    // A zero-count chip is disabled rather than hidden: "none of
                    // my tools send data out" is itself useful information, and
                    // a chip row that changes shape between refreshes is harder
                    // to read than one that greys out.
                    disabled={count === 0 && !selected}
                    title={filter.hint}
                    onClick={() => setActiveFilter(selected ? null : filter.id)}
                    className={cn(
                      filter.id === "blocked" && count > 0 && !selected && "border-destructive/40 text-destructive"
                    )}
                  >
                    {filter.label} <span className="tabular-nums">{count}</span>
                  </Button>
                );
              })}
            </div>

            {/* Full-bleed table: the Card's own padding is removed and its
             * rounded corners clip the header/last row, so the table reads as
             * the card rather than as a box inside one. */}
            <Card className="gap-0 overflow-hidden p-0">
              <CardContent className="p-0">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Tool</TableHead>
                      <TableHead>Status</TableHead>
                      <TableHead>
                        Trust
                        <InfoTip text={AXIS_QUESTIONS.trust} />
                      </TableHead>
                      <TableHead>
                        Can touch
                        <InfoTip text={AXIS_QUESTIONS.sensitivity} />
                      </TableHead>
                      <TableHead>
                        Retry safety
                        <InfoTip text={AXIS_QUESTIONS.determinism} />
                      </TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {visibleRows.length === 0 ? (
                      <TableRow>
                        <TableCell colSpan={5} className="py-10 text-center text-sm text-muted-foreground">
                          No tool matches this filter{query.trim() ? " and search" : ""}.
                        </TableCell>
                      </TableRow>
                    ) : (
                      rowsPagination.pageItems.map((row) => {
                        const blocked = isUnclassified(row);
                        return (
                          <TableRow
                            key={`${row.serverId ?? row.origin ?? "tool"}:${row.toolName}`}
                            // Tinting the whole row means a blocked tool is
                            // findable by scanning the table edge, without
                            // having to read any pill.
                            className={cn(blocked && "bg-destructive/5 hover:bg-destructive/10")}
                          >
                            <TableCell className="max-w-[22rem]">
                              <ToolCell row={row} />
                            </TableCell>
                            <TableCell>
                              <StatusCell row={row} />
                            </TableCell>
                            <TableCell>
                              <TierCell meta={trustMeta(row.trust)} rawValue={row.trust} />
                            </TableCell>
                            <TableCell>
                              <TierCell meta={sensitivityMeta(row.sensitivity)} rawValue={row.sensitivity} />
                            </TableCell>
                            <TableCell>
                              <div className="flex flex-wrap items-center gap-1.5">
                                <TierCell meta={determinismMeta(row.determinism)} rawValue={row.determinism} />
                                {/* Irreversibility is declared separately from
                                 * the determinism axis (a DURABLE tool can
                                 * still be irreversible), and it belongs next
                                 * to retry safety because that is the decision
                                 * it changes. */}
                                {row.irreversible === true && (
                                  <TierPill
                                    meta={{
                                      code: "IRREVERSIBLE",
                                      label: "No undo",
                                      meaning:
                                        "Declared irreversible — once this call lands there is no way to take it back.",
                                      severity: "alarm",
                                      icon: Undo2,
                                    }}
                                  />
                                )}
                              </div>
                            </TableCell>
                          </TableRow>
                        );
                      })
                    )}
                  </TableBody>
                </Table>
                <PaginationControls
                  page={rowsPagination.page}
                  totalPages={rowsPagination.totalPages}
                  totalItems={rowsPagination.totalItems}
                  pageSize={rowsPagination.pageSize}
                  onPageChange={rowsPagination.setPage}
                  onPageSizeChange={rowsPagination.setPageSize}
                  itemLabel="tools"
                />
              </CardContent>
            </Card>
          </>
        )}

        <ToolClassificationLegend />
      </Section>
    </div>
  );
}
