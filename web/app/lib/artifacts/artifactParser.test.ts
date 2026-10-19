import { describe, it, expect } from "vitest";
import {
  MAX_ARTIFACTS_PER_MESSAGE,
  MAX_PARSE_CHARS,
  mightContainArtifact,
  parseArtifactHeader,
  parseArtifactSegments,
  parseArtifacts,
  sanitizeLang,
  sanitizeTitle,
} from "./artifactParser";
import { ARTIFACT_KINDS, MAX_ARTIFACT_CHARS, MAX_TITLE_CHARS } from "./artifactTypes";

const FENCE = "```";

function doc(...lines: string[]): string {
  return lines.join("\n");
}

describe("parseArtifactHeader", () => {
  it("parses every documented type", () => {
    expect(parseArtifactHeader("artifact:html:Pricing page")).toEqual({ kind: "html", lang: null, title: "Pricing page" });
    expect(parseArtifactHeader("artifact:svg:Logo")).toEqual({ kind: "svg", lang: null, title: "Logo" });
    expect(parseArtifactHeader("artifact:markdown:Plan")).toEqual({ kind: "markdown", lang: null, title: "Plan" });
    expect(parseArtifactHeader("artifact:mermaid:Flow")).toEqual({ kind: "mermaid", lang: null, title: "Flow" });
    expect(parseArtifactHeader("artifact:csv:Sales")).toEqual({ kind: "csv", lang: null, title: "Sales" });
    expect(parseArtifactHeader("artifact:code:python:Fizz buzz")).toEqual({ kind: "code", lang: "python", title: "Fizz buzz" });
  });

  it("is case-insensitive and accepts the md/htm aliases", () => {
    expect(parseArtifactHeader("ARTIFACT:HTML:X")?.kind).toBe("html");
    expect(parseArtifactHeader("artifact:md:X")?.kind).toBe("markdown");
    expect(parseArtifactHeader("artifact:htm:X")?.kind).toBe("html");
  });

  it("keeps colons that belong to the title", () => {
    expect(parseArtifactHeader("artifact:markdown:Plan: v2")?.title).toBe("Plan: v2");
    expect(parseArtifactHeader("artifact:code:ts:a:b:c")?.title).toBe("a:b:c");
  });

  it("returns null for unknown types and non-artifact info strings", () => {
    expect(parseArtifactHeader("artifact:pdf:X")).toBeNull();
    expect(parseArtifactHeader("artifact:")).toBeNull();
    expect(parseArtifactHeader("js")).toBeNull();
    expect(parseArtifactHeader("")).toBeNull();
    expect(parseArtifactHeader("artifacts:html:X")).toBeNull();
  });

  it("falls back to a default title and a safe language", () => {
    expect(parseArtifactHeader("artifact:html")?.title).toBe("Untitled html");
    expect(parseArtifactHeader("artifact:html:   ")?.title).toBe("Untitled html");
    expect(parseArtifactHeader("artifact:code:py thon:T")?.lang).toBe("text");
    expect(parseArtifactHeader("artifact:code:C++:T")?.lang).toBe("c++");
  });

  it("keeps the title when the model omits the language (header drift)", () => {
    expect(parseArtifactHeader("artifact:code:My script")).toEqual({ kind: "code", lang: "text", title: "My script" });
    expect(parseArtifactHeader("artifact:code:My script: v2")).toEqual({ kind: "code", lang: "text", title: "My script: v2" });
    // A real language token still wins, and a bare language yields the default title.
    expect(parseArtifactHeader("artifact:code:python:My script")).toEqual({ kind: "code", lang: "python", title: "My script" });
    expect(parseArtifactHeader("artifact:code:python")).toEqual({ kind: "code", lang: "python", title: "Untitled code" });
    // Two different drifted titles must not collapse into one "Untitled code".
    expect(parseArtifactHeader("artifact:code:Alpha tool")?.title).not.toBe(parseArtifactHeader("artifact:code:Beta tool")?.title);
  });
});

