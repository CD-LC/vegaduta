"use client";

import { Select } from "../../components/ui/select";
import { useMemo, useState } from "react";
import { triggerBrowserDownload } from "../../lib/api/core";
import {
  privacyApi,
  type ConsentRecordDto,
  type ContactErasureReportDto,
  type ContactExportReportDto,
} from "../../lib/api/privacy";
import { useTranslations } from "../../lib/i18n";
import { usePagination } from "../../lib/usePagination";
import PaginationControls from "../../components/PaginationControls";
import { EmptyState, Section } from "../../components/ax/page-kit";
import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../../components/ui/alert-dialog";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import { Switch } from "../../components/ui/switch";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../../components/ui/table";
import { offeredChannels, errText, fmtTime } from "./shared";

function ConsentHistory({ records }: { records: ConsentRecordDto[] }) {
  const t = useTranslations("privacySso");
  const pg = usePagination(useMemo(() => records, [records]));
  if (records.length === 0) {
    return <EmptyState title={t("historyEmptyTitle")} description={t("historyEmptyBody")} />;
  }
  return (
    <div className="space-y-3">
      <div className="overflow-x-auto rounded-lg border border-border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t("decidedAt")}</TableHead>
              <TableHead>{t("decision")}</TableHead>
              <TableHead>{t("purpose")}</TableHead>
              <TableHead>{t("noticeVersion")}</TableHead>
              <TableHead>{t("languageCode")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {pg.pageItems.map((r) => (
              <TableRow key={r.id}>
                <TableCell>{fmtTime(r.decidedAt)}</TableCell>
                <TableCell>
                  <Badge variant={r.decision === "GRANTED" ? "default" : "secondary"}>{r.decision}</Badge>
                </TableCell>
                <TableCell>{r.purpose}</TableCell>
                <TableCell className="tabular-nums">{r.noticeVersion}</TableCell>
                <TableCell>{r.noticeLanguage ?? "-"}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      <PaginationControls
        page={pg.page}
        totalPages={pg.totalPages}
        totalItems={pg.totalItems}
        pageSize={pg.pageSize}
        onPageChange={pg.setPage}
        onPageSizeChange={pg.setPageSize}
        itemLabel={t("recordsLabel")}
      />
    </div>
  );
}

export default function ContactsTab() {
  const t = useTranslations("privacySso");
  const [channel, setChannel] = useState("WHATSAPP");
  const [externalId, setExternalId] = useState("");
  const [includeLeads, setIncludeLeads] = useState(false);
  const [keepConsentProof, setKeepConsentProof] = useState(true);
  const [busy, setBusy] = useState<"export" | "erase" | "history" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [exported, setExported] = useState<ContactExportReportDto | null>(null);
  const [erased, setErased] = useState<ContactErasureReportDto | null>(null);
  const [history, setHistory] = useState<ConsentRecordDto[] | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);

  const id = externalId.trim();
  const body = { channel, externalId: id, includeLeads, keepConsentProof };

  const guard = (): boolean => {
    if (!id) {
      setError(t("errContactMissing"));
      return false;
    }
    setError(null);
    return true;
  };

  const doExport = async () => {
    if (!guard()) return;
    setBusy("export");
    setErased(null);
    try {
      setExported(await privacyApi.exportContact(body));
    } catch (e) {
      setError(errText(e));
    } finally {
      setBusy(null);
    }
  };

  const doErase = async () => {
    setConfirmOpen(false);
    if (!guard()) return;
    setBusy("erase");
    setExported(null);
    try {
      setErased(await privacyApi.eraseContact(body));
    } catch (e) {
      setError(errText(e));
    } finally {
      setBusy(null);
    }
  };

  const doHistory = async () => {
    if (!guard()) return;
    setBusy("history");
    try {
      setHistory(await privacyApi.consentRecords(channel, id));
    } catch (e) {
      setError(errText(e));
    } finally {
      setBusy(null);
    }
  };

  const downloadExport = () => {
    if (!exported) return;
    triggerBrowserDownload(
      new Blob([JSON.stringify(exported, null, 2)], { type: "application/json" }),
      `contact-export-${exported.channel.toLowerCase()}.json`
    );
  };

  return (
    <Section title={t("contactsTitle")} description={t("contactsIntro")}>
      <form className="space-y-4" onSubmit={(e) => e.preventDefault()}>
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="grid gap-1.5">
            <Label htmlFor="contact-channel">{t("channel")}</Label>
            <Select
              id="contact-channel"
              value={channel}
              onValueChange={setChannel}
              options={offeredChannels().map((c) => ({ value: c, label: c }))}
            />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="contact-id">{t("externalId")}</Label>
            <Input id="contact-id" value={externalId} onChange={(e) => setExternalId(e.target.value)} />
            <p className="text-xs text-muted-foreground">{t("externalIdHint")}</p>
          </div>
        </div>
        <div className="flex items-center gap-3">
          <Switch
            id="contact-leads"
            checked={includeLeads}
            onCheckedChange={setIncludeLeads}
            aria-label={t("includeLeads")}
          />
          <Label htmlFor="contact-leads">{t("includeLeads")}</Label>
        </div>
        <div className="flex items-center gap-3">
          <Switch
            id="contact-proof"
            checked={keepConsentProof}
            onCheckedChange={setKeepConsentProof}
            aria-label={t("keepConsentProof")}
          />
          <Label htmlFor="contact-proof">{t("keepConsentProof")}</Label>
        </div>
        <p className="text-xs text-muted-foreground">{t("keepConsentProofHint")}</p>

        {error && (
          <Alert variant="destructive">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}

        <div className="flex flex-wrap gap-2">
          <Button type="button" variant="outline" disabled={busy !== null} onClick={() => void doExport()}>
            {busy === "export" ? t("exporting") : t("exportContact")}
          </Button>
          <Button type="button" variant="outline" disabled={busy !== null} onClick={() => void doHistory()}>
            {busy === "history" ? t("loading") : t("viewConsentHistory")}
          </Button>
          <Button
            type="button"
            variant="destructive"
            disabled={busy !== null}
            onClick={() => {
              if (guard()) setConfirmOpen(true);
            }}
          >
            {busy === "erase" ? t("erasing") : t("eraseContact")}
          </Button>
        </div>
      </form>

      <AlertDialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("eraseConfirmTitle")}</AlertDialogTitle>
            <AlertDialogDescription>
              {t("eraseConfirmBody", { channel, contact: id })}{" "}
              {keepConsentProof ? t("eraseKeepsProof") : t("eraseDropsProof")}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t("cancel")}</AlertDialogCancel>
            <AlertDialogAction onClick={() => void doErase()}>{t("eraseConfirmAction")}</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {exported && (
        <div className="space-y-3 rounded-lg border border-border p-4">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h3 className="text-sm font-medium">{t("exportReportTitle", { subject: exported.subjectMasked })}</h3>
            <Button type="button" size="sm" variant="outline" onClick={downloadExport}>
              {t("downloadJson")}
            </Button>
          </div>
          <p className="text-xs text-muted-foreground">{t("generatedAt", { at: fmtTime(exported.generatedAt) })}</p>
          <ul className="list-disc ps-5 text-sm">
            {Object.entries(exported.data).map(([k, v]) => (
              <li key={k}>
                {k}: {Array.isArray(v) ? v.length : typeof v === "object" && v ? Object.keys(v).length : String(v)}
              </li>
            ))}
          </ul>
          {exported.notIncluded.length > 0 && (
            <Alert variant="warning">
              <AlertTitle>{t("notIncludedTitle")}</AlertTitle>
              <AlertDescription>
                <ul className="list-disc ps-5">
                  {exported.notIncluded.map((n) => (
                    <li key={n}>{n}</li>
                  ))}
                </ul>
              </AlertDescription>
            </Alert>
          )}
        </div>
      )}

      {erased && (
        <div className="space-y-3 rounded-lg border border-border p-4">
          <h3 className="text-sm font-medium">{t("erasureReportTitle", { subject: erased.subjectMasked })}</h3>
          <Alert variant={erased.complete ? "success" : "destructive"}>
            <AlertDescription>{erased.complete ? t("erasureComplete") : t("erasureIncomplete")}</AlertDescription>
          </Alert>
          <ul className="list-disc ps-5 text-sm">
            {Object.entries(erased.erased).map(([k, n]) => (
              <li key={k}>
                {k}: {n}
              </li>
            ))}
          </ul>
          {erased.failures.length > 0 && (
            <Alert variant="destructive">
              <AlertTitle>{t("failedSteps")}</AlertTitle>
              <AlertDescription>
                <ul className="list-disc ps-5">
                  {erased.failures.map((f) => (
                    <li key={f}>{f}</li>
                  ))}
                </ul>
              </AlertDescription>
            </Alert>
          )}
          {erased.notReached.length > 0 && (
            <Alert variant="warning">
              <AlertTitle>{t("notReachedTitle")}</AlertTitle>
              <AlertDescription>
                <ul className="list-disc ps-5">
                  {erased.notReached.map((n) => (
                    <li key={n}>{n}</li>
                  ))}
                </ul>
              </AlertDescription>
            </Alert>
          )}
          <p className="text-xs text-muted-foreground">
            {erased.consentProofKept ? t("proofKept") : t("proofNotKept")}
          </p>
        </div>
      )}

      {history && <ConsentHistory records={history} />}
    </Section>
  );
}
