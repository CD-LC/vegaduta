import { describe, expect, it } from "vitest";
import {
  ALIAS_PREFIX,
  assetBlob,
  buildModuleGraph,
  buildStaticDoc,
  normPath,
  resolveSpec,
  rewriteImports,
  shellDoc,
} from "./sandboxModules";

const up = (_dir: string, spec: string) => `<${spec}>`;

describe("normPath / resolveSpec", () => {
  it("treats a leading slash as project root", () => {
    expect(normPath("src/pages", "/src/util.ts")).toBe("src/util.ts");
    expect(normPath("src/pages", "../util.ts")).toBe("src/util.ts");
    expect(normPath("src", "./a//b")).toBe("src/a/b");
  });

  it("resolves root-relative specifiers against the project, extension-less", () => {
    const files = new Map([["src/util.ts", ""]]);
    expect(resolveSpec("src/pages", "/src/util", files)).toBe("src/util.ts");
  });
});

describe("rewriteImports", () => {
  it("rewrites static, side-effect, re-export and dynamic imports", () => {
    const code = [
      'import React from "react";',
      'import { a as b, c } from "./x";',
      'import * as ns from "./y";',
      'import "./style.css";',
      'export { z } from "./z";',
      'export * from "./all";',
      'const m = await import("./lazy");',
    ].join("\n");
    const out = rewriteImports(code, "src", up);
    for (const s of ["react", "./x", "./y", "./style.css", "./z", "./all", "./lazy"]) {
      expect(out).toContain(`"<${s}>"`);
    }
  });

  it("leaves string literals, template literals, comments and regexes alone", () => {
    const code = [
      'const s = "import x from \'./nope\'";',
      "const t = `import y from \"./nope2\"`;",
      '// import z from "./nope3"',
      '/* import w from "./nope4" */',
      'const r = /import q from "x"/;',
      'const o = { import: 1 }; o.import("./nope5");',
    ].join("\n");
    expect(rewriteImports(code, "src", up)).toBe(code);
  });

  it("does not touch exports that are not re-exports", () => {
    const code = 'export const a = "./thing";\nexport default "from";';
    expect(rewriteImports(code, "", up)).toBe(code);
  });

  it("handles a template literal containing braces before a real import", () => {
    const code = 'const a = `x ${ {k: "}"}.k } y`;\nimport b from "./b";';
    expect(rewriteImports(code, "", up)).toContain('"<./b>"');
  });
});

describe("assetBlob", () => {
  it("decodes base64 data URLs into bytes", async () => {
    const b = assetBlob("a.png", "data:image/png;base64,AAEC");
    expect(b?.type).toBe("image/png");
    expect(b?.size).toBe(3);
  });

  it("keeps SVG text, skips undecodable binaries", () => {
    expect(assetBlob("a.svg", "<svg/>")?.type).toBe("image/svg+xml");
    expect(assetBlob("a.png", "��PNG")).toBeNull();
  });
});

describe("buildModuleGraph", () => {
  const deps = () => {
    let n = 0;
    return {
      transpile: (_p: string, c: string) => c,
      makeUrl: (_c: string | Blob, _t: string) => `blob:test/${n++}`,
    };
  };

  it("survives circular imports by aliasing the back-edge", () => {
    const files = new Map([
      ["src/main.js", 'import "./a.js";'],
      ["src/a.js", 'import "./b.js"; export const a = 1;'],
      ["src/b.js", 'import "./a.js"; export const b = 1;'],
    ]);
    const g = buildModuleGraph(files, deps());
    expect(g).not.toBeNull();
    expect(Object.keys(g!.importMap)).toEqual([`${ALIAS_PREFIX}src/a.js`]);
    expect(g!.importMap[`${ALIAS_PREFIX}src/a.js`]).toMatch(/^blob:test/);
  });

  it("maps bare specifiers to esm.sh and leaves full URLs alone", () => {
    const files = new Map([["src/main.js", 'import R from "react"; import u from "https://x.test/u.js";']]);
    const g = buildModuleGraph(files, deps());
    expect(g).not.toBeNull();
    expect(g!.urls.length).toBe(1);
  });

  it("notes an unresolved relative import and a skipped binary asset", () => {
    const files = new Map([
      ["src/main.js", 'import "./missing"; import logo from "./logo.png";'],
      ["src/logo.png", "�binary"],
    ]);
    const g = buildModuleGraph(files, deps());
    expect(g!.notes.join(" ")).toMatch(/Unresolved import "\.\/missing"/);
    expect(g!.notes.join(" ")).toMatch(/Skipped binary asset src\/logo\.png/);
  });

  it("returns null with no entry point", () => {
    expect(buildModuleGraph(new Map([["README.md", "x"]]), deps())).toBeNull();
  });
});

describe("buildStaticDoc / shellDoc", () => {
  it("inlines referenced files and keeps unknown refs", () => {
    const files = new Map([
      ["index.html", '<link href="./style.css"><script src="app.js"></script><img src="./pic.png">'],
      ["style.css", "body{}"],
      ["app.js", "1"],
      ["pic.png", "�bin"],
    ]);
    let n = 0;
    const r = buildStaticDoc(files, () => `blob:s/${n++}`);
    expect(r!.doc).toContain('href="blob:s/0"');
    expect(r!.doc).toContain('src="blob:s/1"');
    expect(r!.doc).toContain('src="./pic.png"');
    expect(r!.notes[0]).toMatch(/pic\.png/);
  });

  it("emits an import map only when needed", () => {
    expect(shellDoc("blob:e")).not.toContain("importmap");
    expect(shellDoc("blob:e", { "vdmod:a": "blob:a" })).toContain('type="importmap"');
  });
});
