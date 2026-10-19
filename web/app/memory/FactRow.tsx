"use client";

import { useState } from "react";
import Link from "next/link";
import { Check, Copy, Pencil, Trash2 } from "lucide-react";
import { api } from "../lib/api";
import { Button, buttonVariants } from "../components/ui/button";
import { Textarea } from "../components/ui/textarea";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../components/ui/alert-dialog";
import { useLocale, useTranslations } from "../lib/i18n";
import type { MemoryFact, MemoryFactSource } from "./memoryTypes";

function formatDate(iso: string | null | undefined, locale: string | undefined): string | null {
  if (!iso) return null;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return null;
  try {
    return d.toLocaleDateString(locale || undefined);
  } catch {
    // An app locale tag the browser's Intl rejects: fall back to its own.
    return d.toLocaleDateString();
  }
}

type SourceState =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "loaded"; source: MemoryFactSource }
  /** The lookup failed or the endpoint is not there: say so, never guess. */
  | { kind: "unknown" };

/**
 * One saved fact: text, who learned it, how often it has been used, an inline
 * editor, delete, and a lazy "where did this come from?" lookup that only
 * calls the server when asked.
 */
export default function FactRow({
  fact,
  agentName,
  agentNames,
  maxChars,
  onEdit,
  onDelete,
}: {
  fact: MemoryFact;
  agentName: string | null;
  /** id -> name for every agent the page knows; used to name the source agent. */
  agentNames: Record<string, string>;
  maxChars: number;
  onEdit: (fact: MemoryFact, content: string) => Promise<void>;
  onDelete: (fact: MemoryFact) => Promise<void>;
}) {
  const t = useTranslations("memoryPage");
  const locale: string | undefined = useLocale();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(fact.content);
  const [busy, setBusy] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [copied, setCopied] = useState(false);
  const [source, setSource] = useState<SourceState>({ kind: "idle" });

  const loadSource = async () => {
    setSource({ kind: "loading" });
    try {
      const found: MemoryFactSource | null = await api.getMemoryFactSource(fact.scope, fact.id);
      setSource(found ? { kind: "loaded", source: found } : { kind: "unknown" });
    } catch {
      setSource({ kind: "unknown" });
    }
  };

  const copyFact = async () => {
    try {
      await navigator.clipboard.writeText(fact.content);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    } catch {
      /* clipboard blocked (insecure context or denied): nothing to undo, the text is still on screen */
    }
  };

  const uses = typeof fact.useCount === "number" ? fact.useCount : 0;
  const savedOn = formatDate(fact.createdAt, locale);
  const lastUsed = formatDate(fact.lastUsedAt, locale);
  const usage =
    uses <= 0
      ? t("usageNever")
      : lastUsed
        ? t(uses === 1 ? "usageOnceLast" : "usageManyLast", { count: uses, date: lastUsed })
        : t(uses === 1 ? "usageOnce" : "usageMany", { count: uses });

  const renderSource = () => {
    if (source.kind === "idle") {
      return (
        <Button type="button" variant="link" size="sm" className="h-auto p-0 text-xs" onClick={loadSource}>
          {t("sourceAsk")}
        </Button>
      );
    }
    if (source.kind === "loading") return <span className="text-xs text-muted-foreground">{t("sourceLoading")}</span>;
    if (source.kind === "unknown") return <span className="text-xs text-muted-foreground">{t("sourceUnknown")}</span>;
    const { source: s } = source;
    const when = formatDate(s.learnedAt, locale) || formatDate(fact.createdAt, locale);
    const sourceAgent = s.agentId ? agentNames[s.agentId] || null : null;
    if (!s.matched) {
      return (
        <span className="text-xs text-muted-foreground">
          {when ? t("sourceUnmatchedOn", { date: when }) : t("sourceUnmatched")}
        </span>
      );
    }
    return (
      <span className="text-xs text-muted-foreground">
        {sourceAgent
          ? when
            ? t("sourceMatchedAgentOn", { name: sourceAgent, date: when })
            : t("sourceMatchedAgent", { name: sourceAgent })
          : when
            ? t("sourceMatchedOn", { date: when })
            : t("sourceMatched")}
        {s.agentId && (
          <>
            {" "}
            <Link
              href={`/playground?agent=${encodeURIComponent(s.agentId)}`}
              className="font-medium text-primary underline-offset-4 hover:underline"
            >
              {t("sourceOpenChat")}
            </Link>
          </>
        )}
      </span>
    );
  };

  return (
    <li className="rounded-lg border border-border bg-card p-3" data-testid="memory-fact-row">
      {editing ? (
        <div className="space-y-2">
          <Textarea value={draft} rows={3} onChange={(e) => setDraft(e.target.value)} aria-label={t("editFact")} />
          <div className="flex items-center justify-between gap-2">
            <span className="text-xs tabular-nums text-muted-foreground">
              {t("charCount", { count: draft.length, max: maxChars })}
            </span>
            <div className="flex gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  setDraft(fact.content);
                  setEditing(false);
                }}
              >
                {t("cancel")}
              </Button>
              <Button
                size="sm"
                disabled={busy || !draft.trim() || draft.length > maxChars || draft === fact.content}
                onClick={async () => {
                  setBusy(true);
                  try {
                    await onEdit(fact, draft.trim());
                    setEditing(false);
                    // The text changed, so an earlier origin lookup no longer describes it.
                    setSource({ kind: "idle" });
                  } catch {
                    /* surfaced by toJson; keep the editor open so nothing typed is lost */
                  } finally {
                    setBusy(false);
                  }
                }}
              >
                {t("save")}
              </Button>
            </div>
          </div>
        </div>
      ) : (
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="whitespace-pre-wrap break-words text-sm text-foreground">{fact.content}</p>
            <p className="mt-1 text-xs text-muted-foreground">
              {fact.scope === "shared"
                ? t("scopeShared")
                : agentName
                  ? t("scopeAgent", { name: agentName })
                  : t("scopeAgentUnknown")}
              {" · "}
              {usage}
              {savedOn && ` · ${t("savedOn", { date: savedOn })}`}
            </p>
            <div className="mt-1">{renderSource()}</div>
          </div>
          <div className="flex shrink-0 gap-1">
            <Button variant="ghost" size="sm" aria-label={t("copyFact")} title={t("copyFact")} onClick={copyFact}>
              {copied ? <Check size={14} aria-hidden="true" /> : <Copy size={14} aria-hidden="true" />}
            </Button>
            <Button
              variant="ghost"
              size="sm"
              aria-label={t("editFact")}
              onClick={() => {
                setDraft(fact.content);
                setEditing(true);
              }}
            >
              <Pencil size={14} aria-hidden="true" />
            </Button>
            <Button
              variant="ghost"
              size="sm"
              aria-label={t("deleteFact")}
              disabled={busy}
              onClick={() => setConfirmDelete(true)}
            >
              <Trash2 size={14} aria-hidden="true" />
            </Button>
          </div>
        </div>
      )}
      <AlertDialog open={confirmDelete} onOpenChange={setConfirmDelete}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("deleteConfirmTitle")}</AlertDialogTitle>
            <AlertDialogDescription>{t("deleteConfirm")}</AlertDialogDescription>
          </AlertDialogHeader>
          <p className="line-clamp-3 break-words rounded-md bg-muted px-3 py-2 text-sm text-foreground">
            {fact.content}
          </p>
          <AlertDialogFooter>
            <AlertDialogCancel>{t("cancel")}</AlertDialogCancel>
            <AlertDialogAction
              className={buttonVariants({ variant: "destructive" })}
              onClick={async () => {
                setBusy(true);
                try {
                  await onDelete(fact);
                } finally {
                  setBusy(false);
                }
              }}
            >
              {t("deleteConfirmButton")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </li>
  );
}
