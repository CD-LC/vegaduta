// @ts-nocheck
"use client";

// Split out of page.tsx (behaviour unchanged).
import Link from "next/link";
import { ExternalLink, GitBranch, GitPullRequest } from "lucide-react";
import { Github } from "../components/lucideBrandIcons";
import { Button } from "../components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "../components/ui/card";
import { useTranslations } from "../lib/i18n";

/* ------------------------------------------------------------------ */
/* GitHub activity — replaces the live-preview iframe (BYO-repo pivot,       */
/* docs/APP-BUILDER-BYO-REPO-CONTRACT-2026-07-30.md §4). Build/preview now   */
/* runs on the tenant's own GitHub (and their own Actions minutes, if they   */
/* add a workflow) — not a container this platform runs. No iframe, no      */
/* start/stop controls; just the branch + PR this project's chat turns have */
/* produced, and an honest prompt to connect a repo if none is yet.         */

export default function GithubActivityPane({ project, githubStatus }) {
    const tHC = useTranslations("hcBuildGithubActivityPane");
  const activity = project?.github || null;
  const connected = !!githubStatus?.connected;

  return (
    <Card className="h-[min(560px,85dvh)]">
      <CardHeader className="pb-3">
        <CardTitle className="flex items-center gap-2 text-sm">
          <span className="cat-chip cat-chip-sm" style={{ ["--cat" as string]: "var(--cat-automation)" }}>
            <Github aria-hidden="true" size={13} />
          </span>
          {tHC("hcBuildGithubActivityPane.s28")}
        </CardTitle>
      </CardHeader>
      <CardContent>
        <p className="mb-4 text-sm leading-relaxed text-muted-foreground">
          {tHC("hcBuildGithubActivityPane.s27")}
        </p>

        <details className="mb-4 rounded-lg border border-border bg-muted/30 p-3 text-xs leading-relaxed text-muted-foreground">
          <summary className="cursor-pointer font-medium text-foreground">
            {tHC("hcBuildGithubActivityPane.s26")}
          </summary>
          <ol className="mt-2 list-decimal space-y-1.5 ps-4">
            <li>
              {tHC("hcBuildGithubActivityPane.s25")}{" "}
              <code className="rounded bg-muted px-1 py-0.5">app-builder/&lt;project&gt;</code> {tHC("hcBuildGithubActivityPane.s24")}{" "}
              <span className="font-medium text-foreground">{tHC("hcBuildGithubActivityPane.s23")}</span> {tHC("hcBuildGithubActivityPane.s22")}
            </li>
            <li>
              {tHC("hcBuildGithubActivityPane.s21")}{" "}
              <span className="font-medium text-foreground">{tHC("hcBuildGithubActivityPane.s20")}</span>{tHC("hcBuildGithubActivityPane.s19")} <span className="font-medium text-foreground">{tHC("hcBuildGithubActivityPane.s18")}</span> job.
            </li>
            <li>
              {tHC("hcBuildGithubActivityPane.s17")}{" "}
              <span className="font-medium text-foreground">{tHC("hcBuildGithubActivityPane.s16")}</span>, then{" "}
              <span className="font-medium text-foreground">{tHC("hcBuildGithubActivityPane.s15")}</span>{tHC("hcBuildGithubActivityPane.s14")}{" "}
              <span className="font-medium text-foreground">{tHC("hcBuildGithubActivityPane.s13")}</span> {tHC("hcBuildGithubActivityPane.s12")}
            </li>
            <li>
              {tHC("hcBuildGithubActivityPane.s11")}
            </li>
          </ol>
        </details>

        {!connected ? (
          <div className="rounded-lg border border-border bg-muted/30 p-4 text-center">
            <p className="mb-3 text-sm text-muted-foreground">
              {tHC("hcBuildGithubActivityPane.s10")}
            </p>
            <Button asChild size="sm">
              <Link href="/build/github">
                <Github aria-hidden="true" size={14} /> {tHC("hcBuildGithubActivityPane.s9")}
              </Link>
            </Button>
          </div>
        ) : !activity ? (
          <div className="rounded-lg border border-border bg-muted/30 p-4 text-sm text-muted-foreground">
            {tHC("hcBuildGithubActivityPane.s8")} <span className="font-mono text-foreground">{githubStatus.repo_full_name}</span>{tHC("hcBuildGithubActivityPane.s7")}
          </div>
        ) : (
          <div className="space-y-3">
            <div className="flex items-center gap-2">
              <span className="font-mono text-sm text-foreground">{githubStatus.repo_full_name}</span>
            </div>
            {activity.branch && (
              <div className="flex items-center gap-2">
                <span className="state-pill" style={{ ["--state" as string]: "var(--state-queued)" }}>
                  <GitBranch aria-hidden="true" size={12} /> {activity.branch}
                </span>
                <span className="text-xs text-muted-foreground">{tHC("hcBuildGithubActivityPane.s6")}</span>
              </div>
            )}
            {activity.pr_url ? (
              <div className="flex items-center gap-2">
                <span className="state-pill" style={{ ["--state" as string]: "var(--state-success)" }}>
                  <span className="state-dot" /> {tHC("hcBuildGithubActivityPane.s5")}
                </span>
                <a
                  href={activity.pr_url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-1 text-sm font-medium text-primary hover:underline"
                >
                  <GitPullRequest aria-hidden="true" size={13} />
                  {activity.pr_number ? `View PR #${activity.pr_number}` : "View PR on GitHub"}
                  <ExternalLink aria-hidden="true" size={11} />
                </a>
              </div>
            ) : (
              <p className="text-xs text-muted-foreground">
                {tHC("hcBuildGithubActivityPane.s4")}
              </p>
            )}
            {activity.pages_url && (
              <div className="flex items-center gap-2">
                <span className="state-pill" style={{ ["--state" as string]: "var(--state-success)" }}>
                  <span className="state-dot" /> {tHC("hcBuildGithubActivityPane.s3")}
                </span>
                <a
                  href={activity.pages_url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-1 text-sm font-medium text-primary hover:underline"
                >
                  {activity.pages_url} <ExternalLink aria-hidden="true" size={11} />
                </a>
                {activity.pages_enabled === false && (
                  <span className="text-xs text-muted-foreground">
                    {tHC("hcBuildGithubActivityPane.s2")}
                  </span>
                )}
              </div>
            )}
            {activity.pushed_at && (
              <p className="text-xs text-muted-foreground">
                {tHC("hcBuildGithubActivityPane.s1")} {new Date(activity.pushed_at).toLocaleString()}
              </p>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
