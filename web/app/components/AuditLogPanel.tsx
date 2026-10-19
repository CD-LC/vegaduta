"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { Search, Download, CheckCircle2, Clock, XCircle, Minus } from "lucide-react";
import { api } from "../lib/api";
import PaginationControls from "./PaginationControls";
import { pageForSizeChange } from "../lib/usePagination";
import { useTranslations } from "../lib/i18n";
import Select from "./Select";
import { Button } from "./ui/button";
import { Skeleton } from "./ui/skeleton";
import { StatusDot } from "./ui/status-dot";
import { errorMessage } from "../lib/errors";
import BrandText from "./BrandText";

// Same t(key, values?) contract useTranslations() returns (see
// I18nProvider's t callback in lib/i18n.tsx, out of this pass's scope) -
// declared locally so resultBadge()/refresh() below can accept it as a
// typed param instead of an implicit any.
type TFunction = (key: string, values?: Record<string, string | number | null | undefined>) => string;

// TS mirror of AuditLogEntry.java (core/audit) - every field this panel
// actually reads (row rendering, CSV export, actor-directory derivation).
// actorId/targetType/targetId/detail are genuinely nullable (system-
// initiated entries, entries with no target) - see the "actorId is a raw
// Keycloak user id (or null...)" comment on actorInitials() below.
interface AuditLogEntry {
  id: string;
  createdAt: string;
  actorId?: string | null;
  actionType: string;
  targetType?: string | null;
  targetId?: string | null;
  outcome: string;
  detail?: string | null;
}

// #909 - pixel-match of design_handoff_axon's "Admin Audit.dc.html": title +
// subtitle + Export CSV header, a pill filter row (search / action type /
// actor / time range / live indicator), and a TIME - ACTOR - ACTION -
// TARGET - RESULT table with action-type/result colors lifted from the
// mock's inline styles. Gated server-side to ROLE_tenant-admin
// (SecurityConfig) - this panel is only ever mounted on /admin/audit.
//
// Two honest departures from the static mock, both because this is wired to
// real data instead of eight hand-picked sample rows:
//   - "Any actor" has no dedicated backend endpoint (there's no
//     actor-directory concept in AuditLogEntry, just a raw actorId string),
//     so its options are derived client-side from whichever rows are
//     currently loaded rather than a full tenant-wide roster.
//   - the entry count in the subtitle reflects the real, backend-capped
//     page size (AuditLogRepository.search's PageRequest.of(0, 200)), not a
//     fabricated "41,208 entries" - so it reads "most recent N" rather than
//     a total.
// labelKey resolves to the translated display label at render time - kept
// as module-level data (not JSX) since it also drives RANGE_MS lookups.
const TIME_RANGES = [
  { value: "", labelKey: "allTime" },
  { value: "1h", labelKey: "last1h" },
  { value: "24h", labelKey: "last24h" },
  { value: "7d", labelKey: "last7d" },
  { value: "30d", labelKey: "last30d" },
];

const RANGE_MS: Record<string, number> = { "1h": 3600000, "24h": 86400000, "7d": 604800000, "30d": 2592000000 };

// Category color mapping straight off the mock's inline per-row styles
// (approval.* amber, tool.*/workflow.* violet, agent./knowledge./provider./
// channel.connect/user.* cyan, anything read as an error red). actionType is
// free-text (AuditLogService.record's only real constraint), so this is a
// best-effort prefix/keyword match, not an enum switch - an unrecognized
// action type just falls back to plain text color rather than erroring.
function actionColor(actionType: string | null | undefined) {
  var a = (actionType || "").toLowerCase();
  if (a.indexOf("error") !== -1 || a.indexOf("fail") !== -1) return "var(--ax-red)";
  if (a.indexOf("approval") === 0) return "var(--ax-amber)";
  // Platform-admin read-only view-as sessions (core records them in this
  // tenant's audit log): amber so a tenant admin spots them.
  if (a.indexOf("impersonation") !== -1 || a.indexOf("view_as") !== -1 || a.indexOf("view-as") !== -1)
    return "var(--ax-amber)";
  if (a.indexOf("tool") === 0 || a.indexOf("workflow") === 0 || a.indexOf("quicktask") === 0) return "var(--ax-violet)";
  if (
    a.indexOf("agent") === 0 ||
    a.indexOf("knowledge") === 0 ||
    a.indexOf("provider") === 0 ||
    a.indexOf("channel") === 0 ||
    a.indexOf("user") === 0
  ) {
    return "var(--ax-cyan)";
  }
  return "var(--ax-text)";
}

