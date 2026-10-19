import { describe, expect, it } from "vitest";
import { appCheck, fixRequestFromFindings, toHex } from "./appCheck";
import { dataToCsv, formatAppData, parseAppData, tabularKeys, tableCsv } from "./dataTab";
import { collapseContext, diffStats, lineDiff } from "./lineDiff";
import { onColor, readTheme, writeTheme, THEME_PRESETS, DEFAULT_TOKENS } from "./theme";
import { TEMPLATES } from "./templates";
import { CONNECT_END, CONNECT_START } from "./connect";

const GOOD = `<!doctype html><html lang="en"><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>A</title></head><body><img src="a.png" alt="logo"><button style="min-height:48px">Go</button></body></html>`;

describe("appCheck", () => {
  it("passes a clean app", () => {
    expect(appCheck(GOOD)).toEqual([]);
  });
  it("flags missing viewport, lang, title and alt", () => {
    const ids = appCheck("<html><head></head><body><img src=x><img src=y alt=''></body></html>").map((f) => f.id);
    expect(ids).toEqual(expect.arrayContaining(["viewport", "lang", "title", "img-alt"]));
    expect(appCheck("<html><body><img src=x><img src=y alt=''></body></html>").find((f) => f.id === "img-alt")!.title).toMatch(/^1 image has/);
  });
  it("flags small inline tap targets but accepts min-height", () => {
    const bad = appCheck(GOOD.replace('style="min-height:48px"', 'style="height:30px;width:30px"'));
    expect(bad.map((f) => f.id)).toContain("tap-target");
    const ok = appCheck(GOOD.replace('style="min-height:48px"', 'style="height:30px;min-height:44px"'));
    expect(ok.map((f) => f.id)).not.toContain("tap-target");
  });
  it("flags low-contrast inline pairs and theme tokens", () => {
    const inline = appCheck(GOOD.replace("<button", '<p style="color:#bbb;background:#fff">x</p><button'));
    expect(inline.map((f) => f.id)).toContain("contrast");
    const themed = writeTheme(GOOD, { text: "#cccccc" });
    expect(appCheck(themed).map((f) => f.id)).toContain("contrast");
    expect(appCheck(writeTheme(GOOD, {})).map((f) => f.id)).not.toContain("contrast");
  });
  it("ignores the generated vd:connect block", () => {
    const withBlock = GOOD.replace("</body>", `${CONNECT_START}<img src="z"><script>1</script>${CONNECT_END}</body>`);
    expect(appCheck(withBlock)).toEqual([]);
  });
  it("builds one fix request from the findings", () => {
    const f = appCheck("<html><body></body></html>");
    const r = fixRequestFromFindings(f);
    expect(r).toContain("viewport");
    expect(fixRequestFromFindings([])).toBe("");
  });
  it("normalises colours", () => {
    expect(toHex("#abc")).toBe("#aabbcc");
    expect(toHex("rgb(255, 0, 0)")).toBe("#ff0000");
    expect(toHex("var(--x)")).toBeNull();
  });
});

describe("dataTab", () => {
  const data = { tasks: JSON.stringify([{ t: "a", done: false }, { t: "b, c", done: true }]), name: "Asha", quoted: JSON.stringify("hi"), n: "3" };
  it("round-trips through the JSON editor losslessly", () => {
    const back = parseAppData(formatAppData(data));
    expect("data" in back && back.data.name).toBe("Asha");
    expect("data" in back && back.data.quoted).toBe(JSON.stringify("hi"));
    expect("data" in back && JSON.parse(back.data.tasks)).toEqual(JSON.parse(data.tasks));
    expect("data" in back && back.data.n).toBe("3");
  });
  it("rejects bad input", () => {
    expect(parseAppData("{oops")).toHaveProperty("error");
    expect(parseAppData("[1,2]")).toHaveProperty("error");
    expect(parseAppData("")).toEqual({ data: {} });
  });
  it("exports tables and key/value CSV with escaping", () => {
    expect(tabularKeys(data)).toEqual(["tasks"]);
    expect(tableCsv(data.tasks)).toBe('t,done\r\na,false\r\n"b, c",true\r\n');
    expect(dataToCsv(data, "tasks")).toBe(tableCsv(data.tasks));
    expect(dataToCsv({ a: "=1+1" }).split("\r\n")[1]).toBe("a,'=1+1");
    expect(dataToCsv({ a: "x" })).toBe("key,value\r\na,x\r\n");
  });
});

