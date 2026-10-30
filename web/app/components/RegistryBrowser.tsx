"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  Search,
  ExternalLink,
  Server,
  Package,
  Box,
  Globe,
  Loader2,
  AlertTriangle,
  SlidersHorizontal,
  CheckCircle2,
  ShieldCheck,
  Clock,
} from "lucide-react";
import { api } from "../lib/api";
import { Button } from "./ui/button";
import { Input } from "./ui/input";
import { Label } from "./ui/label";
import { Badge } from "./ui/badge";
import { Card, CardContent } from "./ui/card";
import { Alert, AlertTitle, AlertDescription } from "./ui/alert";
import { Skeleton } from "./ui/skeleton";
import { cn } from "../lib/utils";
import { useTranslations } from "../lib/i18n";
import PaginationControls from "./PaginationControls";
import { usePagination } from "../lib/usePagination";

// A small runtime -> icon/label map so a result card can show at a glance what
// will actually run (an npx package, a uvx package, a docker image, or a remote
// endpoint). Mirrors the backend RegistryServerMapper's `runtime` strings.
const RUNTIME_META: Record<string, { icon: any; label: string }> = {
  npx: { icon: Package, label: "npx" },
  uvx: { icon: Package, label: "uvx" },
  docker: { icon: Box, label: "docker" },
  remote: { icon: Globe, label: "remote" },
  unsupported: { icon: Server, label: "unsupported" },
};

// Client-side runtime filter chips shown above the results list. Deliberately
// only the runtimes this deployment can actually launch (see RUNTIME_META) -
// "unsupported" isn't offered as a filter target, it's the thing the filter
// helps people avoid.
const RUNTIME_FILTERS: { value: string; label: string }[] = [
  { value: "all", label: "All" },
  { value: "npx", label: "npx" },
  { value: "uvx", label: "uvx" },
  { value: "docker", label: "docker" },
  { value: "remote", label: "remote" },
];

// Tone classes for the "will this connect right away?" badge - same
// color-mix/CSS-var convention McpPanel's ProvenanceReviewBadge already
// established for the (differently-scoped, per-registration) reviewStatus
// badge, so a "will need review" signal reads as the same color everywhere
// in the Tools screen. Deliberately NOT reusing that badge's "Verified"
// wording here - see RegistrySearchResult#reviewRequired's javadoc for why
// this is a "connects now vs. sits pending" signal, not a safety/trust claim.
const TONE_SUCCESS =
  "border-[color-mix(in_srgb,var(--state-success)_32%,transparent)] " +
  "bg-[color-mix(in_srgb,var(--state-success)_12%,transparent)] " +
  "text-[var(--state-success)]";
const TONE_INFO = "border-[var(--info-border)] bg-[var(--info-bg)] text-[var(--info-text)]";

// Replaces {{KEY}} placeholders in a resolved arg list with the value the
// person typed for that positional spec - identical convention to the curated
// catalog's dynamicArgs (see McpPanel's CatalogConnectForm).
function substituteArgs(args: string[], values: Record<string, string>) {
  return (args || []).map((a) => {
    const m = /^\{\{(.+)\}\}$/.exec(a);
    return m ? values[m[1]] || "" : a;
  });
}

/**
 * "Browse registry" - live search over the official public MCP Registry
 * (registry.modelcontextprotocol.io) via GET /api/mcp/registry/search, so a
 * tenant can find any published MCP server (not just the curated catalog) and
 * add it to their workspace. Each backend result already carries a resolved,
 * launch-ready command/args/env (or remote URL) mapped from the registry's raw
 * package schema, so "Add" reuses the same registerMcpServer path as the rest
 * of the Tools screen.
 */
