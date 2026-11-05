"use client";

import { useState } from "react";
import { Check, ChevronDown, ChevronUp, Pencil, Pin, PinOff, Trash2, Undo2, X } from "lucide-react";
import { api } from "../lib/api";
import { Button, buttonVariants } from "../components/ui/button";
import { Badge } from "../components/ui/badge";
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
import { canTogglePin, isActive, isEditable, isPending, labelKey } from "./memoryItems";
import type { MemoryItem, MemoryItemDetail } from "./memoryItemTypes";

function formatDateTime(iso: string | null | undefined, locale: string | undefined): string | null {
  if (!iso) return null;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return null;
  try {
    return d.toLocaleString(locale || undefined);
  } catch {
    return d.toLocaleString();
  }
}

type DetailState =
  { kind: "closed" } | { kind: "loading" } | { kind: "loaded"; detail: MemoryItemDetail } | { kind: "unavailable" };

/**
 * One unified-memory item (ADR-0028 WP6): content, kind/status badges, the
 * review actions the caller's share level allows, and a lazy detail panel
 * (lineage + event history, populated server-side for EDITOR only).
 */
export default function MemoryItemRow({
  item,
  agentId,
  canEdit,
  onChanged,
}: {
  item: MemoryItem;
  agentId: string;
  /** EDITOR on the agent: approve/reject/revert/edit/pin/erase. VIEWER sees a read-only row. */
  canEdit: boolean;
  /** Reload the list after a decision or an erase. */
  onChanged: () => void;
}) {
  const t = useTranslations("memory");
  const locale: string | undefined = useLocale();
  const [busy, setBusy] = useState(false);
  const [confirmErase, setConfirmErase] = useState(false);
  const [detail, setDetail] = useState<DetailState>({ kind: "closed" });
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(item.content);
  const [saving, setSaving] = useState(false);

  const decide = async (op: (agentId: string, itemId: string) => Promise<unknown>) => {
    setBusy(true);
    try {
      await op(agentId, item.id);
      onChanged();
    } catch {
      /* surfaced by toJson */
    } finally {
      setBusy(false);
    }
  };

  const openEdit = () => {
    setDraft(item.content);
    setEditing(true);
  };

  // A failed save keeps the dialog open with the text intact (the error is surfaced by toJson).
  const saveEdit = async () => {
    const text = draft.trim();
    if (!text || text === item.content.trim()) return;
    setSaving(true);
    try {
      await api.editMemoryItem(agentId, item.id, text);
      setEditing(false);
      onChanged();
    } catch {
      /* surfaced by toJson */
    } finally {
      setSaving(false);
    }
  };

  const toggleDetail = async () => {
    if (detail.kind === "closed" || detail.kind === "unavailable") {
      setDetail({ kind: "loading" });
      try {
        const found = await api.getMemoryItem(agentId, item.id);
        setDetail(found ? { kind: "loaded", detail: found } : { kind: "unavailable" });
      } catch {
        setDetail({ kind: "unavailable" });
      }
      return;
    }
    setDetail({ kind: "closed" });
  };

  const created = formatDateTime(item.createdAt, locale);
  const open = detail.kind === "loaded" || detail.kind === "loading";

  return (
    <li className="rounded-lg border border-border bg-card p-3" data-testid="memory-item-row">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="mb-1 flex flex-wrap items-center gap-2">
            <Badge variant="secondary">{t(labelKey("kind", item.kind))}</Badge>
            {item.trust === "QUARANTINED" && <Badge variant="destructive">{t("trust_QUARANTINED")}</Badge>}
            {item.status && item.status !== "ACTIVE" && (
              <Badge variant="outline">{t(labelKey("status", item.status))}</Badge>
            )}
            {item.modelGenerated && <Badge variant="outline">{t("modelGenerated")}</Badge>}
            {item.pinned && (
              <Badge variant="outline" data-testid="memory-item-pinned">
                <Pin size={12} aria-hidden="true" /> {t("pinned")}
              </Badge>
            )}
          </div>
          <p className="whitespace-pre-wrap break-words text-sm text-foreground">{item.content}</p>
          <p className="mt-1 text-xs text-muted-foreground">
            {item.writer && t("writerIs", { writer: item.writer.toLowerCase() })}
            {created && ` · ${t("savedOn", { date: created })}`}
            {(item.subjectKeys || []).length > 0 &&
              ` · ${t("subjects", { subjects: (item.subjectKeys || []).join(", ") })}`}
          </p>
        </div>
        <div className="flex shrink-0 flex-wrap items-center justify-end gap-1">
          {canEdit && isPending(item) && (
            <>
              <Button variant="outline" size="sm" disabled={busy} onClick={() => void decide(api.approveMemoryItem)}>
                <Check size={14} aria-hidden="true" /> {t("approve")}
              </Button>
              <Button variant="outline" size="sm" disabled={busy} onClick={() => void decide(api.rejectMemoryItem)}>
                <X size={14} aria-hidden="true" /> {t("reject")}
              </Button>
            </>
          )}
          {canEdit && isActive(item) && (
            <Button variant="outline" size="sm" disabled={busy} onClick={() => void decide(api.revertMemoryItem)}>
              <Undo2 size={14} aria-hidden="true" /> {t("revert")}
            </Button>
          )}
          {canEdit && isEditable(item) && (
            <Button variant="outline" size="sm" disabled={busy} onClick={openEdit}>
              <Pencil size={14} aria-hidden="true" /> {t("edit")}
            </Button>
          )}
          {canEdit && canTogglePin(item) && (
            <Button
              variant="ghost"
              size="sm"
              aria-pressed={item.pinned === true}
              aria-label={item.pinned ? t("unpin") : t("pin")}
              title={item.pinned ? t("unpin") : t("pin")}
              disabled={busy}
              onClick={() => void decide(item.pinned ? api.unpinMemoryItem : api.pinMemoryItem)}
            >
              {item.pinned ? <PinOff size={14} aria-hidden="true" /> : <Pin size={14} aria-hidden="true" />}
            </Button>
          )}
          {canEdit && (
            <Button
              variant="ghost"
              size="sm"
              aria-label={t("erase")}
              title={t("erase")}
              disabled={busy}
              onClick={() => setConfirmErase(true)}
            >
              <Trash2 size={14} aria-hidden="true" />
            </Button>
          )}
          <Button
            variant="ghost"
            size="sm"
            aria-label={open ? t("detailsHide") : t("details")}
            aria-expanded={open}
            onClick={() => void toggleDetail()}
          >
            {open ? <ChevronUp size={14} aria-hidden="true" /> : <ChevronDown size={14} aria-hidden="true" />}
          </Button>
        </div>
      </div>

      {detail.kind === "loading" && <p className="mt-2 text-xs text-muted-foreground">{t("detailsLoading")}</p>}
      {detail.kind === "unavailable" && <p className="mt-2 text-xs text-muted-foreground">{t("detailsUnavailable")}</p>}
      {detail.kind === "loaded" && (
        <div className="mt-3 space-y-3 border-t border-border pt-3" data-testid="memory-item-detail">
          <dl className="grid grid-cols-1 gap-x-4 gap-y-1 text-xs sm:grid-cols-2">
            <div className="flex gap-1">
              <dt className="text-muted-foreground">{t("detailStatus")}</dt>
              <dd className="text-foreground">{t(labelKey("status", detail.detail.item.status))}</dd>
            </div>
            {detail.detail.item.expiresAt && (
              <div className="flex gap-1">
                <dt className="text-muted-foreground">{t("detailExpires")}</dt>
                <dd className="text-foreground">{formatDateTime(detail.detail.item.expiresAt, locale)}</dd>
              </div>
            )}
            {detail.detail.item.sourceType && (
              <div className="flex gap-1">
                <dt className="text-muted-foreground">{t("detailSource")}</dt>
                <dd className="break-all text-foreground">
                  {detail.detail.item.sourceType}
                  {detail.detail.item.sourceRef ? ` · ${detail.detail.item.sourceRef}` : ""}
                </dd>
              </div>
            )}
            {detail.detail.item.approvedBy && (
              <div className="flex gap-1">
                <dt className="text-muted-foreground">{t("detailApprovedBy")}</dt>
                <dd className="text-foreground">
                  {detail.detail.item.approvedBy}
                  {detail.detail.item.approvedAt ? ` · ${formatDateTime(detail.detail.item.approvedAt, locale)}` : ""}
                </dd>
              </div>
            )}
          </dl>

          <div>
            <h3 className="text-xs font-semibold text-foreground">{t("lineage")}</h3>
            {detail.detail.lineage.length === 0 ? (
              <p className="text-xs text-muted-foreground">{t("lineageEmpty")}</p>
            ) : (
              <ul className="mt-1 space-y-1">
                {detail.detail.lineage.map((s, i) => (
                  <li key={i} className="break-all text-xs text-muted-foreground">
                    {s.sourceTable}
                    {s.sourceKey ? ` · ${s.sourceKey}` : ""}
                    {s.sourceTrust ? ` · ${t(labelKey("trust", s.sourceTrust))}` : ""}
                  </li>
                ))}
              </ul>
            )}
          </div>

          <div>
            <h3 className="text-xs font-semibold text-foreground">{t("events")}</h3>
            {detail.detail.events.length === 0 ? (
              <p className="text-xs text-muted-foreground">{t("eventsEmpty")}</p>
            ) : (
              <ul className="mt-1 space-y-1">
                {detail.detail.events.map((e) => (
                  <li key={e.id} className="text-xs text-muted-foreground">
                    {t(labelKey("event", e.event))}
                    {(e.fromStatus || e.toStatus) &&
                      ` · ${t("eventTransition", {
                        from: e.fromStatus ? t(labelKey("status", e.fromStatus)) : "-",
                        to: e.toStatus ? t(labelKey("status", e.toStatus)) : "-",
                      })}`}
                    {e.actorRef ? ` · ${e.actorRef}` : ""}
                    {e.at ? ` · ${formatDateTime(e.at, locale)}` : ""}
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      )}

      <AlertDialog open={editing} onOpenChange={(o) => !saving && setEditing(o)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("editTitle")}</AlertDialogTitle>
            <AlertDialogDescription>{t("editBody")}</AlertDialogDescription>
          </AlertDialogHeader>
          <Textarea
            value={draft}
            rows={5}
            onChange={(e) => setDraft(e.target.value)}
            aria-label={t("editLabel")}
            data-testid="memory-item-edit-text"
          />
          <AlertDialogFooter>
            <AlertDialogCancel disabled={saving}>{t("cancel")}</AlertDialogCancel>
            <Button
              disabled={saving || !draft.trim() || draft.trim() === item.content.trim()}
              onClick={() => void saveEdit()}
            >
              {saving ? t("editSaving") : t("editSave")}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <AlertDialog open={confirmErase} onOpenChange={setConfirmErase}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("eraseConfirmTitle")}</AlertDialogTitle>
            <AlertDialogDescription>{t("eraseConfirmBody")}</AlertDialogDescription>
          </AlertDialogHeader>
          <p className="line-clamp-3 break-words rounded-md bg-muted px-3 py-2 text-sm text-foreground">
            {item.content}
          </p>
          <AlertDialogFooter>
            <AlertDialogCancel>{t("cancel")}</AlertDialogCancel>
            <AlertDialogAction
              className={buttonVariants({ variant: "destructive" })}
              onClick={() => void decide(api.eraseMemoryItem)}
            >
              {t("eraseConfirmButton")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </li>
  );
}
