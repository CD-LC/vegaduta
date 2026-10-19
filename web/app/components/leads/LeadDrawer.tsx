"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Loader2, Save, Trash2, X } from "lucide-react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { toast } from "../../lib/toastBus";
import {
  LEAD_ACTIVITY_TYPES,
  LEAD_STAGES,
  ageBucket,
  draftFromLead,
  draftToPayload,
  emptyDraft,
  leadDisplayName,
  validateDraft,
  type Lead,
  type LeadActivity,
  type LeadDraft,
  type LeadStage,
} from "../../lib/leads";
import Select from "../Select";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Textarea } from "../ui/textarea";
import { ConfirmDialog } from "../ui/confirm-dialog";
import { useDialogFocus } from "../../lib/hooks/useDialogFocus";

// Lead detail / create drawer for /admin/leads. One form serves both modes
// (`lead` null = create in `initialStage`); edit mode adds the activity
// timeline + "log activity" box underneath. A stage change made in the form
// goes through PUT, which the backend routes via changeStage so it lands on
// the timeline like a board move does.

interface Props {
  lead: Lead | null;
  initialStage?: LeadStage;
  onClose: () => void;
  /** Called after any successful write so the parent can refetch the board/table. */
  onChanged: (saved?: Lead, deletedId?: string) => void;
}

const FIELD_INPUT = "w-full";

