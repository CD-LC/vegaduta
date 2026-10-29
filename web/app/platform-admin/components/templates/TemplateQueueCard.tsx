"use client";

import { useState } from "react";
import { motion } from "motion/react";
import {
  ChevronDown,
  ChevronRight,
  Check,
  X as XIcon,
  Undo2,
  Bot,
  Workflow as WorkflowIcon,
  Building2,
  User,
  Clock,
} from "lucide-react";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import TemplateStatusBadge from "./TemplateStatusBadge";
import ConfigViewer from "./ConfigViewer";
import TemplateReviewSummary from "./TemplateReviewSummary";
import TemplateReasonDialog from "./TemplateReasonDialog";
import { toast } from "../../lib/toastBus";
import type { CommunityTemplate, TemplateSummary } from "../../templates/types";
import { useConfirm } from "../admin/ConfirmDialog";
import { useTranslations } from "../../../lib/i18n";

function timeAgo(iso?: string): string {
  if (!iso) return "—";
  const ts = new Date(iso).getTime();
  if (Number.isNaN(ts)) return "—";
  const diffMs = Date.now() - ts;
  const mins = Math.floor(diffMs / 60000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

const KIND_ICON: Record<string, any> = { AGENT: Bot, WORKFLOW: WorkflowIcon };

// One row in the moderation queue (mode="pending", a full CommunityTemplate
// with `config`) or the published/withdraw list (mode="published", a leaner
// TemplateSummary from GET /api/admin/templates/approved - confirmed to
// carry no config/status/submitter/tenant fields, just enough to identify a
// template before withdrawing it). Collapsed shows only the fields each
// shape actually has; expanding a pending row renders the full ConfigViewer
// so a decision is never made blind - a published row has no config to show
// (already reviewed at approval time), so expanding it just surfaces the
// Withdraw action. Reject requires a reason; Withdraw's is optional
// (confirmed server-side 2026-07-30) - both route through
// TemplateReasonDialog, which enforces that per its `required` prop.
export default function TemplateQueueCard({
  template,
  expanded,
  onToggleExpand,
  mode,
  onApprove,
  onReject,
  onWithdraw,
}: {
  template: CommunityTemplate | TemplateSummary;
  expanded: boolean;
  onToggleExpand: () => void;
  mode: "pending" | "published";
  onApprove?: () => Promise<void>;
  onReject?: (reason: string) => Promise<void>;
  onWithdraw?: (reason: string) => Promise<void>;
}) {
    const tHC = useTranslations("hcPlatformadminComponentsTemplatesTemplateQueueCard");
  const askConfirm = useConfirm();
  // TemplateSummary (published) has none of these - only the pending
  // CommunityTemplate detail does. Narrowed once here so the rest of the
  // component can read them without repeating the mode check.
  const pending = mode === "pending" ? (template as CommunityTemplate) : null;
  const [busy, setBusy] = useState<"approve" | "reject" | "withdraw" | null>(null);
  const [rejectOpen, setRejectOpen] = useState(false);
  const [withdrawOpen, setWithdrawOpen] = useState(false);

  const KindIcon = KIND_ICON[template.kind] || Bot;

  async function handleApprove() {
    if (!onApprove) return;
    const ok = await askConfirm(`Approve "${template.title}"? It becomes publicly listed and importable immediately.`);
    if (!ok) return;
    setBusy("approve");
    try {
      await onApprove();
    } catch (err: any) {
      toast.error(err?.message || "Could not approve this template.");
    } finally {
      setBusy(null);
    }
  }

  async function handleReject(reason: string) {
    if (!onReject) return;
    setBusy("reject");
    try {
      await onReject(reason);
      setRejectOpen(false);
    } catch (err: any) {
      toast.error(err?.message || "Could not reject this template.");
    } finally {
      setBusy(null);
    }
  }

  async function handleWithdraw(reason: string) {
    if (!onWithdraw) return;
    setBusy("withdraw");
    try {
      await onWithdraw(reason);
      setWithdrawOpen(false);
    } catch (err: any) {
      toast.error(err?.message || "Could not withdraw this template.");
    } finally {
      setBusy(null);
    }
  }

  return (
    <motion.div
      layout
      className="rounded-lg border border-border"
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, y: -8 }}
      transition={{ duration: 0.2 }}
    >
      <button
        type="button"
        onClick={onToggleExpand}
        className="flex w-full flex-wrap items-start justify-between gap-3 rounded-lg border-0 bg-transparent p-4 text-start shadow-none transition-colors hover:bg-accent/40"
        aria-expanded={expanded}
      >
        <div className="flex min-w-0 flex-1 items-start gap-3">
          <div className="mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-md bg-primary/10 text-primary">
            <KindIcon size={16} aria-label={template.kind === "WORKFLOW" ? "Workflow template" : "Agent template"} />
          </div>
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <span className="min-w-0 max-w-full truncate text-sm font-semibold text-foreground">
                {template.title || "(untitled)"}
              </span>
              {/* TemplateSummary carries no status field - every row in the
                  Published tab is APPROVED by definition of which endpoint
                  returned it. */}
              <TemplateStatusBadge status={pending ? pending.status : "APPROVED"} />
              {template.category && (
                <Badge variant="outline" className="text-2xs">
                  {template.category}
                </Badge>
              )}
            </div>
            {template.summary && <p className="mt-1 line-clamp-2 text-xs text-muted-foreground">{template.summary}</p>}
            <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 font-mono text-[11px] text-muted-foreground">
              {pending ? (
                <>
                  <span className="inline-flex items-center gap-1">
                    <User size={11} /> {pending.submittedBy || "unknown submitter"}
                  </span>
                  <span className="inline-flex items-center gap-1">
                    <Building2 size={11} /> {pending.sourceTenantName || pending.sourceTenantId || "unknown tenant"}
                  </span>
                  <span className="inline-flex items-center gap-1">
                    <Clock size={11} /> submitted {timeAgo(pending.submittedAt)}
                  </span>
                </>
              ) : (
                <>
                  {template.tags && template.tags.length > 0 && (
                    <span className="inline-flex items-center gap-1">tags: {template.tags.join(", ")}</span>
                  )}
                  <span className="inline-flex items-center gap-1">
                    {template.importCount ?? 0} import{template.importCount === 1 ? "" : "s"}
                  </span>
                  {template.version !== undefined && <span>v{template.version}</span>}
                  <span className="inline-flex items-center gap-1">
                    <Clock size={11} /> updated {timeAgo(template.updatedAt)}
                  </span>
                </>
              )}
            </div>
          </div>
        </div>
        <div className="mt-1 shrink-0 text-muted-foreground">
          {expanded ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
        </div>
      </button>

      {expanded && (
        <div className="space-y-4 border-t border-border p-4">
          {pending ? (
            <>
              <TemplateReviewSummary review={pending.review} requirements={pending.requirements} />
              <ConfigViewer kind={pending.kind} config={pending.config} />
            </>
          ) : (
            <p className="rounded-md border border-border bg-secondary/20 px-3 py-2 text-xs text-muted-foreground">
              {tHC("hcPlatformadminComponentsTemplatesTemplateQueueCard.s1")}
            </p>
          )}

          {mode === "pending" && (
            <div className="flex flex-wrap justify-end gap-2 border-t border-border pt-3">
              <Button variant="outline" size="sm" disabled={busy !== null} onClick={() => setRejectOpen(true)}>
                <XIcon size={14} aria-hidden="true" /> Reject
              </Button>
              <Button variant="default" size="sm" disabled={busy !== null} onClick={handleApprove}>
                <Check size={14} aria-hidden="true" /> {busy === "approve" ? "Approving…" : "Approve"}
              </Button>
            </div>
          )}

          {mode === "published" && (
            <div className="flex flex-wrap justify-end gap-2 border-t border-border pt-3">
              <Button variant="destructive" size="sm" disabled={busy !== null} onClick={() => setWithdrawOpen(true)}>
                <Undo2 size={14} /> Withdraw
              </Button>
            </div>
          )}
        </div>
      )}

      <TemplateReasonDialog
        open={rejectOpen}
        title={`Reject "${template.title}"`}
        description="The submitter will see this reason and can edit and resubmit their draft."
        confirmLabel="Reject"
        submitting={busy === "reject"}
        onCancel={() => setRejectOpen(false)}
        onConfirm={handleReject}
      />
      <TemplateReasonDialog
        open={withdrawOpen}
        title={`Withdraw "${template.title}"`}
        description="This pulls an already-public template from the gallery immediately (e.g. after a report). A reason is recorded and shown to the submitter if you give one, but it isn't required."
        confirmLabel="Withdraw"
        submitting={busy === "withdraw"}
        required={false}
        onCancel={() => setWithdrawOpen(false)}
        onConfirm={handleWithdraw}
      />
    </motion.div>
  );
}
