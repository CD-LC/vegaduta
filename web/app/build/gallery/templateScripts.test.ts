// @vitest-environment node
// Template script rewrites the earlier audit could only read: run them with a
// controllable clock, timezone, confirm() and pre-seeded localStorage.
import { describe, expect, it } from "vitest";
import { JSDOM, VirtualConsole } from "jsdom";
import { STUDIO_GALLERY } from "./galleryData";
import { TEMPLATES } from "../studio/templates";

interface Boot {
  now?: number;
  ls?: Record<string, unknown>;
  confirm?: boolean;
  url?: string;
}
type Win = Window & { __now: number; __confirms: number; localStorage: Storage };

function boot(html: string, o: Boot = {}) {
  const seed = Object.fromEntries(Object.entries(o.ls || {}).map(([k, v]) => [k, JSON.stringify(v)]));
  const prefix =
    "<script>(function(){" +
    (o.now !== undefined
      ? `var R=Date;window.__now=${o.now};` +
        "function D(){var a=[].slice.call(arguments);if(!(this instanceof D))return new R(window.__now).toString();" +
        "return a.length?new (Function.prototype.bind.apply(R,[null].concat(a)))():new R(window.__now);}" +
        "D.prototype=R.prototype;D.now=function(){return window.__now;};D.UTC=R.UTC;D.parse=R.parse;window.Date=D;"
      : "") +
    `var seed=${JSON.stringify(seed)};` +
    "try{for(var k in seed)localStorage.setItem(k,seed[k]);}catch(e){}" +
    `window.__confirms=0;window.confirm=function(){window.__confirms++;return ${o.confirm ?? true};};` +
    "window.alert=function(){};" +
    "})();</script>";
  const withPrefix = /<head[^>]*>/i.test(html) ? html.replace(/<head[^>]*>/i, (m) => m + prefix) : prefix + html;
  const errors: string[] = [];
  const vc = new VirtualConsole();
  vc.on("jsdomError", (e: { message?: unknown }) => errors.push(String(e.message)));
  const dom = new JSDOM(withPrefix, { runScripts: "dangerously", url: o.url ?? "https://example.test/", virtualConsole: vc, pretendToBeVisual: true });
  return { dom, win: dom.window as unknown as Win, d: dom.window.document as unknown as Document, errors };
}

function withTZ<T>(tz: string, fn: () => T): T {
  const prev = process.env.TZ;
  process.env.TZ = tz;
  try {
    return fn();
  } finally {
    if (prev === undefined) delete process.env.TZ;
    else process.env.TZ = prev;
  }
}

const localKey = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
const starter = (id: string) => TEMPLATES.find((t) => t.id === id)!.html;
const gallery = (id: string) => STUDIO_GALLERY.find((t) => t.id === id)!.html;
const shift = (d: Date, days: number) => {
  const x = new Date(d);
  x.setDate(x.getDate() + days);
  return x;
};
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
const streakOf = (d: Document) => d.querySelector(".habit .streak")!.textContent!.replace(/\D/g, "");
const submit = (w: Win, d: Document, id: string) => d.getElementById(id)!.dispatchEvent(new (w as unknown as { Event: typeof Event }).Event("submit", { cancelable: true }));

describe("habit tracker streaks", () => {
  const now = new Date(2026, 2, 11, 12, 0).getTime();
  const habit = (done: string[]) => [{ id: 1, name: "Read", done }];

  it("counts from yesterday when today is not ticked yet", () => {
    const t = new Date(now);
    const { d, errors } = boot(starter("habits"), { now, ls: { habits: habit([localKey(shift(t, -1)), localKey(shift(t, -2))]) } });
    expect(streakOf(d)).toBe("2");
    expect(errors).toEqual([]);
  });

  it("adds today once ticked, and a gap resets the streak", () => {
    const t = new Date(now);
    const a = boot(starter("habits"), { now, ls: { habits: habit([localKey(t), localKey(shift(t, -1)), localKey(shift(t, -2))]) } });
    expect(streakOf(a.d)).toBe("3");
    const b = boot(starter("habits"), { now, ls: { habits: habit([localKey(shift(t, -2)), localKey(shift(t, -3))]) } });
    expect(streakOf(b.d)).toBe("0");
  });

  it("ticking today toggles it and updates the summary", () => {
    const { d, win } = boot(starter("habits"), { now });
    d.querySelector<HTMLElement>(".dot.today")!.click();
    expect(d.getElementById("summary")!.textContent).toBe("1 of 3 done today");
    expect(JSON.parse(win.localStorage.getItem("habits")!)[0].done).toEqual([localKey(new Date(now))]);
    d.querySelector<HTMLElement>(".dot.today")!.click();
    expect(d.getElementById("summary")!.textContent).toBe("0 of 3 done today");
  });

  it("uses the LOCAL date, not UTC, for today's key", () => {
    withTZ("Pacific/Auckland", () => {
      // 12:30 on 11 March in Auckland is still 10 March in UTC.
      const nz = Date.UTC(2026, 2, 10, 23, 30);
      expect(new Date(nz).toISOString().slice(0, 10)).toBe("2026-03-10");
      const { d, win } = boot(starter("habits"), { now: nz });
      d.querySelector<HTMLElement>(".dot.today")!.click();
      expect(JSON.parse(win.localStorage.getItem("habits")!)[0].done).toEqual(["2026-03-11"]);
    });
  });

  it("still reads data saved under the old UTC date keys, and unticking clears them", () => {
    withTZ("America/Los_Angeles", () => {
      // 18:30 on 11 March in Los Angeles is already 12 March in UTC, so the old
      // build stored "2026-03-12" for today and "2026-03-11" for yesterday.
      const la = Date.UTC(2026, 2, 12, 1, 30);
      const legacy = ["2026-03-12", "2026-03-11"];
      const a = boot(starter("habits"), { now: la, ls: { habits: habit(legacy) } });
      expect(streakOf(a.d)).toBe("2");
      expect(a.d.getElementById("summary")!.textContent).toBe("1 of 1 done today");
      const b = boot(starter("habits"), { now: la, ls: { habits: habit(["2026-03-12"]) } });
      expect(streakOf(b.d)).toBe("1");
      b.d.querySelector<HTMLElement>(".dot.today")!.click();
      expect(JSON.parse(b.win.localStorage.getItem("habits")!)[0].done).toEqual([]);
      expect(streakOf(b.d)).toBe("0");
    });
  });
});

