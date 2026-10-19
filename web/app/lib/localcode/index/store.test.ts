import { describe, expect, it } from "vitest";
import { KEY_SEP, chunkKey, fileKey, getStore, type ChunkRecord } from "./store";

describe("localcode index store", () => {
  it("builds collision-free compound keys", () => {
    expect(fileKey("r", "a/b.ts")).toBe(`r${KEY_SEP}a/b.ts`);
    expect(chunkKey("r", "c1")).toBe(`r${KEY_SEP}c1`);
  });

  it("round-trips files and chunks and clears", async () => {
    const store = await getStore();
    await store.clearAll();
    await store.putFile({
      key: fileKey("r", "a.ts"),
      repoId: "r",
      path: "a.ts",
      hash: "h",
      model: "m",
      chunks: 1,
      indexedAt: 1,
    });
    const chunk: ChunkRecord = {
      key: chunkKey("r", "c1"),
      repoId: "r",
      repoPath: `r${KEY_SEP}a.ts`,
      id: "c1",
      path: "a.ts",
      startLine: 1,
      endLine: 2,
      text: "x",
      model: "m",
      vector: new Float32Array([1, 0]),
    };
    await store.putChunks([chunk]);
    expect((await store.getFile("r", "a.ts"))?.hash).toBe("h");
    expect(await store.listChunks("r")).toHaveLength(1);
    await store.deleteChunksForFile("r", "a.ts");
    expect(await store.listChunks("r")).toHaveLength(0);
    await store.clearAll();
    expect(await store.listFiles("r")).toHaveLength(0);
  });
});
