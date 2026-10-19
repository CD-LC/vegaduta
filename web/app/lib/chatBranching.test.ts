import { describe, expect, it } from "vitest";
import {
  activePathFromMap,
  canBranch,
  childrenOf,
  collectExtras,
  mergeServerPath,
  navigateSibling,
  nextLeaf,
  siblingIdAt,
  siblingLabel,
  siblingsOf,
  toMessageMap,
  turnsForEdit,
  turnsForRegenerate,
  turnsFromPath,
  userTextBefore,
  type BranchPath,
  type BranchTurn,
  type TreeNode,
} from "./chatBranching";

// u1 -> a1 -> u2 -> a2            (original)
//        \       \-> u2b -> a2b   (edited u2)
//         \-> (a1b is a regenerated a1, a sibling of a1 under u1)
function node(id: string, parentId: string | null, role: "user" | "assistant", order: number, createdAt = order): TreeNode {
  return { id, parentId, role, content: `${role}:${id}`, order, createdAt };
}

const nodes: TreeNode[] = [
  node("u1", null, "user", 0),
  node("a1", "u1", "assistant", 0),
  node("u2", "a1", "user", 0),
  node("a2", "u2", "assistant", 0),
  node("u2b", "a1", "user", 1),
  node("a2b", "u2b", "assistant", 0),
  node("a1b", "u1", "assistant", 1),
];
const map = toMessageMap(nodes);

describe("activePathFromMap", () => {
  it("walks from the root down to the leaf", () => {
    expect(activePathFromMap(map, "a2b").map((n) => n.id)).toEqual(["u1", "a1", "u2b", "a2b"]);
    expect(activePathFromMap(map, "a2").map((n) => n.id)).toEqual(["u1", "a1", "u2", "a2"]);
  });

  it("is empty for a null or unknown leaf", () => {
    expect(activePathFromMap(map, null)).toEqual([]);
    expect(activePathFromMap(map, undefined)).toEqual([]);
    expect(activePathFromMap(map, "nope")).toEqual([]);
  });

  it("stops on a parent cycle instead of looping", () => {
    const cyc = toMessageMap([node("x", "y", "user", 0), node("y", "x", "assistant", 0)]);
    expect(activePathFromMap(cyc, "x").map((n) => n.id)).toEqual(["y", "x"]);
  });

  it("treats a linear NULL-parent chain as one path with a single version per message", () => {
    const linear = toMessageMap([node("q", null, "user", 0), node("r", "q", "assistant", 0)]);
    expect(activePathFromMap(linear, "r").map((n) => n.id)).toEqual(["q", "r"]);
    expect(siblingsOf(linear, "r")).toEqual({ ids: ["r"], index: 0, count: 1 });
    expect(navigateSibling(linear, "r", 1)).toBeNull();
  });
});

describe("siblings", () => {
  it("lists every version in order with the position of the one asked about", () => {
    expect(siblingsOf(map, "u2b")).toEqual({ ids: ["u2", "u2b"], index: 1, count: 2 });
    expect(siblingsOf(map, "a1")).toEqual({ ids: ["a1", "a1b"], index: 0, count: 2 });
  });

  it("treats root messages as versions of each other", () => {
    const m = toMessageMap([...nodes, node("u1b", null, "user", 1)]);
    expect(siblingsOf(m, "u1")).toEqual({ ids: ["u1", "u1b"], index: 0, count: 2 });
  });

  it("returns null for an unknown message", () => {
    expect(siblingsOf(map, "nope")).toBeNull();
  });

  it("orders ties on `order` by creation time", () => {
    const m = toMessageMap([node("p", null, "user", 0), node("c2", "p", "assistant", 0, 20), node("c1", "p", "assistant", 0, 10)]);
    expect(childrenOf(m, "p").map((n) => n.id)).toEqual(["c1", "c2"]);
  });
});

describe("navigateSibling", () => {
  it("moves one version and clamps at both ends", () => {
    expect(navigateSibling(map, "u2", 1)).toBe("u2b");
    expect(navigateSibling(map, "u2b", -1)).toBe("u2");
    expect(navigateSibling(map, "u2b", 1)).toBeNull();
    expect(navigateSibling(map, "u2", -1)).toBeNull();
    expect(navigateSibling(map, "nope", 1)).toBeNull();
  });
});

describe("nextLeaf", () => {
  it("descends into the newest child until there is none", () => {
    // Under a1 the newest child is u2b, whose only child is a2b.
    expect(nextLeaf(map, "a1")).toBe("a2b");
    // The original u2 lands on its own leaf, not on the edited branch.
    expect(nextLeaf(map, "u2")).toBe("a2");
    expect(nextLeaf(map, "a2")).toBe("a2");
  });

  it("is null for a null or unknown start", () => {
    expect(nextLeaf(map, null)).toBeNull();
    expect(nextLeaf(map, "nope")).toBeNull();
  });
});

