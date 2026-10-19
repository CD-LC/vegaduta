// Test kit for browserAi*.test.ts: fake built-in AI globals in the modern and
// legacy shapes, and the helpers that find the module's tools. Not a test file.

import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { BROWSER_AI_TOOLS } from "./browserAi";
import { type LocalTool } from "./localTools";

export function tool(name: string): LocalTool {
  const found = BROWSER_AI_TOOLS.find((t) => t.name === name);
  if (!found) throw new Error(`no such browser-AI tool: ${name}`);
  return found;
}

const GLOBAL_KEYS = ["Summarizer", "Translator", "LanguageDetector", "ai", "translation"];

export function clearGlobals(): void {
  for (const key of GLOBAL_KEYS) {
    delete (globalThis as Record<string, unknown>)[key];
  }
}

export function setGlobal(key: string, value: unknown): void {
  (globalThis as Record<string, unknown>)[key] = value;
}

export interface FakeOptions {
  availability?: string;
  /** Result of the instance method (summarize/translate/detect). */
  answer?: unknown;
  /** When set, create() rejects with this. */
  createError?: Error;
  /** When set, the instance method rejects with this. */
  answerError?: Error;
  /** When true, the instance method never settles until its signal aborts. */
  hangUntilAborted?: boolean;
  /** Bytes/fractions the monitor emits before create() resolves. */
  progress?: Array<{ loaded: number; total?: number }>;
}

export interface FakeRecord {
  createCalls: Array<Record<string, unknown>>;
  availabilityCalls: unknown[];
  answerCalls: unknown[];
  destroyCalls: number;
}

/** A modern-shape global: `availability()` + `create()`, instance method
 * `methodName`, plus destroy(). */
export function makeModern(methodName: string, opts: FakeOptions): { global: Record<string, unknown>; record: FakeRecord } {
  const record: FakeRecord = { createCalls: [], availabilityCalls: [], answerCalls: [], destroyCalls: 0 };
  const global: Record<string, unknown> = {
    availability: async (options?: unknown): Promise<string> => {
      record.availabilityCalls.push(options);
      return opts.availability === undefined ? "available" : opts.availability;
    },
    create: async (options: Record<string, unknown>): Promise<unknown> => {
      record.createCalls.push(options);
      if (opts.progress && typeof options.monitor === "function") {
        const listeners: Array<(e: unknown) => void> = [];
        (options.monitor as (m: unknown) => void)({
          addEventListener: (_type: string, fn: (e: unknown) => void): void => {
            listeners.push(fn);
          },
        });
        for (const step of opts.progress) {
          for (const fn of listeners) fn({ loaded: step.loaded, total: step.total });
        }
      }
      if (opts.createError) throw opts.createError;
      const instance: Record<string, unknown> = {
        destroy: (): void => {
          record.destroyCalls += 1;
        },
      };
      instance[methodName] = async (input: unknown, callOptions?: { signal?: AbortSignal }): Promise<unknown> => {
        record.answerCalls.push(input);
        if (opts.answerError) throw opts.answerError;
        if (opts.hangUntilAborted === true) {
          return await new Promise((_resolve, reject) => {
            const signal = callOptions?.signal;
            if (!signal) return; // never settles — the test's own timeout would catch it
            signal.addEventListener("abort", () => {
              const abortError = new Error("aborted");
              abortError.name = "AbortError";
              reject(abortError);
            });
          });
        }
        return opts.answer;
      };
      return instance;
    },
  };
  return { global, record };
}

/** A legacy-shape object: `capabilities()` -> {available} + `create()`. */
export function makeLegacyCapabilities(
  methodName: string,
  opts: FakeOptions
): { global: Record<string, unknown>; record: FakeRecord } {
  const record: FakeRecord = { createCalls: [], availabilityCalls: [], answerCalls: [], destroyCalls: 0 };
  const global: Record<string, unknown> = {
    capabilities: async (): Promise<{ available: string }> => {
      record.availabilityCalls.push(null);
      return { available: opts.availability === undefined ? "readily" : opts.availability };
    },
    create: async (options: Record<string, unknown>): Promise<unknown> => {
      record.createCalls.push(options);
      const instance: Record<string, unknown> = { destroy: (): void => void (record.destroyCalls += 1) };
      instance[methodName] = async (input: unknown): Promise<unknown> => {
        record.answerCalls.push(input);
        return opts.answer;
      };
      return instance;
    },
  };
  return { global, record };
}

/** Code of the browserAi module family (browserAi.ts and its browserAi{Surface,
 *  Session}.ts siblings), comments stripped: the headers discuss fetch and
 *  WebSocket by name while promising never to call them, so a scan must read
 *  the code, not the prose. Resolved from the vitest root (web/) rather than
 *  import.meta.url, for the reason localTools.test.ts records: under jsdom
 *  import.meta.url is an http:// URL. A wrong path throws ENOENT, so this cannot
 *  silently scan nothing. */
export const BROWSER_AI_FAMILY = ["browserAi", "browserAiSurface", "browserAiSession"];

export function readFamilyFile(name: string): string {
  return readFileSync(resolve(process.cwd(), `app/lib/edge/${name}.ts`), "utf8");
}

export function stripComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");
}
