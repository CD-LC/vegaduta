"use client";

// "Import an existing repo" - the create-flow alternative to scaffolding from a
// template. Registers the repo as a project without committing anything into
// it; the builder works on its own app-builder/<id> branch.

import { useId, useState, type FormEvent } from "react";
import { FolderGit2, Loader2 } from "lucide-react";
import { appBuilder } from "./lib";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "../components/ui/card";
import { normalizeSubdir, parseRepoInput } from "./syncLogic";
import { useTranslations } from "../lib/i18n";

export default function ImportRepoCard({ onImported }: { onImported: (projectId: string) => void }) {
    const tHC = useTranslations("hcBuildImportRepoCard");
  const [repo, setRepo] = useState("");
  const [branch, setBranch] = useState("");
  const [subdir, setSubdir] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const uid = useId();

  const parsed = parseRepoInput(repo);
  const sub = normalizeSubdir(subdir);
  const subBad = subdir.trim() !== "" && sub === null;
  const repoBad = repo.trim() !== "" && parsed === null;

  const submit = async () => {
    if (!parsed || subBad || busy) return;
    setBusy(true);
    setError("");
    try {
      const project = await appBuilder.importProject({
        repo_full_name: parsed,
        ...(branch.trim() ? { base_branch: branch.trim() } : {}),
        ...(sub ? { subdir: sub } : {}),
      });
      onImported(project.project_id);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card className="mt-6">
      <CardHeader className="pb-3">
        <CardTitle className="flex items-center gap-2 text-sm">
          <FolderGit2 aria-hidden="true" size={14} /> Or import an existing GitHub repo
        </CardTitle>
      </CardHeader>
      <CardContent>
        <form
          className="space-y-3"
          onSubmit={(e: FormEvent) => {
            e.preventDefault();
            void submit();
          }}
        >
          <p className="text-xs leading-relaxed text-muted-foreground">
            {tHC("hcBuildImportRepoCard.s9")} <span className="font-mono">app-builder/</span> {tHC("hcBuildImportRepoCard.s8")}
          </p>
          <div className="space-y-1">
            <label htmlFor={`${uid}-repo`} className="text-xs font-medium text-foreground">
              Repository
            </label>
            <Input
              id={`${uid}-repo`}
              value={repo}
              onChange={(e) => setRepo(e.target.value)}
              placeholder={tHC("hcBuildImportRepoCard.s7")}
              disabled={busy}
              autoComplete="off"
              aria-invalid={repoBad}
              aria-describedby={repoBad ? `${uid}-repo-err` : undefined}
            />
            {repoBad && (
              <p id={`${uid}-repo-err`} className="text-xs text-destructive">
                {tHC("hcBuildImportRepoCard.s6")}
              </p>
            )}
          </div>
          <div className="grid gap-2 sm:grid-cols-2">
            <div className="space-y-1">
              <label htmlFor={`${uid}-branch`} className="text-xs font-medium text-foreground">
                {tHC("hcBuildImportRepoCard.s5")}
              </label>
              <Input
                id={`${uid}-branch`}
                value={branch}
                onChange={(e) => setBranch(e.target.value)}
                placeholder={tHC("hcBuildImportRepoCard.s4")}
                disabled={busy}
                autoComplete="off"
              />
            </div>
            <div className="space-y-1">
              <label htmlFor={`${uid}-sub`} className="text-xs font-medium text-foreground">
                Sub-folder
              </label>
              <Input
                id={`${uid}-sub`}
                value={subdir}
                onChange={(e) => setSubdir(e.target.value)}
                placeholder={tHC("hcBuildImportRepoCard.s3")}
                disabled={busy}
                autoComplete="off"
                aria-invalid={subBad}
                aria-describedby={subBad ? `${uid}-sub-err` : undefined}
              />
            </div>
          </div>
          {subBad && (
            <p id={`${uid}-sub-err`} className="text-xs text-destructive">
              {tHC("hcBuildImportRepoCard.s2")}
            </p>
          )}
          {error && (
            <p className="text-xs text-destructive" role="alert">
              {error}
            </p>
          )}
          <Button type="submit" size="sm" disabled={busy || !parsed || subBad}>
            {busy ? <Loader2 aria-hidden="true" size={14} className="animate-spin" /> : <FolderGit2 aria-hidden="true" size={14} />} {tHC("hcBuildImportRepoCard.s1")}
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}
