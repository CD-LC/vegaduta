import { REPO_URL } from "../../lib/siteLinks";

// Server-side fetch for /downloads (like components/plugins/serverFetch.ts):
// only the page imports this, never a client component.
//
// What /downloads shows: the latest GitHub Release's own asset list, so the
// links a visitor clicks are files release.yml actually attached - never a
// guessed name. The dmg carries its version in the file name
// (VegaDuta-Setup-<ver>.dmg), so a hard-coded "latest" link cannot name it;
// the release's asset list is the only honest source for it. Everything here
// degrades to null when the API is unreachable or the repository still has
// no release - the page then says so and links the releases tab instead.

export interface ReleaseAsset {
  name: string;
  url: string;
  sizeMb: number;
}

export interface LatestRelease {
  tag: string;
  publishedAt: string;
  url: string;
  assets: ReleaseAsset[];
}

export async function fetchLatestRelease(): Promise<LatestRelease | null> {
  try {
    const res = await fetch(`https://api.github.com/repos/${repoPath()}/releases/latest`, {
      headers: { Accept: "application/vnd.github+json", "User-Agent": "vegaduta-downloads" },
      next: { revalidate: 3600 },
    });
    if (!res.ok) return null;
    const body = await res.json();
    const assets: ReleaseAsset[] = Array.isArray(body?.assets)
      ? body.assets
          .filter((a: unknown): a is { name: string; browser_download_url: string; size: number } => {
            const o = a as Record<string, unknown>;
            return typeof o?.name === "string" && typeof o?.browser_download_url === "string";
          })
          .map((a: { name: string; browser_download_url: string; size: number }) => ({
            name: a.name,
            url: a.browser_download_url,
            sizeMb: Math.round(a.size / 10485.76) / 100,
          }))
      : [];
    if (typeof body?.tag_name !== "string" || typeof body?.html_url !== "string") return null;
    return { tag: body.tag_name, publishedAt: String(body.published_at ?? ""), url: body.html_url, assets };
  } catch {
    return null;
  }
}

/** "<owner>/<repo>" out of REPO_URL, for the API path. */
function repoPath(): string {
  return REPO_URL.replace(/^https?:\/\/github\.com\//, "")
    .replace(/\.git$/, "")
    .replace(/\/+$/, "");
}

export interface DownloadSet {
  macos?: ReleaseAsset;
  windows?: ReleaseAsset;
  linuxAmd64?: ReleaseAsset;
  linuxArm64?: ReleaseAsset;
  linuxDeb?: ReleaseAsset;
  darwinAmd64?: ReleaseAsset;
  darwinArm64?: ReleaseAsset;
  sourceTar?: ReleaseAsset;
  sums?: ReleaseAsset;
  sumsSig?: ReleaseAsset;
  sbom?: ReleaseAsset[];
}

/** Classifies the release assets into what the page's cards link to. */
export function pickAssets(assets: ReleaseAsset[]): DownloadSet {
  const byName = (re: RegExp) => assets.find((a) => re.test(a.name));
  return {
    macos: byName(/^VegaDuta-Setup.*\.dmg$/i),
    windows: byName(/windows.*\.zip$/i),
    linuxAmd64: byName(/linux-amd64\.tar\.gz$/i),
    linuxArm64: byName(/linux-arm64\.tar\.gz$/i),
    linuxDeb: byName(/linux.*\.deb$/i),
    darwinAmd64: byName(/darwin-amd64\.tar\.gz$/i),
    darwinArm64: byName(/darwin-arm64\.tar\.gz$/i),
    sourceTar: byName(/^vegaduta-\d/i) ?? byName(/^vegaduta\.tar\.gz$/i),
    sums: byName(/^SHA256SUMS(\.txt)?$/i),
    sumsSig: byName(/^SHA256SUMS.*\.(sig|sigstore\.json)$/i),
    sbom: assets.filter((a) => /^sbom-.*\.spdx\.json$/i.test(a.name)),
  };
}
