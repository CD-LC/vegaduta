import { describe, expect, it } from "vitest";
import { buildPath, isRecordList, listOf, placeholdersOf } from "./ApiCallPanel";
import { isSafeApiPath, validateUiSpec } from "./schema";

describe("apiCall node validation", () => {
  const spec = (props: Record<string, unknown>) => validateUiSpec({ version: "v1", root: { type: "apiCall", props } }).spec!.root;

  it("keeps a same-origin /api path and upper-cases the verb", () => {
    const n = spec({ method: "get", path: "/api/agents", title: "Agents" });
    expect(n.type).toBe("apiCall");
    expect(n.props?.method).toBe("GET");
  });

  it.each(["https://evil.test/api/x", "//evil.test/api/x", "/api/../admin", "/api/%2e%2e/x", "/other", "/api/a\\b"])(
    "refuses %s",
    (path) => {
      expect(isSafeApiPath(path)).toBe(false);
      expect(spec({ method: "GET", path }).type).toBe("__unsupported__");
    }
  );

  it("refuses an unknown verb", () => {
    expect(spec({ method: "TRACE", path: "/api/agents" }).type).toBe("__unsupported__");
  });
});

describe("apiCall helpers", () => {
  it("finds path placeholders and fills them, encoded, with the query", () => {
    expect(placeholdersOf("/api/a/{id}/b/{x}")).toEqual(["id", "x"]);
    expect(buildPath("/api/a/{id}", { id: "a b" }, { page: 2, skip: { n: 1 } })).toBe("/api/a/a%20b?page=2");
  });

  it("recognises lists, including page-shaped responses", () => {
    expect(isRecordList([{ a: 1 }])).toBe(true);
    expect(isRecordList([1])).toBe(false);
    expect(listOf({ content: [{ a: 1 }], total: 1 })).toEqual([{ a: 1 }]);
    expect(listOf({ a: 1 })).toBeNull();
  });
});
