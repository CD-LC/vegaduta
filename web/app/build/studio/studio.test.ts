import { describe, expect, it } from "vitest";
import { parse } from "@babel/parser";
import { applyEditBlocks, dedupeSkeletonHelpers, extractHtmlDocument, firstBrokenScript, looksLikeValidApp, parseEditBlocks, setScriptChecker } from "./patch";
import { readAppBlock, stripConnections, writeConnections } from "./connect";
import { aliasOf, extractResponsePath, renderConnectionTemplate } from "./payload";
import { contrastRatio, readTheme, writeTheme } from "./theme";
import { BLANK_APP, TEMPLATES, VEGADUTA_SAMPLE_APP, matchTemplate } from "./templates";
import { HARNESS_LINES, findInSource, withHarness } from "./runtime";
import { addVersion, newProject, MAX_VERSIONS } from "./store";
import { describeTarget, fixRequest, fromModelView, isWholePageRequest, locateRuntimeError, toModelView } from "./scope";
import { editPrompt } from "./engine";
import { parseQuestions, shouldClarify } from "./buildPlan";
import { splitThink } from "./thinking";
import { studioSuggestions } from "./suggestions";

const APP = `<!doctype html>
<html><head><style>
  h1 { color: red; }
</style></head>
<body>
  <h1>Hello</h1>
  <p>World</p>
<script>
  const x = 1;
</script>
</body></html>`;

