// The install registry (verified versions only - F-1 double-buffer) and the F-1
// update channel's staleness rules. Split out of ../downloadManager.ts; pure
// functions over localStorage, so the manager and its transfer engine share them.
import type { ModelManifest } from "../capabilities";
import { INSTALLED_REGISTRY_KEY } from "./constants";
import type { ManifestAssetEntry } from "./types";
import { normalizeVersion, safeLocalGet, safeLocalSet, type InstalledRegistry } from "./transferUtils";

/** The version an entry should be at: the per-model version when published,
 * else the manifest-level one. Always a normalized string. */
export function targetVersionOf(entry: ManifestAssetEntry, manifestVersion: string): string {
  return entry.version != null ? normalizeVersion(entry.version) : manifestVersion;
}

/** Whether THIS manager can move bytes for an entry. Entries with no `url`
 * (the server seeds every url as null today) are installed/updated by
 * EXTERNAL engines — WebLLM's own fetcher via reportExternalProgress, the
 * Chrome built-in model monitor — never by this transfer loop. Enqueueing
 * them would create permanently-"queued" rows. */
export function isManagerDownloadable(entry: ManifestAssetEntry): boolean {
  return typeof entry.url === "string" && entry.url.length > 0;
}

/** Installed entries whose target version differs AND that this manager can
 * actually act on (non-null url). External-engine assets are excluded: this
 * module has no hook to re-trigger their installers, so flagging them would
 * produce a dead "Update now" button — their engines re-check versions
 * against installedVersion() on their own load path instead. */
export function staleActionableEntries(manifest: ModelManifest): ManifestAssetEntry[] {
  const manifestVersion = normalizeVersion(manifest.version);
  const registry = readRegistry();
  return (manifest.models as ManifestAssetEntry[]).filter((entry) => {
    const installed = registry[entry.id];
    if (!installed) return false;
    if (normalizeVersion(installed.version) === targetVersionOf(entry, manifestVersion)) {
      return false;
    }
    return isManagerDownloadable(entry);
  });
}

export function readRegistry(): InstalledRegistry {
  try {
    const raw = safeLocalGet(INSTALLED_REGISTRY_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as InstalledRegistry;
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    return {};
  }
}

export function writeRegistry(registry: InstalledRegistry): void {
  safeLocalSet(INSTALLED_REGISTRY_KEY, JSON.stringify(registry));
}
