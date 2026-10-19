"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Archive, Check, Download, Trash2, X } from "lucide-react";
import { skillLibraryApi, type LibrarySkillDetail } from "../../lib/api/skillLibrary";
import { triggerBrowserDownload } from "../../lib/api/browserDownload";
import { exportFilename, formatBytes, previewMarkdown } from "../../lib/skillLibrary";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import RichText from "../RichText";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Checkbox } from "../ui/checkbox";
import { ConfirmDialog } from "../ui/confirm-dialog";
import { Input } from "../ui/input";
import { Skeleton } from "../ui/skeleton";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "../ui/tabs";
import { Textarea } from "../ui/textarea";
import SkillDiffView from "./SkillDiffView";

export interface AgentOption {
  id: string;
  name: string;
}

type Mode = "preview" | "edit" | "changes";

/**
 * One skill, opened: rendered preview, edit (with a saved-vs-draft diff for
 * learned skills, whose text an agent wrote and a person must read before it
 * is trusted), which agents may use it, export and delete.
 */
export default function SkillDetailPanel({
  id,
  agents,
  onClose,
  onChanged,
}: {
  id: string;
  agents: AgentOption[];
  onClose: () => void;
  onChanged: () => void | Promise<void>;
}) {
  const t = useTranslations("skillLibrary");
  const [skill, setSkill] = useState<LibrarySkillDetail | null>(null);
  const [failed, setFailed] = useState(false);
  const [mode, setMode] = useState<Mode>("preview");
  const [desc, setDesc] = useState("");
  const [body, setBody] = useState("");
  const [agentIds, setAgentIds] = useState<string[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);

  // The last copy loaded from the server, so a reload can tell an untouched draft (safe to replace)
  // from text the person is still typing (must survive an export, an archive or an agent change).
  const loaded = useRef<LibrarySkillDetail | null>(null);

  const load = useCallback(
    (force: { text?: boolean; agents?: boolean } = { text: true, agents: true }) => {
      setFailed(false);
      return skillLibraryApi
        .getLibrarySkill(id)
        .then((d) => {
          const prev = loaded.current;
          const prevAgents = prev ? [...prev.agentIds].sort().join("|") : null;
          loaded.current = d;
          setSkill(d);
          setDesc((cur) => (force.text || !prev || cur === prev.description ? d.description : cur));
          setBody((cur) => (force.text || !prev || cur === prev.body ? d.body : cur));
          setAgentIds((cur) =>
            force.agents || !prev || [...cur].sort().join("|") === prevAgents ? d.agentIds : cur
          );
        })
        .catch(() => setFailed(true));
    },
    [id]
  );

  useEffect(() => {
    loaded.current = null;
    setSkill(null);
    setMode("preview");
    void load();
  }, [load]);

  const run = useCallback(
    async (key: string, fn: () => Promise<unknown>, done?: string, opts?: { reload?: boolean; force?: { text?: boolean; agents?: boolean } }) => {
      setBusy(key);
      try {
        await fn();
        if (done) toast.success(done);
        if (opts?.reload !== false) {
          await onChanged();
          await load(opts?.force ?? {});
        }
        return true;
      } catch {
        return false; // apiFetch already raised the error toast
      } finally {
        setBusy(null);
      }
    },
    [load, onChanged]
  );

  /**
   * Approving trusts the text on screen. A learned skill can be rewritten after it was opened, so
   * re-read it first and refuse when what the reviewer saw is no longer what would go live.
   */
  const approveReviewed = async () => {
    const shown = loaded.current;
    const fresh = await skillLibraryApi.getLibrarySkill(id);
    if (shown && (fresh.version !== shown.version || fresh.body !== shown.body || fresh.description !== shown.description)) {
      toast.error(t("changedSinceOpened"));
      await load({ text: true });
      throw new Error("skill changed since it was opened");
    }
    return skillLibraryApi.approveLibrarySkill(id);
  };

  const dirty = !!skill && (desc !== skill.description || body !== skill.body);
  const agentsDirty = useMemo(() => {
    if (!skill) return false;
    const a = [...agentIds].sort().join("|");
    const b = [...skill.agentIds].sort().join("|");
    return a !== b;
  }, [agentIds, skill]);

  if (failed) {
    return (
      <section className="rounded-lg border border-border p-4" data-testid="skill-detail-failed">
        <p className="text-sm text-destructive">{t("detailLoadFailed")}</p>
        <div className="mt-2 flex gap-2">
          <Button size="sm" variant="outline" onClick={() => void load()}>
            {t("retry")}
          </Button>
          <Button size="sm" variant="ghost" onClick={onClose}>
            {t("close")}
          </Button>
        </div>
      </section>
    );
  }
  if (!skill) {
    return (
      <section className="space-y-2 rounded-lg border border-border p-4" aria-busy="true" aria-label={t("loadingDetail")}>
        <Skeleton className="h-6 w-1/2" />
        <Skeleton className="h-24 w-full" />
      </section>
    );
  }

  const isLearned = skill.origin === "LEARNED";
  const canReview = skill.status === "PENDING";
  const canRestore = skill.status === "ARCHIVED" || skill.status === "REJECTED";

  const toggleAgent = (agent: string, on: boolean) =>
    setAgentIds((cur) => (on ? (cur.includes(agent) ? cur : [...cur, agent]) : cur.filter((a) => a !== agent)));

  return (
    <section className="space-y-4 rounded-lg border border-border p-4" aria-labelledby="skill-detail-title" data-testid="skill-detail">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <h2 id="skill-detail-title" className="break-words text-base font-semibold">
            {skill.name}
          </h2>
          <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
            <Badge variant="outline">{t(`status_${skill.status}`)}</Badge>
            <Badge variant="secondary">{t(`origin_${skill.origin}`)}</Badge>
            <span>{t("versionN", { n: skill.version })}</span>
            <span>{t("usesN", { n: skill.uses })}</span>
          </div>
        </div>
        <Button size="icon" variant="ghost" onClick={onClose} aria-label={t("close")}>
          <X size={16} aria-hidden="true" />
        </Button>
      </div>

      {isLearned && canReview && (
        <p className="rounded-md border border-border bg-muted/40 p-3 text-sm" role="note">
          {t("learnedNotice")}
        </p>
      )}

      <div className="flex flex-wrap gap-2">
        {canReview && (
          <>
            <Button size="sm" disabled={!!busy || dirty} onClick={() => void run("approve", approveReviewed, t("approved"))}>
              <Check size={14} aria-hidden="true" /> {t("approve")}
            </Button>
            <Button size="sm" variant="outline" disabled={!!busy} onClick={() => void run("reject", () => skillLibraryApi.rejectLibrarySkill(id), t("rejected"))}>
              <X size={14} aria-hidden="true" /> {t("reject")}
            </Button>
          </>
        )}
        {skill.status === "ACTIVE" && (
          <Button size="sm" variant="outline" disabled={!!busy} onClick={() => void run("archive", () => skillLibraryApi.archiveLibrarySkill(id), t("archived"))}>
            <Archive size={14} aria-hidden="true" /> {t("archive")}
          </Button>
        )}
        {canRestore && (
          <Button size="sm" variant="outline" disabled={!!busy || dirty} onClick={() => void run("restore", approveReviewed, t("restored"))}>
            {t("restore")}
          </Button>
        )}
        <Button
          size="sm"
          variant="outline"
          disabled={!!busy}
          onClick={() =>
            void run("export", async () => {
              const blob = await skillLibraryApi.exportLibrarySkill(id);
              triggerBrowserDownload(blob, exportFilename(skill.name));
            }, undefined, { reload: false })
          }
        >
          <Download size={14} aria-hidden="true" /> {t("export")}
        </Button>
        <Button size="sm" variant="destructive" disabled={!!busy} onClick={() => setConfirmDelete(true)}>
          <Trash2 size={14} aria-hidden="true" /> {t("delete")}
        </Button>
      </div>

      <Tabs value={mode} onValueChange={(v) => setMode(v as Mode)}>
        <TabsList aria-label={t("detailViews")}>
          <TabsTrigger value="preview">{t("tabPreview")}</TabsTrigger>
          <TabsTrigger value="edit">{t("tabEdit")}</TabsTrigger>
          {isLearned && <TabsTrigger value="changes">{t("tabChanges")}</TabsTrigger>}
        </TabsList>

        <TabsContent value="preview">
          {skill.description && <p className="mb-3 text-sm text-muted-foreground">{skill.description}</p>}
          {skill.body.trim() ? (
            <div className="rounded-md border border-border p-3 text-sm" data-testid="skill-preview">
              <RichText text={previewMarkdown(skill.body)} />
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">{t("bodyEmpty")}</p>
          )}
          {skill.files.length > 0 && (
            <div className="mt-3">
              <h3 className="mb-1 text-xs font-medium uppercase tracking-wide text-muted-foreground">{t("bundledFiles")}</h3>
              <ul className="space-y-0.5 text-xs">
                {skill.files.map((f) => (
                  <li key={f.path} className="flex justify-between gap-3">
                    <code className="min-w-0 break-all">{f.path}</code>
                    <span className="shrink-0 text-muted-foreground">{formatBytes(f.size)}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </TabsContent>

        <TabsContent value="edit" className="space-y-3">
          <div>
            <label htmlFor="skill-edit-desc" className="mb-1 block text-xs font-medium">
              {t("fieldDescription")}
            </label>
            <Input id="skill-edit-desc" value={desc} maxLength={1024} onChange={(e) => setDesc(e.target.value)} />
          </div>
          <div>
            <label htmlFor="skill-edit-body" className="mb-1 block text-xs font-medium">
              {t("fieldBody")}
            </label>
            <Textarea
              id="skill-edit-body"
              value={body}
              rows={14}
              spellCheck={false}
              className="font-mono text-xs"
              onChange={(e) => setBody(e.target.value)}
            />
          </div>
          {isLearned && dirty && <p className="text-xs text-muted-foreground">{t("reviewChangesHint")}</p>}
          <div className="flex gap-2">
            <Button
              size="sm"
              disabled={!dirty || !!busy}
              onClick={() =>
                void run("save", () => skillLibraryApi.updateLibrarySkill(id, { description: desc, body }), t("saved"), { force: { text: true } })
              }
            >
              {t("save")}
            </Button>
            <Button
              size="sm"
              variant="ghost"
              disabled={!dirty || !!busy}
              onClick={() => {
                setDesc(skill.description);
                setBody(skill.body);
              }}
            >
              {t("discard")}
            </Button>
          </div>
        </TabsContent>

        {isLearned && (
          <TabsContent value="changes">
            <SkillDiffView before={skill.body} after={body} />
          </TabsContent>
        )}
      </Tabs>

      <fieldset className="space-y-2" disabled={!!busy}>
        <legend className="text-sm font-medium">{t("agentsHeading")}</legend>
        <p className="text-xs text-muted-foreground">{t("agentsHint")}</p>
        {agents.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("agentsNone")}</p>
        ) : (
          <ul className="grid gap-1 sm:grid-cols-2">
            {agents.map((a) => (
              <li key={a.id} className="flex items-center gap-2 text-sm">
                <Checkbox
                  id={`skill-agent-${a.id}`}
                  checked={agentIds.includes(a.id)}
                  onCheckedChange={(on) => toggleAgent(a.id, on)}
                  aria-label={t("enableForAgent", { agent: a.name })}
                />
                <label htmlFor={`skill-agent-${a.id}`} className="min-w-0 truncate">
                  {a.name}
                </label>
              </li>
            ))}
          </ul>
        )}
        {skill.status !== "ACTIVE" && <p className="text-xs text-muted-foreground">{t("agentsInactive")}</p>}
        <Button
          size="sm"
          variant="outline"
          disabled={!agentsDirty || !!busy}
          onClick={() => void run("agents", () => skillLibraryApi.setLibrarySkillAgents(id, agentIds), t("agentsSaved"), { force: { agents: true } })}
        >
          {t("agentsSave")}
        </Button>
      </fieldset>

      <ConfirmDialog
        open={confirmDelete}
        onOpenChange={setConfirmDelete}
        title={t("deleteTitle", { name: skill.name })}
        description={t("deleteBody")}
        confirmLabel={t("delete")}
        cancelLabel={t("cancel")}
        destructive
        onConfirm={async () => {
          try {
            await skillLibraryApi.deleteLibrarySkill(id);
          } catch {
            return; // apiFetch already raised the error toast; keep the skill open
          }
          toast.success(t("deleted"));
          await onChanged();
          onClose();
        }}
      />
    </section>
  );
}