export default function RegistryBrowser({ onChanged }: { onChanged: () => void }) {
    const tHC = useTranslations("hcComponentsRegistryBrowser");
  const t = useTranslations("mcpPanel");
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<any[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // "The registry didn't answer" is NOT "the registry has nothing" - the
  // backend flags the difference (see RegistrySearchResponse#unavailable),
  // because rendering an upstream timeout as "No servers match 'youtube'"
  // told people the registry was empty when it simply hadn't replied.
  const [unavailable, setUnavailable] = useState(false);
  const [selectedName, setSelectedName] = useState<string | null>(null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [addingName, setAddingName] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  // Deprecated servers are shown by default (a filter that can hide 100% of
  // results by default is exactly the bug this replaces - see git history,
  // 2026-08-03: an earlier "Verified" toggle defaulted to a field the real
  // registry API doesn't expose, so it silently hid every result for every
  // search). Hiding deprecated servers is opt-in, not opt-out.
  const [hideDeprecated, setHideDeprecated] = useState(false);
  // Same opt-in, off-by-default posture as hideDeprecated above, for the
  // other filterable signal a result carries: whether adding it today would
  // sit PENDING platform-staff review before it can connect (see
  // RegistrySearchResult#reviewRequired's javadoc). Defaulting this to true
  // would repeat the exact "Verified" toggle bug this file's git history
  // already fixed once (2026-08-03) - hiding most/all results by default.
  const [hidePendingReview, setHidePendingReview] = useState(false);
  // Client-side runtime filter (chip group above the list) - "all" by
  // default so nothing is hidden until the person opts in, same posture as
  // hideDeprecated above. Purely a filter over the already-fetched `results`
  // array; never sent to the backend.
  const [runtimeFilter, setRuntimeFilter] = useState<string>("all");
  // Guards against a slow earlier search overwriting a newer one's results.
  const reqSeq = useRef(0);
  // Row DOM nodes keyed by result name - Add's immediate feedback (MCP-UX
  // follow-up, 2026-07-26) scrolls the specific row into view synchronously
  // when clicked, same "select + scroll the thing you just acted on"
  // affordance McpPanel's catalog cards now have. `addingName` (already
  // existed) already drives the button's disabled/"Adding..." label, so
  // that alone doubles as this row's transitional-state signal - no need
  // for a second phase flag.
  const rowRefs = useRef<Map<string, HTMLElement>>(new Map());

  const runSearch = useCallback(
    async (q: string, cursor: string | null) => {
      const seq = ++reqSeq.current;
      setLoading(true);
      setError(null);
      try {
        const data = await api.searchMcpRegistry(q, cursor);
        if (seq !== reqSeq.current) return; // a newer search superseded this one
        setResults((prev) => (cursor ? [...prev, ...(data.results || [])] : data.results || []));
        setNextCursor(data.nextCursor || null);
        setUnavailable(Boolean(data.unavailable));
      } catch (err: any) {
        if (seq !== reqSeq.current) return;
        setError(err?.message || t("browseRegistryError"));
        setUnavailable(true);
        if (!cursor) setResults([]);
      } finally {
        if (seq === reqSeq.current) setLoading(false);
      }
    },
    [t]
  );

  // Debounced search as the query changes. We deliberately do NOT auto-fire on
  // an empty query: an empty search against the public registry is slow and
  // usually returns nothing, so the tab would open on a long spinner then a
  // "no results" message. Instead the empty-query case renders a friendly idle
  // state (see below). 350ms is long enough that typing doesn't fire a call per
  // keystroke, short enough to feel live.
  useEffect(() => {
    const trimmed = query.trim();
    if (!trimmed) {
      // Clear any stale results/spinner and short-circuit the debounce.
      reqSeq.current++; // invalidate any in-flight search
      setLoading(false);
      setResults([]);
      setNextCursor(null);
      setError(null);
      setUnavailable(false);
      return undefined;
    }
    const handle = setTimeout(() => {
      void runSearch(trimmed, null);
    }, 350);
    return () => clearTimeout(handle);
  }, [query, runSearch]);

  // Fast-start suggestions shown in the idle state so a first-time visitor has
  // a one-click way into a real, populated search instead of a blank prompt.
  const SUGGESTIONS = ["github", "filesystem", "postgres", "slack", "puppeteer", "brave-search"];

  // Filter results based on the (opt-in, off-by-default) hide-deprecated and
  // hide-pending-review toggles, and the (opt-in, "all" by default) runtime
  // chip filter.
  const filteredResults = results
    .filter((r) => !hideDeprecated || r.status !== "deprecated")
    .filter((r) => !hidePendingReview || !r.reviewRequired)
    .filter((r) => runtimeFilter === "all" || r.runtime === runtimeFilter);
  const filtersActive = hideDeprecated || hidePendingReview || runtimeFilter !== "all";

  // Client-side page slicing over the accumulated results - the registry
  // search itself is cursor-paged upstream ("Load more" below appends the
  // next batch), and this keeps the rendered list at a readable page size
  // as that collection grows.
  const resultsPagination = usePagination(filteredResults);

  // Human-readable reason list for the "all results hidden by filters" empty
  // state - built dynamically so any subset of the three filters composes
  // into one honest sentence instead of hardcoding every combination.
  function activeFilterReasons(): string[] {
    const reasons: string[] = [];
    if (hideDeprecated) reasons.push("marked deprecated");
    if (hidePendingReview) reasons.push("pending review");
    if (runtimeFilter !== "all") reasons.push("a different runtime");
    return reasons;
  }

  function selectResult(r: any) {
    setSuccess(null);
    setError(null);
    setSelectedName((cur) => (cur === r.name ? null : r.name));
    setValues({});
  }

  async function add(r: any) {
    setError(null);
    setSuccess(null);
    setAddingName(r.name);
    // Immediate feedback: scroll the row into view synchronously, before the
    // await - on a long result list (or after scrolling while filling in
    // credentials) there was previously no guarantee the row you just acted
    // on was still the one visible when the request resolved.
    requestAnimationFrame(() => {
      rowRefs.current.get(r.name)?.scrollIntoView({ behavior: "smooth", block: "nearest" });
    });
    try {
      const payload: any = {
        name: r.name,
        type: r.type,
        description: r.description,
        enabled: true,
      };
      const env: Record<string, string> = {};
      (r.envVars || []).forEach((v: any) => {
        if (!v.positional && values[v.name]) env[v.name] = values[v.name];
      });
      if (r.command) {
        payload.command = r.command;
        payload.args = substituteArgs(r.args, values);
        payload.env = env;
      } else if (r.url) {
        payload.url = r.url;
        payload.env = env;
      }
      const res = await api.registerMcpServer(payload);
      // MCP-STDIO-SANDBOXING (2026-08-05) - every registry-browsed STDIO
      // server is self-service/unvetted (see McpReviewStatus's javadoc, the
      // "Both sources are equally unvetted" note), so it starts PENDING and
      // won't actually connect until platform staff review it. Surfacing that
      // immediately here, plus register()'s own transient warnings (e.g. a
      // missing recommended credential), rather than only discoverable later
      // via a confusing "still not connected" state.
      let message = t("browseRegistryAddedSuccess", { name: r.displayName || r.name });
      if (res?.reviewStatus === "PENDING") message += ` ${t("browseRegistryReviewPendingSuffix")}`;
      if (Array.isArray(res?.warnings) && res.warnings.length > 0) {
        message += ` ${t("browseRegistryWarningsPrefix", { warnings: res.warnings.join(" ") })}`;
      }
      setSuccess(message);
      setSelectedName(null);
      onChanged();
    } catch (err: any) {
      setError(err?.message || t("browseRegistryAddError"));
    } finally {
      setAddingName(null);
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="text-sm font-semibold text-foreground">{t("browseRegistryTitle")}</div>

      <div className="flex flex-wrap items-center gap-2">
        <div className="relative min-w-[14rem] flex-1">
          <Search
            aria-hidden="true"
            size={15}
            className="pointer-events-none absolute start-3 top-1/2 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t("browseRegistrySearchPlaceholder")}
            className="ps-8"
            aria-label={t("browseRegistryTitle")}
          />
        </div>
        <Button
          type="button"
          variant={hideDeprecated ? "default" : "outline"}
          size="sm"
          aria-pressed={hideDeprecated}
          onClick={() => setHideDeprecated(!hideDeprecated)}
          title={
            hideDeprecated
              ? "Deprecated servers are hidden - click to show them"
              : "Hide servers the registry marks deprecated"
          }
          className="shrink-0"
        >
          <AlertTriangle aria-hidden="true" size={14} />
          {tHC("s17")}
        </Button>
        <Button
          type="button"
          variant={hidePendingReview ? "default" : "outline"}
          size="sm"
          aria-pressed={hidePendingReview}
          onClick={() => setHidePendingReview(!hidePendingReview)}
          title={
            hidePendingReview
              ? "Servers that would need a staff review are hidden - click to show them"
              : "Hide servers that would sit pending platform-staff review before they can connect"
          }
          className="shrink-0"
        >
          <Clock aria-hidden="true" size={14} />
          {tHC("s16")}
        </Button>
      </div>

      {/* Runtime filter chips - client-side only, over the already-fetched
       * results array. Many public registry results use runtimes this
       * deployment can't launch (runnable: false); this lets someone jump
       * straight to the runtimes that will actually work instead of
       * scanning every card's notRunnableReason one at a time. */}
      <div className="flex flex-wrap items-center gap-1.5">
        <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
          <SlidersHorizontal aria-hidden="true" size={12} /> Runtime:
        </span>
        {RUNTIME_FILTERS.map((f) => (
          <Button
            key={f.value}
            type="button"
            variant={runtimeFilter === f.value ? "default" : "outline"}
            size="sm"
            aria-pressed={runtimeFilter === f.value}
            className="h-8 px-2.5 text-xs"
            onClick={() => setRuntimeFilter(f.value)}
          >
            {f.label}
          </Button>
        ))}
      </div>

      {error && (
        <Alert variant="destructive">
          <AlertTriangle aria-hidden="true" />
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {success && (
        <Alert variant="success">
          <CheckCircle2 aria-hidden="true" />
          <AlertDescription>{success}</AlertDescription>
        </Alert>
      )}

      {loading && results.length === 0 ? (
        <div className="flex flex-col gap-3">
          <div className="flex items-center justify-center gap-2 text-sm text-muted-foreground">
            <Loader2 aria-hidden="true" size={15} className="animate-spin" /> {t("browseRegistrySearching")}
          </div>
          {[0, 1, 2].map((i) => (
            <Card key={i}>
              <CardContent className="flex flex-col gap-2 pt-6">
                <Skeleton className="h-4 w-1/3" />
                <Skeleton className="h-3 w-2/3" />
                <Skeleton className="h-3 w-1/2" />
              </CardContent>
            </Card>
          ))}
        </div>
      ) : !query.trim() ? (
        // IDLE: nothing typed yet. Invite a search rather than firing an empty
        // one, and offer a few one-click suggestions as a fast starting point.
        <Card>
          <CardContent className="flex flex-col items-center gap-3 py-10 text-center">
            <Search aria-hidden="true" size={22} className="text-muted-foreground" />
            <p className="max-w-md text-sm text-muted-foreground">
              {tHC("s15")} <code>*</code> / <code>?</code> {tHC("s14")}
            </p>
            <div className="flex flex-wrap items-center justify-center gap-1.5">
              <span className="text-xs text-muted-foreground">Try:</span>
              {SUGGESTIONS.map((s) => (
                <Button key={s} type="button" variant="ghost" size="sm" onClick={() => setQuery(s)}>
                  {s}
                </Button>
              ))}
            </div>
          </CardContent>
        </Card>
      ) : results.length === 0 ? (
        // NO RESULTS: either the registry couldn't be reached, or it really
        // has nothing matching. Say which - these look identical from here
        // otherwise, and claiming "no match" for an unreachable registry sends
        // people off inventing new search terms for a problem that isn't
        // theirs.
        <Alert variant={unavailable ? "warning" : "default"}>
          <AlertTriangle aria-hidden="true" />
          <AlertTitle>{unavailable ? "The registry didn't respond" : `No servers match “${query.trim()}”`}</AlertTitle>
          <AlertDescription className="flex flex-col items-start gap-2">
            <span>
              {unavailable ? "This search couldn't run. It's usually back within a few minutes." : "Try another term."}
            </span>
            <Button type="button" variant="ghost" size="sm" onClick={() => runSearch(query.trim(), null)}>
              {tHC("s13")}
            </Button>
          </AlertDescription>
        </Alert>
      ) : filteredResults.length === 0 ? (
        // ALL RESULTS FILTERED OUT: results exist but the active filter(s) -
        // hide-deprecated and/or the runtime chip - hide every one of them.
        <Alert variant="warning">
          <AlertTriangle aria-hidden="true" />
          <AlertTitle>{tHC("s12")}</AlertTitle>
          <AlertDescription className="flex flex-col items-start gap-2">
            <span>
              {results.length} server{results.length === 1 ? "" : "s"} {tHC("s11")}{query.trim()}&rdquo;, but
              {(() => {
                const reasons = activeFilterReasons();
                if (reasons.length === 0) return " none of them are shown.";
                if (reasons.length === 1) return ` they're all ${reasons[0]}.`;
                return ` they're all either ${reasons.slice(0, -1).join(", ")} or ${reasons[reasons.length - 1]}.`;
              })()}
            </span>
            <div className="flex flex-wrap gap-2">
              {hideDeprecated && (
                <Button type="button" variant="ghost" size="sm" onClick={() => setHideDeprecated(false)}>
                  {tHC("s10")}
                </Button>
              )}
              {hidePendingReview && (
                <Button type="button" variant="ghost" size="sm" onClick={() => setHidePendingReview(false)}>
                  {tHC("s9")}
                </Button>
              )}
              {runtimeFilter !== "all" && (
                <Button type="button" variant="ghost" size="sm" onClick={() => setRuntimeFilter("all")}>
                  {tHC("s8")}
                </Button>
              )}
            </div>
          </AlertDescription>
        </Alert>
      ) : (
        <div className="flex flex-col gap-3">
          {filtersActive && filteredResults.length !== results.length && (
            <div className="px-0.5 text-xs text-muted-foreground">
              {filteredResults.length} of {results.length} {tHC("s7")}
            </div>
          )}
          {resultsPagination.pageItems.map((r) => {
            const meta = RUNTIME_META[r.runtime] || RUNTIME_META.unsupported;
            const RuntimeIcon = meta.icon;
            const isOpen = selectedName === r.name;
            return (
              <Card
                key={r.name}
                ref={(el) => {
                  if (el) rowRefs.current.set(r.name, el);
                  else rowRefs.current.delete(r.name);
                }}
                className={cn(isOpen && "border-primary/50 ring-1 ring-primary/20")}
              >
                <CardContent className="flex flex-col gap-3 pt-6">
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="flex min-w-0 flex-1 items-start gap-3">
                      <span
                        className={cn(
                          "flex size-8 shrink-0 items-center justify-center rounded-md bg-muted",
                          r.runnable ? "text-primary" : "text-muted-foreground"
                        )}
                      >
                        <RuntimeIcon size={16} />
                      </span>
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="truncate font-semibold text-foreground" title={r.displayName || r.name}>
                            {r.displayName || r.name}
                          </span>
                          <Badge variant="outline">{meta.label}</Badge>
                          {r.status === "deprecated" && (
                            <Badge
                              variant="destructive"
                              title={tHC("s6")}
                              className="gap-1"
                            >
                              <AlertTriangle aria-hidden="true" size={12} />
                              deprecated
                            </Badge>
                          )}
                          {/* "Will this connect right away, or sit blocked pending
                           * staff review?" - the real, already-enforced distinction
                           * (McpReviewStatus) behind self-service STDIO registrations,
                           * not a "verified"/"safe" claim - see
                           * RegistrySearchResult#reviewRequired's javadoc. */}
                          {r.runnable &&
                            (r.reviewRequired ? (
                              <Badge
                                variant="outline"
                                className={cn("gap-1", TONE_INFO)}
                                title={tHC("s5")}
                              >
                                <Clock aria-hidden="true" size={12} />
                                {tHC("s4")}
                              </Badge>
                            ) : (
                              <Badge
                                variant="outline"
                                className={cn("gap-1", TONE_SUCCESS)}
                                title={tHC("s3")}
                              >
                                <ShieldCheck aria-hidden="true" size={12} />
                                {tHC("s2")}
                              </Badge>
                            ))}
                        </div>
                        <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                          <span className="truncate font-mono" title={r.name}>
                            {r.name}
                          </span>
                          {r.version ? <span>v{r.version}</span> : null}
                        </div>
                        {r.description && <p className="mt-1.5 text-sm text-muted-foreground">{r.description}</p>}
                      </div>
                    </div>
                    <div className="shrink-0">
                      {r.runnable ? (
                        <Button
                          type="button"
                          size="sm"
                          disabled={addingName === r.name}
                          onClick={() => selectResult(r)}
                        >
                          {addingName === r.name
                            ? t("browseRegistryAddingButton")
                            : isOpen
                              ? t("closeAriaLabel")
                              : t("browseRegistryAddButton")}
                        </Button>
                      ) : (
                        <Badge variant="secondary" title={r.notRunnableReason || ""} className="whitespace-nowrap">
                          {t("browseRegistryNotRunnable")}
                        </Badge>
                      )}
                    </div>
                  </div>

                  {isOpen && (
                    <div className="flex flex-col gap-3 border-t border-border pt-3">
                      <div className="flex flex-col gap-1">
                        <span className="text-xs text-muted-foreground">{t("browseRegistryRuntimeLabel")}</span>
                        <code className="block w-full break-words rounded bg-muted px-2 py-1 font-mono text-xs">
                          {r.command ? `${r.command} ${(r.args || []).join(" ")}` : r.url}
                        </code>
                      </div>
                      {(r.envVars || []).length > 0 && (
                        <div className="flex flex-col gap-2">
                          <span className="text-xs font-medium text-foreground">
                            {t("browseRegistryCredentialsTitle")}
                          </span>
                          <p className="text-xs text-muted-foreground">
                            {tHC("s1")}
                          </p>
                          <div className="flex flex-col gap-2">
                            {r.envVars.map((v: any) => {
                              const envFieldId = `registry-${r.name}-${v.name}`;
                              return (
                                <div key={v.name} className="flex flex-col gap-1">
                                  <Label htmlFor={envFieldId} className="text-xs font-normal text-muted-foreground">
                                    {v.name}
                                    {v.required ? <span className="text-destructive"> *</span> : null}
                                  </Label>
                                  {v.description ? (
                                    <span className="text-xs text-muted-foreground">{v.description}</span>
                                  ) : null}
                                  <Input
                                    id={envFieldId}
                                    type={v.secret ? "password" : "text"}
                                    value={values[v.name] || ""}
                                    placeholder={v.defaultValue || v.name}
                                    onChange={(e) => setValues((prev) => ({ ...prev, [v.name]: e.target.value }))}
                                  />
                                </div>
                              );
                            })}
                          </div>
                        </div>
                      )}
                      <div className="flex flex-wrap items-center gap-2">
                        <Button type="button" size="sm" disabled={addingName === r.name} onClick={() => add(r)}>
                          {addingName === r.name ? t("browseRegistryAddingButton") : t("browseRegistryAddButton")}
                        </Button>
                        {r.repositoryUrl ? (
                          <Button type="button" variant="outline" size="sm" asChild>
                            <a href={r.repositoryUrl} target="_blank" rel="noreferrer">
                              {t("browseRegistryViewSource")} <ExternalLink aria-hidden="true" size={12} />
                            </a>
                          </Button>
                        ) : null}
                      </div>
                    </div>
                  )}
                </CardContent>
              </Card>
            );
          })}

          <PaginationControls
            page={resultsPagination.page}
            totalPages={resultsPagination.totalPages}
            totalItems={resultsPagination.totalItems}
            pageSize={resultsPagination.pageSize}
            onPageChange={resultsPagination.setPage}
            onPageSizeChange={resultsPagination.setPageSize}
            itemLabel={t("serversItemLabel")}
          />

          {nextCursor && filteredResults.length > 0 && (
            <Button
              type="button"
              variant="ghost"
              size="sm"
              disabled={loading}
              onClick={() => runSearch(query.trim(), nextCursor)}
              className="mt-1 self-center"
            >
              {loading ? t("browseRegistrySearching") : t("browseRegistryLoadMore")}
            </Button>
          )}
        </div>
      )}
    </div>
  );
}
