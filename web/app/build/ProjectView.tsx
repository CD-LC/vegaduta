// @ts-nocheck
"use client";

// Split out of page.tsx (behaviour unchanged).

import { useEffect, useState } from "react";
import { Loader2, RotateCw, Timer, Archive, Infinity as InfinityIcon, Download } from "lucide-react";
import { Button } from "../components/ui/button";
import { Badge } from "../components/ui/badge";
import { Card, CardContent } from "../components/ui/card";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "../components/ui/tabs";
import StoreReadiness from "./StoreReadiness";
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
import { useTranslations } from "../lib/i18n";
import { appBuilder } from "./lib";
import SyncPanel from "./SyncPanel";
import SandboxPreview from "./SandboxPreview";
import { downloadZip } from "./zip";
import { BUILD_STRINGS as S } from "./buildStrings";
import ChatPane from "./ChatPane";
import DesignPanel from "./DesignPanel";
import GithubActivityPane from "./GithubActivityPane";
import BackendPanel from "./BackendPanel";

/** "expires in 5h" / "expires in 3d" / "expired" for an ephemeral project's
 * expires_at (epoch seconds). */
function expiryLabel(expiresAt, t) {
  const ms = expiresAt * 1000 - Date.now();
  if (ms <= 0) return t("expiryExpired");
  const h = Math.round(ms / 3_600_000);
  return h < 48 ? t("expiryHours", { h }) : t("expiryDays", { d: Math.round(h / 24) });
}
/* ------------------------------------------------------------------ */
/* Project view: chat (left) + preview/backend (right)                */