describe("siblingLabel", () => {
  it("is 1-based", () => {
    expect(siblingLabel(0, 3)).toBe("1/3");
    expect(siblingLabel(1, 2)).toBe("2/2");
  });
});

function serverPath(): BranchPath {
  return {
    sessionId: "s1",
    activeLeafId: "a1",
    messages: [
      { id: "u1", parentId: null, role: "user", content: "hello", siblingIndex: 0, siblingCount: 1 },
      { id: "a1", parentId: "u1", role: "assistant", content: "hi there", siblingIndex: 1, siblingCount: 2 },
    ],
  };
}

describe("mergeServerPath", () => {
  it("attaches ids and version positions while keeping everything local", () => {
    const turns: BranchTurn[] = [
      { role: "user", content: "hello" },
      { role: "assistant", content: "hi there (streamed)", steps: [{ id: 1 }], ref: "turn-1" },
    ];
    const merged = mergeServerPath(turns, serverPath()) ?? [];
    expect(merged).toHaveLength(2);
    expect(merged[1]).toMatchObject({ messageId: "a1", siblingIndex: 1, siblingCount: 2, ref: "turn-1", content: "hi there (streamed)" });
    expect(merged[0]).toMatchObject({ messageId: "u1", siblingCount: 1 });
    // The input is not mutated.
    expect(turns[0].messageId).toBeUndefined();
  });

  it("refuses to line up turns that differ in length or role", () => {
    expect(mergeServerPath([{ role: "user", content: "hello" }], serverPath())).toBeNull();
    expect(
      mergeServerPath(
        [
          { role: "assistant", content: "x" },
          { role: "user", content: "y" },
        ],
        serverPath()
      )
    ).toBeNull();
  });
});

describe("turnsFromPath / collectExtras", () => {
  it("rebuilds turns from the server path and restores remembered extras by message id", () => {
    const before: BranchTurn[] = [
      { role: "user", content: "hello", messageId: "u1", siblingIndex: 0, siblingCount: 1 },
      { role: "assistant", content: "old text", messageId: "a1", siblingIndex: 1, siblingCount: 2, steps: ["s"], ref: "turn-9" },
    ];
    const extras = collectExtras(before);
    expect(extras.a1).toEqual({ steps: ["s"], ref: "turn-9" });
    expect(extras.u1).toEqual({});
    const rebuilt = turnsFromPath(serverPath(), extras);
    expect(rebuilt[1]).toMatchObject({ role: "assistant", content: "hi there", messageId: "a1", ref: "turn-9", steps: ["s"] });
  });

  it("collectExtras skips turns the server has not numbered yet", () => {
    expect(collectExtras([{ role: "user", content: "x" }])).toEqual({});
  });
});

describe("local turn shaping", () => {
  const turns: BranchTurn[] = [
    { role: "user", content: "q1" },
    { role: "assistant", content: "r1" },
    { role: "user", content: "q2" },
    { role: "assistant", content: "r2" },
  ];

  it("an edit keeps what came before the message and starts a fresh exchange", () => {
    expect(turnsForEdit(turns, 2, "q2 edited")).toEqual([
      { role: "user", content: "q1" },
      { role: "assistant", content: "r1" },
      { role: "user", content: "q2 edited" },
      { role: "assistant", content: "" },
    ]);
  });

  it("a regenerate keeps the user message and starts a fresh reply", () => {
    expect(turnsForRegenerate(turns, 3)).toEqual([
      { role: "user", content: "q1" },
      { role: "assistant", content: "r1" },
      { role: "user", content: "q2" },
      { role: "assistant", content: "" },
    ]);
  });

  it("finds the user text a regenerate answers", () => {
    expect(userTextBefore(turns, 3)).toBe("q2");
    expect(userTextBefore(turns, 0)).toBeNull();
    expect(userTextBefore([{ role: "assistant", content: "a" }, { role: "assistant", content: "b" }], 1)).toBeNull();
  });
});

describe("canBranch", () => {
  it("is false once any reply was answered on-device", () => {
    expect(canBranch([{ role: "user", content: "a" }, { role: "assistant", content: "b" }])).toBe(true);
    expect(canBranch([{ role: "assistant", content: "b", edgeTier: 1 }])).toBe(false);
    expect(canBranch([{ role: "assistant", content: "b", edgeTier: null }])).toBe(true);
  });
});

describe("siblingIdAt", () => {
  const siblings = { ids: ["a", "b", "c"], index: 1 };
  it("steps within the list", () => {
    expect(siblingIdAt(siblings, -1)).toBe("a");
    expect(siblingIdAt(siblings, 1)).toBe("c");
  });
  it("is null past either end", () => {
    expect(siblingIdAt({ ids: ["a", "b", "c"], index: 0 }, -1)).toBeNull();
    expect(siblingIdAt({ ids: ["a", "b", "c"], index: 2 }, 1)).toBeNull();
  });
});
