import { describe, expect, it } from "vitest";
import { MAX_VERSIONS, newProject, type StudioProject } from "./store";
import {
  canAdminister,
  canDeleteComment,
  canEdit,
  collabErrorMessage,
  isShared,
  openCount,
  orderComments,
  roleLabel,
  validateCommentBody,
  type Comment,
} from "./collab";
import { mergeProject, type RemoteProject } from "./studioSync";

const c = (id: string, createdAt: number, resolved = false, author = "u1"): Comment => ({
  id,
  versionAt: null,
  authorSubject: author,
  authorName: "A",
  body: "b",
  createdAt,
  resolved,
});

describe("role helpers", () => {
  it("treats a role-less (local-only) project as fully editable", () => {
    expect(canEdit(undefined)).toBe(true);
    expect(canAdminister(undefined)).toBe(true);
    expect(isShared(undefined)).toBe(false);
  });
  it("editor edits but does not administer; viewer does neither", () => {
    expect(canEdit("EDITOR")).toBe(true);
    expect(canAdminister("EDITOR")).toBe(false);
    expect(canEdit("VIEWER")).toBe(false);
    expect(canAdminister("VIEWER")).toBe(false);
    expect(isShared("EDITOR")).toBe(true);
    expect(roleLabel("VIEWER")).toBe("Can view");
  });
});

describe("comments", () => {
  it("lists open threads oldest-first, then resolved newest-first", () => {
    const out = orderComments([c("r1", 1, true), c("o2", 20), c("o1", 10), c("r2", 5, true)]);
    expect(out.map((x) => x.id)).toEqual(["o1", "o2", "r2", "r1"]);
    expect(openCount(out)).toBe(2);
  });
  it("allows delete by author or owner only", () => {
    const mine = c("x", 1, false, "me");
    expect(canDeleteComment("VIEWER", mine, "me")).toBe(true);
    expect(canDeleteComment("EDITOR", mine, "other")).toBe(false);
    expect(canDeleteComment("OWNER", mine, "other")).toBe(true);
    expect(canDeleteComment(undefined, mine, "me")).toBe(false);
  });
  it("validates the body like core does", () => {
    expect(validateCommentBody("  hi ")).toEqual({ ok: true, body: "hi" });
    expect(validateCommentBody("   ").ok).toBe(false);
    expect(validateCommentBody("x".repeat(2001)).ok).toBe(false);
    expect(validateCommentBody("x".repeat(2000)).ok).toBe(true);
  });
  it("maps core error codes to messages with a safe fallback", () => {
    expect(collabErrorMessage("not_a_tenant_member")).toMatch(/workspace/);
    expect(collabErrorMessage("weird")).toMatch(/went wrong/);
    expect(collabErrorMessage(null, 429)).toMatch(/Too many/);
  });
});

const remote = (over: Partial<RemoteProject> = {}): RemoteProject => ({
  id: "sa-1",
  name: "Owner name",
  emoji: "🚀",
  settings: null,
  createdAt: 1000,
  updatedAt: 5000,
  versionCount: 1,
  versions: [{ at: 1000, note: "first", source: "template", html: "<h1>1</h1>" }],
  ...over,
});

const local = (over: Partial<StudioProject> = {}): StudioProject => ({
  ...newProject("Mine", "🎯", "<h1>1</h1>", "first", "template"),
  id: "sa-1",
  createdAt: 1000,
  updatedAt: 9000,
  versions: [
    { at: 1000, note: "first", source: "template", html: "<h1>1</h1>" },
    { at: 8000, note: "local edit", source: "manual", html: "<h1>local</h1>" },
  ],
  ...over,
});

describe("mergeProject with roles", () => {
  it("owner: keeps role-less, unions histories and lets the newer side win", () => {
    const m = mergeProject(local(), remote({ role: "OWNER" }));
    expect(m.role).toBeUndefined();
    expect(m.versions.map((v) => v.at)).toEqual([1000, 8000]);
    expect(m.name).toBe("Mine");
  });
  it("viewer: server copy is authoritative - local-only versions and name are dropped", () => {
    const m = mergeProject(local(), remote({ role: "VIEWER" }));
    expect(m.role).toBe("VIEWER");
    expect(m.versions.map((v) => v.at)).toEqual([1000]);
    expect(m.name).toBe("Owner name");
  });
  it("editor: keeps own versions but takes the owner's name/settings", () => {
    const m = mergeProject(local(), remote({ role: "EDITOR" }));
    expect(m.role).toBe("EDITOR");
    expect(m.versions.map((v) => v.at)).toEqual([1000, 8000]);
    expect(m.name).toBe("Owner name");
    expect(m.versions.length).toBeLessThanOrEqual(MAX_VERSIONS);
  });
  it("adopts a project shared with us for the first time", () => {
    const m = mergeProject(null, remote({ role: "EDITOR" }));
    expect(m.role).toBe("EDITOR");
    expect(m.name).toBe("Owner name");
  });
});