export function ProjectView({
  project,
  setProject,
  onDeleted,
  onError,
  onClearError,
  onProjectsChanged,
  githubStatus,
}) {
  const t = useTranslations("appBuilder");
  const [tab, setTab] = useState("preview");
  // The project's file set: fetched once on open, then merged with every
  // generate/apply response — feeds SandboxPreview's instant render so the
  // design loop doesn't wait on Actions/Pages.
  const [fileSet, setFileSet] = useState(null);
  const [assets, setAssets] = useState([]);
  const [chatPrefill, setChatPrefill] = useState({ text: "", nonce: 0 });
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [deletingProject, setDeletingProject] = useState(false);
  const [lifecycleBusy, setLifecycleBusy] = useState(null); // "archive" | "extend" | "keep"
  const [fixRequest, setFixRequest] = useState(null);
  const [chatBusy, setChatBusy] = useState(false);

  useEffect(() => {
    setFileSet(null);
    setAssets([]);
    appBuilder
      .listFiles(project.project_id)
      .then((r) => setFileSet(r?.files || []))
      .catch(() => setFileSet([]));
    appBuilder
      .listAssets(project.project_id)
      .then((r) => setAssets(r?.assets || []))
      .catch(() => setAssets([]));
  }, [project.project_id]);

  const mergeFiles = (files) => {
    if (!files?.length) return;
    setFileSet((cur) => {
      const map = new Map((cur || []).map((f) => [f.path, f]));
      for (const f of files) map.set(f.path, f);
      return [...map.values()];
    });
  };

  const refreshAssets = () =>
    appBuilder
      .listAssets(project.project_id)
      .then((r) => setAssets(r?.assets || []))
      .catch(() => {});

  // Disposable-app lifecycle: archive/extend/keep return the updated project
  // dict, so a successful call refreshes the header in place - no refetch.
  const runLifecycle = async (op) => {
    setLifecycleBusy(op);
    try {
      const fn =
        op === "extend"
          ? () => appBuilder.extendProject(project.project_id, 72)
          : op === "keep"
            ? () => appBuilder.keepProject(project.project_id)
            : () => appBuilder.archiveProject(project.project_id);
      const updated = await fn();
      if (updated) setProject(updated);
      onClearError?.();
      // The project selector's labels (archived / ephemeral) come from the list.
      onProjectsChanged?.();
    } catch (e) {
      onError(e.message);
    } finally {
      setLifecycleBusy(null);
    }
  };

  const archived = !!project.archived_at;
  const ephemeral = project.lifecycle === "ephemeral" && !archived;

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Badge variant="secondary">{project.template_id}</Badge>
        <span className="font-mono text-xs text-muted-foreground">{project.project_id}</span>
        {project.status && <Badge variant="outline">{project.status}</Badge>}
        {ephemeral && (
          <Badge variant="outline" className="gap-1">
            <Timer size={11} aria-hidden="true" />
            {t("lifecycleEphemeral")}
            {project.expires_at ? ` · ${expiryLabel(project.expires_at, t)}` : ""}
          </Badge>
        )}
        {archived && (
          <Badge variant="secondary" className="gap-1">
            <Archive size={11} aria-hidden="true" /> {t("lifecycleArchived")}
          </Badge>
        )}
        <div className="ms-auto flex flex-wrap items-center gap-1.5">
          <Button
            variant="outline"
            size="sm"
            disabled={!fileSet?.length}
            title="Download every project file as a .zip"
            onClick={() => downloadZip(project.project_id, fileSet || [])}
          >
            <Download aria-hidden="true" size={13} /> Download .zip
          </Button>
          {ephemeral && (
            <>
              <Button variant="outline" size="sm" disabled={!!lifecycleBusy} onClick={() => runLifecycle("extend")}>
                {lifecycleBusy === "extend" ? (
                  <Loader2 aria-hidden="true" size={13} className="animate-spin" />
                ) : (
                  <Timer aria-hidden="true" size={13} />
                )}
                {t("extendButton")}
              </Button>
              <Button variant="outline" size="sm" disabled={!!lifecycleBusy} onClick={() => runLifecycle("keep")}>
                {lifecycleBusy === "keep" ? (
                  <Loader2 aria-hidden="true" size={13} className="animate-spin" />
                ) : (
                  <InfinityIcon aria-hidden="true" size={13} />
                )}
                {t("keepButton")}
              </Button>
            </>
          )}
          {archived ? (
            <Button variant="outline" size="sm" disabled={!!lifecycleBusy} onClick={() => runLifecycle("keep")}>
              {lifecycleBusy === "keep" ? (
                <Loader2 aria-hidden="true" size={13} className="animate-spin" />
              ) : (
                <RotateCw aria-hidden="true" size={13} />
              )}
              {t("restoreButton")}
            </Button>
          ) : (
            <Button variant="ghost" size="sm" disabled={!!lifecycleBusy} onClick={() => runLifecycle("archive")}>
              <Archive aria-hidden="true" size={13} /> {t("archiveButton")}
            </Button>
          )}
          <Button
            variant="ghost"
            size="sm"
            className="text-destructive hover:bg-destructive/10 hover:text-destructive"
            onClick={() => setConfirmDelete(true)}
          >
            {t("deleteProjectButton")}
          </Button>
        </div>
      </div>

      {archived && (
        <Alert className="mb-4">
          <Archive size={14} aria-hidden="true" />
          <AlertDescription>{t("archivedBanner")}</AlertDescription>
        </Alert>
      )}

      <AlertDialog open={confirmDelete} onOpenChange={(open) => !open && setConfirmDelete(false)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{S.deleteProjectTitle(project.project_id)}</AlertDialogTitle>
            <AlertDialogDescription>{S.deleteProjectBody}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deletingProject}>{S.cancel}</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              disabled={deletingProject}
              onClick={(e) => {
                e.preventDefault();
                setDeletingProject(true);
                appBuilder
                  .deleteProject(project.project_id)
                  .then(() => onDeleted(project.project_id))
                  .catch((err) => onError(err.message))
                  .finally(() => {
                    setDeletingProject(false);
                    setConfirmDelete(false);
                  });
              }}
            >
              {deletingProject ? S.deleting : t("deleteProjectButton")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <div className="grid gap-4 lg:grid-cols-2 lg:items-start">
        <ChatPane
          project={project}
          setProject={setProject}
          onError={onError}
          onClearError={onClearError}
          onFiles={mergeFiles}
          assets={assets}
          onAssetsChanged={refreshAssets}
          fixRequest={fixRequest}
          onBusyChange={setChatBusy}
          prefill={chatPrefill}
          fileSet={fileSet}
          disabled={archived}
        />

        <div>
          <Tabs value={tab} onValueChange={setTab} className="mb-3">
            <TabsList>
              <TabsTrigger value="preview">{t("tabPreview")}</TabsTrigger>
              <TabsTrigger value="design">{S.tabDesign}</TabsTrigger>
              <TabsTrigger value="github">{S.tabGithub}</TabsTrigger>
              <TabsTrigger value="backend">{t("tabBackend")}</TabsTrigger>
              {project.template_id === "capacitor-app" && <TabsTrigger value="store">{S.tabStore}</TabsTrigger>}
            </TabsList>
            <TabsContent value="preview">
              <Card className="h-[min(560px,85dvh)]">
                <CardContent className="h-full p-3">
                  <SandboxPreview
                    files={fileSet}
                    pagesUrl={project?.github?.pages_url || null}
                    fixDisabled={chatBusy || archived}
                    onFix={(text) => setFixRequest({ id: Date.now(), text })}
                  />
                </CardContent>
              </Card>
            </TabsContent>
            <TabsContent value="design">
              <DesignPanel
                project={project}
                fileSet={fileSet}
                onFiles={mergeFiles}
                assets={assets}
                onAssetsChanged={refreshAssets}
                onError={onError}
                disabled={archived}
                onUseAsset={(text) => setChatPrefill((c) => ({ text, nonce: c.nonce + 1 }))}
              />
            </TabsContent>
            <TabsContent value="github">
              <GithubActivityPane project={project} githubStatus={githubStatus} />
              <SyncPanel project={project} onError={onError} />
            </TabsContent>
            {project.template_id === "capacitor-app" && (
              <TabsContent value="store">
                <StoreReadiness project={project} assets={assets} onError={onError} />
              </TabsContent>
            )}
            <TabsContent value="backend">
              <BackendPanel project={project} setProject={setProject} onError={onError} fileSet={fileSet} />
            </TabsContent>
          </Tabs>
        </div>
      </div>
    </div>
  );
}
