// Test kit for indexer*.test.ts: a deterministic stand-in embedder, an
// in-memory file source, the override-installing loader and the network
// poison every indexer test runs under. Not a test file itself.

import { vi } from "vitest";

/** Deterministic stand-in for MiniLM: one dimension per keyword, plus a small
 * floor so no vector is all-zero (an all-zero row makes cosine similarity 0
 * against everything and would hide ordering bugs). */
export const KEYWORDS = ["alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta"];

export function vectorFor(text: string): Float32Array {
  const lower = String(text).toLowerCase();
  const vector = new Float32Array(KEYWORDS.length);
  for (let i = 0; i < KEYWORDS.length; i++) {
    vector[i] = lower.includes(KEYWORDS[i]) ? 1 : 0.01;
  }
  return vector;
}

export interface EmbedSpy {
  (texts: string[]): Promise<Float32Array[]>;
  calls: string[][];
  reset(): void;
}

export function createEmbedSpy(onBatch?: (batch: string[]) => void): EmbedSpy {
  const calls: string[][] = [];
  const fn = (async (texts: string[]): Promise<Float32Array[]> => {
    calls.push(texts.slice());
    if (onBatch) onBatch(texts);
    return texts.map(vectorFor);
  }) as EmbedSpy;
  fn.calls = calls;
  fn.reset = () => {
    calls.length = 0;
  };
  return fn;
}

export const REPO = { id: "repo-1", name: "demo", dir: {} } as any;

export function createFileAccess(files: Record<string, string>, sizes?: Record<string, number>) {
  return {
    files,
    async listCodeFiles(): Promise<any[]> {
      return Object.keys(files).map((path: string) => ({
        path,
        size: sizes?.[path] ?? files[path].length,
        lang: "ts",
      }));
    },
    async readLocalFile(_repo: any, path: string): Promise<string> {
      const text = files[path];
      if (text === undefined) throw new Error(`no such file: ${path}`);
      return text;
    },
  };
}

export const SOURCES: Record<string, string> = {
  "src/alpha.ts": 'export function alpha() {\n  return "alpha handler";\n}\n',
  "src/beta.ts": 'export function beta() {\n  return "beta handler";\n}\n',
  "src/gamma.ts": 'export function gamma() {\n  return "gamma handler";\n}\n',
};

export async function loadIndexer(embed: EmbedSpy, access: any, signature?: string) {
  const mod = await import("./indexer");
  mod.__setIndexerOverridesForTests({ embed, files: access, embedSignature: signature } as any);
  return mod;
}

/** Every test in this file runs with the network poisoned: nothing on any path
 * under test is allowed to reach it, and two tests assert that explicitly. */
export function poisonFetch(): ReturnType<typeof vi.fn> {
  const spy = vi.fn(async () => {
    throw new Error("no network access is allowed from the local-code path");
  });
  vi.stubGlobal("fetch", spy);
  return spy;
}
