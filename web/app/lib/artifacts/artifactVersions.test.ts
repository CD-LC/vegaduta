import { describe, it, expect } from "vitest";
import {
  MAX_ARTIFACTS,
  MAX_VERSIONS_PER_ARTIFACT,
  blockKey,
  buildArtifactRegistry,
  findEntry,
  latestVersion,
  neighbours,
  resolveVersion,
} from "./artifactVersions";

const F = "```";
const art = (kind: string, title: string, body: string) => `${F}artifact:${kind}:${title}\n${body}\n${F}`;
const asst = (content: string) => ({ role: "assistant", content });
const user = (content: string) => ({ role: "user", content });

describe("buildArtifactRegistry", () => {
  it("is empty for no messages and for messages without artifacts", () => {
    expect(buildArtifactRegistry([]).entries).toEqual([]);
    expect(buildArtifactRegistry(null).entries).toEqual([]);
    expect(buildArtifactRegistry([user("hi"), asst("hello")]).entries).toEqual([]);
  });

  it("creates one entry per title with version 1", () => {
    const reg = buildArtifactRegistry([asst("ok\n" + art("html", "Page", "<p>1</p>"))]);
    expect(reg.entries).toHaveLength(1);
    expect(reg.entries[0].title).toBe("Page");
    expect(reg.entries[0].versions.map((v) => v.number)).toEqual([1]);
    expect(reg.byBlock[blockKey(0, 0)]).toEqual({ id: reg.entries[0].id, number: 1 });
  });

  it("appends a new VERSION when the same title is re-emitted (case/spacing insensitive)", () => {
    const reg = buildArtifactRegistry([
      asst(art("html", "Pricing page", "<p>1</p>")),
      user("make it blue"),
      asst(art("html", "pricing  PAGE", "<p>2</p>")),
    ]);
    expect(reg.entries).toHaveLength(1);
    const e = reg.entries[0];
    expect(e.versions.map((v) => v.number)).toEqual([1, 2]);
    expect(e.versions.map((v) => v.content)).toEqual(["<p>1</p>", "<p>2</p>"]);
    expect(e.title).toBe("pricing PAGE");
    expect(reg.byBlock[blockKey(2, 0)].number).toBe(2);
    expect(latestVersion(e)?.number).toBe(2);
  });

  it("does not add a version for an unchanged re-send", () => {
    const reg = buildArtifactRegistry([asst(art("html", "P", "<p>1</p>")), asst(art("html", "P", "<p>1</p>"))]);
    expect(reg.entries[0].versions).toHaveLength(1);
    expect(reg.byBlock[blockKey(1, 0)].number).toBe(1);
  });

  it("keeps different titles as different artifacts, in order of first appearance", () => {
    const reg = buildArtifactRegistry([asst(art("csv", "B", "a,b") + "\n" + art("svg", "A", "<svg/>"))]);
    expect(reg.entries.map((e) => e.title)).toEqual(["B", "A"]);
  });

  it("ignores artifact fences in USER messages", () => {
    const reg = buildArtifactRegistry([user(art("html", "Spoof", "<p>x</p>"))]);
    expect(reg.entries).toEqual([]);
  });

  it("records an incomplete (streaming) block as an incomplete version", () => {
    const reg = buildArtifactRegistry([asst(art("html", "P", "<p>1</p>")), asst(`${F}artifact:html:P\n<p>2`)]);
    const v = reg.entries[0].versions;
    expect(v).toHaveLength(2);
    expect(v[1].complete).toBe(false);
  });

  it("uses the caller's text mapper so block indices match the rendered text", () => {
    const reg = buildArtifactRegistry([asst("RAW")], () => art("csv", "T", "a,b"));
    expect(reg.entries[0].title).toBe("T");
  });

  it("caps artifacts and versions", () => {
    const many = Array.from({ length: MAX_ARTIFACTS + 5 }, (_, i) => asst(art("csv", "T" + i, "a,b")));
    expect(buildArtifactRegistry(many).entries).toHaveLength(MAX_ARTIFACTS);
    const versions = Array.from({ length: MAX_VERSIONS_PER_ARTIFACT + 5 }, (_, i) => asst(art("csv", "T", "v" + i)));
    const e = buildArtifactRegistry(versions).entries[0];
    expect(e.versions).toHaveLength(MAX_VERSIONS_PER_ARTIFACT);
    expect(e.versions[e.versions.length - 1].number).toBe(MAX_VERSIONS_PER_ARTIFACT + 5);
  });
});

describe("version helpers", () => {
  const reg = buildArtifactRegistry([
    asst(art("html", "P", "1")),
    asst(art("html", "P", "2")),
    asst(art("html", "P", "3")),
  ]);
  const e = reg.entries[0];

  it("resolves a requested version, else the latest", () => {
    expect(resolveVersion(e, 2)?.content).toBe("2");
    expect(resolveVersion(e, null)?.content).toBe("3");
    expect(resolveVersion(e, 99)?.content).toBe("3");
  });

  it("finds an entry by id", () => {
    expect(findEntry(reg, e.id)).toBe(e);
    expect(findEntry(reg, "nope")).toBeNull();
    expect(findEntry(reg, null)).toBeNull();
  });

  it("reports neighbours for the switcher", () => {
    expect(neighbours(e, 1)).toEqual({ prev: null, next: 2 });
    expect(neighbours(e, 2)).toEqual({ prev: 1, next: 3 });
    expect(neighbours(e, 3)).toEqual({ prev: 2, next: null });
    expect(neighbours(e, 42)).toEqual({ prev: null, next: null });
  });
});
