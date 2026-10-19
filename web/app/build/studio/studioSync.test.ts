import { describe, expect, it } from "vitest";
import { MAX_VERSIONS, newProject, type StudioProject } from "./store";
import { mergeAll, mergeProject, pushSigOf, settingsOf, sigOf, type RemoteProject } from "./studioSync";

const remote = (over: Partial<RemoteProject> = {}): RemoteProject => ({
  id: "sa-1",
  name: "Remote name",
  emoji: "🚀",
  settings: JSON.stringify({ connections: [{ kind: "agent", id: "a", shareId: "s", name: "N" }], publish: null, auth: { mode: "optional" }, ai: null }),
  createdAt: 1000,
  updatedAt: 5000,
  versionCount: 2,
  versions: [
    { at: 1000, note: "first", source: "template", html: "<h1>1</h1>" },
    { at: 2000, note: "second", source: "cloud", html: "<h1>2</h1>" },
  ],
  ...over,
});

const local = (over: Partial<StudioProject> = {}): StudioProject => ({
  ...newProject("Local name", "🎯", "<h1>1</h1>", "first", "template"),
  id: "sa-1",
  createdAt: 1000,
  updatedAt: 1000,
  versions: [{ at: 1000, note: "first", source: "template", html: "<h1>1</h1>" }],
  ...over,
});

describe("studioSync", () => {
  it("adopts a project this device has never seen", () => {
    const merged = mergeProject(null, remote());
    expect(merged.id).toBe("sa-1");
    expect(merged.name).toBe("Remote name");
    expect(merged.versions.map((v) => v.at)).toEqual([1000, 2000]);
    expect(merged.auth).toEqual({ mode: "optional" });
    expect(merged.connections).toHaveLength(1);
    expect(merged.appData).toEqual({});
  });

  it("unions histories by timestamp without losing local versions", () => {
    const l = local({
      updatedAt: 9000,
      versions: [
        { at: 1000, note: "first", source: "template", html: "<h1>1</h1>" },
        { at: 3000, note: "local only", source: "manual", html: "<h1>3</h1>" },
      ],
    });
    const merged = mergeProject(l, remote());
    expect(merged.versions.map((v) => v.at)).toEqual([1000, 2000, 3000]);
  });

  it("keeps the local name/settings when local was edited more recently", () => {
    const merged = mergeProject(local({ updatedAt: 9000 }), remote({ updatedAt: 5000 }));
    expect(merged.name).toBe("Local name");
    expect(merged.connections).toEqual([]);
  });

  it("takes the remote name/settings when the remote is newer", () => {
    const merged = mergeProject(local({ updatedAt: 1000 }), remote({ updatedAt: 5000 }));
    expect(merged.name).toBe("Remote name");
    expect(merged.emoji).toBe("🚀");
    expect(merged.auth).toEqual({ mode: "optional" });
  });

  it("caps the merged history at the local maximum, keeping the newest", () => {
    const versions = Array.from({ length: MAX_VERSIONS + 5 }, (_, i) => ({
      at: 100 + i,
      note: "",
      source: "device" as const,
      html: `<p>${i}</p>`,
    }));
    const merged = mergeProject(null, remote({ versions, versionCount: versions.length }));
    expect(merged.versions).toHaveLength(MAX_VERSIONS);
    expect(merged.versions[merged.versions.length - 1].at).toBe(100 + MAX_VERSIONS + 4);
  });

  it("maps an unknown remote source to manual and tolerates bad settings JSON", () => {
    const merged = mergeProject(
      null,
      remote({ settings: "{not json", versions: [{ at: 7, note: null, source: "weird", html: "<p/>" }] }),
    );
    expect(merged.versions[0].source).toBe("manual");
    expect(merged.versions[0].note).toBe("");
    expect(merged.connections).toEqual([]);
  });

  it("mergeAll appends unknown remotes, drops empty ones, sorts newest first", () => {
    const a = local({ id: "sa-a", updatedAt: 100 });
    const out = mergeAll(
      [a],
      [remote({ id: "sa-b", updatedAt: 8000 }), remote({ id: "sa-empty", versions: [], versionCount: 0 })],
    );
    expect(out.map((p) => p.id)).toEqual(["sa-b", "sa-a"]);
  });

  it("push signature changes on rename or settings change, not otherwise", () => {
    const p = local();
    const s1 = pushSigOf(p);
    expect(pushSigOf({ ...p })).toBe(s1);
    expect(pushSigOf({ ...p, name: "renamed" })).not.toBe(s1);
    expect(pushSigOf({ ...p, auth: { mode: "required" } })).not.toBe(s1);
    expect(sigOf(settingsOf(p))).toBe(sigOf(settingsOf({ ...p, name: "renamed" })));
  });
});
