// Server mirror for Studio projects + version history (core /api/studio-projects).
//
// The studio stays LOCAL-FIRST: localStorage is still the working copy and
// nothing here blocks or fails an edit. In the background we push new versions
// up and, on open, pull whatever this device is missing - so history survives
// a cleared browser, a quota trim or a new phone. Every failure (signed out,
// offline, older core without the endpoint) is swallowed; the next save retries.
//
// Deliberately not synced: `appData` (the app's own runtime data mirrored from
// the preview - large, and belongs to the app's users, not the builder).
// Deleting on one device does not tombstone: a device that still holds the
// project will re-push it.

import { apiFetch, jsonBody } from "../../lib/api/core";
import { MAX_VERSIONS, type AppVersion, type CollabRole, type StudioProject, type VersionSource } from "./store";
import { canEdit } from "./collab";

export interface RemoteVersion {
  at: number;
  note: string | null;
  source: string;
  html: string;
  /** Who saved it (Keycloak subject); null for pre-collaboration versions. */
  authorSubject?: string | null;
}

export interface RemoteProject {
  id: string;
  name: string;
  emoji: string | null;
  settings: string | null;
  createdAt: number;
  updatedAt: number;
  versionCount: number;
  versions?: RemoteVersion[] | null;
  /** The caller's role on this project (absent on an older core = owner). */
  role?: CollabRole;
}

const SYNC_KEY = "vd.studio.sync.v1";
const PUSH_DEBOUNCE_MS = 2500;
const BACKOFF_MS = 60_000;

interface SyncEntry {
  /** Newest local version timestamp the server has acknowledged. */
  pushedAt: number;
  /** Signature of the settings blob last pushed. */
  sig: string;
  /** The server's updatedAt we last saw for this project. */
  remoteUpdatedAt: number;
  /** Role on a project shared with us; absent for our own. */
  role?: CollabRole;
  /** The owner stopped sharing with us (PUT 404) - stop pushing. */
  revoked?: boolean;
}
type SyncState = Record<string, SyncEntry>;

const SOURCES: VersionSource[] = ["template", "device", "cloud", "design", "manual", "restore", "connect", "publish"];

/** The parts of a project that ride along as opaque settings JSON. */
export function settingsOf(p: StudioProject): string {
  return JSON.stringify({
    connections: p.connections ?? [],
    publish: p.publish ?? null,
    auth: p.auth ?? null,
    ai: p.ai ?? null,
  });
}

/** Cheap stable signature - only used to detect "settings changed". */
export function sigOf(s: string): string {
  let h = 5381;
  for (let i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) | 0;
  return `${s.length}:${h}`;
}

/** What "needs pushing besides new versions" means: name, emoji or settings changed. */
export function pushSigOf(p: StudioProject): string {
  return sigOf(`${p.name}\n${p.emoji}\n${settingsOf(p)}`);
}

function toVersion(v: RemoteVersion): AppVersion {
  const source = (SOURCES as string[]).includes(v.source) ? (v.source as VersionSource) : "manual";
  return { html: v.html, note: v.note ?? "", source, at: v.at };
}

function parseSettings(raw: string | null): Partial<StudioProject> {
  if (!raw) return {};
  try {
    const s = JSON.parse(raw);
    return {
      connections: Array.isArray(s.connections) ? s.connections : [],
      publish: s.publish ?? null,
      auth: s.auth ?? null,
      ai: s.ai ?? null,
    };
  } catch {
    return {};
  }
}

/** Union the two histories by timestamp (newest MAX_VERSIONS kept), and take
 * name/emoji/settings from whichever side was edited last. Never loses a local
 * version, so it is safe to apply even if the user edited during the pull. */
export function mergeProject(local: StudioProject | null, remote: RemoteProject): StudioProject {
  const remoteVersions = (remote.versions ?? []).map(toVersion);
  const role = remote.role && remote.role !== "OWNER" ? remote.role : undefined;
  const byAt = new Map<number, AppVersion>();
  for (const v of remoteVersions) byAt.set(v.at, v);
  // A viewer cannot edit, so the server copy is authoritative: never keep
  // local-only versions (they could not be pushed and would only drift).
  if (role !== "VIEWER") for (const v of local?.versions ?? []) byAt.set(v.at, v); // local wins on a tie
  const versions = [...byAt.values()].sort((a, b) => a.at - b.at).slice(-MAX_VERSIONS);

  // Non-owners cannot change name/settings, so the owner's copy always wins for them.
  const remoteNewer = !local || role !== undefined || remote.updatedAt > local.updatedAt;
  const base: StudioProject = local ?? {
    id: remote.id,
    name: remote.name,
    emoji: remote.emoji ?? "✨",
    createdAt: remote.createdAt,
    updatedAt: remote.updatedAt,
    versions: [],
    appData: {},
    connections: [],
  };
  return {
    ...base,
    ...(remoteNewer ? { name: remote.name, emoji: remote.emoji ?? base.emoji, ...parseSettings(remote.settings) } : {}),
    role,
    versions,
    updatedAt: Math.max(base.updatedAt, versions[versions.length - 1]?.at ?? 0),
  };
}

/** Merge fetched remote details into the local list; unknown remotes are
 * appended (newest first). Projects with no versions are dropped, matching
 * loadProjects(). */