describe("lineDiff", () => {
  it("diffs a small edit", () => {
    const d = lineDiff("a\nb\nc", "a\nB\nc\nd");
    expect(d.map((l) => l.kind + ":" + l.text)).toEqual(["same:a", "del:b", "add:B", "same:c", "add:d"]);
    expect(diffStats(d)).toEqual({ added: 2, removed: 1 });
  });
  it("is empty-diff for identical text and handles empty sides", () => {
    expect(diffStats(lineDiff("x\ny", "x\ny"))).toEqual({ added: 0, removed: 0 });
    expect(diffStats(lineDiff("", "a\nb"))).toEqual({ added: 2, removed: 1 });
  });
  it("collapses unchanged context", () => {
    const a = Array.from({ length: 30 }, (_, i) => "l" + i).join("\n");
    const rows = collapseContext(lineDiff(a, a.replace("l15", "X")), 2);
    expect(rows.filter((r) => r.kind === "gap")).toHaveLength(2);
    expect(rows.length).toBeLessThan(12);
  });
});

describe("on-primary text colour", () => {
  it("picks dark text on light primaries and white on dark ones", () => {
    expect(onColor("#4ade80")).toBe("#111111"); // Forest
    expect(onColor("#ffffff")).toBe("#111111");
    expect(onColor("#6d5dfc")).toBe("#ffffff");
    expect(onColor("#000000")).toBe("#ffffff");
    expect(onColor("not-a-colour")).toBe("#ffffff");
  });
  it("is written into every theme block and survives a read", () => {
    const forest = THEME_PRESETS.find((p) => p.name === "Forest")!;
    const html = writeTheme(TEMPLATES[0].html, forest.tokens);
    expect(html).toContain("--on-primary: #111111;");
    expect(readTheme(html)).not.toBeNull();
    expect(writeTheme(html, { primary: DEFAULT_TOKENS.primary })).toContain("--on-primary: #ffffff;");
  });
});

import { vi } from "vitest";
import { MAX_PROJECTS, loadProjects, newProject, saveNotice, saveProjectsDetailed } from "./store";
import { withHarness } from "./runtime";
import { REMIX_TARGETS, remixPrompt } from "../gallery/galleryHelpers";

describe("store quota handling", () => {
  it("never drops a project silently past the cap", () => {
    localStorage.clear();
    const list = Array.from({ length: MAX_PROJECTS + 1 }, (_, i) => newProject("p" + i, "x", "<html></html>", "n", "template"));
    expect(saveProjectsDetailed(list).ok).toBe(true);
    expect(loadProjects()).toHaveLength(MAX_PROJECTS + 1);
  });
  it("trims versions then data, and says so; reports failure when nothing fits", () => {
    const big = newProject("big", "x", "a".repeat(400), "n", "template");
    big.versions = Array.from({ length: 8 }, (_, i) => ({ html: "h" + i + "x".repeat(300), note: "n", source: "manual" as const, at: i + 1 }));
    big.appData = { k: "d".repeat(3000) };
    const spy = vi.spyOn(Storage.prototype, "setItem").mockImplementation((_k, v) => {
      if (String(v).length > 1500) throw new Error("quota");
    });
    const r = saveProjectsDetailed([big]);
    expect(r.ok).toBe(true);
    expect(r.trimmedVersions).toBe(true);
    expect(r.trimmedData).toBe(true);
    expect(saveNotice(r)).toMatch(/dropped/);
    spy.mockImplementation(() => {
      throw new Error("quota");
    });
    const bad = saveProjectsDetailed([big]);
    expect(bad.ok).toBe(false);
    expect(saveNotice(bad)).toMatch(/NOT saved/);
    spy.mockRestore();
  });
});

describe("harness + remix", () => {
  it("shims sessionStorage and understands the env message", () => {
    const h = withHarness("<html><head></head><body></body></html>");
    expect(h).toContain("sessionStorage");
    expect(h).toContain('d.vdStudio==="env"');
    expect(h).toContain("e.source!==P");
  });
  it("builds an adapt prompt per remix target", () => {
    expect(REMIX_TARGETS.map((r) => r.id)).toEqual(["bakery", "clinic", "salon", "tuition"]);
    expect(remixPrompt("Menu", "Tuition centre")).toContain("my tuition centre");
  });
});