describe("sanitizeTitle / sanitizeLang", () => {
  it("strips control and bidi-override characters and caps the length", () => {
    const bidi = String.fromCharCode(0x202e);
    const bell = String.fromCharCode(7);
    const t = sanitizeTitle(`ev${bidi}il${bell}x`, "html");
    expect(t).not.toContain(bidi);
    expect(t).not.toContain(bell);
    expect(sanitizeTitle("a".repeat(500), "html").length).toBeLessThanOrEqual(MAX_TITLE_CHARS);
  });

  it("collapses whitespace", () => {
    expect(sanitizeTitle("  a \t\n  b  ", "html")).toBe("a b");
  });

  it("rejects hostile language tokens", () => {
    expect(sanitizeLang("js")).toBe("js");
    expect(sanitizeLang("<script>")).toBe("text");
    expect(sanitizeLang("x".repeat(40))).toBe("text");
    expect(sanitizeLang(undefined)).toBe("text");
  });
});

describe("parseArtifactSegments - well-formed input", () => {
  it("splits prose, artifact, prose", () => {
    const segs = parseArtifactSegments(doc("Here you go", FENCE + "artifact:html:Pricing page", "<h1>Hi</h1>", FENCE, "Done."));
    expect(segs).toHaveLength(3);
    expect(segs[0]).toEqual({ kind: "text", text: "Here you go" });
    const a = segs[1];
    expect(a.kind).toBe("artifact");
    if (a.kind === "artifact") {
      expect(a.artifact).toEqual({
        kind: "html",
        lang: null,
        title: "Pricing page",
        content: "<h1>Hi</h1>",
        complete: true,
        truncated: false,
      });
      expect(a.blockIndex).toBe(0);
    }
    expect(segs[2]).toEqual({ kind: "text", text: "Done." });
  });

  it("returns the original text untouched when there is no artifact", () => {
    const t = "hello\n" + FENCE + "js\nlet x = 1\n" + FENCE + "\nbye";
    expect(parseArtifactSegments(t)).toEqual([{ kind: "text", text: t }]);
  });

  it("handles empty and non-string input", () => {
    expect(parseArtifactSegments("")).toEqual([]);
    expect(parseArtifactSegments(undefined)).toEqual([]);
    expect(parseArtifactSegments(null)).toEqual([]);
    expect(parseArtifactSegments(42)).toEqual([]);
    expect(parseArtifactSegments({})).toEqual([]);
  });

  it("numbers several blocks in order", () => {
    const text = doc(
      FENCE + "artifact:csv:A", "a,b", FENCE,
      "between",
      FENCE + "artifact:svg:B", "<svg></svg>", FENCE,
    );
    const arts = parseArtifactSegments(text).filter((s) => s.kind === "artifact");
    expect(arts.map((s) => (s.kind === "artifact" ? s.blockIndex : -1))).toEqual([0, 1]);
  });

  it("accepts tilde fences and CRLF line endings", () => {
    expect(parseArtifacts("~~~artifact:svg:Logo\n<svg/>\n~~~")[0].content).toBe("<svg/>");
    const crlf = parseArtifacts(FENCE + "artifact:csv:T\r\na,b\r\n" + FENCE);
    expect(crlf[0].content).toBe("a,b");
    expect(crlf[0].complete).toBe(true);
  });
});