describe("patch", () => {
  // The browser registers Babel (app/lib/babel.ts); tests register the real
  // @babel/parser so the script-syntax gate is exercised end to end.
  setScriptChecker((body, opts) => {
    parse(body, { sourceType: opts.module ? "module" : "script" });
  });

  it("parses blocks surrounded by prose and fences", () => {
    const reply = "Sure! Here:\n```\n<<<<<<< SEARCH\n  <h1>Hello</h1>\n=======\n  <h1>Hi there</h1>\n>>>>>>> REPLACE\n```\nDone.";
    const blocks = parseEditBlocks(reply);
    expect(blocks).toHaveLength(1);
    expect(blocks[0].search).toBe("  <h1>Hello</h1>");
    expect(blocks[0].replace).toBe("  <h1>Hi there</h1>");
  });

  it("applies exact and indentation-insensitive matches, reports failures", () => {
    const blocks = [
      { search: "  <h1>Hello</h1>", replace: "  <h1>Hi</h1>" },
      { search: "<p>World</p>\n<script>", replace: "<p>Earth</p>\n<script>" }, // indentation differs
      { search: "<nav>missing</nav>", replace: "x" },
    ];
    const r = applyEditBlocks(APP, blocks);
    expect(r.applied).toBe(2);
    expect(r.failed).toHaveLength(1);
    expect(r.html).toContain("<h1>Hi</h1>");
    expect(r.html).toContain("<p>Earth</p>");
    expect(r.html).not.toContain("World");
  });

  it("empty SEARCH appends before </body>", () => {
    const r = applyEditBlocks(APP, [{ search: "", replace: "<footer>f</footer>" }]);
    expect(r.applied).toBe(1);
    expect(r.html.indexOf("<footer>")).toBeLessThan(r.html.indexOf("</body>"));
  });

  it("extracts a full document and rejects a truncated one", () => {
    expect(extractHtmlDocument("here you go\n" + APP + "\nenjoy")).toBe(APP);
    expect(extractHtmlDocument(APP.slice(0, 80))).toBeNull();
  });

  it("parses blocks with loose marker widths and casing", () => {
    const reply = "Sure!\n<<< search\n  <h1>Hello</h1>\n====\n  <h1>Hi</h1>\n>>> REPLACE\nDone.";
    const blocks = parseEditBlocks(reply);
    expect(blocks).toHaveLength(1);
    expect(blocks[0].search).toBe("  <h1>Hello</h1>");
    expect(blocks[0].replace).toBe("  <h1>Hi</h1>");
  });

  it("salvages a document truncated after its last closing tag", () => {
    const truncated = APP.slice(0, APP.indexOf("</body>")); // ends at </script>
    const doc = extractHtmlDocument("Here:\n" + truncated + "\nlet me know");
    expect(doc).not.toBeNull();
    expect(looksLikeValidApp(doc!)).toBe(true);
    expect(doc).toContain("</html>");
    expect(doc).not.toContain("let me know");
  });

  it("rejects a document cut mid-script", () => {
    const cut = APP.slice(0, APP.indexOf("</script>"));
    expect(extractHtmlDocument(cut)).toBeNull();
  });

  it("validates app structure", () => {
    expect(looksLikeValidApp(APP)).toBe(true);
    expect(looksLikeValidApp(APP.replace("</script>", ""))).toBe(false);
  });

  it("does not split a block on a comment divider inside its body", () => {
    // A `// ====== Section ======` divider inside the REPLACE body must not
    // be mistaken for the SEARCH/REPLACE separator - that splice is how a
    // raw `=======` line ended up inside <script> ("Unexpected token '==='").
    const reply =
      "<<<<<<< SEARCH\n  <h1>Hello</h1>\n=======\n  <h1>Hi</h1>\n  // ====== greeting section ======\n  render();\n>>>>>>> REPLACE";
    const blocks = parseEditBlocks(reply);
    expect(blocks).toHaveLength(1);
    expect(blocks[0].replace).toContain("greeting section");
    expect(blocks[0].replace).toContain("render()");
  });

  it("rejects a document with a leaked edit-marker line", () => {
    const leaked = APP.replace("const x = 1;", "const x = 1;\n=======\nconst y = 2;");
    expect(looksLikeValidApp(leaked)).toBe(false);
    const leakedSearch = APP.replace("const x = 1;", "const x = 1;\n<<<<<<< SEARCH\nconst y = 2;");
    expect(looksLikeValidApp(leakedSearch)).toBe(false);
  });

  it("rejects a document whose inline script does not parse, and names the script", () => {
    // "Uncaught SyntaxError ... Unexpected token 'const'" - the doc ships dead
    // unless validation catches it before commit.
    const broken = APP.replace("const x = 1;", "const x = 1;\nconst x = ;");
    expect(firstBrokenScript(broken)).toContain("const x = ;");
    expect(looksLikeValidApp(broken)).toBe(false);
  });

  it("still accepts a valid app and a type=module script", () => {
    expect(looksLikeValidApp(APP)).toBe(true);
    const mod = APP.replace("<script>", '<script type="module">').replace(
      "const x = 1;",
      'import { x } from "./y.js";\nexport const z = x;'
    );
    expect(looksLikeValidApp(mod)).toBe(true);
  });

  it("parseQuestions reads only real question lines, READY means build now", () => {
    expect(parseQuestions("READY")).toEqual([]);
    expect(parseQuestions("")).toEqual([]);
    expect(parseQuestions("Looks clear enough, build it.")).toEqual([]);
    expect(parseQuestions("1. Who uses this?\n2. What goes on the main screen?\n3. Which features matter most?")).toEqual([
      "Who uses this?",
      "What goes on the main screen?",
      "Which features matter most?",
    ]);
    // a fourth question is dropped, prose without a ? is dropped
    const four = "Who uses this app?\nWhat goes on screen?\nWhich features matter?\nWhat else changes?\nsome note";
    expect(parseQuestions(four)).toHaveLength(3);
  });

  it("shouldClarify skips a detailed brief", () => {
    expect(shouldClarify("a todo app")).toBe(true);
    expect(shouldClarify("x".repeat(241))).toBe(false);
  });

  it("splitThink separates streamed reasoning from the reply", () => {
    expect(splitThink("hello")).toEqual({ thinking: "", visible: "hello" });
    const r = splitThink("pre<think>reasoning here</think>answer");
    expect(r.thinking).toBe("reasoning here");
    expect(r.visible).toBe("preanswer");
    // an unclosed block mid-stream is still reasoning, not visible text
    expect(splitThink("<think>still thinking").visible).toBe("");
  });

  it("studioSuggestions follows what the app lacks", () => {
    const plain = studioSuggestions(APP);
    expect(plain.length).toBeGreaterThan(0);
    expect(plain.length).toBeLessThanOrEqual(3);
    // an app that already persists, searches and dark-modes gets the tail rules
    const rich = APP.replace("const x = 1;", "save('k',[]); load('k',[]); function searchInput(){} function settings(){} function addItem(){} function undo(){} dark mode");
    expect(studioSuggestions(rich).map((s) => s.label)).not.toContain("Add a dark mode");
  });

  it("skips the script check when no parser is registered", () => {
    setScriptChecker(null);
    try {
      const broken = APP.replace("const x = 1;", "const x = ;");
      expect(firstBrokenScript(broken)).toBeNull();
      expect(looksLikeValidApp(broken)).toBe(true);
    } finally {
      setScriptChecker((body, opts) => {
        parse(body, { sourceType: opts.module ? "module" : "script" });
      });
    }
  });
});

