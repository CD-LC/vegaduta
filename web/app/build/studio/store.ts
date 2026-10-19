// Studio projects + version history, kept in THIS browser's localStorage.
// No server, no account needed to keep working - that is the point of an
// on-device studio. Every model edit, design change or manual edit is a new
// version; restore is one click. Caps keep us far under the ~5 MB quota;
// on a quota error the oldest versions are dropped, never the latest.

export type VersionSource = "template" | "device" | "cloud" | "design" | "manual" | "restore" | "connect" | "publish";

export interface AppVersion {
  html: string;
  note: string;
  source: VersionSource;
  at: number;
}

/** An agent or workflow the app can call through its PUBLIC share link. The
 * shareId baked into the app's HTML is the whole credential - the same
 * model the /agent/[shareId] public pages already use (rate-limited,
 * revocable from the resource's share settings). Wired in by the Connect
 * panel; persisted with the project and spliced into every version's HTML
 * by writeConnections(). */
export interface StudioConnection {
  kind: "agent" | "workflow";
  id: string;
  shareId: string;
  name: string;
  /** The name app code calls it by - `vd.call(alias, input)`. Slugified from the name when attached. */
  alias?: string;
  /** What the app's input becomes before the call - `{{input}}`, `{{user.name}}`, `{{user.email}}`, `{{data.<key>}}` placeholders. Empty = input verbatim. */
  requestTemplate?: string;
  /** Which part of the response the app sees - dotted path like `reply`/`resultText`. Empty = platform default. */
  responsePath?: string;
  /** When true (or the app requires sign-in globally) the public call 401s until an end user signs in. */
  requiresAuth?: boolean;
}

/** Where the builder puts authentication: "off" (anyone), "optional" (the
 * app asks when it needs to - `vd.auth.require()` or a gated connection),
 * "required" (the runner shows the sign-in gate before the app loads). */
export type StudioAuthMode = "off" | "optional" | "required";

/** The app's end-user sign-in config. End users are the BUILDER's customers,
 * not console users - they get an app-scoped account created by the runner's
 * own sign-in overlay (passwords never enter the sandboxed app). */
export interface StudioAuthConfig {
  mode: StudioAuthMode;
}

/** The "AI inside the app" block - `vd.ai.chat(prompt)` turns run on the
 * VegaDuta hosted model pool (the app owner's quota), shaped by the owner's
 * system prompt. Only live once the app is published. */
export interface StudioAiConfig {
  enabled: boolean;
  systemPrompt?: string;
}

/** Server-side publication: the app gets a short, stable link
 * (run-app.html#a=slug), working end-user auth, hosted AI, and alias calls.
 * Everything the URL-fragment share can't carry. */
export interface StudioPublish {
  appId: string;
  slug: string;
  publicUrl: string;
  publishedAt: number;
  /** Set when "Take offline" ran - the slug is reserved but the link 404s until republished. */
  offlineAt?: number;
}

export interface StudioProject {
  id: string;
  name: string;
  emoji: string;
  createdAt: number;
  updatedAt: number;
  versions: AppVersion[];
  /** The app's own saved data (its localStorage), mirrored from the preview. */
  appData: Record<string, string>;
  connections: StudioConnection[];
  publish?: StudioPublish | null;
  auth?: StudioAuthConfig | null;
  ai?: StudioAiConfig | null;
  /** Team sharing: the caller's role on a server project. Absent = local-only / owner. */
  role?: CollabRole;
}

/** Role on a shared studio project (mirrors core StudioCollabRules.Role). */
export type CollabRole = "OWNER" | "EDITOR" | "VIEWER";

const KEY = "vd.studio.projects.v1";
export const MAX_VERSIONS = 20;
export const MAX_PROJECTS = 15;

export function loadProjects(): StudioProject[] {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return [];
    const list = JSON.parse(raw);
    return Array.isArray(list)
      ? list
          .filter((p) => p && Array.isArray(p.versions) && p.versions.length > 0)
          .map((p) => ({ connections: [], ...p }))
      : [];
  } catch {
    return [];
  }
}

