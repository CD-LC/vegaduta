import { describe, it, expect, vi } from "vitest";
import {
  FORBIDDEN_SANDBOX_FLAGS,
  SANDBOX_PAUSED,
  SANDBOX_RUN,
  artifactFrameProps,
  artifactSandbox,
  buildArtifactCsp,
  buildArtifactDoc,
  previewVerdict,
  sanitizeArtifactHtml,
  stripNavigationHelpers,
  svgDataUrl,
  svgPreviewVerdict,
} from "./artifactFrame";
import { MAX_PREVIEW_BYTES } from "./artifactTypes";

describe("sandbox attribute", () => {
  it("is empty (everything denied) until Run, then exactly allow-scripts", () => {
    expect(artifactSandbox(false)).toBe("");
    expect(artifactSandbox(true)).toBe("allow-scripts");
    expect(SANDBOX_PAUSED).toBe("");
    expect(SANDBOX_RUN).toBe("allow-scripts");
  });

  it("never contains allow-same-origin or any other forbidden flag, in either state", () => {
    for (const run of [false, true]) {
      const flags = artifactFrameProps(run).sandbox.split(/\s+/).filter(Boolean);
      expect(flags).not.toContain("allow-same-origin");
      for (const forbidden of FORBIDDEN_SANDBOX_FLAGS) expect(flags).not.toContain(forbidden);
      expect(artifactFrameProps(run).sandbox).not.toMatch(/same-origin/);
    }
  });

  it("only ever grants allow-scripts", () => {
    const granted = artifactFrameProps(true).sandbox.split(/\s+/).filter(Boolean);
    expect(granted).toEqual(["allow-scripts"]);
  });

  it("sends no referrer and grants no feature policy", () => {
    const p = artifactFrameProps(true);
    expect(p.referrerPolicy).toBe("no-referrer");
    expect(p.allow).toBe("");
  });
});

describe("CSP", () => {
  it("blocks script until Run, then allows inline script only", () => {
    expect(buildArtifactCsp(false)).toContain("script-src 'none'");
    const run = buildArtifactCsp(true);
    expect(run).toContain("script-src 'unsafe-inline'");
    expect(run).not.toContain("unsafe-eval");
    expect(run).not.toMatch(/script-src[^;]*https?:/);
  });

  it("denies network, frames, objects, base and forms in both states", () => {
    for (const run of [false, true]) {
      const csp = buildArtifactCsp(run);
      expect(csp).toContain("default-src 'none'");
      expect(csp).toContain("connect-src 'none'");
      expect(csp).toContain("frame-src 'none'");
      expect(csp).toContain("object-src 'none'");
      expect(csp).toContain("base-uri 'none'");
      expect(csp).toContain("form-action 'none'");
      expect(csp).toContain("img-src data: blob:");
      expect(csp).toContain("style-src 'unsafe-inline'");
    }
  });
});