describe("theme", () => {
  it("reads and writes tokens without touching the rest", () => {
    const t = TEMPLATES[0];
    const theme = readTheme(t.html);
    expect(theme?.primary).toBe("#f97316");
    const next = writeTheme(t.html, { primary: "#123456", radius: "4px" });
    expect(readTheme(next)?.primary).toBe("#123456");
    expect(readTheme(next)?.radius).toBe("4px");
    expect(next.replace(/\/\* studio:theme:start[\s\S]*studio:theme:end \*\//, "")).toBe(
      t.html.replace(/\/\* studio:theme:start[\s\S]*studio:theme:end \*\//, "")
    );
  });

  it("refuses values that could break out of the style block", () => {
    const next = writeTheme(TEMPLATES[0].html, { primary: "red;}</style><script>alert(1)</script>" });
    expect(next).not.toContain("alert(1)");
  });

  it("injects a block when missing", () => {
    expect(readTheme(writeTheme(APP, { primary: "#000000" }))?.primary).toBe("#000000");
  });

  it("computes contrast", () => {
    expect(contrastRatio("#000000", "#ffffff")).toBeCloseTo(21, 0);
    expect(contrastRatio("nope", "#fff")).toBeNull();
  });
});

describe("templates", () => {
  it.each([...TEMPLATES.map((t) => [t.id, t.html]), ["blank", BLANK_APP]])("%s is a valid, themed, offline app", (_id, html) => {
    expect(looksLikeValidApp(html)).toBe(true);
    expect(readTheme(html)).not.toBeNull();
    expect(html).not.toMatch(/<script[^>]+src=/i);
    expect(html).not.toMatch(/<link[^>]+stylesheet/i);
    // Small enough to fit a 4k-token on-device context alongside a prompt.
    expect(html.length).toBeLessThan(9000);
  });

  it("scripts parse", () => {
    for (const t of TEMPLATES) {
      const js = t.html.match(/<script>([\s\S]*)<\/script>/)?.[1] || "";
      expect(() => new Function(js)).not.toThrow();
    }
  });

  it("VEGADUTA_SAMPLE_APP is a valid, themed, offline app", () => {
    const html = VEGADUTA_SAMPLE_APP.html;
    expect(looksLikeValidApp(html)).toBe(true);
    expect(readTheme(html)).not.toBeNull();
    expect(readTheme(html)?.primary).toBe("#f4521e");
    expect(html).toContain("VegaDūta");
    expect(html).not.toMatch(/<script[^>]+src=/i);
    expect(html).not.toMatch(/<link[^>]+stylesheet/i);
    expect(html).not.toMatch(/fetch\(|XMLHttpRequest/i);
    expect(html.length).toBeLessThan(9000);
    const js = html.match(/<script>([\s\S]*)<\/script>/)?.[1] || "";
    expect(() => new Function(js)).not.toThrow();
  });

  it("VEGADUTA_SAMPLE_APP stays out of the starter grid", () => {
    expect(TEMPLATES).toHaveLength(6);
    expect(TEMPLATES.find((t) => t.id === VEGADUTA_SAMPLE_APP.id)).toBeUndefined();
  });

  it("matches descriptions to starters", () => {
    expect(matchTemplate("an order app for my bakery with WhatsApp")?.id).toBe("shop");
    expect(matchTemplate("track my monthly spending")?.id).toBe("expenses");
    expect(matchTemplate("a game about space invaders")).toBeNull();
    expect(matchTemplate("A simple tip calculator: bill amount, tip percent, people, and a result line")).toBeNull();
  });
});

describe("runtime", () => {
  it("injects the harness first in <head>", () => {
    const out = withHarness(APP, { a: "1" });
    expect(out.indexOf("vdStudio")).toBeLessThan(out.indexOf("<style>"));
    expect(out).toContain('{"a":"1"}');
  });

  it("escapes </script> in the storage seed", () => {
    expect(withHarness(APP, { x: "</script><b>" })).not.toContain('"</script><b>"');
  });

  it("finds a tapped element in the source by path", () => {
    const doc = new DOMParser().parseFromString(APP, "text/html");
    const el = findInSource(doc, { path: [1], tag: "p", text: "", html: "", color: "", background: "", fontSize: "" });
    expect(el?.textContent).toBe("World");
    expect(findInSource(doc, { path: [1], tag: "h1", text: "", html: "", color: "", background: "", fontSize: "" })).toBeNull();
  });
});

describe("store", () => {
  it("dedupes identical versions and caps history", () => {
    let p = newProject("x", "✨", "a", "start", "template");
    p = addVersion(p, "a", "same", "manual");
    expect(p.versions).toHaveLength(1);
    for (let i = 0; i < MAX_VERSIONS + 5; i++) p = addVersion(p, `v${i}`, "n", "device");
    expect(p.versions).toHaveLength(MAX_VERSIONS);
  });
});

describe("connect", () => {
  const cxn = [
    { kind: "agent" as const, id: "a-1", shareId: "sh-abc", name: "Support bot" },
    { kind: "workflow" as const, id: "w-1", shareId: "sh-def", name: "Daily report" },
  ];

  it("splices the block before </body> with the shareIds embedded", () => {
    const out = writeConnections(APP, cxn);
    expect(out).toContain("<!-- vd:connect -->");
    expect(out).toContain("sh-abc");
    expect(out).toContain("sh-def");
    expect(out.indexOf("vd:connect")).toBeLessThan(out.indexOf("</body>"));
    expect(looksLikeValidApp(out)).toBe(true);
  });

  it("rewrites idempotently and strips cleanly when empty", () => {
    const once = writeConnections(APP, cxn);
    const twice = writeConnections(once, cxn.slice(0, 1));
    expect(twice).not.toContain("sh-def");
    expect(twice.match(/<!-- vd:connect -->/g)).toHaveLength(1);
    expect(stripConnections(once)).toBe(APP);
    expect(writeConnections(once, [])).toBe(APP);
  });

  it("appends the block when there is no </body>", () => {
    const out = writeConnections("<p>hi</p>", cxn);
    expect(out).toContain("vd:connect");
    expect(out.startsWith("<p>hi</p>")).toBe(true);
  });

  it("carries the published-app config (slug, auth, ai) for vd.call/vd.ai/vd.auth", () => {
    const out = writeConnections(APP, cxn, {
      publish: { appId: "app-1", slug: "sl-ug1_234567890", publicUrl: "https://app.x/run-app.html#a=sl", publishedAt: 1 },
      auth: { mode: "optional" },
      ai: { enabled: true },
    });
    expect(out).toContain("window.vd.call");
    expect(out).toContain("window.vd.ai");
    expect(out).toContain("window.vd.auth");
    const block = readAppBlock(out);
    expect(block?.slug).toBe("sl-ug1_234567890");
    expect(block?.auth).toBe("optional");
    expect(block?.ai).toBe(true);
  });

  it("writes the runtime for a published slug even with no connections", () => {
    const out = writeConnections(APP, [], {
      publish: { appId: "a", slug: "sl-ug1_234567890", publicUrl: "", publishedAt: 1 },
      ai: { enabled: true },
    });
    expect(out).toContain("vd:connect");
    expect(readAppBlock(out)?.slug).toBe("sl-ug1_234567890");
  });

  it("keeps vd.* share helpers working for unpublished apps (shareId fallback)", () => {
    const out = writeConnections(APP, cxn);
    const block = readAppBlock(out);
    expect(block?.slug ?? null).toBeNull();
    expect(out).toContain("sh-abc");
  });
});

describe("payload", () => {
  it("renders input/user/data placeholders and leaves unknown ones", () => {
    expect(renderConnectionTemplate("Summarise {{input}}", "hello")).toBe("Summarise hello");
    expect(
      renderConnectionTemplate("For {{user.name}} <{{user.email}}>: {{input}} / {{data.city}} / {{mystery}}", "hi", { city: "Pune" }, {
        name: "Asha",
        email: "a@x.io",
      })
    ).toBe("For Asha <a@x.io>: hi / Pune / {{mystery}}");
    expect(renderConnectionTemplate(null, "raw")).toBe("raw");
    expect(renderConnectionTemplate("", "raw")).toBe("raw");
    expect(renderConnectionTemplate("{{user.name}} said {{input}}", "hey", {}, null)).toBe(" said hey");
  });

  it("extracts dotted paths from JSON, falls back to the raw body", () => {
    const body = JSON.stringify({ reply: "hi", data: { items: [{ name: "first" }] } });
    expect(extractResponsePath("reply", "reply", body)).toBe("hi");
    expect(extractResponsePath("data.items.0.name", "reply", body)).toBe("first");
    expect(extractResponsePath("missing.path", "reply", body)).toBe(body);
    expect(extractResponsePath("reply", "reply", "not json")).toBe("not json");
    expect(extractResponsePath(null, "resultText", JSON.stringify({ resultText: "done" }))).toBe("done");
  });

  it("derives unique, valid aliases from names", () => {
    const taken = new Set<string>();
    const a = aliasOf("Support Bot!", taken);
    expect(a).toBe("support-bot");
    taken.add(a);
    expect(aliasOf("Support Bot!", taken)).toBe("support-bot-2");
    expect(aliasOf("123")).toBe("service");
    expect(aliasOf("")).toBe("service");
  });
});

describe("scope", () => {
  it("flags whole-page requests only", () => {
    for (const r of ["Redesign the whole app", "change the entire page to dark", "revamp it", "Rebuild the UI from scratch", "update all the screens", "complete makeover please", "redesign"]) {
      expect(isWholePageRequest(r), r).toBe(true);
    }
    for (const r of ["Make it more colourful", "Add a search box", "rewrite the page title", "make the button bigger", "redesign the button", "change the full page title"]) {
      expect(isWholePageRequest(r), r).toBe(false);
    }
  });

  it("hides the connect block and its shareIds from the model, then restores it", () => {
    const cx = [{ kind: "agent" as const, id: "a1", shareId: "SECRET-SHARE-123", name: "Support Bot", alias: "support-bot" }];
    const full = writeConnections(APP, cx, {});
    const view = toModelView(full, cx);
    expect(view.html).not.toContain("SECRET-SHARE-123");
    expect(view.html).toContain("support-bot");
    expect(view.html.length).toBeLessThan(full.length - 2000);
    const prompt = editPrompt(view.html, "make it blue", null);
    expect(prompt).not.toContain("SECRET-SHARE-123");
    const edited = applyEditBlocks(view.html, [{ search: "  <h1>Hello</h1>", replace: "  <h1>Hi</h1>" }]);
    const back = fromModelView(edited.html, view);
    expect(back).toContain("<h1>Hi</h1>");
    expect(back).toContain("SECRET-SHARE-123");
    expect(stripConnections(back)).toBe(stripConnections(full).replace("<h1>Hello</h1>", "<h1>Hi</h1>"));
  });

  it("leaves apps without connections untouched", () => {
    const view = toModelView(APP, []);
    expect(view.html).toBe(APP);
    expect(fromModelView("x", view)).toBe("x");
  });

  it("describes the tapped element without its markup", () => {
    const sel = { path: [0], tag: "h1", text: "Hello", html: "<h1>Hello</h1>", color: "", background: "", fontSize: "" };
    const d = describeTarget(sel, APP);
    expect(d).toContain("<h1>");
    expect(d).toContain('"Hello"');
    expect(d).toContain("in the APP markup");
    const runtime = describeTarget({ ...sel, path: [9], tag: "li" }, APP);
    expect(runtime).toContain("runtime");
  });
});

describe("fix it with AI", () => {
  const BUGGY = `<!doctype html>
<html><head><style>h1{color:red}</style></head>
<body>
  <h1 id="t">Count</h1>
<script>
  let n = 0;
  document.getElementById("t").addEventListener("click", () => {
    n = n + 1;
    document.getElementById("missing").textContent = n;
  });
</script>
</body></html>`;

  const srcdocLineOf = (doc: string, needle: string) => doc.split("\n").findIndex((l) => l.includes(needle)) + 1;

  it("maps the preview's line number back to the failing source line", () => {
    const doc = withHarness(BUGGY, { a: "1" });
    const previewLine = srcdocLineOf(doc, 'getElementById("missing")');
    expect(previewLine).toBeGreaterThan(HARNESS_LINES);
    const where = locateRuntimeError(BUGGY, { message: "Cannot set properties of null", line: previewLine }, HARNESS_LINES);
    expect(where.line).toBe(srcdocLineOf(BUGGY, 'getElementById("missing")'));
    expect(where.code).toBe('document.getElementById("missing").textContent = n;');
    expect(where.nearby).toContain("addEventListener");
  });

  it("reads the line from a stack when the event has none (promise rejections)", () => {
    const doc = withHarness(BUGGY);
    const previewLine = srcdocLineOf(doc, "n = n + 1;");
    const where = locateRuntimeError(
      BUGGY,
      { message: "boom", line: 0, stack: `Error: boom\n    at HTMLHeadingElement.<anonymous> (about:srcdoc:${previewLine}:7)` },
      HARNESS_LINES
    );
    expect(where.code).toBe("n = n + 1;");
  });

  it("flags errors raised inside the hidden connect runtime and points at the app's call", () => {
    const cx = [{ kind: "agent" as const, id: "a", shareId: "S", name: "Bot", alias: "bot" }];
    const app = writeConnections(BUGGY, cx, {});
    const doc = withHarness(app);
    const runtimeLine = srcdocLineOf(doc, "vd:connect") + 1;
    const appLine = srcdocLineOf(doc, "n = n + 1;");
    const where = locateRuntimeError(
      app,
      { message: "Unknown call name", line: 0, stack: `Error\n at x (about:srcdoc:${runtimeLine}:5)\n at y (about:srcdoc:${appLine}:3)` },
      HARNESS_LINES
    );
    expect(where.inStudioRuntime).toBe(true);
    expect(where.code).toBe("n = n + 1;");
    const req = fixRequest({ message: "Unknown call name" }, where, true);
    expect(req).toContain("must not be edited");
    expect(req).toContain("different approach");
    expect(req).not.toContain('"S"');
  });

  it("a fix reply copied from APP applies and keeps the connections", () => {
    const cx = [{ kind: "agent" as const, id: "a", shareId: "SHARE-9", name: "Bot", alias: "bot" }];
    const app = writeConnections(BUGGY, cx, {});
    const view = toModelView(app, cx);
    const where = locateRuntimeError(app, { message: "null", line: srcdocLineOf(withHarness(app), 'getElementById("missing")') }, HARNESS_LINES);
    const prompt = editPrompt(view.html, fixRequest({ message: "null" }, where), null);
    expect(prompt).toContain('document.getElementById("missing").textContent = n;');
    const reply = [
      "<<<<<<< SEARCH",
      '    document.getElementById("missing").textContent = n;',
      "=======",
      '    document.getElementById("t").textContent = "Count " + n;',
      ">>>>>>> REPLACE",
    ].join("\n");
    const r = applyEditBlocks(view.html, parseEditBlocks(reply));
    expect(r.applied).toBe(1);
    const fixed = fromModelView(r.html, view);
    expect(fixed).toContain('"Count " + n');
    expect(fixed).toContain("SHARE-9");
    expect(looksLikeValidApp(fixed)).toBe(true);
  });
});

describe("dedupeSkeletonHelpers", () => {
  const pre = (extra: string) =>
    `<script>\nconst esc = s => String(s);\nconst load = (k, d) => d;\nconst save = (k, v) => {};\n${extra}\n</script>`;

  it("drops the skeleton helper when the model redeclares it at top level", () => {
    const out = dedupeSkeletonHelpers(pre("const esc = s => s.trim();\nfunction load(k) { return 1; }"));
    expect(out.match(/\besc =/g)).toHaveLength(1);
    expect(out).toContain("s.trim()");
    expect(out).not.toContain("const load =");
    expect(out).toContain("const save =");
  });

  it("leaves a function-local (indented) redeclaration alone", () => {
    const html = pre("function r() {\n  const esc = s => s;\n}");
    expect(dedupeSkeletonHelpers(html)).toBe(html);
  });

  it("is a no-op when nothing collides", () => {
    expect(dedupeSkeletonHelpers(BLANK_APP)).toBe(BLANK_APP);
    for (const t of TEMPLATES) expect(dedupeSkeletonHelpers(t.html)).toBe(t.html);
  });
});
