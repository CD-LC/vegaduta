import { describe, expect, it } from "vitest";
import {
  conflictPaths,
  externalEditsLine,
  isImported,
  normalizeSubdir,
  parseRepoInput,
  syncChips,
  type AppBuilderSyncStatus,
} from "./syncLogic";

const status = (over: Partial<AppBuilderSyncStatus> = {}): AppBuilderSyncStatus => ({
  base_branch: "main",
  branch: "app-builder/x",
  status: "identical",
  ahead_by: 0,
  behind_by: 0,
  up_to_date: true,
  can_pull: false,
  diverged: false,
  ...over,
});

describe("syncChips", () => {
  it("is empty without a status", () => {
    expect(syncChips(null)).toEqual([]);
  });
  it("shows up to date when not behind", () => {
    const chips = syncChips(status());
    expect(chips.map((c) => c.key)).toEqual(["current"]);
    expect(chips[0].tone).toBe("ok");
  });
  it("shows behind + ahead + diverged with singular/plural", () => {
    const chips = syncChips(status({ behind_by: 1, ahead_by: 2, diverged: true, can_pull: true, up_to_date: false }));
    expect(chips.map((c) => c.label)).toEqual(["1 commit behind main", "2 commits ahead", "Diverged"]);
  });
  it("ahead only still counts as up to date", () => {
    const keys = syncChips(status({ ahead_by: 3 })).map((c) => c.key);
    expect(keys).toEqual(["ahead", "current"]);
  });
});

describe("conflictPaths", () => {
  it("reads paths from a 409 body", () => {
    const err = Object.assign(new Error("x"), { status: 409, body: { conflicted_paths: ["a.js", "b.js"] } });
    expect(conflictPaths(err)).toEqual(["a.js", "b.js"]);
  });
  it("returns an empty list for a 409 with no paths", () => {
    const err = Object.assign(new Error("x"), { status: 409, body: { conflicted_paths: [] } });
    expect(conflictPaths(err)).toEqual([]);
  });
  it("is null for other errors", () => {
    expect(conflictPaths(Object.assign(new Error("x"), { status: 500 }))).toBeNull();
    expect(conflictPaths(Object.assign(new Error("x"), { status: 409, body: {} }))).toBeNull();
    expect(conflictPaths(null)).toBeNull();
  });
});

describe("externalEditsLine", () => {
  const c = (author: string) => ({ sha: "1", message: "m", author, date: "" });
  it("is empty when nothing external", () => {
    expect(externalEditsLine(null)).toBe("");
    expect(
      externalEditsLine({ branch: "b", edited_outside: false, count: 0, commits: [], truncated: false })
    ).toBe("");
  });
  it("names authors and pluralises", () => {
    expect(
      externalEditsLine({ branch: "b", edited_outside: true, count: 2, commits: [c("Jane"), c("Raj")], truncated: false })
    ).toBe("2 commits edited outside the builder by Jane, Raj.");
  });
  it("marks truncation and caps names", () => {
    const line = externalEditsLine({
      branch: "b",
      edited_outside: true,
      count: 1,
      commits: [c("A"), c("B"), c("C")],
      truncated: true,
    });
    expect(line).toContain("1 commit+ edited outside");
    expect(line).toContain("A, B and others");
  });
});

describe("parseRepoInput", () => {
  it("accepts owner/repo", () => expect(parseRepoInput("acme/widgets")).toBe("acme/widgets"));
  it("accepts https URLs with .git and trailing paths", () => {
    expect(parseRepoInput("https://github.com/acme/widgets.git")).toBe("acme/widgets");
    expect(parseRepoInput("https://github.com/acme/widgets/tree/main/src")).toBe("acme/widgets");
    expect(parseRepoInput("github.com/acme/widgets/")).toBe("acme/widgets");
  });
  it("accepts ssh URLs", () => expect(parseRepoInput("git@github.com:acme/widgets.git")).toBe("acme/widgets"));
  it("rejects junk", () => {
    expect(parseRepoInput("")).toBeNull();
    expect(parseRepoInput("widgets")).toBeNull();
    expect(parseRepoInput("../etc/passwd")).toBeNull();
    expect(parseRepoInput("a b/c")).toBeNull();
  });
});

describe("normalizeSubdir", () => {
  it("trims slashes and backslashes", () => {
    expect(normalizeSubdir("/apps/web/")).toBe("apps/web");
    expect(normalizeSubdir("apps\\web")).toBe("apps/web");
  });
  it("is null for empty or escaping paths", () => {
    expect(normalizeSubdir("  ")).toBeNull();
    expect(normalizeSubdir(undefined)).toBeNull();
    expect(normalizeSubdir("../x")).toBeNull();
    expect(normalizeSubdir("a/./b")).toBeNull();
  });
});

describe("isImported", () => {
  it("reads the imported flag", () => {
    expect(isImported({ github: { imported: true } })).toBe(true);
    expect(isImported({ github: { branch: "b" } })).toBe(false);
    expect(isImported(null)).toBe(false);
  });
});

describe("parseRepoInput hardening", () => {
  it("handles .git and trailing slashes in any order", () => {
    expect(parseRepoInput("https://github.com/acme/widgets.git/")).toBe("acme/widgets");
    expect(parseRepoInput("https://github.com/acme/widgets/")).toBe("acme/widgets");
    expect(parseRepoInput("acme/widgets.git")).toBe("acme/widgets");
    expect(parseRepoInput("ssh://git@github.com/acme/widgets.git")).toBe("acme/widgets");
  });
  it("rejects other hosts", () => {
    expect(parseRepoInput("gitlab.com/foo/bar")).toBeNull();
    expect(parseRepoInput("https://gitlab.com/foo/bar")).toBeNull();
    expect(parseRepoInput("https://bitbucket.org/foo/bar.git")).toBeNull();
    expect(parseRepoInput("git@gitlab.com:foo/bar.git")).toBeNull();
  });
});

describe("normalizeSubdir collapsing", () => {
  it("collapses repeated slashes", () => {
    expect(normalizeSubdir("a//b///c/")).toBe("a/b/c");
  });
});
