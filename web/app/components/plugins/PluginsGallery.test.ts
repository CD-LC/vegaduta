import { describe, expect, it } from "vitest";
import { filterPlugins } from "./PluginsGallery";
import type { PluginEntry } from "./serverFetch";

const base: Pick<PluginEntry, "description" | "latestVersion" | "releasedAt" | "releaseNotes" | "externalStoreUrl"> = { description: "", latestVersion: null, releasedAt: null, releaseNotes: null, externalStoreUrl: null };
const plugins: PluginEntry[] = [
  { ...base, id: "vs", displayName: "VS Code", description: "Editor chat", artifact: { fileName: "a.vsix", sizeBytes: 1, sha256: "x" } },
  { ...base, id: "jb", displayName: "JetBrains", artifact: null },
  { ...base, id: "ch", displayName: "Chrome", artifact: null, externalStoreUrl: "https://store.example/c" },
];

describe("filterPlugins", () => {
  it("returns everything for an empty query", () => {
    expect(filterPlugins(plugins, "  ", "all")).toHaveLength(3);
  });
  it("matches name and description case-insensitively", () => {
    expect(filterPlugins(plugins, "editor", "all").map((p) => p.id)).toEqual(["vs"]);
    expect(filterPlugins(plugins, "JETB", "all").map((p) => p.id)).toEqual(["jb"]);
  });
  it("splits available (artifact or store link) from coming soon", () => {
    expect(filterPlugins(plugins, "", "available").map((p) => p.id)).toEqual(["vs", "ch"]);
    expect(filterPlugins(plugins, "", "soon").map((p) => p.id)).toEqual(["jb"]);
  });
});
