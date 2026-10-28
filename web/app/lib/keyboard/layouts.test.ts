// lib/keyboard/layouts — the locale→layout map and the lazy loader.
//
// The thing that can quietly rot here is the map itself: a locale points at a
// module name, and if that file is renamed or dropped upstream the toggle
// still lights up but the board never loads. These tests load EVERY mapped
// layout for real — a stale name fails the suite, not the user.

import { describe, expect, it } from "vitest";

import { SUPPORTED_LOCALES } from "../i18n-config";
import { KEYBOARD_LOCALES, keyboardLayoutFor, loadKeyboardLayout } from "./layouts";

describe("keyboardLayoutFor", () => {
  it("maps every supported non-Latin locale to a layout or null — never throws", () => {
    for (const { code } of SUPPORTED_LOCALES) {
      expect(() => keyboardLayoutFor(code)).not.toThrow();
    }
  });

  it("leaves English without a board — same script, nothing to gain", () => {
    expect(keyboardLayoutFor("en")).toBeNull();
  });

  it("keeps locale codes without a layout out of the advertised set", () => {
    for (const code of KEYBOARD_LOCALES) {
      expect(keyboardLayoutFor(code)).not.toBeNull();
    }
  });
});

describe("loadKeyboardLayout", () => {
  it("resolves null for an unmapped locale", async () => {
    await expect(loadKeyboardLayout("en")).resolves.toBeNull();
  });

  it("loads rows for every mapped locale — the map stays honest", async () => {
    const failures: string[] = [];
    for (const code of KEYBOARD_LOCALES) {
      const data = await loadKeyboardLayout(code);
      const rows = data?.layout?.default;
      if (!Array.isArray(rows) || rows.length === 0 || rows.some((r) => typeof r !== "string" || !r.trim())) {
        failures.push(`${code}→${keyboardLayoutFor(code)}`);
      }
    }
    expect(failures).toEqual([]);
  });

  it("keeps the special tokens the renderer understands", async () => {
    const data = await loadKeyboardLayout("hi");
    const text = data?.layout?.default?.join(" ") ?? "";
    expect(text).toContain("{space}");
    expect(text).toContain("{enter}");
  });
});