describe("parseArtifactSegments - fences", () => {
  it("lets a 3-backtick markdown artifact contain a language-tagged code block", () => {
    const text = doc(
      FENCE + "artifact:markdown:Doc",
      "# T",
      FENCE + "js",
      "let a = 1",
      FENCE,
      "end",
      FENCE,
      "after",
    );
    const segs = parseArtifactSegments(text);
    const art = parseArtifacts(text)[0];
    expect(art.content).toBe(doc("# T", FENCE + "js", "let a = 1", FENCE, "end"));
    expect(art.complete).toBe(true);
    expect(segs[segs.length - 1]).toEqual({ kind: "text", text: "after" });
  });

  it("honours a longer outer fence exactly (CommonMark)", () => {
    const text = doc("````artifact:markdown:Doc", FENCE, "inner", FENCE, "````", "x");
    const art = parseArtifacts(text)[0];
    expect(art.content).toBe(doc(FENCE, "inner", FENCE));
    expect(art.complete).toBe(true);
  });

  it("does not treat an artifact line inside an ordinary code block as an artifact", () => {
    const text = doc(FENCE + "text", FENCE + "artifact:html:X", "<b>", FENCE, "after");
    expect(parseArtifacts(text)).toHaveLength(0);
    expect(parseArtifactSegments(text)).toEqual([{ kind: "text", text }]);
  });

  it("ignores a fence indented four or more spaces", () => {
    expect(parseArtifacts("    " + FENCE + "artifact:html:X\n<p>\n" + FENCE)).toHaveLength(0);
  });

  it("does not treat a backtick in the info string as a fence", () => {
    expect(parseArtifacts(FENCE + "artifact:html:a`b\nx\n" + FENCE)).toHaveLength(0);
  });

  it("leaves an unknown artifact type as an ordinary code block", () => {
    const text = doc(FENCE + "artifact:pdf:X", "body", FENCE);
    expect(parseArtifacts(text)).toHaveLength(0);
  });

  it("caps the number of artifacts per message; the rest stay ordinary text", () => {
    let text = "";
    for (let i = 0; i < MAX_ARTIFACTS_PER_MESSAGE + 5; i++) {
      text += FENCE + "artifact:csv:T" + i + "\na,b\n" + FENCE + "\n";
    }
    expect(parseArtifacts(text)).toHaveLength(MAX_ARTIFACTS_PER_MESSAGE);
  });
});

