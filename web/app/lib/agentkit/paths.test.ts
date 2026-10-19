// agentkit/paths — confinement, secret detection, include globs.
//
// Every path here is what a model (or text in a repo steering a model) could
// plausibly emit. The assertions are about the BOUNDARY: what is refused, and
// that what is accepted comes out normalised and still inside the root.

import { describe, expect, it } from "vitest";

import { compileIncludeGlob, confinePath, dirCanMatchPrefix, includeLiteralPrefix, isSecretPath, isVcsInternalPath } from "./paths";

describe("agentkit/paths confinePath", () => {
  it("normalises ordinary relative paths, backslashes and wrapping quotes", () => {
    expect(confinePath("src/app/page.tsx")).toEqual({ ok: true, path: "src/app/page.tsx", segments: ["src", "app", "page.tsx"] });
    expect(confinePath("src\\lib\\a.ts")).toMatchObject({ ok: true, path: "src/lib/a.ts" });
    expect(confinePath("./src//a.ts")).toMatchObject({ ok: true, path: "src/a.ts" });
    expect(confinePath('"src/a.ts"')).toMatchObject({ ok: true, path: "src/a.ts" });
    expect(confinePath("app/[slug]/(group)/page.tsx")).toMatchObject({ ok: true });
  });

  it('treats "." and "" as the root itself', () => {
    expect(confinePath(".")).toEqual({ ok: true, path: "", segments: [] });
    expect(confinePath("")).toEqual({ ok: true, path: "", segments: [] });
  });

  const escapes: Array<[string, unknown]> = [
    ["parent traversal", "../secrets.txt"],
    ["traversal in the middle", "src/../../etc/passwd"],
    ["backslash traversal", "src\\..\\..\\windows\\system32"],
    ["absolute posix", "/etc/passwd"],
    ["windows drive", "C:\\Users\\me\\.ssh\\id_rsa"],
    ["drive-relative", "C:secrets"],
    ["UNC share", "\\\\server\\share\\file"],
    ["home directory", "~/.ssh/id_rsa"],
    ["file URL", "file:///etc/passwd"],
    ["http URL", "https://example.com/x"],
    ["NUL byte", "src/a.ts\0.png"],
    ["dots-only segment (Windows strips trailing dots)", "src/.../a.ts"],
    ["alternate data stream", "src/a.ts:stream"],
    ["reserved device name", "src/nul"],
    ["reserved device name with extension", "con.txt"],
    ["not a string", 42],
    ["null", null],
    ["absurdly long", "a/".repeat(2000)],
  ];
  for (const [label, input] of escapes) {
    it(`refuses ${label}`, () => {
      const result = confinePath(input);
      expect(result.ok).toBe(false);
      expect((result as { reason: string }).reason).toBeTruthy();
    });
  }

  it("never returns a path containing a traversal segment, whatever it accepted", () => {
    for (const input of ["a/./b", "a//b", "  a/b  ", "`a/b`"]) {
      const result = confinePath(input);
      expect(result.ok).toBe(true);
      expect((result as { segments: string[] }).segments).not.toContain("..");
    }
  });
});

describe("agentkit/paths isSecretPath", () => {
  const secret = [
    ".env",
    ".env.local",
    "apps/web/.env.production",
    "prod.env",
    "deploy/tls/server.pem",
    "certs/private.key",
    "id_rsa",
    "keys/id_ed25519.pub",
    ".ssh/config",
    "home/.aws/credentials",
    "config/secrets.json",
    "infra/terraform.tfvars",
    "infra/terraform.tfstate",
    "android/upload.keystore",
    ".npmrc",
    "WINDOWS\\Path\\.ENV",
  ];
  for (const path of secret) it(`flags ${path}`, () => expect(isSecretPath(path)).toBe(true));

  const ordinary = ["src/env.ts", "README.md", "src/keyboard.ts", "docs/environment.md", "app/lib/auth.ts", "package.json", ""];
  for (const path of ordinary) it(`does not flag ${path || "(empty)"}`, () => expect(isSecretPath(path)).toBe(false));
});

