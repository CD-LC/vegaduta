"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { Check, Copy, Download, FileText, Link2, Loader2, Printer, Share2 } from "lucide-react";
import { api, triggerBrowserDownload } from "../../lib/api";
import type { ResearchResult } from "../../lib/api/types";
import { Button } from "../ui/button";
import { useTranslations } from "../../lib/i18n";
import {
  ExportFailedError,
  buildDocumentRequest,
  generateAndFetch,
  pdfWouldDropCharacters,
  reportText,
  sanitizeFilename,
  plainInline,
  toMarkdown,
  type ExportLabels,
} from "./exportReport";
import { toFootnotedMarkdown } from "./reportStructure";
import { RS } from "./researchStrings";

type DocType = "PDF" | "DOCX";

/**
 * Take the report out of the console: copy it, or save it as Markdown, PDF or
 * Word.
 *
 * Markdown and copy are built in the browser and always work. PDF and Word go
 * through the document service (POST /api/documentgen/documents), which is a
 * paid-plan feature and may be switched off or capped:
 *
 *  - on a FREE plan the two buttons are not offered at all - a line says they
 *    come with paid plans (the plan is read the way DashboardHome reads it;
 *    while it is unknown the buttons stay, and a refusal is handled below);
 *  - if the generate request itself is refused, the buttons are withdrawn for
 *    this visit and a line says Markdown still works. That request goes
 *    through the quiet client method, so the inline line is the only feedback;
 *  - any other failure (a poll or download that failed, a network fault) says
 *    the file could not be created and leaves the buttons for another try.
 */
