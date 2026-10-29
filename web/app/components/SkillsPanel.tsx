"use client";

// Skills list + viewer/editor. See app/skills/page.tsx for the page/panel split.
//
// Writes are workspace-admin only on the server (a skill's name + description
// are injected into EVERY agent's instructions, so authoring one is closer to
// editing shared policy than a personal document). The panel mirrors that:
// non-admins can browse and read every skill but see no write controls, rather
// than controls that 403.

import { useEffect, useMemo, useRef, useState } from "react";
import { FileText, Plus, Upload } from "lucide-react";
import { api, type PendingSkillImprovement, type SkillDetail, type SkillSummary } from "../lib/api";
import { triggerBrowserDownload } from "../lib/api/core";
import { SKILL_TEMPLATES, filterSkills, forkSkillMd, type SkillListFilter } from "../lib/skillAuthoring";
import { useAuth } from "./AuthProvider";
import { useTranslations } from "../lib/i18n";
import { Alert, AlertDescription } from "./ui/alert";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "./ui/alert-dialog";
import { Button } from "./ui/button";
import { Card, CardContent } from "./ui/card";
import PaginationControls from "./PaginationControls";
import { usePagination } from "../lib/usePagination";
import type { SkillSecurityOverview, SkillSecurityReport } from "../lib/api/skillSecurity";
import { confirmBeforeSave, skillsNeedingReview, summaryByName } from "../lib/skillSecurity";
import SkillReviewBanner from "./skills/SkillReviewBanner";
import SkillRow from "./skills/SkillRow";
import SkillViewerToolbar from "./skills/SkillViewerToolbar";
import SkillImprovementReview from "./skills/SkillImprovementReview";
import SkillScanDialog, { type SkillScanPrompt } from "./skills/SkillScanDialog";
import SkillScanFindings from "./skills/SkillScanFindings";
import { SkillFilterBar, SkillUsage } from "./skills/SkillAuthoringAids";
import SkillEditorForm from "./skills/SkillEditorForm";

// The starter text lives with the other templates (lib/skillAuthoring). It used to be defined here with an
// angle-bracket placeholder in the description, which the server rejects ("cannot contain XML or HTML tags").
const NEW_SKILL_TEMPLATE = SKILL_TEMPLATES[0].skillMd;

type EditorState =
  | { mode: "closed" }
  | { mode: "view"; detail: SkillDetail; filePath: string | null; fileContent: string | null }
  | { mode: "edit"; detail: SkillDetail | null; draft: string };