// RESULT badge - AuditOutcome.java's six values collapsed into the mock's
// three-way ok / held / fail vocabulary.
function resultBadge(outcome: string | null | undefined, t: TFunction) {
  switch (outcome) {
    case "SUCCESS":
    case "APPROVED":
    case "AUTO_APPROVED":
      return { Icon: CheckCircle2, label: t("resultOk"), cls: "ax-adt-result-ok" };
    case "PENDING_APPROVAL":
      return { Icon: Clock, label: t("resultHeld"), cls: "ax-adt-result-held" };
    case "FAILURE":
    case "REJECTED":
      return { Icon: XCircle, label: t("resultFail"), cls: "ax-adt-result-fail" };
    default:
      return { Icon: Minus, label: (outcome || "").toLowerCase(), cls: "" };
  }
}

// Token-based tinted avatars (2026-07-20 premium pass): the previous
// hardcoded hex gradients could not react to light or dark mode and forced
// a fixed dark glyph color. A muted same-hue tint behind same-hue initials
// (the Linear-style treatment) resolves entirely through the mode-reactive
// ax accent tokens, so both themes stay legible with zero literal colors.
const ACTOR_TONES = ["var(--ax-violet)", "var(--ax-cyan)", "var(--ax-green)", "var(--ax-amber)"];

// actorId is a raw Keycloak user id (or null for a system-initiated entry) -
// there's no display-name directory wired to this endpoint, so the avatar
// is a deterministic color + the id's own first two characters, same
// "honest about what data actually exists" reasoning as the class doc above.
function actorInitials(actorId: string | null | undefined) {
  if (!actorId) return "SYS";
  var cleaned = actorId
    .replace(/[^a-zA-Z0-9]/g, "")
    .slice(0, 2)
    .toUpperCase();
  return cleaned || "??";
}

function actorTone(actorId: string | null | undefined) {
  if (!actorId) return "var(--ax-red)";
  var hash = 0;
  for (var i = 0; i < actorId.length; i++) {
    hash = (hash * 31 + actorId.charCodeAt(i)) >>> 0;
  }
  return ACTOR_TONES[hash % ACTOR_TONES.length];
}

function timeOf(iso: string) {
  try {
    return new Date(iso).toTimeString().slice(0, 8);
  } catch (e) {
    return "--:--:--";
  }
}

function csvEscape(value: unknown) {
  var s = String(value == null ? "" : value);
  return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
}

const AUTO_REFRESH_MS = 20000;

