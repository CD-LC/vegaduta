"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { AlertCircle, ExternalLink, Loader2, Pencil, Plus, RefreshCw, Send, Undo2, Sparkles } from "lucide-react";
import { api } from "../../lib/api";
import type { CommunityTemplateSubmission } from "../../lib/api";
import { auth } from "../../lib/auth";
import { toast } from "../../lib/toastBus";
import { Button } from "../ui/button";
import { Card, CardContent } from "../ui/card";
import { Skeleton } from "../ui/skeleton";
import CategoryChip from "./CategoryChip";
import StatusPill from "./StatusPill";
import SubmitTemplateFlow from "./SubmitTemplateFlow";
import EditSubmissionModal from "./EditSubmissionModal";
import { categoryMeta, kindMeta, statusMeta } from "./categories";
import PaginationControls from "../PaginationControls";
import { usePagination } from "../../lib/usePagination";
import { useTranslations } from "../../lib/i18n";

// The authed "my submissions" surface. Because /templates is a PUBLIC route
// prefix (so the gallery works logged-out), AuthProvider does NOT run the
// Keycloak lifecycle here - this component establishes/checks the session
// itself and prompts sign-in when there isn't one.
//
// Creating and submitting a template needs MANAGE_TEMPLATES (it publishes
// tenant agent configuration to a cross-tenant gallery). For the same reason
// as auth, AuthProvider's permissions are not loaded here, so this reads
// /api/me itself; without the permission the share and submit actions
// are replaced by a note, and editing or withdrawing an existing one stays.
export default function MySubmissions({ autoOpenSubmit = false }: { autoOpenSubmit?: boolean }) {
  const tHC = useTranslations("hcComponentsTemplatesMySubmissions");
  const [authState, setAuthState] = useState<"checking" | "anon" | "authed">("checking");
  const [items, setItems] = useState<CommunityTemplateSubmission[] | null>(null);
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");
  const [error, setError] = useState<string | null>(null);
  const [submitOpen, setSubmitOpen] = useState(false);
  // null until known: the share actions stay hidden rather than flash.
  const [canShare, setCanShare] = useState<boolean | null>(null);
  const [editing, setEditing] = useState<CommunityTemplateSubmission | null>(null);
  const [actionId, setActionId] = useState<string | null>(null);
  // Must sit above the auth-state early returns below (checking/anon) so the
  // hook order stays stable across the signed-in/out transition.
  const subsPagination = usePagination(items || []);

  const load = useCallback(async () => {
    setStatus("loading");
    setError(null);
    try {
      const res = await api.listMyTemplates();
      setItems(Array.isArray(res) ? res : []);
      setStatus("ready");
    } catch (e: any) {
      setError(e?.message || "Couldn't load your submissions.");
      setStatus("error");
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    auth
      .init()
      .then((kc) => {
        if (cancelled) return;
        if (!kc) {
          setAuthState("anon");
          return;
        }
        setAuthState("authed");
        void load();
        api
          .getMe()
          .then((me) => {
            if (cancelled) return;
            const allowed = (me?.permissions ?? []).includes("MANAGE_TEMPLATES");
            setCanShare(allowed);
            if (allowed && autoOpenSubmit) setSubmitOpen(true);
          })
          .catch(() => {
            if (!cancelled) setCanShare(false);
          });
      })
      .catch(() => {
        if (!cancelled) setAuthState("anon");
      });
    return () => {
      cancelled = true;
    };
  }, [load, autoOpenSubmit]);

  async function runAction(id: string, fn: () => Promise<unknown>, successMsg: string) {
    setActionId(id);
    try {
      await fn();
      toast.success(successMsg);
      await load();
    } catch (e: any) {
      toast.error(e?.message || "That didn't work. Please try again.");
    } finally {
      setActionId(null);
    }
  }

  // ---- Not signed in ------------------------------------------------
  if (authState === "checking") {
    return (
      <div className="space-y-3">
        <Skeleton className="h-24 w-full rounded-xl" />
        <Skeleton className="h-24 w-full rounded-xl" />
      </div>
    );
  }
  if (authState === "anon") {
    return (
      <Card elevation="base" className="items-center gap-3 py-12 text-center">
        <CardContent className="flex flex-col items-center gap-3">
          <span className="cat-chip cat-chip-lg" style={{ ["--cat" as string]: "var(--cat-assistant)" }}>
            <Sparkles size={18} strokeWidth={2} aria-hidden="true" />
          </span>
          <p className="text-sm font-medium text-foreground">{tHC("s11")}</p>
          <p className="max-w-sm text-sm text-muted-foreground">{tHC("s10")}</p>
          <Button onClick={() => auth.login({ redirectUri: `${window.location.origin}/templates/mine` })}>
            Sign in
          </Button>
        </CardContent>
      </Card>
    );
  }

  // ---- Signed in ----------------------------------------------------
  return (
    <div className="space-y-6">
      <div className="flex flex-col items-start justify-between gap-3 sm:flex-row sm:items-center">
        <p className="text-sm text-muted-foreground">{tHC("s9")}</p>
        {canShare && (
          <Button onClick={() => setSubmitOpen(true)} className="shrink-0">
            <Plus size={16} strokeWidth={2} aria-hidden="true" /> {tHC("s3")}
          </Button>
        )}
      </div>

      {canShare === false && (
        <p className="text-sm text-muted-foreground" data-testid="templates-share-needs-permission">
          {tHC("s8")}
        </p>
      )}

      {status === "loading" && (
        <div className="space-y-3">
          <Skeleton className="h-24 w-full rounded-xl" />
          <Skeleton className="h-24 w-full rounded-xl" />
        </div>
      )}

      {status === "error" && (
        <Card elevation="base" className="items-center gap-3 py-12 text-center">
          <CardContent className="flex flex-col items-center gap-3">
            <span className="cat-chip cat-chip-lg" style={{ ["--cat" as string]: "var(--state-error)" }}>
              <AlertCircle size={18} strokeWidth={2} aria-hidden="true" />
            </span>
            <p className="text-sm font-medium text-foreground">{tHC("s7")}</p>
            <p className="max-w-sm text-sm text-muted-foreground">{error}</p>
            <Button variant="outline" size="sm" onClick={load}>
              <RefreshCw size={14} strokeWidth={2} aria-hidden="true" /> {tHC("s6")}
            </Button>
          </CardContent>
        </Card>
      )}

      {status === "ready" && items && items.length === 0 && (
        <Card elevation="base" className="items-center gap-3 py-12 text-center">
          <CardContent className="flex flex-col items-center gap-3">
            <span className="cat-chip cat-chip-lg" style={{ ["--cat" as string]: "var(--cat-assistant)" }}>
              <Sparkles size={18} strokeWidth={2} aria-hidden="true" />
            </span>
            <p className="text-sm font-medium text-foreground">{tHC("s5")}</p>
            <p className="max-w-sm text-sm text-muted-foreground">{tHC("s4")}</p>
            {canShare && (
              <Button onClick={() => setSubmitOpen(true)}>
                <Plus size={16} strokeWidth={2} aria-hidden="true" /> {tHC("s3")}
              </Button>
            )}
          </CardContent>
        </Card>
      )}

      {status === "ready" && items && items.length > 0 && (
        <div className="space-y-3">
          {subsPagination.pageItems.map((sub) => (
            <SubmissionRow
              key={sub.id}
              sub={sub}
              busy={actionId === sub.id}
              canSubmit={canShare === true}
              onEdit={() => setEditing(sub)}
              onSubmit={() => runAction(sub.id, () => api.submitTemplate(sub.id), "Submitted for review.")}
              onWithdraw={() => runAction(sub.id, () => api.withdrawTemplate(sub.id), "Withdrawn - no longer public.")}
            />
          ))}
          <PaginationControls
            page={subsPagination.page}
            totalPages={subsPagination.totalPages}
            totalItems={subsPagination.totalItems}
            pageSize={subsPagination.pageSize}
            onPageChange={subsPagination.setPage}
            onPageSizeChange={subsPagination.setPageSize}
            itemLabel="submissions"
          />
        </div>
      )}

      <SubmitTemplateFlow open={submitOpen} onClose={() => setSubmitOpen(false)} onSubmitted={() => load()} />
      <EditSubmissionModal
        submission={editing}
        onClose={() => setEditing(null)}
        onSaved={() => {
          setEditing(null);
          void load();
        }}
      />
    </div>
  );
}

function SubmissionRow({
  sub,
  busy,
  canSubmit,
  onEdit,
  onSubmit,
  onWithdraw,
}: {
  sub: CommunityTemplateSubmission;
  busy: boolean;
  canSubmit: boolean;
  onEdit: () => void;
  onSubmit: () => void;
  onWithdraw: () => void;
}) {
  const tHC = useTranslations("hcComponentsTemplatesMySubmissions");
  const cat = categoryMeta(sub.category);
  const kind = kindMeta(sub.kind);
  const KindIcon = kind.icon;
  const meta = statusMeta(sub.status);

  return (
    <Card elevation="base" cat={cat.cat} className="gap-0 p-0">
      <CardContent className="flex flex-col gap-3 p-4 sm:flex-row sm:items-start sm:gap-4">
        <CategoryChip categoryId={sub.category} size="lg" />

        <div className="min-w-0 flex-1 space-y-1.5">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="text-sm font-semibold text-foreground">{sub.title}</h3>
            <StatusPill status={sub.status} />
            <span className="inline-flex items-center gap-1 text-[0.6875rem] text-muted-foreground">
              <KindIcon size={12} strokeWidth={2} aria-hidden="true" />
              {kind.label.replace(/s$/, "")}
            </span>
          </div>
          <p className="line-clamp-2 text-sm text-muted-foreground">{sub.summary}</p>

          {/* Status context line - and, for rejected, the moderator's reason. */}
          {sub.status === "REJECTED" ? (
            <p
              className="rounded-md border px-2.5 py-1.5 text-xs"
              style={{
                color: "var(--state-error)",
                borderColor: "color-mix(in srgb, var(--state-error) 28%, transparent)",
                backgroundColor: "color-mix(in srgb, var(--state-error) 10%, transparent)",
              }}
            >
              <span className="font-medium">{tHC("s2")}</span>{" "}
              {sub.rejectReason || "No reason was provided. Edit and resubmit when ready."}
            </p>
          ) : (
            <p className="text-xs text-muted-foreground">{meta.description}</p>
          )}
        </div>

        {/* Per-status actions */}
        <div className="flex shrink-0 flex-wrap items-center gap-2">
          {busy && <Loader2 size={15} className="animate-spin text-muted-foreground" aria-hidden="true" />}

          {sub.status === "APPROVED" && (
            <Button asChild variant="ghost" size="sm">
              <Link href={`/templates/${encodeURIComponent(sub.id)}`}>
                <ExternalLink size={14} strokeWidth={2} aria-hidden="true" /> {tHC("s1")}
              </Link>
            </Button>
          )}

          {(sub.status === "DRAFT" || sub.status === "REJECTED") && (
            <>
              <Button variant="outline" size="sm" onClick={onEdit} disabled={busy}>
                <Pencil size={14} strokeWidth={2} aria-hidden="true" /> Edit
              </Button>
              {canSubmit && (
                <Button size="sm" onClick={onSubmit} disabled={busy}>
                  <Send size={14} strokeWidth={2} aria-hidden="true" />
                  {sub.status === "REJECTED" ? "Resubmit" : "Submit for review"}
                </Button>
              )}
            </>
          )}

          {(sub.status === "PENDING" || sub.status === "APPROVED") && (
            <Button variant="outline" size="sm" onClick={onWithdraw} disabled={busy}>
              <Undo2 size={14} strokeWidth={2} aria-hidden="true" /> Withdraw
            </Button>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
