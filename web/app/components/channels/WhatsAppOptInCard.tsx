"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { api } from "../../lib/api";
import { errorMessage } from "../../lib/errors";
import { useTranslations } from "../../lib/i18n";
import { PAGE_SIZE_OPTIONS, pageForSizeChange, usePagination } from "../../lib/usePagination";
import PaginationControls from "../PaginationControls";
import { Alert, AlertDescription } from "../ui/alert";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import Select from "../Select";
import { listWhatsAppOptIns, type OptInListRow } from "./optInApi";

/** Where the recipient gave consent. The server stores the string as the audit trail's "source". */
export const OPT_IN_SOURCES = ["website_form", "inbound_message", "checkout", "in_person", "import"] as const;

type OptInRow = OptInListRow;

/**
 * WhatsApp opt-in records (POST/DELETE/GET /api/channels/whatsapp-compliance/opt-in). The send guard refuses
 * template sends to a number with no active opt-in, so this is where a tenant records consent, withdraws it and
 * sees every record (server-paged). When the list endpoint is unavailable (older core, error) the table falls
 * back to the records handled in this browser session; a number recorded earlier can still be revoked by typing it.
 */
export function WhatsAppOptInCard() {
  const t = useTranslations("channelsUsageMcp");
  const tl = useTranslations("channelsOptInList");
  const [phone, setPhone] = useState("");
  const [source, setSource] = useState<string>(OPT_IN_SOURCES[0]);
  const [evidence, setEvidence] = useState("");
  const [revokePhone, setRevokePhone] = useState("");
  const [rows, setRows] = useState<OptInRow[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  // "server": rows come from GET /opt-in, paged by the API. "session": the endpoint failed, rows are local.
  const [mode, setMode] = useState<"loading" | "server" | "session">("loading");
  const [serverRows, setServerRows] = useState<OptInRow[]>([]);
  const [total, setTotal] = useState<number | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(PAGE_SIZE_OPTIONS[0]);
  const [reloadTick, setReloadTick] = useState(0);
  const pg = usePagination(rows, 5);

  useEffect(() => {
    let cancelled = false;
    listWhatsAppOptIns(pageSize, (page - 1) * pageSize)
      .then((res) => {
        if (cancelled) return;
        setServerRows(res.rows);
        setTotal(res.total);
        setHasMore(res.hasMore);
        setMode("server");
      })
      .catch(() => {
        if (!cancelled) setMode("session");
      });
    return () => {
      cancelled = true;
    };
  }, [page, pageSize, reloadTick]);
  const reload = useCallback(() => setReloadTick((n) => n + 1), []);

  const shown = mode === "server" ? serverRows : pg.pageItems;

  async function record(e: FormEvent) {
    e.preventDefault();
    if (!phone.trim() || busy) return;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const res = (await api.recordWhatsAppOptIn(phone.trim(), source, evidence.trim() || undefined)) as OptInRow;
      setRows((prev) => [res, ...prev.filter((r) => r.phoneDigits !== res.phoneDigits)]);
      if (mode === "server") {
        setPage(1);
        reload();
      }
      setNotice(t("optInRecorded"));
      setPhone("");
      setEvidence("");
    } catch (err) {
      setError(errorMessage(err, t("optInRecordError")));
    } finally {
      setBusy(false);
    }
  }

  async function revoke(number: string) {
    if (!number.trim() || busy) return;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const found = await api.revokeWhatsAppOptIn(number.trim());
      if (found) {
        const digits = number.replace(/\D/g, "");
        setRows((prev) => prev.map((r) => (r.phoneDigits === digits ? { ...r, active: false } : r)));
        if (mode === "server") reload();
        setNotice(t("optInRevoked"));
        setRevokePhone("");
      } else {
        setError(t("optInNotFound"));
      }
    } catch (err) {
      setError(errorMessage(err, t("optInRevokeError")));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="w-full space-y-3 rounded-xl border border-border bg-muted/40 p-4 text-left" data-testid="wa-optin">
      <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{t("optInTitle")}</div>
      <p className="text-xs text-muted-foreground">{t("optInIntro")}</p>

      {error && (
        <Alert variant="destructive" data-testid="wa-optin-error">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {notice && (
        <Alert variant="success" data-testid="wa-optin-notice">
          <AlertDescription>{notice}</AlertDescription>
        </Alert>
      )}

      <form className="grid gap-2 sm:grid-cols-2" onSubmit={record}>
        <div className="space-y-1">
          <Label htmlFor="wa-optin-phone" className="text-xs text-muted-foreground">
            {t("optInPhoneLabel")}
          </Label>
          <Input
            id="wa-optin-phone"
            type="tel"
            inputMode="tel"
            placeholder="+919876543210"
            value={phone}
            onChange={(e) => setPhone(e.target.value)}
          />
        </div>
        <div className="space-y-1">
          <Label htmlFor="wa-optin-source" className="text-xs text-muted-foreground">
            {t("optInSourceLabel")}
          </Label>
          <Select
            id="wa-optin-source"
            className="select-inline"
            value={source}
            onChange={(e) => setSource(e.target.value)}
            options={OPT_IN_SOURCES.map((v) => ({ value: v, label: t(`optInSource_${v}`) }))}
          />
        </div>
        <div className="space-y-1 sm:col-span-2">
          <Label htmlFor="wa-optin-evidence" className="text-xs text-muted-foreground">
            {t("optInEvidenceLabel")}
          </Label>
          <Input
            id="wa-optin-evidence"
            type="text"
            placeholder={t("optInEvidencePlaceholder")}
            value={evidence}
            onChange={(e) => setEvidence(e.target.value)}
          />
        </div>
        <div className="sm:col-span-2">
          <Button type="submit" size="sm" variant="outline" disabled={busy || !phone.trim()}>
            {t("optInRecordButton")}
          </Button>
        </div>
      </form>

      <div className="space-y-2" data-testid="wa-optin-list" data-mode={mode}>
        <div className="text-xs font-medium text-foreground">
          {mode === "server" ? tl("listTitleServer") : t("optInListTitle")}
        </div>
        {mode === "loading" ? (
          <p className="text-xs text-muted-foreground">{tl("loading")}</p>
        ) : shown.length === 0 && page === 1 ? (
          <p className="text-xs text-muted-foreground">{mode === "server" ? tl("emptyServer") : t("optInEmpty")}</p>
        ) : (
          <>
            <ul className="divide-y divide-border rounded-md border border-border bg-card">
              {shown.map((r) => (
                <li key={r.phoneDigits} className="flex flex-wrap items-center gap-2 px-3 py-2 text-xs">
                  <span className="font-mono">+{r.phoneDigits}</span>
                  <Badge variant={r.active ? "secondary" : "outline"}>
                    {r.active ? t("optInActive") : t("optInInactive")}
                  </Badge>
                  <span className="text-muted-foreground">{r.source}</span>
                  {r.consentedAt && (
                    <span className="text-muted-foreground">
                      {tl("consentedOn", { date: new Date(r.consentedAt).toLocaleDateString() })}
                    </span>
                  )}
                  {r.revokedAt && (
                    <span className="text-muted-foreground">
                      {tl("revokedOn", { date: new Date(r.revokedAt).toLocaleDateString() })}
                    </span>
                  )}
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    className="ml-auto"
                    disabled={busy || !r.active}
                    aria-label={t("optInRevokeFor", { phone: r.phoneDigits })}
                    onClick={() => void revoke(r.phoneDigits)}
                  >
                    {t("optInRevokeButton")}
                  </Button>
                </li>
              ))}
            </ul>
            {mode === "server" ? (
              <PaginationControls
                page={page}
                totalItems={total ?? undefined}
                totalPages={total != null ? Math.max(1, Math.ceil(total / pageSize)) : undefined}
                hasMore={hasMore}
                pageItemCount={serverRows.length}
                pageSize={pageSize}
                onPageChange={setPage}
                onPageSizeChange={(size) => {
                  setPage(pageForSizeChange(page, pageSize, size));
                  setPageSize(size);
                }}
                itemLabel={t("optInItemLabel")}
              />
            ) : (
              <PaginationControls
                page={pg.page}
                totalPages={pg.totalPages}
                totalItems={pg.totalItems}
                pageSize={pg.pageSize}
                onPageChange={pg.setPage}
                onPageSizeChange={pg.setPageSize}
                itemLabel={t("optInItemLabel")}
              />
            )}
          </>
        )}
      </div>

      <form
        className="flex flex-wrap items-end gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          void revoke(revokePhone);
        }}
      >
        <div className="min-w-0 flex-1 space-y-1">
          <Label htmlFor="wa-optin-revoke" className="text-xs text-muted-foreground">
            {t("optInRevokeByNumber")}
          </Label>
          <Input
            id="wa-optin-revoke"
            type="tel"
            inputMode="tel"
            value={revokePhone}
            onChange={(e) => setRevokePhone(e.target.value)}
          />
        </div>
        <Button type="submit" size="sm" variant="outline" disabled={busy || !revokePhone.trim()}>
          {t("optInRevokeButton")}
        </Button>
      </form>
      <p className="text-[11px] text-muted-foreground">{mode === "server" ? tl("serverNote") : tl("fallbackNote")}</p>
    </div>
  );
}
