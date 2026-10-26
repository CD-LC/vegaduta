import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import DownloadsPage from "./page";
import { pickAssets } from "../components/downloads/releaseInfo";

// /downloads renders from the latest release's real asset list. These tests pin
// the asset-name mapping (the dmg's name carries its version, so the page can
// never guess it) and the no-release fallback, which is what every visitor
// sees until the first tag is cut.

const ASSETS = [
  { name: "VegaDuta-Setup-1.2.3.dmg", url: "https://dl/VegaDuta-Setup-1.2.3.dmg", sizeMb: 12 },
  { name: "vegaduta-setup-windows-amd64.zip", url: "https://dl/win.zip", sizeMb: 8 },
  { name: "vegaduta-setup-linux-amd64.tar.gz", url: "https://dl/lin64.tgz", sizeMb: 9 },
  { name: "vegaduta-setup-linux-arm64.tar.gz", url: "https://dl/linarm.tgz", sizeMb: 8.6 },
  { name: "vegaduta-setup-darwin-amd64.tar.gz", url: "https://dl/darwin64.tgz", sizeMb: 8.6 },
  { name: "vegaduta-1.2.3.tar.gz", url: "https://dl/src.tgz", sizeMb: 19 },
  { name: "SHA256SUMS", url: "https://dl/sums", sizeMb: 0.01 },
  { name: "SHA256SUMS.sigstore.json", url: "https://dl/sums.sig", sizeMb: 0.01 },
  { name: "sbom-core.spdx.json", url: "https://dl/sbom.json", sizeMb: 0.48 },
];

describe("pickAssets", () => {
  it("maps a real release asset list onto the OS cards", () => {
    const set = pickAssets(ASSETS);
    expect(set.macos?.url).toBe("https://dl/VegaDuta-Setup-1.2.3.dmg");
    expect(set.windows?.url).toBe("https://dl/win.zip");
    expect(set.linuxAmd64?.url).toBe("https://dl/lin64.tgz");
    expect(set.linuxArm64?.url).toBe("https://dl/linarm.tgz");
    expect(set.darwinAmd64?.url).toBe("https://dl/darwin64.tgz");
    expect(set.sourceTar?.name).toBe("vegaduta-1.2.3.tar.gz");
    expect(set.sums?.name).toBe("SHA256SUMS");
    expect(set.sumsSig?.name).toBe("SHA256SUMS.sigstore.json");
    expect(set.sbom).toHaveLength(1);
  });

  it("leaves cards empty when the release does not carry that artifact", () => {
    const set = pickAssets(ASSETS.filter((a) => !a.name.endsWith(".dmg")));
    expect(set.macos).toBeUndefined();
    expect(set.windows).toBeDefined();
  });
});

describe("/downloads", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("links the real assets of the latest platform release", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => ({
        ok: true,
        json: async () => [
          // A component release published after the platform tag: /releases/latest
          // would follow it, so the page must skip non-vX.Y.Z tags.
          {
            tag_name: "sdk-v9.9.9",
            html_url: "https://github.com/CD-LC/vegaduta/releases/tag/sdk-v9.9.9",
            published_at: "2026-10-25T00:00:00Z",
            assets: [],
          },
          {
            tag_name: "v1.2.3",
            html_url: "https://github.com/CD-LC/vegaduta/releases/tag/v1.2.3",
            published_at: "2026-10-20T00:00:00Z",
            assets: ASSETS.map((a) => ({
              name: a.name,
              browser_download_url: a.url,
              size: a.sizeMb * 1048576,
            })),
          },
        ],
      })),
    );
    render(await DownloadsPage());
    // BrandName splits the wordmark into accent spans; match the level-1 heading, not a flattened name.
    const h1 = screen.getByRole("heading", { level: 1 });
    expect(h1.textContent).toBe("Download VegaDūta");
    expect(h1.querySelector(".brand-name-accent")?.textContent).toBe("Dūta");
    const dmg = screen.getByRole("link", { name: /macOS \.dmg/ });
    expect(dmg.getAttribute("href")).toBe("https://dl/VegaDuta-Setup-1.2.3.dmg");
    expect(document.body.textContent).toContain("v1.2.3");
  });

  it("falls back to the releases page when no release exists yet", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => ({ ok: false })));
    render(await DownloadsPage());
    expect(screen.getByRole("link", { name: /Releases on GitHub/ }).getAttribute("href")).toBe(
      "https://github.com/CD-LC/vegaduta/releases",
    );
    // Cards still render with a button that lands on the releases tab - no dead links.
    expect(screen.getByRole("link", { name: /macOS \.dmg/ }).getAttribute("href")).toBe(
      "https://github.com/CD-LC/vegaduta/releases",
    );
  });
});