describe("focus timer", () => {
  const now = new Date(2026, 2, 11, 9, 0).getTime();
  const time = (d: Document) => d.getElementById("time")!.textContent;

  it("counts down from the wall clock, not from tick counts", async () => {
    const { d, win } = boot(starter("timer"), { now });
    d.getElementById("go")!.click();
    win.__now += 60_000; // a backgrounded tab: a minute passes with no ticks
    await sleep(350);
    expect(time(d)).toBe("24:00");
    expect(d.getElementById("go")!.textContent).toBe("Pause");
    win.close();
  });

  it("does not advance while paused and resumes from where it stopped", async () => {
    const { d, win } = boot(starter("timer"), { now });
    d.getElementById("go")!.click();
    win.__now += 90_000;
    d.getElementById("go")!.click(); // pause
    expect(time(d)).toBe("23:30");
    win.__now += 3_600_000;
    await sleep(350);
    expect(time(d)).toBe("23:30");
    expect(d.getElementById("go")!.textContent).toBe("Resume");
    d.getElementById("go")!.click();
    win.__now += 30_000;
    await sleep(350);
    expect(time(d)).toBe("23:00");
    win.close();
  });

  it("logs a finished focus session under today's local date", async () => {
    const { d, win } = boot(starter("timer"), { now });
    d.getElementById("go")!.click();
    win.__now += 25 * 60_000 + 1000;
    await sleep(350);
    expect(JSON.parse(win.localStorage.getItem("focusLog")!)).toEqual({ [localKey(new Date(now))]: 1 });
    expect(d.getElementById("stats")!.textContent).toBe("1 focus session today");
    expect(time(d)).toBe("25:00");
    win.close();
  });
});

describe("sales dashboard", () => {
  const OLD = { sales: [{ day: "2000-01-01", rev: 100, orders: 1 }] }; // not today, so a new sale appends a row
  const addSale = (w: Win, d: Document, v: string) => {
    (d.getElementById("amt") as HTMLInputElement).value = v;
    submit(w, d, "form");
  };

  it("keeps the data when the reset confirmation is declined", () => {
    const { d, win } = boot(gallery("g-dashboard"), { confirm: false, ls: OLD });
    addSale(win, d, "777");
    const before = win.localStorage.getItem("sales")!;
    expect(before).toContain('"rev":777');
    d.getElementById("reset")!.click();
    expect(win.__confirms).toBe(1);
    expect(win.localStorage.getItem("sales")).toBe(before);
  });

  it("replaces the data only after the person confirms", () => {
    const { d, win } = boot(gallery("g-dashboard"), { confirm: true, ls: OLD });
    addSale(win, d, "777");
    expect(win.localStorage.getItem("sales")).toContain('"rev":777');
    d.getElementById("reset")!.click();
    expect(win.__confirms).toBe(1);
    expect(win.localStorage.getItem("sales")).not.toContain('"rev":777');
  });

  it("files today's sale under the local date, not UTC", () => {
    withTZ("Pacific/Auckland", () => {
      const nz = Date.UTC(2026, 2, 10, 23, 30);
      const { d, win } = boot(gallery("g-dashboard"), { now: nz, ls: { sales: [{ day: "2026-03-10", rev: 100, orders: 1 }] } });
      addSale(win, d, "50");
      const rows = JSON.parse(win.localStorage.getItem("sales")!);
      expect(rows.map((r: { day: string }) => r.day)).toEqual(["2026-03-10", "2026-03-11"]);
    });
  });
});

describe("link in bio", () => {
  const copyRow = (d: Document) => d.getElementById("copy")!.parentElement as HTMLElement;

  it("shows the copy-link button on a normal page", () => {
    const { d } = boot(gallery("g-link-in-bio"));
    expect(copyRow(d).style.display).not.toBe("none");
  });

  it("hides the copy-link button inside the preview's about:srcdoc frame", () => {
    const { d } = boot(gallery("g-link-in-bio"), { url: "about:srcdoc" });
    expect(copyRow(d).style.display).toBe("none");
  });
});