export function mergeAll(local: StudioProject[], remotes: RemoteProject[]): StudioProject[] {
  const byId = new Map(local.map((p) => [p.id, p]));
  const out = [...local];
  for (const r of remotes) {
    const merged = mergeProject(byId.get(r.id) ?? null, r);
    if (merged.versions.length === 0) continue;
    const i = out.findIndex((p) => p.id === r.id);
    if (i >= 0) out[i] = merged;
    else out.push(merged);
  }
  return out.sort((a, b) => b.updatedAt - a.updatedAt);
}

function readState(): SyncState {
  try {
    return JSON.parse(localStorage.getItem(SYNC_KEY) || "{}") || {};
  } catch {
    return {};
  }
}

function writeState(s: SyncState): void {
  try {
    localStorage.setItem(SYNC_KEY, JSON.stringify(s));
  } catch {
    /* sync bookkeeping only - worst case we re-push idempotently */
  }
}

let backoffUntil = 0;
let lastStatus = 0;

async function json<T>(path: string, options?: object): Promise<T | null> {
  if (Date.now() < backoffUntil) return null;
  try {
    const res = await apiFetch(path, options);
    lastStatus = res.status;
    if (!res.ok) {
      // 404 on the collection = older core without the endpoint; 401 = signed out.
      if (res.status === 404 || res.status === 401 || res.status >= 500) backoffUntil = Date.now() + BACKOFF_MS;
      return null;
    }
    return res.status === 204 ? (null as T | null) : ((await res.json()) as T);
  } catch {
    backoffUntil = Date.now() + BACKOFF_MS;
    return null;
  }
}

/** Fetch projects this device is missing or that changed remotely. Returns the
 * remote details to merge (empty when nothing to do or the server is out of reach). */
export async function pullRemote(local: StudioProject[]): Promise<RemoteProject[]> {
  const list = await json<RemoteProject[]>("/api/studio-projects");
  if (!list) return [];
  const state = readState();
  const have = new Set(local.map((p) => p.id));
  const details: RemoteProject[] = [];
  for (const r of list) {
    const seen = state[r.id]?.remoteUpdatedAt ?? 0;
    const roleNow = r.role && r.role !== "OWNER" ? r.role : undefined;
    if (have.has(r.id) && r.updatedAt <= seen && state[r.id]?.role === roleNow) continue; // role change re-pulls
    const d = await json<RemoteProject>(`/api/studio-projects/${encodeURIComponent(r.id)}`);
    if (d) {
      details.push(d);
      state[r.id] = {
        // What we just pulled is already on the server - don't echo it back.
        pushedAt: Math.max(state[r.id]?.pushedAt ?? 0, ...(d.versions ?? []).map((v) => v.at)),
        sig: state[r.id]?.sig ?? "",
        remoteUpdatedAt: d.updatedAt,
        role: d.role && d.role !== "OWNER" ? d.role : undefined,
      };
    }
  }
  writeState(state);
  return details;
}

/** Push every project with unsynced versions or changed settings. */
export async function pushProjects(projects: StudioProject[]): Promise<void> {
  const state = readState();
  for (const p of projects) {
    const settings = settingsOf(p);
    const sig = pushSigOf(p);
    const prev = state[p.id];
    // Read-only (viewer) or no-longer-shared projects are never pushed.
    if (!canEdit(p.role) || prev?.revoked) continue;
    const pending = p.versions.filter((v) => v.at > (prev?.pushedAt ?? 0));
    if (prev && pending.length === 0 && prev.sig === sig) continue;
    const res = await json<RemoteProject>(`/api/studio-projects/${encodeURIComponent(p.id)}`, {
      method: "PUT",
      ...jsonBody({
        name: p.name,
        emoji: p.emoji,
        settings,
        createdAt: p.createdAt,
        versions: pending.map((v) => ({ at: v.at, note: v.note, source: v.source, html: v.html })),
      }),
    });
    if (!res) {
      // 404 on a project we only hold as a shared editor = access was removed.
      if (lastStatus === 404 && p.role === "EDITOR") state[p.id] = { ...(prev ?? { pushedAt: 0, sig: "", remoteUpdatedAt: 0 }), revoked: true };
      continue; // otherwise retry on the next save
    }
    state[p.id] = {
      role: prev?.role,
      pushedAt: Math.max(prev?.pushedAt ?? 0, ...p.versions.map((v) => v.at)),
      sig,
      remoteUpdatedAt: res.updatedAt,
    };
  }
  writeState(state);
}

let timer: ReturnType<typeof setTimeout> | null = null;
let latest: StudioProject[] = [];
let running = false;

/** Debounced background push of the newest snapshot; never throws. */
export function scheduleSync(projects: StudioProject[]): void {
  latest = projects;
  if (timer) clearTimeout(timer);
  timer = setTimeout(() => {
    timer = null;
    if (running) {
      scheduleSync(latest);
      return;
    }
    running = true;
    pushProjects(latest)
      .catch((): void => undefined)
      .finally((): void => {
        running = false;
      });
  }, PUSH_DEBOUNCE_MS);
}

/** Remove the server copy (and its history) of a project deleted locally. */
export async function deleteRemote(id: string): Promise<void> {
  // Deleting is owner-only server-side; a shared project is just dropped locally.
  if (readState()[id]?.role) {
    const state = readState();
    delete state[id];
    writeState(state);
    return;
  }
  await json(`/api/studio-projects/${encodeURIComponent(id)}`, { method: "DELETE" });
  const state = readState();
  delete state[id];
  writeState(state);
}
