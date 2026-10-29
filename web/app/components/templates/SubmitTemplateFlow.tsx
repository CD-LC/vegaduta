"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { Bot, GitBranch, Info, Loader2, X } from "lucide-react";
import { api } from "../../lib/api";
import type {
  CommunityTemplateKind,
  CommunityTemplateSubmission,
  CommunityTemplateCreatePayload,
  TemplateAiReview,
  TemplateIssue,
  TemplateRequirement,
} from "../../lib/api";
import { toast } from "../../lib/toastBus";
import { isPersonalEdition } from "../../lib/edition";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Textarea } from "../ui/textarea";
import { Label } from "../ui/label";
import { Select } from "../ui/select";
import { TEMPLATE_CATEGORIES } from "./categories";
import { useDialogFocus } from "../../lib/hooks/useDialogFocus";
import TemplateStandardGuide from "./TemplateStandardGuide";
import TemplateReviewPanel from "./TemplateReviewPanel";
import TemplateRequirementsPanel from "./TemplateRequirementsPanel";
import { useTranslations } from "../../lib/i18n";

// What the last check (or a rejected submit) told us. One shape for both so the panel renders either.
interface CheckResult {
  blockers: TemplateIssue[];
  advisories: TemplateIssue[];
  requirements: TemplateRequirement[];
  ai: TemplateAiReview | null;
}

export interface PresetSource {
  kind: CommunityTemplateKind;
  id: string;
  name?: string;
}

interface Pickable {
  id: string;
  name: string;
  kind: CommunityTemplateKind;
}

// The "Share as template" flow: pick one of my agents/workflows, describe it,
// and either save a draft or submit it for review. Moderation is stated up
// front (contract requirement) - nothing a member submits goes public
// unreviewed. Rendered as a modal so it can be launched from the Agents
// screen (via ShareAsTemplateButton) and from /templates/mine alike.
export default function SubmitTemplateFlow(props: SubmitTemplateFlowProps) {
  // Community templates are submitted to a shared gallery with a moderation queue; a personal install has
  // neither (/templates/mine is a denied route there), so the flow is not mounted and loads nothing. The
  // edition is fixed for the page's life, so the hook order inside SubmitTemplateFlowInner never changes.
  if (isPersonalEdition()) return null;
  return <SubmitTemplateFlowInner {...props} />;
}

interface SubmitTemplateFlowProps {
  open: boolean;
  onClose: () => void;
  onSubmitted?: (submission: CommunityTemplateSubmission) => void;
  presetSource?: PresetSource | null;
}