describe("parseArtifactSegments - streaming", () => {
  it("marks an unterminated block incomplete", () => {
    const art = parseArtifacts(doc(FENCE + "artifact:html:Page", "<p>hi"))[0];
    expect(art.complete).toBe(false);
    expect(art.content).toBe("<p>hi");
  });

  it("drops a half-streamed closing fence from the content", () => {
    const art = parseArtifacts(doc(FENCE + "artifact:html:P", "<p>x</p>", "``"))[0];
    expect(art.complete).toBe(false);
    expect(art.content).toBe("<p>x</p>");
  });

  it("hides a header that is still being typed on the last line", () => {
    expect(parseArtifactSegments("intro\n" + FENCE + "artifact:ht")).toEqual([{ kind: "text", text: "intro" }]);
    expect(parseArtifactSegments("intro\n" + FENCE + "artifact:html:Pri")).toEqual([{ kind: "text", text: "intro" }]);
    expect(parseArtifactSegments("see artifact: x\n" + FENCE + "art")).toEqual([{ kind: "text", text: "see artifact: x" }]);
  });

  it("every prefix of a well-formed message parses; content only grows and completes exactly at the closing fence", () => {
    const body = "<p>1</p>\n<p>2</p>";
    const full = "A\n" + FENCE + "artifact:html:P\n" + body + "\n" + FENCE + "\nB";
    const closeStart = full.indexOf("\n" + FENCE + "\nB") + 1;
    for (let k = 0; k <= full.length; k++) {
      const prefix = full.slice(0, k);
      const segs = parseArtifactSegments(prefix);
      for (const s of segs) {
        if (s.kind === "text") expect(s.text).not.toMatch(/```artifact/i);
      }
      const arts = parseArtifacts(prefix);
      expect(arts.length).toBeLessThanOrEqual(1);
      if (arts.length === 1) {
        const a = arts[0];
        expect(body.startsWith(a.content.replace(/\n$/, ""))).toBe(true);
        expect(a.complete).toBe(k >= closeStart + 3);
        if (a.complete) expect(a.content).toBe(body);
      }
    }
  });
});

describe("parseArtifactSegments - hostile input", () => {
  it("never interprets the body: script tags and html survive byte for byte", () => {
    const hostile = "<script>alert(document.cookie)</script><img src=x onerror=alert(1)>";
    const art = parseArtifacts(doc(FENCE + "artifact:html:X", hostile, FENCE))[0];
    expect(art.content).toBe(hostile);
  });

  it("keeps script tags in prose as plain text segments", () => {
    const segs = parseArtifactSegments("<script>x</script>\n" + FENCE + "artifact:csv:T\na,b\n" + FENCE);
    expect(segs[0]).toEqual({ kind: "text", text: "<script>x</script>" });
  });

  it("cuts a body over the cap and flags it", () => {
    const line = "x".repeat(1000) + "\n";
    const text = FENCE + "artifact:html:Big\n" + line.repeat(600) + FENCE;
    const art = parseArtifacts(text)[0];
    expect(art.truncated).toBe(true);
    expect(art.complete).toBe(true);
    expect(art.content.length).toBeLessThanOrEqual(MAX_ARTIFACT_CHARS);
  });

  it("skips parsing for absurdly large messages (one text segment)", () => {
    const huge = FENCE + "artifact:html:X\n" + "a".repeat(MAX_PARSE_CHARS + 10) + "\n" + FENCE;
    const segs = parseArtifactSegments(huge);
    expect(segs).toHaveLength(1);
    expect(segs[0].kind).toBe("text");
  });

  it("handles many unterminated fence openers in linear time", () => {
    const text = (FENCE + "artifact:html:T\n").repeat(5000);
    const t0 = Date.now();
    const segs = parseArtifactSegments(text);
    expect(Date.now() - t0).toBeLessThan(2000);
    expect(Array.isArray(segs)).toBe(true);
  });

  it("is deterministic", () => {
    const text = doc("a", FENCE + "artifact:svg:S", "<svg/>", FENCE, "b");
    expect(parseArtifactSegments(text)).toEqual(parseArtifactSegments(text));
  });
});

// Small deterministic PRNG so the fuzz run is reproducible.
function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

describe("parseArtifactSegments - fuzz", () => {
  const FRAGMENTS = [
    FENCE, "````", "~~~", "`", "``", "\n", "\n", "\r\n", " ", "    ", "artifact:", "artifact:html:T", "artifact:code:py:T",
    "artifact:svg:S", "artifact:csv:C", "artifact:mermaid:M", "artifact:pdf:Z", "<script>", "</script>", "<svg>", "a,b", "x",
    ":", "# h", "	", String.fromCharCode(0x2028), String.fromCharCode(0x202e),
  ];

  it("never throws and always returns well-formed segments", () => {
    const rnd = mulberry32(20261004);
    for (let iter = 0; iter < 400; iter++) {
      const n = 1 + Math.floor(rnd() * 80);
      let text = "";
      for (let j = 0; j < n; j++) text += FRAGMENTS[Math.floor(rnd() * FRAGMENTS.length)];
      const segs = parseArtifactSegments(text);
      let blocks = 0;
      for (const s of segs) {
        if (s.kind === "text") {
          expect(typeof s.text).toBe("string");
        } else {
          expect(s.blockIndex).toBe(blocks++);
          expect(ARTIFACT_KINDS).toContain(s.artifact.kind);
          expect(s.artifact.title.length).toBeGreaterThan(0);
          expect(s.artifact.title.length).toBeLessThanOrEqual(MAX_TITLE_CHARS);
          expect(s.artifact.content.length).toBeLessThanOrEqual(MAX_ARTIFACT_CHARS);
          expect(typeof s.artifact.complete).toBe("boolean");
        }
      }
      expect(blocks).toBeLessThanOrEqual(MAX_ARTIFACTS_PER_MESSAGE);
      // A message with no artifact blocks must come back as exactly the original text or a trimmed text-only form.
      if (!mightContainArtifact(text)) {
        expect(segs).toEqual(text === "" ? [] : [{ kind: "text", text }]);
      }
    }
  });
});

describe("parseArtifactSegments - memoisation", () => {
  it("returns the same parse for the same text and a fresh parse for changed text", () => {
    const a = "intro\n```artifact:html:Page\n<p>x</p>\n```\n";
    const first = parseArtifactSegments(a);
    expect(parseArtifactSegments(a)).toBe(first);
    expect(parseArtifactSegments(a + "more")).not.toBe(first);
    expect(first.filter((s) => s.kind === "artifact")).toHaveLength(1);
  });
});
