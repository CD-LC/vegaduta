// @ts-nocheck
"use client";

// Split out of page.tsx (behaviour unchanged).
import Link from "next/link";
import { ExternalLink, GitBranch, GitPullRequest } from "lucide-react";
import { Github } from "../components/lucideBrandIcons";
import { Button } from "../components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "../components/ui/card";

/* ------------------------------------------------------------------ */
/* GitHub activity — replaces the live-preview iframe (BYO-repo pivot,       */
/* docs/APP-BUILDER-BYO-REPO-CONTRACT-2026-07-30.md §4). Build/preview now   */
/* runs on the tenant's own GitHub (and their own Actions minutes, if they   */
/* add a workflow) — not a container this platform runs. No iframe, no      */
/* start/stop controls; just the branch + PR this project's chat turns have */
/* produced, and an honest prompt to connect a repo if none is yet.         */

export default function GithubActivityPane({ project, githubStatus }) {
  const activity = project?.github || null;
  const connected = !!githubStatus?.connected;

  return (
    <Card className="h-[min(560px,85dvh)]">
      <CardHeader className="pb-3">
        <CardTitle className="flex items-center gap-2 text-sm">
          <span className="cat-chip cat-chip-sm" style={{ ["--cat" as string]: "var(--cat-automation)" }}>
            <Github aria-hidden="true" size={13} />
          </span>
          Build on GitHub
        </CardTitle>
      </CardHeader>
      <CardContent>
        <p className="mb-4 text-sm leading-relaxed text-muted-foreground">
          Build and preview run on your own GitHub repo and Actions minutes — not on this platform. Apps with
          a buildable web output get a free GitHub Pages preview on every push to the app-builder branch.
        </p>

        <details className="mb-4 rounded-lg border border-border bg-muted/30 p-3 text-xs leading-relaxed text-muted-foreground">
          <summary className="cursor-pointer font-medium text-foreground">
            How the preview works — and what to do when it 404s
          </summary>
          <ol className="mt-2 list-decimal space-y-1.5 pl-4">
            <li>
              Every change you ask for is committed to the{" "}
              <code className="rounded bg-muted px-1 py-0.5">app-builder/&lt;project&gt;</code> branch. The
              repo&apos;s own Actions workflow builds the app and deploys it to{" "}
              <span className="font-medium text-foreground">GitHub Pages</span> — the link below.
            </li>
            <li>
              The first deploy takes a minute or two — the link <span className="font-medium text-foreground">404s
              until that run finishes</span>. Watch it under the repo&apos;s{" "}
              <span className="font-medium text-foreground">Actions → pages</span> job.
            </li>
            <li>
              Still 404 after that? Pages isn&apos;t enabled on the repo (the connected token can&apos;t switch it
              on itself). Fix once: repo <span className="font-medium text-foreground">Settings → Pages → Source:
              GitHub Actions</span>, then <span className="font-medium text-foreground">Actions → the failed run →
              Re-run failed jobs</span>. Or disconnect and reconnect here with a token that also has{" "}
              <span className="font-medium text-foreground">Pages: Read and write</span> — we&apos;ll enable it for
              you automatically.
            </li>
            <li>
              Only pushes to the app-builder branch publish the preview — commits elsewhere don&apos;t touch it.
              Merging or closing the PR does not affect the preview either.
            </li>
          </ol>
        </details>

        {!connected ? (
          <div className="rounded-lg border border-border bg-muted/30 p-4 text-center">
            <p className="mb-3 text-sm text-muted-foreground">
              Connect a GitHub repo to see branches and pull requests here.
            </p>
            <Button asChild size="sm">
              <Link href="/build/github">
                <Github aria-hidden="true" size={14} /> Connect GitHub
              </Link>
            </Button>
          </div>
        ) : !activity ? (
          <div className="rounded-lg border border-border bg-muted/30 p-4 text-sm text-muted-foreground">
            Connected to <span className="font-mono text-foreground">{githubStatus.repo_full_name}</span>. Ask
            for a change in the chat and it will push a branch and open a pull request here.
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
                <span className="text-xs text-muted-foreground">Branch pushed</span>
              </div>
            )}
            {activity.pr_url ? (
              <div className="flex items-center gap-2">
                <span className="state-pill" style={{ ["--state" as string]: "var(--state-success)" }}>
                  <span className="state-dot" /> PR opened
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
                Waiting on a pull request for this branch — it will appear here once opened.
              </p>
            )}
            {activity.pages_url && (
              <div className="flex items-center gap-2">
                <span className="state-pill" style={{ ["--state" as string]: "var(--state-success)" }}>
                  <span className="state-dot" /> Pages preview
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
                    (404s until Pages is enabled — see &quot;How the preview works&quot; above, step 3)
                  </span>
                )}
              </div>
            )}
            {activity.pushed_at && (
              <p className="text-xs text-muted-foreground">
                Last pushed {new Date(activity.pushed_at).toLocaleString()}
              </p>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