export default function AuditLogPanel() {
  const t = useTranslations("auditLogPanel");
  const [entries, setEntries] = useState<AuditLogEntry[]>([]);
  const [actionTypes, setActionTypes] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(true);
  // Server paging: the API returns one page plus X-Total-Count / X-Has-More.
  const [page, setPageState] = useState(1);
  const [pageSize, setPageSizeState] = useState(25);
  const [total, setTotal] = useState<number | undefined>(undefined);
  const [hasMore, setHasMore] = useState(false);
  const tp = useTranslations("auditNotifications");

  const [search, setSearch] = useState("");
  const [actionType, setActionType] = useState("");
  const [actor, setActor] = useState("");
  const [timeRange, setTimeRange] = useState("24h");

  useEffect(function () {
    api
      .listAuditLogActionTypes()
      .then(setActionTypes)
      .catch(function () {
        setActionTypes([]);
      });
  }, []);

  const refresh = useCallback(function (actionTypeFilter: string, rangeKey: string, pageNo: number, size: number) {
    setError(null);
    return (async () => {
      try {
        const from =
          rangeKey && RANGE_MS[rangeKey] ? new Date(Date.now() - RANGE_MS[rangeKey]).toISOString() : undefined;
        const res = await api.listAuditLogPage({
          limit: size,
          offset: (pageNo - 1) * size,
          actionType: actionTypeFilter || undefined,
          from,
        });
        setEntries(res.rows);
        setTotal(res.total);
        setHasMore(res.hasMore);
      } catch (err) {
        setError(errorMessage(err, t("loadFailed")));
      } finally {
        setBusy(false);
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(
    function () {
      // refresh() settles every error itself (try/catch/finally) - nothing to await here.
      void refresh(actionType, timeRange, page, pageSize);
    },
    [refresh, actionType, timeRange, page, pageSize]
  );

  // "live - auto-refresh" - a genuinely re-fetched page every 20s, not a
  // decorative label (same real-polling reasoning as AdminEventTail.js).
  useEffect(
    function () {
      const id = setInterval(function () {
        void refresh(actionType, timeRange, page, pageSize);
      }, AUTO_REFRESH_MS);
      return function () {
        clearInterval(id);
      };
    },
    [refresh, actionType, timeRange, page, pageSize]
  );

  const actorOptions = useMemo(
    function () {
      const seen = new Set();
      const list = [];
      for (const e of entries) {
        const id = e.actorId || "";
        if (!seen.has(id)) {
          seen.add(id);
          list.push(id);
        }
      }
      return list;
    },
    [entries]
  );

  const filtered = useMemo(
    function () {
      let rows = entries;
      if (actor)
        rows = rows.filter(function (e) {
          return (e.actorId || "") === actor;
        });
      const q = search.trim().toLowerCase();
      if (q) {
        rows = rows.filter(function (e) {
          return [e.actorId, e.actionType, e.targetType, e.targetId, e.detail].some(function (f) {
            return (f || "").toLowerCase().indexOf(q) !== -1;
          });
        });
      }
      return rows;
    },
    [entries, actor, search]
  );

  const filtersActive = Boolean(search.trim() || actionType || actor || timeRange);

  const pageItems = filtered;

  // Snap back when the filters shrink the result set below the current page.
  useEffect(
    function () {
      if (total !== undefined && page > 1 && (page - 1) * pageSize >= total) {
        setPageState(Math.max(1, Math.ceil(total / pageSize)));
      }
    },
    [total, page, pageSize]
  );

  function setPage(next: number) {
    setPageState(Math.max(1, next));
  }
  function setPageSize(size: number) {
    setPageState(pageForSizeChange(page, pageSize, size));
    setPageSizeState(size);
  }
  function resetPage() {
    setPageState(1);
  }

  function exportCsv() {
    const header = ["time", "actorId", "actionType", "targetType", "targetId", "outcome", "detail"];
    const lines = [header.join(",")].concat(
      filtered.map(function (e) {
        return [
          e.createdAt,
          e.actorId || "",
          e.actionType,
          e.targetType || "",
          e.targetId || "",
          e.outcome,
          e.detail || "",
        ]
          .map(csvEscape)
          .join(",");
      })
    );
    const blob = new Blob([lines.join("\n")], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = "audit-log-page" + page + "-" + new Date().toISOString().slice(0, 10) + ".csv";
    a.click();
    URL.revokeObjectURL(url);
  }

  return (
    <div className="ax-adt-panel">
      <div className="ax-adt-head">
        <div>
          <h2 className="ax-adt-title">
            <BrandText text={t("title")} />
          </h2>
          <p className="ax-adt-sub">
            <BrandText
              text={t("subtitle", {
                count: total ?? entries.length,
                unit: (total ?? entries.length) === 1 ? t("entrySingular") : t("entryPlural"),
              })}
            />
          </p>
        </div>
        <Button type="button" variant="outline" size="sm" onClick={exportCsv}
          disabled={filtered.length === 0}
          title={tp("auditExportHint", { count: filtered.length })}
        >
          <Download aria-hidden="true" size={13} /> {tp("auditExportPage")}
        </Button>
      </div>

      {error && (
        <div className="ax-kb-error flex flex-wrap items-center gap-3" role="alert" style={{ marginTop: "12px" }}>
          <span>{error}</span>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={function () {
              void refresh(actionType, timeRange, page, pageSize);
            }}
          >
            {tp("retry")}
          </Button>
        </div>
      )}

      <div className="ax-adt-filters">
        <div className="ax-adt-search">
          <Search size={13} aria-hidden="true" />
          <input
            type="text"
            placeholder={t("searchPlaceholder")}
            aria-label={t("searchPlaceholder")}
            value={search}
            onChange={function (e) {
              setSearch(e.target.value);
            }}
            data-testid="audit-log-search"
          />
        </div>

        <div className="ax-adt-pill-wrap">
          <Select
            value={actionType}
            onChange={function (e) {
              setActionType(e.target.value);
              resetPage();
            }}
            options={[{ value: "", label: t("allActions") }].concat(
              actionTypes.map(function (actionTypeValue) {
                return { value: actionTypeValue, label: actionTypeValue };
              })
            )}
            className="select-inline"
            data-testid="audit-log-action-pill"
            aria-label={t("actionTypeFilterLabel")}
          />
        </div>

        <div className="ax-adt-pill-wrap">
          <Select
            value={actor}
            onChange={function (e) {
              setActor(e.target.value);
            }}
            options={[{ value: "", label: t("anyActor") }].concat(
              actorOptions.map(function (id) {
                return { value: id, label: id || t("systemActorLower") };
              })
            )}
            className="select-inline"
            data-testid="audit-log-actor-pill"
            aria-label={t("actorFilterLabel")}
          />
        </div>

        <div className="ax-adt-pill-wrap">
          <Select
            value={timeRange}
            onChange={function (e) {
              setTimeRange(e.target.value);
              resetPage();
            }}
            options={TIME_RANGES.map(function (r) {
              return { value: r.value, label: t(r.labelKey) };
            })}
            className="select-inline"
            data-testid="audit-log-range-pill"
            aria-label={t("timeRangeFilterLabel")}
          />
        </div>

        <div style={{ flex: 1 }} />
        <span className="text-xs text-muted-foreground" data-testid="audit-log-scope-note">
          {tp("auditFilterScope")}
        </span>
        <span className="ax-adt-live">
          <StatusDot status="up" size={6} pulse /> {t("liveAutoRefresh")}
        </span>
      </div>

      <div className="ax-adt-table" role="table" aria-label={t("title")}>
        <div className="ax-adt-row-grid ax-adt-table-head" role="row">
          <span role="columnheader">{t("timeColumn")}</span>
          <span role="columnheader">{t("actorColumn")}</span>
          <span role="columnheader">{t("actionColumn")}</span>
          <span role="columnheader">{t("targetColumn")}</span>
          <span role="columnheader">{t("resultColumn")}</span>
        </div>

        {busy && (
          <div className="space-y-2 p-3" aria-busy="true" aria-label={t("loadingTrail")}>
            {[0, 1, 2, 3, 4].map(function (n) {
              return <Skeleton key={n} className="h-8 w-full" />;
            })}
          </div>
        )}
        {!busy && filtered.length === 0 && !error && (
          <div className="ax-adt-empty">
            <span>{filtersActive ? t("noMatchingEntries") : t("emptyLog")}</span>
            {filtersActive && (
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={function () {
                  setSearch("");
                  setActionType("");
                  setActor("");
                  setTimeRange("");
                  resetPage();
                }}
              >
                {t("clearFilters")}
              </Button>
            )}
          </div>
        )}

        {pageItems.map(function (e: AuditLogEntry) {
          const result = resultBadge(e.outcome, t);
          const target = e.detail || "" + (e.targetType || "") + (e.targetId ? "/" + e.targetId : "") || "-";
          return (
            <div key={e.id} className="ax-adt-row-grid ax-adt-row" role="row">
              <span className="ax-adt-time" role="cell">
                {timeOf(e.createdAt)}
              </span>
              <span className="ax-adt-actor" role="cell">
                <span
                  className="ax-adt-actor-avatar"
                  aria-hidden="true"
                  style={{
                    background: "color-mix(in srgb, " + actorTone(e.actorId) + " 14%, transparent)",
                    color: actorTone(e.actorId),
                  }}
                >
                  {actorInitials(e.actorId)}
                </span>
                <span className="ax-adt-actor-name">{e.actorId || t("systemActor")}</span>
              </span>
              <span style={{ color: actionColor(e.actionType) }} role="cell">
                {e.actionType}
              </span>
              <span className="ax-adt-target" title={target} role="cell">
                {target}
              </span>
              <span className={"ax-adt-result " + result.cls} role="cell">
                <result.Icon size={12} aria-hidden="true" /> {result.label}
              </span>
            </div>
          );
        })}
      </div>

      <PaginationControls
        page={page}
        totalItems={total}
        hasMore={hasMore}
        pageItemCount={entries.length}
        loading={busy}
        pageSize={pageSize}
        onPageChange={setPage}
        onPageSizeChange={setPageSize}
        itemLabel={t("itemLabelEntries")}
      />
    </div>
  );
}
