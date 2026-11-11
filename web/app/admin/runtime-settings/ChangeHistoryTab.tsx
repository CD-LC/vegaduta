"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Search } from "lucide-react";
import { useLocale, useTranslations } from "../../lib/i18n";
import type { SettingCatalogEntry, SettingHistoryRow, SettingsHistoryClient } from "../../lib/api/settings";
import { Alert, AlertDescription } from "../../components/ui/alert";
import { Button } from "../../components/ui/button";
import { Input } from "../../components/ui/input";
import { Select } from "../../components/ui/select";
import { Skeleton } from "../../components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../../components/ui/table";
import { useSettingsText } from "../../components/settings/useSettingsText";

// Every change to a setting of this workspace, newest first: the workspace's own changes and the agents' own, in one
// list with a column that says which. The API serves the whole history in pages (one request per page, newest
// first), so "Show more" asks the server for the next page instead of cutting a list that was loaded up front.

const PAGE = 100;

type ScopeFilter = "all" | "tenant" | "agent";

interface Loaded {
  rows: SettingHistoryRow[];
  total: number;
  hasMore: boolean;
}

type State = { status: "loading" } | { status: "error"; message: string } | ({ status: "ready" } & Loaded);

const rowId = (r: SettingHistoryRow) => `${r.scope}:${r.agentId ?? ""}:${r.key}:${r.version}`;

function formatWhen(iso: string, locale: string): string {
  const d = new Date(iso);
  if (!iso || Number.isNaN(d.getTime())) return iso;
  try {
    return new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" }).format(d);
  } catch {
    return d.toISOString();
  }
}

/** A key that left the catalog since the change was made still has a row; it is shown with its raw values. */
function unknownEntry(key: string): SettingCatalogEntry {
  return {
    key,
    group: key.split(".")[0],
    labelKey: `settings.${key}.label`,
    descriptionKey: `settings.${key}.description`,
    type: "string",
    risk: "low",
    scopes: ["tenant", "agent"],
    default: "",
    minRole: "TENANT_ADMIN",
    restartRequired: false,
  };
}

function errorText(err: unknown): string {
  return err instanceof Error ? err.message : String(err ?? "");
}