export interface SaveResult {
  ok: boolean;
  /** Older versions had to be dropped from the SAVED copy to fit the quota. */
  trimmedVersions: boolean;
  /** An app's saved data had to be dropped from the SAVED copy to fit the quota. */
  trimmedData: boolean;
}

/** Persist; on quota errors trim the oldest versions of the largest
 * projects, then (last resort) the saved app data, and retry. The in-memory
 * state is never touched - only what reaches storage. `ok` is false if even
 * that failed. The project list is NOT capped here: silently dropping the
 * oldest app was data loss - callers block creating past MAX_PROJECTS. */
export function saveProjectsDetailed(list: StudioProject[]): SaveResult {
  let working = list.map((p) => ({ ...p, versions: p.versions.slice(-MAX_VERSIONS) }));
  let trimmedVersions = false;
  let trimmedData = false;
  for (let attempt = 0; attempt < 12; attempt++) {
    try {
      localStorage.setItem(KEY, JSON.stringify(working));
      return { ok: true, trimmedVersions, trimmedData };
    } catch {
      if (attempt < 6) {
        const before = working.reduce((n, p) => n + p.versions.length, 0);
        working = working.map((p) => ({ ...p, versions: p.versions.slice(-Math.max(1, Math.floor(p.versions.length / 2))) }));
        if (working.reduce((n, p) => n + p.versions.length, 0) < before) trimmedVersions = true;
      } else {
        // Versions are down to one each - the saved app data (a mirror of the
        // preview's localStorage) is the only thing left to shed, biggest first.
        let big = -1;
        let bigSize = 0;
        working.forEach((p, i) => {
          const n = JSON.stringify(p.appData || {}).length;
          if (n > bigSize && n > 2) {
            big = i;
            bigSize = n;
          }
        });
        if (big < 0) break;
        working = working.map((p, i) => (i === big ? { ...p, appData: {} } : p));
        trimmedData = true;
      }
    }
  }
  return { ok: false, trimmedVersions, trimmedData };
}

/** Boolean form: true when everything was saved (possibly after trimming). */
export function saveProjects(list: StudioProject[]): boolean {
  return saveProjectsDetailed(list).ok;
}

/** A person-readable note for a save that was trimmed or failed; null when clean. */
export function saveNotice(r: SaveResult): string | null {
  if (!r.ok)
    return "This browser's storage is full, so your latest changes are NOT saved on this device. Export your app (Share tab, .html file) or delete an app you no longer need.";
  if (r.trimmedData) return "Storage was nearly full: an app's saved data was dropped from this device's copy to make room. Delete unused apps to free space.";
  if (r.trimmedVersions) return "Storage was nearly full: older versions were dropped from this device's saved copy. Delete unused apps to free space.";
  return null;
}

export function newProject(name: string, emoji: string, html: string, note: string, source: VersionSource): StudioProject {
  const now = Date.now();
  return {
    id: `sa-${now.toString(36)}-${Math.random().toString(36).slice(2, 6)}`,
    name,
    emoji,
    createdAt: now,
    updatedAt: now,
    versions: [{ html, note, source, at: now }],
    appData: {},
    connections: [],
  };
}

export function addVersion(p: StudioProject, html: string, note: string, source: VersionSource): StudioProject {
  const last = p.versions[p.versions.length - 1];
  if (last && last.html === html) return p;
  const versions = [...p.versions, { html, note, source, at: Date.now() }].slice(-MAX_VERSIONS);
  return { ...p, versions, updatedAt: Date.now() };
}

export function currentHtml(p: StudioProject): string {
  return p.versions[p.versions.length - 1].html;
}

/** Pull the <title> for naming shared/exported apps. */
export function titleOf(html: string, fallback = "My app"): string {
  const m = html.match(/<title[^>]*>([^<]{1,80})<\/title>/i);
  return (m ? m[1].trim() : "") || fallback;
}
