// @vitest-environment node
import { describe, expect, it } from "vitest";
import { JSDOM, VirtualConsole } from "jsdom";
import { GALLERY, SERVER_GALLERY, STUDIO_GALLERY } from "./galleryData";
import { externalHosts } from "./galleryHelpers";
import { QR_JS } from "./qr";
import { readTheme } from "../studio/theme";

// Hosts a template may mention: link targets the person taps, and the SVG
// XML namespace (an identifier, never fetched).
const ALLOWED_HOSTS = new Set(["wa.me", "example.com", "www.w3.org"]);

function run(html: string): { dom: InstanceType<typeof JSDOM>; errors: string[] } {
  const errors: string[] = [];
  const vc = new VirtualConsole();
  vc.on("jsdomError", (e: { message?: unknown }) => errors.push(String(e.message)));
  const dom = new JSDOM(html, { runScripts: "dangerously", url: "https://example.test/", virtualConsole: vc, pretendToBeVisual: true });
  dom.window.addEventListener("error", (e: { message: string }) => errors.push(String(e.message)));
  return { dom, errors };
}

describe("gallery catalogue", () => {
  it("has at least 8 studio templates and 3 server templates with unique ids", () => {
    expect(STUDIO_GALLERY.length).toBeGreaterThanOrEqual(8);
    expect(SERVER_GALLERY.length).toBe(3);
    const ids = GALLERY.map((g) => g.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it("covers the required starting points", () => {
    const names = STUDIO_GALLERY.map((t) => t.id).join(" ");
    for (const k of ["landing", "portfolio", "menu", "booking", "link-in-bio", "dashboard", "quiz", "expense"]) expect(names).toContain(k);
  });
});

describe.each(STUDIO_GALLERY.map((t) => [t.id, t] as const))("studio template %s", (_id, t) => {
  it("is a complete document with a title and a studio theme block", () => {
    const doc = new JSDOM(t.html).window.document;
    expect(doc.title.trim().length).toBeGreaterThan(0);
    expect(t.html.startsWith("<!doctype html>")).toBe(true);
    expect(doc.querySelector("meta[name=viewport]")).not.toBeNull();
    expect(readTheme(t.html)).not.toBeNull();
  });

  it("makes no network calls and points nowhere but allowed hosts", () => {
    expect(t.html).not.toMatch(/\bfetch\s*\(|XMLHttpRequest|WebSocket|EventSource|sendBeacon|importScripts|@import|\bimport\s*\(/);
    expect(t.html).not.toMatch(/<script[^>]+\bsrc\s*=|<link[^>]+href|<iframe|<img[^>]+src\s*=\s*["']https?:/i);
    expect(t.html).not.toMatch(/localhost|127\.0\.0\.1/i);
    for (const h of externalHosts(t.html)) expect(ALLOWED_HOSTS.has(h), `${t.id} mentions ${h}`).toBe(true);
  });

  it("stays small enough to edit with an on-device model window", () => {
    expect(t.html.length).toBeLessThan(24_000);
  });

  it("boots without a runtime error", () => {
    const { dom, errors } = run(t.html);
    expect(errors).toEqual([]);
    expect(dom.window.document.body.textContent!.trim().length).toBeGreaterThan(20);
    dom.window.close();
  });
});

describe("template behaviour", () => {
  const html = (id: string) => STUDIO_GALLERY.find((t) => t.id === id)!.html;

  it("quiz scores a correct answer and reaches the result screen", () => {
    const { dom, errors } = run(html("g-quiz"));
    const d = dom.window.document;
    const answers = [1, 1, 2, 0, 1];
    for (const a of answers) {
      (d.querySelectorAll(".opt")[a] as HTMLElement).click();
      (d.getElementById("next") as HTMLElement).click();
    }
    expect(d.querySelector(".score")!.textContent).toBe("5/5");
    expect(errors).toEqual([]);
  });

  it("expense tracker adds an expense and totals it", () => {
    const { dom } = run(html("g-expense-tracker"));
    const d = dom.window.document;
    (d.getElementById("amt") as HTMLInputElement).value = "250";
    d.getElementById("form")!.dispatchEvent(new dom.window.Event("submit", { cancelable: true }));
    expect(d.getElementById("spent")!.textContent).toContain("250");
    expect(JSON.parse(dom.window.localStorage.getItem("exp")!)).toHaveLength(1);
  });

  it("booking disables a slot once it is taken", () => {
    const { dom } = run(html("g-booking"));
    const d = dom.window.document;
    // pick a future date that is not a closed Sunday
    const date = new Date(Date.now() + 3 * 864e5);
    if (date.getDay() === 0) date.setDate(date.getDate() + 1);
    const dateInput = d.getElementById("date") as HTMLInputElement;
    dateInput.value = date.toISOString().slice(0, 10);
    dateInput.dispatchEvent(new dom.window.Event("change"));
    (d.querySelector(".slot") as HTMLElement).click();
    (d.getElementById("name") as HTMLInputElement).value = "Asha";
    (d.getElementById("phone") as HTMLInputElement).value = "99999";
    d.getElementById("form")!.dispatchEvent(new dom.window.Event("submit", { cancelable: true }));
    expect((d.querySelector(".slot") as HTMLButtonElement).disabled).toBe(true);
    expect(d.querySelectorAll("#list .bk")).toHaveLength(1);
  });

  it("menu builds a QR code svg for a link", () => {
    const { dom } = run(html("g-menu-qr"));
    const d = dom.window.document;
    const url = d.getElementById("url") as HTMLInputElement;
    url.value = "https://example.com/menu";
    url.dispatchEvent(new dom.window.Event("input"));
    expect(d.querySelector("#qr svg")).not.toBeNull();
  });
});

describe("embedded QR encoder", () => {
  const qrMatrix = new Function(`${QR_JS};return qrMatrix;`)() as (t: string) => number[][] | null;

  it("picks the smallest version and draws finder patterns", () => {
    const m = qrMatrix("hi")!;
    expect(m.length).toBe(21);
    expect(qrMatrix("x".repeat(20))!.length).toBe(25); // version 2
    expect(qrMatrix("x".repeat(40))!.length).toBe(29); // version 3
    expect(qrMatrix("x".repeat(78))!.length).toBe(33); // version 4
    // finder pattern: dark ring, light ring, dark 3x3 centre
    expect(m[0].slice(0, 7)).toEqual([1, 1, 1, 1, 1, 1, 1]);
    expect(m[1].slice(0, 7)).toEqual([1, 0, 0, 0, 0, 0, 1]);
    expect(m[3].slice(2, 5)).toEqual([1, 1, 1]);
    // dark module and timing pattern
    expect(m[m.length - 8][8]).toBe(1);
    expect(m[6][8]).toBe(1);
    expect(m[6][9]).toBe(0);
  });

  it("is deterministic and refuses over-long text", () => {
    expect(qrMatrix("https://a.b/c")).toEqual(qrMatrix("https://a.b/c"));
    expect(qrMatrix("x".repeat(79))).toBeNull();
  });
});