export default function ChangeHistoryTab({
  client,
  entries,
  canView,
}: {
  client: SettingsHistoryClient;
  /** The whole catalog: a change at agent level can be to a key that only agents have. */
  entries: SettingCatalogEntry[];
  /** History of workspace settings is for workspace admins; others get an explanation instead of a failing call. */
  canView: boolean;
}) {
  const t = useTranslations("runtimeSettings");
  const { label, formatValue } = useSettingsText();
  const locale = useLocale();
  const [state, setState] = useState<State>({ status: "loading" });
  const [nonce, setNonce] = useState(0);
  const [scope, setScope] = useState<ScopeFilter>("all");
  const [query, setQuery] = useState("");
  const [more, setMore] = useState<{ busy: boolean; error: string | null }>({ busy: false, error: null });
  // Each load gets a ticket; only the newest may write state (changing the filter while a page is on its way).
  const ticket = useRef(0);

  const byKey = useMemo(() => new Map(entries.map((e) => [e.key, e])), [entries]);
  const entryFor = useCallback((key: string) => byKey.get(key) ?? unknownEntry(key), [byKey]);

  useEffect(() => {
    if (!canView) return undefined;
    const mine = ++ticket.current;
    setState({ status: "loading" });
    setMore({ busy: false, error: null });
    client
      .getHistoryPage({ scope, limit: PAGE })
      .then((p) => {
        if (mine === ticket.current) setState({ status: "ready", rows: p.entries, total: p.total, hasMore: p.hasMore });
      })
      .catch((err: unknown) => {
        if (mine === ticket.current) setState({ status: "error", message: errorText(err) });
      });
    return () => {
      ticket.current++;
    };
  }, [client, canView, scope, nonce]);

  const retry = useCallback(() => setNonce((n) => n + 1), []);

  const loadMore = useCallback(async () => {
    if (state.status !== "ready") return;
    const mine = ticket.current;
    setMore({ busy: true, error: null });
    try {
      const p = await client.getHistoryPage({ scope, limit: PAGE, offset: state.rows.length });
      if (mine !== ticket.current) return;
      setState((cur) => {
        if (cur.status !== "ready") return cur;
        // New changes made meanwhile shift the pages: a row already shown is not shown twice.
        const seen = new Set(cur.rows.map(rowId));
        const fresh = p.entries.filter((r) => !seen.has(rowId(r)));
        return { status: "ready", rows: [...cur.rows, ...fresh], total: p.total, hasMore: p.hasMore };
      });
      setMore({ busy: false, error: null });
    } catch (err: unknown) {
      if (mine === ticket.current) setMore({ busy: false, error: errorText(err) });
    }
  }, [client, scope, state]);

  const filtered = useMemo(() => {
    if (state.status !== "ready") return [];
    const q = query.trim().toLowerCase();
    if (!q) return state.rows;
    return state.rows.filter((r) =>
      [r.key, label(entryFor(r.key)), r.actor, r.reason ?? "", r.agentId ?? ""].some((s) => s.toLowerCase().includes(q))
    );
  }, [state, query, label, entryFor]);

  if (!canView) {
    return (
      <Alert variant="info" data-testid="history-forbidden">
        <AlertDescription>{t("history.adminOnly")}</AlertDescription>
      </Alert>
    );
  }

  if (state.status === "loading") {
    return (
      <div
        role="status"
        aria-busy="true"
        aria-label={t("history.loading")}
        className="space-y-3"
        data-testid="history-loading"
      >
        <Skeleton className="h-10" />
        <Skeleton className="h-10" />
        <Skeleton className="h-10" />
      </div>
    );
  }

  if (state.status === "error") {
    return (
      <Alert variant="destructive" data-testid="history-error">
        <AlertDescription>
          <span>{t("history.loadError", { message: state.message })}</span>
          <Button type="button" size="sm" variant="outline" onClick={retry}>
            {t("history.retry")}
          </Button>
        </AlertDescription>
      </Alert>
    );
  }

  const searching = query.trim() !== "";

  return (
    <div className="space-y-4" data-testid="history-tab">
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
        <div className="relative sm:max-w-xs sm:flex-1">
          <Search
            aria-hidden="true"
            className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            type="search"
            className="ps-9"
            aria-label={t("history.search")}
            placeholder={t("history.searchPlaceholder")}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>
        <div className="sm:w-48">
          <Select
            aria-label={t("history.scopeFilter")}
            value={scope}
            onValueChange={(v) => setScope(v as ScopeFilter)}
            options={[
              { value: "all", label: t("history.filterAll") },
              { value: "tenant", label: t("history.filterWorkspace") },
              { value: "agent", label: t("history.filterAgents") },
            ]}
          />
        </div>
      </div>

      {state.rows.length === 0 ? (
        <p className="text-sm text-muted-foreground" data-testid="history-empty">
          {t("history.empty")}
        </p>
      ) : filtered.length === 0 ? (
        <p className="text-sm text-muted-foreground" data-testid="history-no-match">
          {t("history.noMatch")}
        </p>
      ) : (
        <>
          <p role="status" aria-live="polite" className="text-xs text-muted-foreground">
            {searching
              ? t("history.count", { shown: filtered.length, total: filtered.length })
              : t("history.count", { shown: filtered.length, total: state.total })}
            {searching && state.hasMore ? ` ${t("history.searchLoadedOnly")}` : ""}
          </p>
          <div className="rounded-md border border-border">
            <Table aria-label={t("history.tableLabel")}>
              <TableHeader>
                <TableRow>
                  <TableHead scope="col">{t("history.when")}</TableHead>
                  <TableHead scope="col">{t("history.scope")}</TableHead>
                  <TableHead scope="col">{t("history.setting")}</TableHead>
                  <TableHead scope="col">{t("history.change")}</TableHead>
                  <TableHead scope="col">{t("history.by")}</TableHead>
                  <TableHead scope="col">{t("history.reason")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filtered.map((r) => {
                  const entry = entryFor(r.key);
                  return (
                    <TableRow key={rowId(r)} data-testid="history-row">
                      <TableCell className="whitespace-nowrap text-xs">
                        <time dateTime={r.at}>{formatWhen(r.at, locale)}</time>
                      </TableCell>
                      <TableCell className="whitespace-nowrap text-sm" data-testid="history-scope">
                        {r.scope === "agent" ? (
                          <span title={r.agentId}>
                            {t("history.scopeAgent", { id: (r.agentId ?? "").slice(0, 8) })}
                          </span>
                        ) : (
                          t("history.scopeWorkspace")
                        )}
                      </TableCell>
                      <TableCell>
                        <span className="block text-sm font-medium">{label(entry)}</span>
                        <span className="block font-mono text-xs text-muted-foreground">{r.key}</span>
                      </TableCell>
                      <TableCell className="text-sm">
                        <span className="sr-only">{t("history.before")} </span>
                        <del className="text-muted-foreground">
                          {r.previousValue == null ? t("history.inherited") : formatValue(entry, r.previousValue)}
                        </del>
                        <span aria-hidden="true"> → </span>
                        <span className="sr-only">{t("history.after")} </span>
                        <ins className="font-medium no-underline">
                          {r.value == null ? t("history.inherited") : formatValue(entry, r.value)}
                        </ins>
                      </TableCell>
                      <TableCell className="text-sm">{r.actor || t("history.unknownActor")}</TableCell>
                      <TableCell className="max-w-xs text-sm">
                        {r.reason || <span className="text-muted-foreground">{t("history.noReason")}</span>}
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </div>
          {more.error ? (
            <Alert variant="destructive" data-testid="history-more-error">
              <AlertDescription>{t("history.moreFailed", { message: more.error })}</AlertDescription>
            </Alert>
          ) : null}
          {state.hasMore ? (
            <Button type="button" variant="outline" size="sm" disabled={more.busy} onClick={() => void loadMore()}>
              {more.busy ? t("history.loadingMore") : t("history.showMore")}
            </Button>
          ) : null}
        </>
      )}
    </div>
  );
}