describe("agentkit/paths compileIncludeGlob", () => {
  it("reads an empty, match-all or unusable pattern as NO filter rather than match-nothing", () => {
    for (const glob of ["", "  ", "**", "**/*", undefined, 7, "src/{a,b"]) expect(compileIncludeGlob(glob)).toBeNull();
  });

  it("matches the dialect search_text advertises", () => {
    const srcTree = compileIncludeGlob("src/**");
    expect(srcTree?.test("src/a/b.ts")).toBe(true);
    expect(srcTree?.test("lib/a.ts")).toBe(false);

    const anyTs = compileIncludeGlob("*.ts");
    expect(anyTs?.test("deep/down/a.ts")).toBe(true);
    expect(anyTs?.test("a.tsx")).toBe(false);

    const braces = compileIncludeGlob("**/*.{ts,tsx}");
    expect(braces?.test("a.tsx")).toBe(true);
    expect(braces?.test("x/y/a.ts")).toBe(true);
    expect(braces?.test("x/y/a.js")).toBe(false);

    // A literal dot must not act as a regex wildcard.
    expect(compileIncludeGlob("a.ts")?.test("abts")).toBe(false);
  });
});

describe("agentkit/paths — .git and include prefixes", () => {
  it("recognises .git at any depth and in any case, and nothing that merely resembles it", () => {
    for (const path of [".git", ".git/config", ".git/hooks/pre-commit", "vendor/lib/.git/config", ".GIT/config", ".git\\config"]) {
      expect(isVcsInternalPath(path)).toBe(true);
    }
    for (const path of [".github/workflows/ci.yml", ".gitignore", ".gitattributes", "src/git/index.ts", "docs/.gitkeep", "a.git/x"]) {
      expect(isVcsInternalPath(path)).toBe(false);
    }
  });

  it("treats .git-credentials as a secret", () => {
    expect(isSecretPath(".git-credentials")).toBe(true);
    expect(isSecretPath("home/.git-credentials")).toBe(true);
  });

  it("extracts only the wildcard-free leading FOLDERS of an include glob", () => {
    expect(includeLiteralPrefix("src/**")).toEqual(["src"]);
    expect(includeLiteralPrefix("./Src/lib/**/*.ts")).toEqual(["src", "lib"]);
    expect(includeLiteralPrefix("src/a.ts")).toEqual(["src"]);
    expect(includeLiteralPrefix("**/*.ts")).toEqual([]);
    expect(includeLiteralPrefix("*.ts")).toEqual([]);
    expect(includeLiteralPrefix("src/{a,b}/**")).toEqual(["src"]);
    expect(includeLiteralPrefix("")).toEqual([]);
    expect(includeLiteralPrefix(undefined)).toEqual([]);
    expect(includeLiteralPrefix("{unclosed/x")).toEqual([]);
  });

  it("descends only into folders that lead to, or sit under, the prefix", () => {
    expect(dirCanMatchPrefix(["src"], ["src", "lib"])).toBe(true);
    expect(dirCanMatchPrefix(["SRC", "lib", "deep"], ["src", "lib"])).toBe(true);
    expect(dirCanMatchPrefix(["docs"], ["src", "lib"])).toBe(false);
    expect(dirCanMatchPrefix(["src", "app"], ["src", "lib"])).toBe(false);
    expect(dirCanMatchPrefix(["anything"], [])).toBe(true);
  });

  it("never prunes a folder the compiled glob would have matched a file in", () => {
    for (const glob of ["src/**", "src/lib/*.ts", "src/**/*.ts", "a/b/c/**"]) {
      const compiled = compileIncludeGlob(glob)!;
      const prefix = includeLiteralPrefix(glob);
      for (const path of ["src/a.ts", "src/lib/a.ts", "src/lib/deep/a.ts", "a/b/c/d.ts", "docs/a.ts"]) {
        if (!compiled.test(path)) continue;
        const dirs = path.split("/").slice(0, -1);
        for (let depth = 1; depth <= dirs.length; depth += 1) {
          expect(dirCanMatchPrefix(dirs.slice(0, depth), prefix)).toBe(true);
        }
      }
    }
  });
});
