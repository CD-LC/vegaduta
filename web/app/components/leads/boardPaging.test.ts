import { describe, expect, it } from "vitest";
import type { Lead } from "../../lib/leads";
import { boardIsTruncated, mergeLeadsById, nextColumnPage, remainingInColumn } from "./boardPaging";

const lead = (id: string) => ({ id }) as Lead;

describe("board column paging", () => {
  it("picks the server page holding the next unseen rows", () => {
    expect(nextColumnPage(0)).toBe(0);
    expect(nextColumnPage(37)).toBe(0);
    expect(nextColumnPage(100)).toBe(1);
    expect(nextColumnPage(250, 100)).toBe(2);
  });
  it("merges without duplicates and keeps identity when nothing is new", () => {
    const a = [lead("1"), lead("2")];
    expect(mergeLeadsById(a, [lead("2"), lead("3")]).map((l) => l.id)).toEqual(["1", "2", "3"]);
    expect(mergeLeadsById(a, [lead("1")])).toBe(a);
  });
  it("computes remaining and truncation", () => {
    expect(remainingInColumn(130, 100)).toBe(30);
    expect(remainingInColumn(5, 9)).toBe(0);
    expect(boardIsTruncated(450, 200)).toBe(true);
    expect(boardIsTruncated(200, 200)).toBe(false);
  });
});
