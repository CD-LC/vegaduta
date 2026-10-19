// The pure rules behind the /coding page: which actions a status allows, the diff classifier, the form validation
// (mirrors the server's allowlist so the form can say "no" early; the server stays the authority), the FormData the
// start call sends, and the elapsed label.

import { describe, expect, it } from "vitest";
import {
  allowedActions,
  buildStartForm,
  classifyDiff,
  diffStats,
  formatElapsed,
  isTerminalStatus,
  looksLikePublicGitUrl,
  statusTone,
  testCommandOf,
  validateDraft,
  CODING_TASK_STATUSES,
  type CodingTaskDraft,
} from "./codingTasks";

const config = {
  testRunners: ["mvn", "gradle", "npm", "pytest", "go"],
  maxArchiveBytes: 1024 * 1024,
  caps: { maxSteps: 40, maxWallSeconds: 1800, maxTokens: 400000, maxFixIterations: 5 },
};

function draft(over: Partial<CodingTaskDraft> = {}): CodingTaskDraft {
  return {
    agentId: "a1",
    instruction: "Add validation",
    source: "git",
    repoUrl: "https://github.com/acme/widgets",
    archive: null,
    testRunner: "mvn",
    testArgs: "-q test",
    maxMinutes: "",
    ...over,
  };
}

describe("status helpers", () => {
  it("knows which statuses are final", () => {
    expect(CODING_TASK_STATUSES.filter(isTerminalStatus)).toEqual(["APPROVED", "REJECTED", "FAILED", "CANCELLED", "ROLLED_BACK"]);
    expect(isTerminalStatus("RUNNING")).toBe(false);
    expect(isTerminalStatus("AWAITING_APPROVAL")).toBe(false);
  });

  it("maps statuses to tones", () => {
    expect(statusTone("APPROVED")).toBe("success");
    expect(statusTone("AWAITING_APPROVAL")).toBe("warning");
    expect(statusTone("FAILED")).toBe("error");
    expect(statusTone("RUNNING")).toBe("info");
    expect(statusTone("CANCELLED")).toBe("idle");
  });
});

describe("allowedActions", () => {
  it("offers approve only while waiting, with a diff and its hash", () => {
    expect(allowedActions({ status: "AWAITING_APPROVAL", diffAvailable: true, diffHash: "abc" })).toEqual({
      approve: true,
      reject: true,
      cancel: true,
      rollback: false,
      patch: true,
    });
    // No diff or hash yet: nothing to approve, but the user can still reject or cancel.
    const noDiff = allowedActions({ status: "AWAITING_APPROVAL", diffAvailable: false, diffHash: null });
    expect(noDiff.approve).toBe(false);
    expect(noDiff.reject).toBe(true);
  });

  it("never offers approve, reject or cancel for a finished task", () => {
    for (const status of ["APPROVED", "REJECTED", "FAILED", "CANCELLED", "ROLLED_BACK"] as const) {
      const a = allowedActions({ status, diffAvailable: status === "APPROVED", diffHash: status === "APPROVED" ? "h" : null });
      expect(a.approve).toBe(false);
      expect(a.reject).toBe(false);
      expect(a.cancel).toBe(false);
    }
  });

  it("offers rollback and the patch only after approval", () => {
    const approved = allowedActions({ status: "APPROVED", diffAvailable: true, diffHash: "h" });
    expect(approved.rollback).toBe(true);
    expect(approved.patch).toBe(true);
    expect(allowedActions({ status: "RUNNING", diffAvailable: false, diffHash: null }).rollback).toBe(false);
    expect(allowedActions({ status: "ROLLED_BACK", diffAvailable: false, diffHash: null }).patch).toBe(false);
  });

  it("only a running task can be cancelled", () => {
    expect(allowedActions({ status: "RUNNING", diffAvailable: false, diffHash: null }).cancel).toBe(true);
    expect(allowedActions({ status: "QUEUED", diffAvailable: false, diffHash: null }).cancel).toBe(true);
  });
});

describe("classifyDiff", () => {
  const diff = ["diff --git a/A.java b/A.java", "index 1..2 100644", "--- a/A.java", "+++ b/A.java", "@@ -1,2 +1,2 @@", " same", "-old", "+new", ""].join("\n");

  it("classifies every line kind", () => {
    const kinds = classifyDiff(diff).map((l) => l.kind);
    expect(kinds).toEqual(["file", "meta", "meta", "meta", "hunk", "ctx", "del", "add", "ctx"]);
  });

  it("counts added and removed lines without the file headers", () => {
    expect(diffStats(diff)).toEqual({ added: 1, removed: 1 });
  });

  it("returns nothing for empty text and keeps markup as text", () => {
    expect(classifyDiff("")).toEqual([]);
    const [line] = classifyDiff("+<img src=x onerror=alert(1)>");
    expect(line).toEqual({ kind: "add", text: "+<img src=x onerror=alert(1)>" });
  });

  it("tolerates CRLF", () => {
    expect(classifyDiff("+a\r\n-b\r\n").map((l) => l.text)).toEqual(["+a", "-b", ""]);
  });
});