function SubmitTemplateFlowInner({ open, onClose, onSubmitted, presetSource }: SubmitTemplateFlowProps) {
    const tHC = useTranslations("hcComponentsTemplatesSubmitTemplateFlow");
  const [sources, setSources] = useState<Pickable[] | null>(null);
  const [sourcesError, setSourcesError] = useState<string | null>(null);
  const [sourceId, setSourceId] = useState<string>(presetSource?.id ?? "");
  const [sourceKind, setSourceKind] = useState<CommunityTemplateKind>(presetSource?.kind ?? "AGENT");

  const [title, setTitle] = useState(presetSource?.name ?? "");
  const [summary, setSummary] = useState("");
  const [category, setCategory] = useState(TEMPLATE_CATEGORIES[0].id);
  const [tagsInput, setTagsInput] = useState("");
  const [busy, setBusy] = useState<null | "draft" | "submit" | "check">(null);
  // The draft is created once and then updated in place, so "Check" followed by "Submit" never duplicates it.
  const [draftId, setDraftId] = useState<string | null>(null);
  const [result, setResult] = useState<CheckResult | null>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  // Focus in on open, Tab trap, Escape closes, focus back to the opener on close (FE-041).
  useDialogFocus(dialogRef, null, onClose, { enabled: open });

  // Only load the user's own agents/workflows when there's no preset (i.e. the
  // person opened the flow from /templates/mine and must pick a source).
  useEffect(() => {
    if (!open || presetSource) return undefined;
    let cancelled = false;
    setSources(null);
    setSourcesError(null);
    Promise.allSettled([api.listAgents(), api.listWorkflows()])
      .then(([agentsRes, workflowsRes]) => {
        if (cancelled) return;
        const list: Pickable[] = [];
        if (agentsRes.status === "fulfilled" && Array.isArray(agentsRes.value)) {
          for (const a of agentsRes.value) {
            list.push({ id: String(a.id), name: a.name || `Agent ${a.id}`, kind: "AGENT" });
          }
        }
        if (workflowsRes.status === "fulfilled" && Array.isArray(workflowsRes.value)) {
          for (const w of workflowsRes.value) {
            list.push({ id: String(w.id), name: w.name || `Workflow ${w.id}`, kind: "WORKFLOW" });
          }
        }
        setSources(list);
        if (agentsRes.status === "rejected" && workflowsRes.status === "rejected") {
          setSourcesError("Couldn't load your agents or workflows. Please try again.");
        }
      })
      .catch(() => {
        if (!cancelled) setSourcesError("Couldn't load your agents or workflows. Please try again.");
      });
    return () => {
      cancelled = true;
    };
  }, [open, presetSource]);

  const tags = useMemo(
    () =>
      tagsInput
        .split(",")
        .map((t) => t.trim())
        .filter(Boolean)
        .slice(0, 8),
    [tagsInput]
  );

  const selectedSource = presetSource
    ? { id: presetSource.id, name: presetSource.name || "", kind: presetSource.kind }
    : sources?.find((s) => s.id === sourceId && s.kind === sourceKind) || null;

  const canSubmit = !!(presetSource || selectedSource) && title.trim().length > 0 && summary.trim().length > 0;

  // Create the draft the first time, update it afterwards. POST /api/templates ALWAYS creates a DRAFT (no
  // submit-on-create), so every path below goes through here first.
  async function saveDraft(): Promise<CommunityTemplateSubmission | null> {
    const src = presetSource ?? selectedSource;
    if (!src) {
      toast.error("Pick one of your agents or workflows to share first.");
      return null;
    }
    if (!title.trim() || !summary.trim()) {
      toast.error("Add a title and a short summary before continuing.");
      return null;
    }
    try {
      if (draftId) {
        return await api.updateTemplate(draftId, { title: title.trim(), summary: summary.trim(), category, tags });
      }
      const payload: CommunityTemplateCreatePayload = {
        kind: src.kind,
        sourceId: src.id,
        title: title.trim(),
        summary: summary.trim(),
        category,
        tags,
      };
      const created = await api.createTemplate(payload);
      setDraftId(created.id);
      return created;
    } catch (e: any) {
      toast.error(e?.message || "Couldn't save your template. Please try again.");
      return null;
    }
  }

  // Runs the same checks submit will run (rules + AI review) without submitting, and shows every result.
  async function handleCheck() {
    setBusy("check");
    try {
      const saved = await saveDraft();
      if (!saved) return;
      const review = await api.validateTemplate(saved.id, true);
      setResult({
        blockers: [...review.rules.blockers, ...review.ai.blockers],
        advisories: [...review.rules.advisories, ...review.ai.advisories],
        requirements: review.rules.requirements,
        ai: review.ai,
      });
    } catch (e: any) {
      toast.error(e?.message || "Couldn't run the check. Please try again.");
    } finally {
      setBusy(null);
    }
  }

  async function handleSubmit(mode: "draft" | "submit") {
    setBusy(mode);
    try {
      const saved = await saveDraft();
      if (!saved) return;

      if (mode === "draft") {
        toast.success("Saved as a draft. You can submit it for review any time.");
        onSubmitted?.(saved);
        onClose();
        return;
      }

      // If THIS fails the draft still exists. A 422 means the template didn't meet the standard: stay open and
      // show the full list instead of closing - the author fixes it and submits again from here.
      try {
        const submitted = await api.submitTemplate(saved.id);
        toast.success("Submitted. It was checked against the template standard and is now with our team for review.");
        onSubmitted?.(submitted);
        onClose();
      } catch (e: any) {
        if (e?.status === 422 && e?.report) {
          setResult({
            blockers: e.report.blockers ?? [],
            advisories: e.report.advisories ?? [],
            requirements: e.report.requirements ?? [],
            ai: null,
          });
          toast.error("Not ready to submit yet - see what to fix below.");
          return;
        }
        toast.error(
          e?.message
            ? `Saved as a draft, but submitting for review failed: ${e.message} You can submit it from My templates.`
            : "Saved as a draft, but submitting for review failed. You can submit it from My templates."
        );
        onSubmitted?.(saved);
        onClose();
      }
    } finally {
      setBusy(null);
    }
  }

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/60 p-4 backdrop-blur-sm sm:p-8"
      role="presentation"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-label={tHC("hcComponentsTemplatesSubmitTemplateFlow.s13")}
        className="elev-raised w-full max-w-xl rounded-2xl p-6 shadow-xl"
      >
        <div className="mb-4 flex items-start justify-between gap-4">
          <div>
            <h2 className="text-lg font-semibold tracking-tight text-foreground">{tHC("hcComponentsTemplatesSubmitTemplateFlow.s13")}</h2>
            <p className="mt-1 text-sm text-muted-foreground">{tHC("hcComponentsTemplatesSubmitTemplateFlow.s12")}</p>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="rounded-md p-1 text-muted-foreground transition-colors hover:bg-[var(--surface-hover)] hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          >
            <X size={18} strokeWidth={2} aria-hidden="true" />
          </button>
        </div>

        {/* Moderation expectation - stated explicitly, contract requirement. */}
        <div
          className="mb-5 flex items-start gap-2.5 rounded-lg border p-3 text-sm"
          style={{
            color: "var(--state-queued)",
            borderColor: "color-mix(in srgb, var(--state-queued) 30%, transparent)",
            backgroundColor: "color-mix(in srgb, var(--state-queued) 10%, transparent)",
          }}
        >
          <Info size={16} strokeWidth={2} className="mt-0.5 shrink-0" aria-hidden="true" />
          <span>
            {tHC("hcComponentsTemplatesSubmitTemplateFlow.s11")}
          </span>
        </div>

        <div className="mb-5">
          <TemplateStandardGuide />
        </div>

        <div className="space-y-4">
          {/* Source picker (only when not launched with a preset source) */}
          {presetSource ? (
            <div className="flex items-center gap-2 rounded-lg border border-border bg-card px-3 py-2.5 text-sm">
              {presetSource.kind === "WORKFLOW" ? (
                <GitBranch size={15} strokeWidth={2} aria-hidden="true" />
              ) : (
                <Bot size={15} strokeWidth={2} aria-hidden="true" />
              )}
              <span className="text-muted-foreground">Sharing:</span>
              <span className="font-medium text-foreground">
                {presetSource.name || `${presetSource.kind} ${presetSource.id}`}
              </span>
            </div>
          ) : (
            <div className="space-y-1.5">
              <Label htmlFor="tpl-source">{tHC("hcComponentsTemplatesSubmitTemplateFlow.s10")}</Label>
              {sources === null && !sourcesError ? (
                <div className="flex items-center gap-2 text-sm text-muted-foreground">
                  <Loader2 size={14} className="animate-spin" aria-hidden="true" /> {tHC("hcComponentsTemplatesSubmitTemplateFlow.s9")}
                </div>
              ) : sourcesError ? (
                <p className="text-sm" style={{ color: "var(--state-error)" }}>
                  {sourcesError}
                </p>
              ) : sources && sources.length === 0 ? (
                <p className="text-sm text-muted-foreground">
                  {tHC("hcComponentsTemplatesSubmitTemplateFlow.s8")}
                </p>
              ) : (
                <Select
                  id="tpl-source"
                  value={selectedSource ? `${selectedSource.kind}:${selectedSource.id}` : ""}
                  placeholder={tHC("hcComponentsTemplatesSubmitTemplateFlow.s7")}
                  onValueChange={(v) => {
                    const [k, ...rest] = v.split(":");
                    setSourceKind(k as CommunityTemplateKind);
                    setSourceId(rest.join(":"));
                  }}
                  options={(sources ?? []).map((s) => ({
                    value: `${s.kind}:${s.id}`,
                    label: `${s.kind === "WORKFLOW" ? "Workflow" : "Agent"} · ${s.name}`,
                  }))}
                />
              )}
            </div>
          )}

          <div className="space-y-1.5">
            <Label htmlFor="tpl-title">Title</Label>
            <Input
              id="tpl-title"
              value={title}
              maxLength={80}
              onChange={(e) => setTitle(e.target.value)}
              placeholder={tHC("hcComponentsTemplatesSubmitTemplateFlow.s6")}
            />
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="tpl-summary">Summary</Label>
            <Textarea
              id="tpl-summary"
              value={summary}
              maxLength={280}
              rows={3}
              onChange={(e) => setSummary(e.target.value)}
              placeholder={tHC("hcComponentsTemplatesSubmitTemplateFlow.s5")}
            />
            <p className="text-end text-[0.6875rem] text-muted-foreground">{summary.length}/280</p>
          </div>

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label htmlFor="tpl-category">Category</Label>
              <Select
                id="tpl-category"
                value={category}
                onValueChange={setCategory}
                options={TEMPLATE_CATEGORIES.map((c) => ({ value: c.id, label: c.label }))}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="tpl-tags">Tags</Label>
              <Input
                id="tpl-tags"
                value={tagsInput}
                onChange={(e) => setTagsInput(e.target.value)}
                placeholder={tHC("hcComponentsTemplatesSubmitTemplateFlow.s4")}
              />
              {tags.length > 0 && (
                <div className="flex flex-wrap gap-1">
                  {tags.map((t) => (
                    <span
                      key={t}
                      className="rounded-full border border-border/70 px-2 py-0.5 text-[0.625rem] text-muted-foreground"
                    >
                      {t}
                    </span>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>

        {result && (
          <div className="mt-5 space-y-4">
            <TemplateReviewPanel blockers={result.blockers} advisories={result.advisories} ai={result.ai} />
            <TemplateRequirementsPanel requirements={result.requirements} />
          </div>
        )}

        <div className="mt-6 flex flex-wrap items-center justify-end gap-2">
          <Button variant="ghost" onClick={onClose} disabled={busy !== null}>
            Cancel
          </Button>
          <Button variant="outline" onClick={handleCheck} disabled={busy !== null || !canSubmit}>
            {busy === "check" ? <Loader2 size={15} className="animate-spin" aria-hidden="true" /> : null}
            {tHC("hcComponentsTemplatesSubmitTemplateFlow.s3")}
          </Button>
          <Button variant="outline" onClick={() => handleSubmit("draft")} disabled={busy !== null || !canSubmit}>
            {busy === "draft" ? <Loader2 size={15} className="animate-spin" aria-hidden="true" /> : null}
            {tHC("hcComponentsTemplatesSubmitTemplateFlow.s2")}
          </Button>
          <Button onClick={() => handleSubmit("submit")} disabled={busy !== null || !canSubmit}>
            {busy === "submit" ? <Loader2 size={15} className="animate-spin" aria-hidden="true" /> : null}
            {tHC("hcComponentsTemplatesSubmitTemplateFlow.s1")}
          </Button>
        </div>
      </div>
    </div>
  );
}
