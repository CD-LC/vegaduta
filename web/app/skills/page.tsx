"use client";

// /skills - Agent Skills: packaged expert instructions (a SKILL.md plus optional
// bundled files) that agents load on demand. Built-in skills ship with the
// platform; custom ones belong to this workspace.
//
// Page/panel split follows /secrets: this file owns fetching and route
// readiness, SkillsPanel owns the list, the editor and every mutation, and
// calls onChanged() after a write so the list always reflects what the server
// actually stored (an import can rename nothing but can change description,
// file count and enabled state in one go).

import { useCallback, useEffect, useState } from "react";
import { api, type SkillSummary } from "../lib/api";
import AppShell from "../components/AppShell";
import { usePageReady } from "../components/RouteTransition";
import PageHeader from "../components/ax/PageHeader";
import SkillsPanel from "../components/SkillsPanel";
import SkillRegistryBrowser from "../components/SkillRegistryBrowser";
import SkillStarters from "../components/skills/SkillStarters";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Button } from "../components/ui/button";
import { Skeleton } from "../components/ui/skeleton";
import { useTranslations } from "../lib/i18n";

export default function SkillsPage() {
  const t = useTranslations("skillsPage");
  const [skills, setSkills] = useState<SkillSummary[]>([]);
  const [loaded, setLoaded] = useState(false);
  // A failed load used to be indistinguishable from "this workspace has no skills": the page showed
  // empty lists and the admin could not tell. Keep the last good list on a failed refresh and say so.
  const [loadFailed, setLoadFailed] = useState(false);

  const refresh = useCallback(() => {
    return api
      .listSkills()
      .then((list) => {
        setSkills(Array.isArray(list) ? list : []);
        setLoadFailed(false);
      })
      .catch(() => setLoadFailed(true))
      .finally(() => setLoaded(true));
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  // Once, unconditionally, before any early return - rules of hooks, and the
  // convention every gated page in this app follows.
  usePageReady(loaded);

  return (
    <AppShell eyebrow={t("pageEyebrow")}>
      <PageHeader title={t("pageTitle")} subtitle={t("pageSubtitle")} />
      {loadFailed && (
        <Alert variant="destructive" className="mb-6" data-testid="skills-load-failed">
          <AlertDescription className="flex flex-wrap items-center gap-3">
            <span>Could not load your skills. {skills.length > 0 ? "Showing the last list we have." : "Nothing below reflects what is stored."}</span>
            <Button size="sm" variant="outline" onClick={() => void refresh()}>
              Try again
            </Button>
          </AlertDescription>
        </Alert>
      )}
      {!loaded ? (
        <div className="space-y-3" aria-busy="true" aria-label="Loading skills" data-testid="skills-loading">
          <Skeleton className="h-10 w-full" />
          <Skeleton className="h-20 w-full" />
          <Skeleton className="h-20 w-full" />
        </div>
      ) : (
        <>
          <SkillStarters skills={skills} />
          {(!loadFailed || skills.length > 0) && <SkillsPanel skills={skills} onChanged={refresh} />}
        </>
      )}
      <SkillRegistryBrowser installed={skills} onChanged={refresh} />
    </AppShell>
  );
}