describe("looksLikePublicGitUrl", () => {
  it("accepts public https repositories", () => {
    expect(looksLikePublicGitUrl("https://github.com/acme/widgets")).toBe(true);
    expect(looksLikePublicGitUrl(" https://gitlab.com/group/sub/repo.git ")).toBe(true);
  });

  it.each([
    "http://github.com/acme/widgets",
    "git@github.com:acme/widgets.git",
    "https://localhost/a/b",
    "https://127.0.0.1/a/b",
    "https://user:pw@github.com/a/b",
    "https://github.com/a/b?x=1",
    "https://github.com/",
    "https://github.com/../etc",
    "",
  ])("rejects %s", (url) => {
    expect(looksLikePublicGitUrl(url)).toBe(false);
  });
});

describe("validateDraft", () => {
  it("accepts a good git draft and a good archive draft", () => {
    expect(validateDraft(draft(), config)).toBeNull();
    const file = new File(["x"], "repo.zip");
    expect(validateDraft(draft({ source: "archive", archive: file }), config)).toBeNull();
  });

  it("names the first problem", () => {
    expect(validateDraft(draft({ agentId: "" }), config)).toBe("problem_agentRequired");
    expect(validateDraft(draft({ instruction: "  " }), config)).toBe("problem_instructionRequired");
    expect(validateDraft(draft({ instruction: "x".repeat(4001) }), config)).toBe("problem_instructionTooLong");
    expect(validateDraft(draft({ repoUrl: "http://x.com/a/b" }), config)).toBe("problem_repoUrl");
    expect(validateDraft(draft({ source: "archive" }), config)).toBe("problem_archiveRequired");
    const big = new File([new Uint8Array(2 * 1024 * 1024)], "big.zip");
    expect(validateDraft(draft({ source: "archive", archive: big }), config)).toBe("problem_archiveTooLarge");
    expect(validateDraft(draft({ testRunner: "bash" }), config)).toBe("problem_testRunner");
    expect(validateDraft(draft({ testArgs: "test; rm -rf /" }), config)).toBe("problem_testArgs");
    expect(validateDraft(draft({ testArgs: "test $(id)" }), config)).toBe("problem_testArgs");
    expect(validateDraft(draft({ maxMinutes: "0" }), config)).toBe("problem_minutes");
    expect(validateDraft(draft({ maxMinutes: "31" }), config)).toBe("problem_minutes");
    expect(validateDraft(draft({ maxMinutes: "abc" }), config)).toBe("problem_minutes");
    expect(validateDraft(draft({ maxMinutes: "30" }), config)).toBeNull();
  });
});

describe("request building", () => {
  it("joins runner and arguments with single spaces", () => {
    expect(testCommandOf({ testRunner: "pytest", testArgs: "  -q   tests/ " })).toBe("pytest -q tests/");
    expect(testCommandOf({ testRunner: "go", testArgs: "" })).toBe("go");
  });

  it("sends the repo URL for a git source and the archive for an upload, never both", () => {
    const git = buildStartForm(draft({ maxMinutes: "10" }));
    expect(git.get("agentId")).toBe("a1");
    expect(git.get("testCommand")).toBe("mvn -q test");
    expect(git.get("repoUrl")).toBe("https://github.com/acme/widgets");
    expect(git.get("archive")).toBeNull();
    expect(git.get("maxWallSeconds")).toBe("600");

    const file = new File(["x"], "repo.zip");
    const up = buildStartForm(draft({ source: "archive", archive: file, repoUrl: "https://ignored.example/a/b" }));
    expect(up.get("repoUrl")).toBeNull();
    expect((up.get("archive") as File).name).toBe("repo.zip");
    expect(up.get("maxWallSeconds")).toBeNull();
  });

  it("carries no field that could pre-approve anything", () => {
    const keys = [...buildStartForm(draft()).keys()];
    expect(keys.filter((k) => /approv|auto|skip|confirm/i.test(k))).toEqual([]);
  });
});

describe("formatElapsed", () => {
  it("formats seconds and minutes", () => {
    expect(formatElapsed(0)).toBe("0s");
    expect(formatElapsed(42_000)).toBe("42s");
    expect(formatElapsed(65_000)).toBe("1m 05s");
    expect(formatElapsed(-5)).toBe("0s");
  });
});