describe("buildArtifactDoc", () => {
  it("puts the CSP meta before any artifact byte", () => {
    const doc = buildArtifactDoc("<p>hello</p>", false);
    expect(doc.indexOf("Content-Security-Policy")).toBeGreaterThan(-1);
    expect(doc.indexOf("Content-Security-Policy")).toBeLessThan(doc.indexOf("<p>hello</p>"));
    expect(doc).toContain("script-src 'none'");
  });

  it("uses the run policy only when asked", () => {
    expect(buildArtifactDoc("x", true)).toContain("script-src 'unsafe-inline'");
    expect(buildArtifactDoc("x", false)).not.toContain("script-src 'unsafe-inline'");
  });

  it("strips meta refresh and base tags from the artifact", () => {
    const hostile = '<meta http-equiv="refresh" content="0;url=https://evil.example"><base href="https://evil.example/"><p>x</p>';
    const doc = buildArtifactDoc(hostile, true);
    expect(doc).not.toMatch(/http-equiv\s*=\s*["']?refresh/i);
    expect(doc).not.toMatch(/<base\b/i);
    expect(doc).toContain("<p>x</p>");
    expect(stripNavigationHelpers("<META HTTP-EQUIV=refresh CONTENT='0;url=x'>")).toBe("");
  });

  it("removes entity-encoded and quote-confused meta refresh (zero-click navigation) that a regex misses", () => {
    const bypasses = [
      '<meta http-equiv="re&#102;resh" content="0;url=https://evil.example">',
      '<meta http-equiv="&#x72;efresh" content="0;url=https://evil.example">',
      '<meta content="a>" http-equiv="refresh">',
      "<meta content='0;url=https://evil.example' http-equiv='refresh'>",
      '<meta/http-equiv="refresh"/content="0;url=https://evil.example">',
      "<META\nHTTP-EQUIV=refresh\nCONTENT=0;url=https://evil.example>",
      '<noscript><meta http-equiv="refresh" content="0;url=https://evil.example"></noscript>',
      '<head><meta http-equiv="refresh" content="0;url=https://evil.example"></head><body><p>x</p></body>',
      '<link rel="prefetch" href="https://evil.example/"><base href="https://evil.example/">',
    ];
    for (const attack of bypasses) {
      for (const run of [false, true]) {
        const doc = buildArtifactDoc(`${attack}<p>kept</p>`, run);
        // Only our own meta tags may remain: charset, CSP, referrer, viewport.
        const parsed = new DOMParser().parseFromString(doc, "text/html");
        const metas = Array.from(parsed.querySelectorAll("meta")).map((m) => m.getAttribute("http-equiv") || m.getAttribute("name") || (m.hasAttribute("charset") ? "charset" : "?"));
        expect(metas).toEqual(["charset", "Content-Security-Policy", "referrer", "viewport"]);
        expect(parsed.querySelector("base, link")).toBeNull();
        expect(doc).not.toMatch(/evil\.example/);
        expect(doc).toContain("<p>kept</p>");
      }
    }
  });

  it("keeps the artifact's own styles, scripts and body attributes", () => {
    const doc = buildArtifactDoc('<!doctype html><html><head><style>p{color:red}</style></head><body class="dark" style="margin:0"><p>x</p><script>1</script></body></html>', true);
    expect(doc).toContain("<style>p{color:red}</style>");
    expect(doc).toContain("<script>1</script>");
    expect(doc).toMatch(/<body class="dark" style="margin:0">/);
  });

  it("fails closed (empty body) when the DOM parser is unavailable", () => {
    vi.stubGlobal("DOMParser", undefined);
    try {
      expect(sanitizeArtifactHtml("<p>x</p>")).toBeNull();
      expect(stripNavigationHelpers("<p>x</p>")).toBe("");
      const doc = buildArtifactDoc('<meta http-equiv="refresh" content="0;url=https://evil.example"><p>x</p>', false);
      expect(doc).not.toContain("evil.example");
      expect(doc).not.toContain("<p>x</p>");
      expect(doc).toContain("Content-Security-Policy");
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("does not let artifact html close our head before the CSP (the head is already closed)", () => {
    const doc = buildArtifactDoc("</head><script>1</script>", false);
    const cspAt = doc.indexOf("Content-Security-Policy");
    const headClose = doc.indexOf("</head>");
    expect(cspAt).toBeLessThan(headClose);
  });
});

describe("preview verdict", () => {
  it("refuses incomplete, truncated, empty and oversize bodies", () => {
    expect(previewVerdict("<p>x</p>", false, false)).toEqual({ status: "refused", reason: "incomplete" });
    expect(previewVerdict("<p>x</p>", true, true)).toEqual({ status: "refused", reason: "truncated" });
    expect(previewVerdict("   ", true, false)).toEqual({ status: "refused", reason: "empty" });
    expect(previewVerdict("a".repeat(MAX_PREVIEW_BYTES + 1), true, false)).toEqual({ status: "refused", reason: "too-large" });
  });

  it("accepts a normal complete body", () => {
    expect(previewVerdict("<p>x</p>", true, false)).toEqual({ status: "ok" });
  });
});

describe("svg data url", () => {
  it("builds an image data url, never inline markup", () => {
    const url = svgDataUrl('<svg xmlns="http://www.w3.org/2000/svg"><circle r="1"/></svg>');
    expect(url).not.toBeNull();
    expect(url!.startsWith("data:image/svg+xml;charset=utf-8,")).toBe(true);
    expect(url).not.toContain("<");
  });

  it("adds the svg namespace when the model forgot it", () => {
    const url = svgDataUrl("<svg width='10'><rect/></svg>");
    expect(decodeURIComponent(url!.split(",").slice(1).join(","))).toContain('xmlns="http://www.w3.org/2000/svg"');
  });

  it("refuses non-svg content, entity declarations, incomplete and oversize bodies", () => {
    expect(svgDataUrl("<p>not an svg</p>")).toBeNull();
    expect(svgPreviewVerdict("<p>x</p>", true, false).reason).toBe("not-svg");
    const bomb = '<!DOCTYPE svg [<!ENTITY a "aaaa">]><svg xmlns="http://www.w3.org/2000/svg">&a;</svg>';
    expect(svgDataUrl(bomb)).toBeNull();
    expect(svgPreviewVerdict(bomb, true, false).reason).toBe("unsafe");
    expect(svgDataUrl("<svg></svg>", false, false)).toBeNull();
    expect(svgDataUrl("<svg>" + "a".repeat(MAX_PREVIEW_BYTES) + "</svg>")).toBeNull();
  });

  it("a script inside an svg stays inert text inside the data url", () => {
    const url = svgDataUrl('<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>');
    expect(url).not.toBeNull();
    // Percent-encoded: no raw tag survives into the attribute value.
    expect(url).not.toContain("<script");
  });
});