export default function LeadDrawer({ lead, initialStage = "NEW", onClose, onChanged }: Props) {
  const t = useTranslations("leadsPage");
  const tg = useTranslations("f2GapsAdmin");
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [draft, setDraft] = useState<LeadDraft>(() => (lead ? draftFromLead(lead) : emptyDraft(initialStage)));
  const [touched, setTouched] = useState(false);
  const [saving, setSaving] = useState(false);
  const [activities, setActivities] = useState<LeadActivity[]>([]);
  const [activitiesLoading, setActivitiesLoading] = useState(false);
  const [activityType, setActivityType] = useState<string>("NOTE");
  const [activityBody, setActivityBody] = useState("");
  const [logging, setLogging] = useState(false);
  const drawerRef = useRef<HTMLElement | null>(null);
  const closeBtnRef = useRef<HTMLButtonElement | null>(null);

  const errors = validateDraft(draft);
  const hasErrors = Object.keys(errors).length > 0;
  const leadId = lead?.id;

  useEffect(() => {
    setDraft(lead ? draftFromLead(lead) : emptyDraft(initialStage));
    setTouched(false);
  }, [lead, initialStage]);

  const loadActivities = useCallback(async () => {
    if (!leadId) return;
    setActivitiesLoading(true);
    try {
      setActivities(await api.listLeadActivities(leadId));
    } catch {
      setActivities([]);
    } finally {
      setActivitiesLoading(false);
    }
  }, [leadId]);

  useEffect(() => {
    setActivities([]);
    void loadActivities();
  }, [loadActivities]);

  useDialogFocus(drawerRef, closeBtnRef, onClose);

  const set = (patch: Partial<LeadDraft>) => setDraft((prev) => ({ ...prev, ...patch }));

  async function save() {
    setTouched(true);
    if (hasErrors) return;
    setSaving(true);
    try {
      const payload = draftToPayload(draft);
      const saved = leadId ? await api.updateLead(leadId, payload) : await api.createLead(payload);
      toast.success(t(leadId ? "savedToast" : "createdToast"));
      onChanged(saved);
      if (leadId) void loadActivities();
      else onClose();
    } catch (e: any) {
      toast.error(e?.message || t("saveFailed"));
    } finally {
      setSaving(false);
    }
  }

  async function remove() {
    if (!lead) return;
    try {
      await api.deleteLead(lead.id);
      toast.success(t("deletedToast"));
      onChanged(undefined, lead.id);
      onClose();
    } catch (e: any) {
      toast.error(e?.message || t("deleteFailed"));
    }
  }

  async function logActivity() {
    if (!leadId || !activityBody.trim()) return;
    setLogging(true);
    try {
      await api.addLeadActivity(leadId, activityType, activityBody.trim());
      setActivityBody("");
      await loadActivities();
      // The activity can advance the stage / last-contacted stamps server-side.
      onChanged();
    } catch (e: any) {
      toast.error(e?.message || t("activityFailed"));
    } finally {
      setLogging(false);
    }
  }

  const ageLabel = (iso?: string | null) => {
    const b = ageBucket(iso);
    if (!b) return "";
    if (b.unit === "now") return t("justNow");
    return t(b.unit === "m" ? "minutesAgo" : b.unit === "h" ? "hoursAgo" : "daysAgo", { n: b.count });
  };

  const stageOptions = LEAD_STAGES.map((s) => ({ value: s, label: t(`stage_${s}`) }));
  const errorId = (key: "nameOrEmail" | "email" | "score" | "value") => `lead-${key}-error`;
  const fieldInvalid = (key: "nameOrEmail" | "email" | "score" | "value") => touched && !!errors[key];
  const errorText = (key: "nameOrEmail" | "email" | "score" | "value") =>
    fieldInvalid(key) ? (
      <p id={errorId(key)} className="mt-1 text-xs text-destructive">
        {t(`error_${key}`)}
      </p>
    ) : null;

  return (
    <div className="fixed inset-0 z-50 flex justify-end" role="dialog" aria-modal="true" aria-label={lead ? leadDisplayName(lead) : t("newLead")}>
      <button
        type="button"
        aria-label={t("close")}
        className="absolute inset-0 cursor-default bg-black/30"
        onClick={onClose}
      />
      <aside ref={drawerRef} className="relative flex h-full w-full flex-col overflow-y-auto border-l border-border bg-card shadow-xl sm:w-[30rem]">
        <header className="sticky top-0 z-10 flex items-center justify-between gap-3 border-b border-border bg-card px-4 py-3">
          <h2 className="truncate text-base font-semibold">{lead ? leadDisplayName(lead) : t("newLead")}</h2>
          <Button ref={closeBtnRef} type="button" variant="ghost" size="icon" onClick={onClose} aria-label={t("close")}>
            <X size={16} />
          </Button>
        </header>

        <div className="flex flex-col gap-4 p-4">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div className="sm:col-span-2">
              <Label htmlFor="lead-name">{t("fieldName")}</Label>
              <Input
                id="lead-name"
                className={FIELD_INPUT}
                value={draft.name}
                onChange={(e) => set({ name: e.target.value })}
                aria-invalid={fieldInvalid("nameOrEmail")}
                aria-describedby={fieldInvalid("nameOrEmail") ? errorId("nameOrEmail") : undefined}
              />
              {errorText("nameOrEmail")}
            </div>
            <div>
              <Label htmlFor="lead-email">{t("fieldEmail")}</Label>
              <Input
                id="lead-email"
                type="email"
                className={FIELD_INPUT}
                value={draft.email}
                onChange={(e) => set({ email: e.target.value })}
                aria-invalid={fieldInvalid("email")}
                aria-describedby={fieldInvalid("email") ? errorId("email") : undefined}
              />
              {errorText("email")}
            </div>
            <div>
              <Label htmlFor="lead-phone">{t("fieldPhone")}</Label>
              <Input id="lead-phone" className={FIELD_INPUT} value={draft.phone} onChange={(e) => set({ phone: e.target.value })} />
            </div>
            <div>
              <Label htmlFor="lead-company">{t("fieldCompany")}</Label>
              <Input id="lead-company" className={FIELD_INPUT} value={draft.company} onChange={(e) => set({ company: e.target.value })} />
            </div>
            <div>
              <Label htmlFor="lead-title">{t("fieldTitle")}</Label>
              <Input id="lead-title" className={FIELD_INPUT} value={draft.title} onChange={(e) => set({ title: e.target.value })} />
            </div>
            <div>
              <Label htmlFor="lead-stage">{t("fieldStage")}</Label>
              <Select id="lead-stage" value={draft.stage} onChange={(e: any) => set({ stage: e.target.value as LeadStage })} options={stageOptions} />
            </div>
            <div>
              <Label htmlFor="lead-source">{t("fieldSource")}</Label>
              <Input id="lead-source" className={FIELD_INPUT} value={draft.source} onChange={(e) => set({ source: e.target.value })} />
            </div>
            <div>
              <Label htmlFor="lead-score">{t("fieldScore")}</Label>
              <Input
                id="lead-score"
                inputMode="numeric"
                className={FIELD_INPUT}
                value={draft.score}
                onChange={(e) => set({ score: e.target.value })}
                aria-invalid={fieldInvalid("score")}
                aria-describedby={fieldInvalid("score") ? errorId("score") : undefined}
              />
              {errorText("score")}
            </div>
            <div>
              <Label htmlFor="lead-value">{t("fieldValue")}</Label>
              <Input
                id="lead-value"
                inputMode="decimal"
                className={FIELD_INPUT}
                value={draft.value}
                onChange={(e) => set({ value: e.target.value })}
                aria-invalid={fieldInvalid("value")}
                aria-describedby={fieldInvalid("value") ? errorId("value") : undefined}
              />
              {errorText("value")}
            </div>
            <div>
              <Label htmlFor="lead-owner">{t("fieldOwner")}</Label>
              <Input id="lead-owner" className={FIELD_INPUT} value={draft.owner} onChange={(e) => set({ owner: e.target.value })} />
            </div>
            <div>
              <Label htmlFor="lead-tags">{t("fieldTags")}</Label>
              <Input id="lead-tags" className={FIELD_INPUT} placeholder={t("tagsPlaceholder")} value={draft.tags} onChange={(e) => set({ tags: e.target.value })} />
            </div>
            <div className="sm:col-span-2">
              <Label htmlFor="lead-notes">{t("fieldNotes")}</Label>
              <Textarea id="lead-notes" rows={3} className={FIELD_INPUT} value={draft.notes} onChange={(e) => set({ notes: e.target.value })} />
            </div>
          </div>

          {lead && (lead.lastContactedAt || lead.lastRepliedAt) && (
            <p className="text-xs text-muted-foreground">
              {lead.lastContactedAt && <span>{t("lastContacted", { when: ageLabel(lead.lastContactedAt) })}</span>}
              {lead.lastContactedAt && lead.lastRepliedAt && " · "}
              {lead.lastRepliedAt && <span>{t("lastReplied", { when: ageLabel(lead.lastRepliedAt) })}</span>}
            </p>
          )}

          <div className="flex items-center justify-between gap-2">
            <Button type="button" onClick={save} disabled={saving}>
              {saving ? <Loader2 size={16} className="animate-spin" /> : <Save size={16} />} {t(lead ? "saveButton" : "createButton")}
            </Button>
            {lead && (
              <Button type="button" variant="ghost" className="text-destructive" onClick={() => setConfirmDelete(true)}>
                <Trash2 size={16} /> {t("deleteButton")}
              </Button>
            )}
          </div>

          {lead && (
            <section aria-label={t("timelineTitle")} className="border-t border-border pt-4">
              <h3 className="mb-2 text-sm font-semibold">{t("timelineTitle")}</h3>
              <div className="mb-3 flex flex-col gap-2">
                <div className="grid grid-cols-[9rem_1fr] gap-2">
                  <Select
                    value={activityType}
                    onChange={(e: any) => setActivityType(e.target.value)}
                    options={LEAD_ACTIVITY_TYPES.map((a) => ({ value: a, label: t(`activity_${a}`) }))}
                  />
                  <Textarea
                    rows={2}
                    value={activityBody}
                    placeholder={t("activityPlaceholder")}
                    aria-label={t("activityPlaceholder")}
                    onChange={(e) => setActivityBody(e.target.value)}
                  />
                </div>
                <div>
                  <Button type="button" variant="outline" size="sm" disabled={logging || !activityBody.trim()} onClick={logActivity}>
                    {logging && <Loader2 size={14} className="animate-spin" />} {t("logActivity")}
                  </Button>
                </div>
              </div>
              {activitiesLoading && activities.length === 0 ? (
                <p className="text-sm text-muted-foreground">{t("loading")}</p>
              ) : activities.length === 0 ? (
                <p className="text-sm text-muted-foreground">{t("timelineEmpty")}</p>
              ) : (
                <ol className="flex flex-col gap-3 border-l border-border pl-4">
                  {activities.map((a) => (
                    <li key={a.id} className="relative">
                      <span className="absolute -left-[1.3rem] top-1.5 size-2 rounded-full bg-primary" aria-hidden="true" />
                      <div className="flex items-baseline justify-between gap-2">
                        <span className="font-mono text-[11px] font-medium uppercase tracking-[0.1em] text-muted-foreground">
                          {t(`activity_${a.type}`)}
                        </span>
                        <time className="text-xs text-muted-foreground" dateTime={a.createdAt} title={new Date(a.createdAt).toLocaleString()}>
                          {ageLabel(a.createdAt)}
                        </time>
                      </div>
                      {a.body && <p className="mt-0.5 whitespace-pre-wrap break-words text-sm">{a.body}</p>}
                    </li>
                  ))}
                </ol>
              )}
            </section>
          )}
        </div>
      </aside>
      <ConfirmDialog
        open={confirmDelete}
        onOpenChange={setConfirmDelete}
        title={tg("deleteLeadTitle")}
        description={lead ? t("deleteConfirm", { name: leadDisplayName(lead) }) : undefined}
        confirmLabel={tg("delete")}
        cancelLabel={tg("cancel")}
        destructive
        onConfirm={remove}
      />
    </div>
  );
}
