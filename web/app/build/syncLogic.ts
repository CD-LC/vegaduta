// Pure (DOM-free) helpers + types for the GitHub import/sync UI
// (SyncPanel.tsx, ImportRepoCard.tsx). Kept separate from lib.ts so they are
// unit-testable without the auth/fetch layer.

export interface AppBuilderSyncStatus {
  base_branch: string;
  branch: string;
  status: string;
  ahead_by: number;
  behind_by: number;
  up_to_date: boolean;
  can_pull: boolean;
  diverged: boolean;
}

export interface AppBuilderSyncPullResult {
  merged: boolean;
  up_to_date: boolean;
  sha: string | null;
  reindexed: boolean;
}

export interface AppBuilderExternalCommit {
  sha: string;
  message: string;
  author: string;
  date: string;
}

export interface AppBuilderExternalEdits {
  branch: string;
  edited_outside: boolean;
  count: number;
  commits: AppBuilderExternalCommit[];
  truncated: boolean;
}

export interface ImportRepoRequest {
  repo_full_name: string;
  base_branch?: string;
  subdir?: string;
  project_id?: string;
}

export interface SyncChip {
  key: string;
  label: string;
  tone: "ok" | "warn" | "info";
}

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

/** Status chips for the panel: what the branch looks like vs its base. */
export function syncChips(s: AppBuilderSyncStatus | null | undefined): SyncChip[] {
  if (!s) return [];
  const chips: SyncChip[] = [];
  if (s.behind_by > 0) {
    chips.push({
      key: "behind",
      label: `${plural(s.behind_by, "commit", "commits")} behind ${s.base_branch}`,
      tone: "warn",
    });
  }
  if (s.ahead_by > 0) {
    chips.push({
      key: "ahead",
      label: `${plural(s.ahead_by, "commit", "commits")} ahead`,
      tone: "info",
    });
  }
  if (s.behind_by === 0) {
    chips.push({ key: "current", label: `Up to date with ${s.base_branch}`, tone: "ok" });
  }
  if (s.diverged) chips.push({ key: "diverged", label: "Diverged", tone: "warn" });
  return chips;
}

/** Conflicted paths from a failed pull. `err` is what lib.ts's call() throws:
 * an Error carrying `status` and the parsed response `body`. Returns null when
 * the error is not a merge conflict. */
export function conflictPaths(err: unknown): string[] | null {
  const e = err as { status?: number; body?: { conflicted_paths?: unknown } } | null;
  if (!e || e.status !== 409) return null;
  const paths = e.body?.conflicted_paths;
  if (!Array.isArray(paths)) return null;
  return paths.filter((p): p is string => typeof p === "string");
}

/** One line for the "edited outside the builder" notice; "" when none. */
export function externalEditsLine(e: AppBuilderExternalEdits | null | undefined): string {
  if (!e || !e.edited_outside || e.count === 0) return "";
  const who = Array.from(new Set(e.commits.map((c) => c.author).filter(Boolean)));
  const by = who.length ? ` by ${who.slice(0, 2).join(", ")}${who.length > 2 ? " and others" : ""}` : "";
  return `${plural(e.count, "commit", "commits")}${e.truncated ? "+" : ""} edited outside the builder${by}.`;
}

/** Accepts "owner/repo", a github.com URL (https or ssh), with optional .git
 * or trailing path. Returns "owner/repo" or null. Other hosts (gitlab.com/...)
 * are rejected. Mirrors the backend's repo-name pattern; the backend
 * re-validates. */
export function parseRepoInput(input: string): string | null {
  let s = (input || "").trim();
  if (!s) return null;
  s = s.replace(/^(?:ssh:\/\/)?git@github\.com[:/]/i, "").replace(/^(https?:\/\/)?(www\.)?github\.com\//i, "");
  // Anything still carrying a scheme is some other host.
  if (/^[a-z][a-z0-9+.-]*:\/\//i.test(s) || /^[^/]*@/.test(s)) return null;
  s = s.replace(/[?#].*$/, "").replace(/\/+$/, "");
  const parts = s.split("/");
  if (parts.length < 2) return null;
  const owner = parts[0];
  const repo = parts[1].replace(/\.git$/i, "");
  // GitHub owners are alphanumeric + hyphen (no dots), so "gitlab.com/x" fails here.
  if (!/^[A-Za-z0-9][A-Za-z0-9-]{0,38}$/.test(owner)) return null;
  if (!/^[A-Za-z0-9._-]{1,100}$/.test(repo) || repo === "." || repo === "..") return null;
  return `${owner}/${repo}`;
}

/** Normalise a subdir the user typed ("/apps/web/" -> "apps/web"); null if
 * empty, undefined-safe. Rejects path escapes by returning null. */
export function normalizeSubdir(input: string | undefined): string | null {
  const segs = (input || "").trim().replace(/\\/g, "/").split("/").filter(Boolean);
  if (segs.length === 0) return null;
  if (segs.some((p) => p === ".." || p === ".")) return null;
  return segs.join("/");
}

/** True when the project was imported from an existing repo. */
export function isImported(project: { github?: Record<string, unknown> | null } | null | undefined): boolean {
  return !!(project?.github && (project.github as { imported?: boolean }).imported);
}