export default function SkillsPanel({
  skills,
  onChanged,
}: {
  skills: SkillSummary[];
  onChanged: () => Promise<unknown> | void;
}) {
    const tHC = useTranslations("hcComponentsSkillsPanel");
  const t = useTranslations("skillsPage");
  const { hasPermission } = useAuth();
  const canManageSkills = hasPermission("MANAGE_AGENTS");
  const [editor, setEditor] = useState<EditorState>({ mode: "closed" });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<SkillSummary | null>(null);
  const [replaceFile, setReplaceFile] = useState<File | null>(null);
  // The staged agent-authored rewrite of the skill currently open in the
  // viewer (V243/V395). Loaded on open() when the summary flags it; admins
  // apply or discard it from the review block - the live skill is untouched
  // until Apply.
  const [improvement, setImprovement] = useState<PendingSkillImprovement | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);

  // Supply-chain scan (core /api/skill-security). null = scanning is off, or not available to
  // this user: the page then shows no security UI at all. A scan reduces risk, it does not
  // prove a skill is safe - every surface below says so.
  const [security, setSecurity] = useState<SkillSecurityOverview | null>(null);
  const [report, setReport] = useState<SkillSecurityReport | null>(null);
  const [scanPrompt, setScanPrompt] = useState<SkillScanPrompt | null>(null);
  const securityByName = useMemo(() => summaryByName(security), [security]);
  // A skill that is switched off (including one the admin just rejected) is not waiting on anyone.
  const needReview = useMemo(
    () => skillsNeedingReview(security, (name) => skills.find((s) => s.name === name)?.enabled !== false),
    [security, skills]
  );

  // `skills` is a fresh array after every refresh (create, edit, import, toggle...), so reading
  // the overview whenever it changes keeps the badges in step with what the server stored.
  useEffect(() => {
    let cancelled = false;
    void api.getSkillSecurityOverview().then((o) => {
      if (!cancelled) setSecurity(o);
    });
    return () => {
      cancelled = true;
    };
  }, [skills]);

  const refreshReport = async (name: string) => {
    setReport(security ? await api.getSkillSecurityReport(name) : null);
  };

  // Search + filter over both lists. "Needs attention" = agent drafts, staged rewrites and anything the
  // supply-chain scan queued for review.
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<SkillListFilter>("all");
  const flaggedNames = useMemo(() => new Set(needReview.map((s) => s.name)), [needReview]);
  const visible = useMemo(
    () => filterSkills(skills, query, filter, flaggedNames),
    [skills, query, filter, flaggedNames]
  );
  const builtIn = useMemo(() => visible.filter((s) => s.source === "BUILT_IN"), [visible]);
  const custom = useMemo(() => visible.filter((s) => s.source === "CUSTOM"), [visible]);
  const customTotal = useMemo(() => skills.filter((s) => s.source === "CUSTOM").length, [skills]);
  const filtering = query.trim() !== "" || filter !== "all";

  // Shared client-side pagination - both lists are tenant-scoped arrays the
  // endpoint returns whole, so the same usePagination the other panels use
  // slices them here.
  const customPagination = usePagination(custom);
  const builtInPagination = usePagination(builtIn);

  // api.ts already raised the global toast for a failed call; the inline
  // message is for context next to the control that failed.
  const run = async (work: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await work();
    } catch (e: any) {
      setError(e?.message || t("genericError"));
    } finally {
      setBusy(false);
    }
  };

  const open = (skill: SkillSummary) =>
    run(async () => {
      const detail = await api.getSkill(skill.name);
      // 404 inside getSkillImprovement already maps to null ("nothing staged"),
      // so a cleared flag between list and open can't wedge the viewer.
      setImprovement(
        canManageSkills && skill.pendingImprovement && detail.id ? await api.getSkillImprovement(detail.id) : null
      );
      setReport(security ? await api.getSkillSecurityReport(skill.name) : null);
      setEditor({ mode: "view", detail, filePath: null, fileContent: null });
      // On lg+ the viewer column is sticky so it is already on screen; below
      // that the columns stack and a click far down the list leaves the
      // viewer off-screen, so pull it into view.
      if (!window.matchMedia("(min-width: 1024px)").matches) {
        panelRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
      }
    });

  const openFile = (detail: SkillDetail, path: string) =>
    run(async () => {
      const fileContent = await api.getSkillFile(detail.name, path);
      setEditor({ mode: "view", detail, filePath: path, fileContent });
    });

  const save = () =>
    run(async () => {
      if (editor.mode !== "edit") return;
      const persist = async () => {
        if (editor.mode !== "edit") return;
        const saved = editor.detail?.id
          ? await api.updateSkill(editor.detail.id, editor.draft)
          : await api.createSkill(editor.draft);
        await onChanged();
        await refreshReport(saved.name);
        setNotice(t("savedNotice", { name: saved.name }));
        setEditor({ mode: "view", detail: saved, filePath: null, fileContent: null });
      };
      // Scan the text before it is stored; only a medium/high result interrupts the save.
      if (security) {
        const outcome = await api.previewSkillDraft(editor.draft);
        if (outcome.error) throw new Error(outcome.error);
        if (outcome.preview && confirmBeforeSave(outcome.preview.scan.riskLevel)) {
          setScanPrompt({
            preview: outcome.preview,
            confirmLabel: t("scanConfirmSave"),
            onConfirm: () => {
              setScanPrompt(null);
              void run(persist);
            },
          });
          return;
        }
      }
      await persist();
    });

  // Upload: scan the archive first, show the result, install only on confirm. Nothing is
  // stored while the dialog is open.
  const startImport = (file: File) =>
    run(async () => {
      if (security) {
        const outcome = await api.previewSkillArchive(file);
        if (outcome.error) throw new Error(outcome.error);
        if (outcome.preview) {
          setScanPrompt({
            preview: outcome.preview,
            confirmLabel: t("scanConfirmInstall"),
            onConfirm: () => {
              setScanPrompt(null);
              // The dialog already said an existing skill of that name is replaced, so confirming
              // is the replace confirmation - no second prompt.
              void doImport(file, outcome.preview.replacesExisting);
            },
          });
          return;
        }
      }
      await doImport(file, false);
    });

  const approveCurrent = (acknowledgeHighRisk: boolean) =>
    run(async () => {
      if (!report) return;
      try {
        const next = await api.approveSkill(report.name, {
          expectedSha256: report.contentSha256,
          acknowledgeHighRisk,
        });
        setReport(next);
        await onChanged();
        setNotice(t("reviewApprovedNotice", { name: next.name }));
      } catch (e: any) {
        if (e?.status === 409) {
          // Changed again while the reviewer was looking: show the latest, approve nothing.
          await refreshReport(report.name);
          setError(t("reviewConflict"));
          return;
        }
        throw e;
      }
    });

  // Reject = switch the skill off; its content stays for the author to fix or delete.
  const rejectCurrent = () =>
    run(async () => {
      if (!report) return;
      await api.setSkillEnabled(report.name, false);
      await onChanged();
      await refreshReport(report.name);
      setNotice(t("reviewRejectedNotice", { name: report.name }));
    });

  const toggle = (skill: SkillSummary) =>
    run(async () => {
      await api.setSkillEnabled(skill.name, !skill.enabled);
      await onChanged();
    });

  const doImport = (file: File, replace: boolean) =>
    run(async () => {
      try {
        const result = await api.importSkill(file, replace);
        await onChanged();
        setReplaceFile(null);
        await refreshReport(result.skill.name);
        setNotice(
          result.skippedPaths.length > 0
            ? t("importedWithSkipped", { name: result.skill.name, count: result.skippedPaths.length })
            : t("importedNotice", { name: result.skill.name })
        );
        setEditor({ mode: "view", detail: result.skill, filePath: null, fileContent: null });
      } catch (e: any) {
        // "already has a skill named" is the one import failure with a useful
        // next step: offer to replace instead of just reporting it.
        if (!replace && e?.status === 400 && /already has a skill named/i.test(e?.message || "")) {
          setReplaceFile(file);
          return;
        }
        throw e;
      }
    });

  const doExport = (name: string) =>
    run(async () => {
      triggerBrowserDownload(await api.exportSkill(name), `${name}.zip`);
    });

  // The SKILL.md text alone (or the bundled file being viewed), for pasting into another Agent Skills host.
  const doDownloadText = (detail: SkillDetail, filePath: string | null, content: string | null) => {
    const text = filePath === null ? detail.skillMd : (content ?? "");
    const fileName = filePath === null ? "SKILL.md" : filePath.split("/").pop() || "file.txt";
    triggerBrowserDownload(new Blob([text], { type: "text/plain;charset=utf-8" }), fileName);
  };

  // Copy a skill (built-in or custom) into a new custom draft under a fresh name; nothing is saved
  // until the admin presses Save, so it also goes through the normal scan-before-save step.
  const duplicate = (detail: SkillDetail) => {
    const { skillMd } = forkSkillMd(
      detail,
      skills.map((s) => s.name)
    );
    setError(null);
    setNotice(null);
    setEditor({ mode: "edit", detail: null, draft: skillMd });
  };

  const doDelete = () =>
    run(async () => {
      if (!deleting?.id) return;
      const res = await api.deleteSkill(deleting.id);
      if (!res.ok) throw new Error(t("genericError"));
      if (editor.mode !== "closed" && editor.detail?.name === deleting.name) setEditor({ mode: "closed" });
      setDeleting(null);
      await onChanged();
    });

  const applyImprovement = () =>
    run(async () => {
      if (!improvement) return;
      const saved = await api.applySkillImprovement(improvement.skillId);
      await onChanged();
      await refreshReport(saved.name);
      setNotice(t("improvementAppliedNotice", { name: saved.name }));
      setImprovement(null);
      setEditor({ mode: "view", detail: saved, filePath: null, fileContent: null });
    });

  const discardImprovement = () =>
    run(async () => {
      if (!improvement) return;
      await api.discardSkillImprovement(improvement.skillId);
      await onChanged();
      setNotice(t("improvementDiscardedNotice", { name: improvement.name }));
      setImprovement(null);
    });

  const row = (skill: SkillSummary) => (
    <SkillRow
      key={skill.name}
      skill={skill}
      selected={editor.mode !== "closed" && editor.detail?.name === skill.name}
      sec={securityByName.get(skill.name)}
      canManageSkills={canManageSkills}
      busy={busy}
      onOpen={() => open(skill)}
      onToggle={() => toggle(skill)}
    />
  );

  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,5fr)_minmax(0,6fr)]">
      <div className="min-w-0 space-y-6">
        <Alert>
          <AlertDescription>{t("howItWorks")}</AlertDescription>
        </Alert>

        {canManageSkills && (
          <div className="flex flex-wrap gap-2">
            <Button
              size="sm"
              disabled={busy}
              onClick={() => setEditor({ mode: "edit", detail: null, draft: NEW_SKILL_TEMPLATE })}
            >
              <Plus aria-hidden="true" size={14} /> {t("newSkillButton")}
            </Button>
            <Button size="sm" variant="outline" disabled={busy} onClick={() => fileInput.current?.click()}>
              <Upload aria-hidden="true" size={14} /> {t("uploadButton")}
            </Button>
            <input
              ref={fileInput}
              type="file"
              accept=".zip,application/zip"
              className="hidden"
              onChange={(e) => {
                const file = e.target.files?.[0];
                e.target.value = "";
                if (file) void startImport(file);
              }}
            />
          </div>
        )}

        {error && (
          <Alert variant="destructive">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}
        {notice && (
          <Alert>
            <AlertDescription>{notice}</AlertDescription>
          </Alert>
        )}
        {needReview.length > 0 && (
          <Alert variant="warning" data-testid="skill-review-queue">
            <AlertDescription>
              {t("reviewQueueSummary", { count: needReview.length, names: needReview.map((s) => s.name).join(", ") })}
            </AlertDescription>
          </Alert>
        )}
        {security && (
          <p className="text-xs leading-relaxed text-muted-foreground">
            {security.enforcing ? t("securityModeEnforcing") : t("securityModeWarnOnly")}
          </p>
        )}

        <SkillFilterBar
          query={query}
          onQuery={setQuery}
          filter={filter}
          onFilter={setFilter}
          shown={visible.length}
          total={skills.length}
        />

        <section>
          <h2 className="mb-2 text-sm font-semibold text-foreground">{t("customHeading")}</h2>
          {custom.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              {filtering && customTotal > 0
                ? "No custom skills match this search."
                : canManageSkills
                  ? t("customEmptyAdmin")
                  : t("customEmptyMember")}
            </p>
          ) : (
            <>
              <ul className="space-y-2">{customPagination.pageItems.map(row)}</ul>
              <PaginationControls
                page={customPagination.page}
                totalPages={customPagination.totalPages}
                totalItems={customPagination.totalItems}
                pageSize={customPagination.pageSize}
                onPageChange={customPagination.setPage}
                onPageSizeChange={customPagination.setPageSize}
                itemLabel={t("itemLabelSkills")}
              />
            </>
          )}
        </section>

        <section>
          <h2 className="mb-1 text-sm font-semibold text-foreground">{t("builtInHeading")}</h2>
          <p className="mb-2 text-xs text-muted-foreground">{t("builtInSubtitle")}</p>
          {builtIn.length === 0 && filtering && (
            <p className="mb-2 text-sm text-muted-foreground">{tHC("hcComponentsSkillsPanel.s1")}</p>
          )}
          <ul className="space-y-2">{builtInPagination.pageItems.map(row)}</ul>
          <PaginationControls
            page={builtInPagination.page}
            totalPages={builtInPagination.totalPages}
            totalItems={builtInPagination.totalItems}
            pageSize={builtInPagination.pageSize}
            onPageChange={builtInPagination.setPage}
            onPageSizeChange={builtInPagination.setPageSize}
            itemLabel={t("itemLabelSkills")}
          />
        </section>
      </div>

      <div
        ref={panelRef}
        className="min-w-0 lg:sticky lg:top-24 lg:self-start lg:max-h-[calc(100vh-8rem)] lg:overflow-y-auto"
      >
        {editor.mode === "closed" && (
          <Card>
            <CardContent className="p-6 text-sm text-muted-foreground">{t("selectPrompt")}</CardContent>
          </Card>
        )}

        {editor.mode === "view" && (
          <Card>
            <CardContent className="space-y-4 p-5">
              <SkillViewerToolbar
                detail={editor.detail}
                filePath={editor.filePath}
                canManageSkills={canManageSkills}
                busy={busy}
                onExport={() => doExport(editor.detail.name)}
                onDownloadText={() => doDownloadText(editor.detail, editor.filePath, editor.fileContent)}
                onDuplicate={() => duplicate(editor.detail)}
                onEdit={() => setEditor({ mode: "edit", detail: editor.detail, draft: editor.detail.skillMd })}
                onDelete={() => setDeleting(skills.find((s) => s.name === editor.detail.name) ?? null)}
              />

              {report &&
                report.name === editor.detail.name &&
                skills.find((s) => s.name === report.name)?.enabled === false &&
                (report.status === "NEEDS_REVIEW" ||
                  (report.status === "UNPINNED" && report.scan.riskLevel === "high")) && (
                  <p className="text-xs text-muted-foreground" data-testid="skill-review-off-note">
                    {t("reviewOffNote")}
                  </p>
                )}

              {report &&
                report.name === editor.detail.name &&
                skills.find((s) => s.name === report.name)?.enabled !== false &&
                (report.status === "NEEDS_REVIEW" ||
                  (report.status === "UNPINNED" && report.scan.riskLevel === "high")) && (
                  <SkillReviewBanner
                    report={report}
                    canManage={canManageSkills}
                    busy={busy}
                    onApprove={(ack) => void approveCurrent(ack)}
                    onReject={() => void rejectCurrent()}
                  />
                )}

              {report && report.name === editor.detail.name && (
                <div className="space-y-2 rounded-md border border-border p-3">
                  <p className="text-sm font-medium text-foreground">{t("scanHeading")}</p>
                  {report.source === "BUILT_IN" && (
                    <p className="text-xs text-muted-foreground">{t("scanBuiltInNote")}</p>
                  )}
                  <SkillScanFindings report={report.scan} />
                </div>
              )}

              {report && report.name === editor.detail.name && (
                <SkillUsage attachedAgentIds={report.attachedAgentIds ?? []} />
              )}

              {improvement && canManageSkills && (
                <SkillImprovementReview
                  improvement={improvement}
                  stagedScan={report?.name === editor.detail.name ? report.stagedScan : null}
                  busy={busy}
                  onApply={applyImprovement}
                  onDiscard={discardImprovement}
                />
              )}

              <div className="flex flex-wrap gap-1.5">
                <Button
                  size="sm"
                  variant={editor.filePath === null ? "default" : "outline"}
                  onClick={() => setEditor({ ...editor, filePath: null, fileContent: null })}
                >
                  <FileText aria-hidden="true" size={14} /> SKILL.md
                </Button>
                {editor.detail.files.map((f) => (
                  <Button
                    key={f.path}
                    size="sm"
                    variant={editor.filePath === f.path ? "default" : "outline"}
                    disabled={busy}
                    onClick={() => openFile(editor.detail, f.path)}
                  >
                    {f.path}
                  </Button>
                ))}
              </div>

              <pre className="max-h-[60vh] overflow-auto whitespace-pre-wrap break-words rounded-md border border-border bg-muted/40 p-4 font-mono text-xs leading-relaxed text-foreground">
                {editor.filePath === null ? editor.detail.skillMd : editor.fileContent}
              </pre>
            </CardContent>
          </Card>
        )}

        {editor.mode === "edit" && (
          <SkillEditorForm
            detail={editor.detail}
            draft={editor.draft}
            onDraft={(draft) => setEditor({ ...editor, draft })}
            existingNames={skills.map((s) => s.name)}
            busy={busy}
            onSave={save}
            onCancel={() =>
              setEditor(
                editor.detail
                  ? { mode: "view", detail: editor.detail, filePath: null, fileContent: null }
                  : { mode: "closed" }
              )
            }
          />
        )}
      </div>

      <AlertDialog open={!!deleting} onOpenChange={(o) => !o && setDeleting(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("deleteConfirmTitle", { name: deleting?.name ?? "" })}</AlertDialogTitle>
            <AlertDialogDescription>{t("deleteConfirmBody")}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={busy}>{t("cancelButton")}</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              disabled={busy}
              onClick={(e) => {
                e.preventDefault();
                void doDelete();
              }}
            >
              {t("deleteButton")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <SkillScanDialog prompt={scanPrompt} busy={busy} onCancel={() => setScanPrompt(null)} />

      <AlertDialog open={!!replaceFile} onOpenChange={(o) => !o && setReplaceFile(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("replaceConfirmTitle")}</AlertDialogTitle>
            <AlertDialogDescription>{t("replaceConfirmBody")}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={busy}>{t("cancelButton")}</AlertDialogCancel>
            <AlertDialogAction
              disabled={busy}
              onClick={(e) => {
                e.preventDefault();
                if (replaceFile) void doImport(replaceFile, true);
              }}
            >
              {t("replaceButton")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