export default function ExportMenu({ result }: { result: ResearchResult }) {
  const t = useTranslations("researchPage");
  const [busy, setBusy] = useState<DocType | null>(null);
  const [copied, setCopied] = useState(false);
  const [linkCopied, setLinkCopied] = useState(false);
  const [canShare, setCanShare] = useState(false);
  const timers = useRef<ReturnType<typeof setTimeout>[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const [docsUnavailable, setDocsUnavailable] = useState(false);
  // TenantSettingsResponse#plan; null = not known (yet, or the read failed).
  const [tenantPlan, setTenantPlan] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.resolve()
      .then(() => api.getTenantSettings())
      .then((s: { plan?: string | null }) => {
        if (!cancelled) setTenantPlan(s?.plan ?? null);
      })
      .catch(() => {
        // Unknown plan: keep offering the buttons; a refusal is shown inline.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    setCanShare(typeof navigator !== "undefined" && typeof navigator.share === "function");
    const pending = timers.current;
    return () => pending.forEach(clearTimeout);
  }, []);

  const freePlan = tenantPlan === "FREE";
  const offerDocs = !docsUnavailable && !freePlan;

  const labels: ExportLabels = useMemo(
    () => ({
      sources: t("exportSourcesHeading"),
      unanswered: t("unansweredHeading"),
      partialNote: t("partialNote"),
      fallbackTitle: t("reportHeading"),
    }),
    [t]
  );
  const pdfLossy = useMemo(() => pdfWouldDropCharacters(reportText(result)), [result]);

  async function copy() {
    setMessage(null);
    try {
      await navigator.clipboard.writeText(toMarkdown(result, labels));
      setCopied(true);
      timers.current.push(setTimeout(() => setCopied(false), 2000));
    } catch {
      setMessage(t("exportCopyFailed"));
    }
  }

  function saveMarkdown() {
    setMessage(null);
    const blob = new Blob([toFootnotedMarkdown(result, labels)], { type: "text/markdown;charset=utf-8" });
    triggerBrowserDownload(blob, `${sanitizeFilename(result.title)}.md`);
  }

  async function copyLink() {
    setMessage(null);
    try {
      await navigator.clipboard.writeText(window.location.href);
      setLinkCopied(true);
      timers.current.push(setTimeout(() => setLinkCopied(false), 2000));
    } catch {
      setMessage(RS.exportShareFailed);
    }
  }

  async function share() {
    setMessage(null);
    try {
      const text = plainInline(result.reportMarkdown).replace(/\s+/g, " ").slice(0, 280);
      await navigator.share({ title: result.title || labels.fallbackTitle, text, url: window.location.href });
    } catch (err: unknown) {
      // The person closing the share sheet is not an error.
      if (!(err instanceof DOMException && err.name === "AbortError")) setMessage(RS.exportShareFailed);
    }
  }

  async function saveDocument(type: DocType) {
    setBusy(type);
    setMessage(null);
    try {
      const file = await generateAndFetch(
        {
          generate: api.generateResearchDocument,
          list: api.listGeneratedDocuments,
          download: api.downloadGeneratedDocument,
          sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
        },
        buildDocumentRequest(type, result, labels)
      );
      triggerBrowserDownload(file.blob, file.filename);
    } catch (err: unknown) {
      if (err instanceof ExportFailedError && err.reason === "refused") {
        // The service refused the request outright (not on this deployment or
        // plan, switched off, quota). Stop offering it for this visit.
        setDocsUnavailable(true);
        setMessage(t("exportDocsUnavailable"));
      } else if (err instanceof ExportFailedError && err.reason === "timeout") {
        setMessage(t("exportTimeout"));
      } else {
        // The generator failed, or a poll / download / the network did. That
        // says nothing about whether the service is offered here, so the
        // buttons stay.
        setMessage(t("exportFailed"));
      }
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="space-y-2" data-testid="research-export">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{t("exportLabel")}</span>
        <Button type="button" size="sm" variant="outline" onClick={copy}>
          {copied ? <Check className="size-4" aria-hidden="true" /> : <Copy className="size-4" aria-hidden="true" />}
          {copied ? t("exportCopied") : t("exportCopy")}
        </Button>
        <Button type="button" size="sm" variant="outline" onClick={saveMarkdown}>
          <Download className="size-4" aria-hidden="true" /> {RS.exportFootnoted}
        </Button>
        <Button type="button" size="sm" variant="outline" onClick={() => window.print()}>
          <Printer className="size-4" aria-hidden="true" /> {RS.exportPrint}
        </Button>
        {canShare && (
          <Button type="button" size="sm" variant="outline" onClick={share}>
            <Share2 className="size-4" aria-hidden="true" /> {RS.exportShare}
          </Button>
        )}
        <Button type="button" size="sm" variant="outline" onClick={copyLink} title={RS.exportLinkNote}>
          {linkCopied ? (
            <Check className="size-4" aria-hidden="true" />
          ) : (
            <Link2 className="size-4" aria-hidden="true" />
          )}
          {linkCopied ? RS.exportLinkCopied : RS.exportCopyLink}
        </Button>
        {offerDocs &&
          (["PDF", "DOCX"] as const).map((type) => (
            <Button
              key={type}
              type="button"
              size="sm"
              variant="outline"
              disabled={busy !== null}
              onClick={() => saveDocument(type)}
            >
              {busy === type ? (
                <Loader2 className="size-4 animate-spin" aria-hidden="true" />
              ) : (
                <FileText className="size-4" aria-hidden="true" />
              )}
              {busy === type ? t("exportPreparing") : type === "PDF" ? t("exportPdf") : t("exportDocx")}
            </Button>
          ))}
      </div>
      {freePlan && !docsUnavailable && (
        <p className="text-xs text-muted-foreground" data-testid="research-export-paid-note">
          {t("exportDocsPaidPlan")}
        </p>
      )}
      {pdfLossy && offerDocs && <p className="text-xs text-muted-foreground">{t("exportPdfScriptNote")}</p>}
      <p aria-live="polite" className="sr-only">
        {copied ? t("exportCopied") : linkCopied ? RS.exportLinkCopied : ""}
      </p>
      {message && (
        <p role="status" className="text-xs text-muted-foreground">
          {message}
        </p>
      )}
    </div>
  );
}
