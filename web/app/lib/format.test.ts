import { describe, it, expect } from "vitest";
import { formatBytes, formatDateTime, timeAgo, type TimeAgoOptions } from "./format";

// The per-component copies FE-016 merged, verbatim. Each call-site preset must
// reproduce its original output exactly (for valid input) - checked on a grid.
const legacyBytes = {
  edge(bytes: number): string {
    if (bytes >= 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`;
    if (bytes >= 1024 * 1024) return `${Math.round(bytes / (1024 * 1024))} MB`;
    if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
    return `${bytes} B`;
  },
  intelligence(bytes: number): string {
    if (bytes >= 1_000_000_000) return `${(bytes / 1_000_000_000).toFixed(1)} GB`;
    if (bytes >= 1_000_000) return `${Math.round(bytes / 1_000_000)} MB`;
    return `${Math.max(1, Math.round(bytes / 1_000))} KB`;
  },
  attachments(bytes: number): string {
    if (!Number.isFinite(bytes) || bytes < 0) return "0 B";
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(bytes < 10 * 1024 ? 1 : 0)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  },
  knowledge(bytes: number): string {
    if (!bytes) return "0 KB";
    if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  },
};

const presets = {
  edge: (b: number) => formatBytes(b),
  intelligence: (b: number) => formatBytes(b, { base: 1000, minUnit: "KB" }),
  attachments: (b: number) => formatBytes(b, { maxUnit: "MB", decimals: { KB: (kb) => (kb < 10 ? 1 : 0), MB: 1 } }),
  knowledge: (b: number) =>
    formatBytes(b, { minUnit: "KB", maxUnit: "MB", decimals: { MB: 1 }, zero: "0 KB", invalid: "0 KB" }),
};

const SIZES = [
  0, 1, 12, 499, 500, 999, 1000, 1023, 1024, 1025, 1536, 5000, 10 * 1024 - 1, 10 * 1024, 10 * 1024 + 1, 99_999,
  512 * 1024, 999_999, 1_000_000, 1024 * 1024 - 1, 1024 * 1024, 1_500_000, 3 * 1024 * 1024 + 7, 999_999_999,
  1_000_000_000, 1024 ** 3 - 1, 1024 ** 3, 2.5 * 1024 ** 3, 4_200_000_000, 7 * 1024 ** 4,
];

describe("formatBytes", () => {
  for (const name of Object.keys(presets) as (keyof typeof presets)[]) {
    it(`matches the former ${name} copy on every sample size`, () => {
      for (const size of SIZES) {
        expect(presets[name](size), `${name}(${size})`).toBe(legacyBytes[name](size));
      }
    });
  }

  it("defaults to binary units with one decimal only for GB", () => {
    expect(formatBytes(0)).toBe("0 B");
    expect(formatBytes(1536)).toBe("2 KB");
    expect(formatBytes(5 * 1024 * 1024)).toBe("5 MB");
    expect(formatBytes(1.5 * 1024 ** 3)).toBe("1.5 GB");
  });

  it("returns the invalid label for NaN, Infinity, negatives and non-numbers", () => {
    expect(formatBytes(Number.NaN)).toBe("0 B");
    expect(formatBytes(Number.POSITIVE_INFINITY)).toBe("0 B");
    expect(formatBytes(-5, { invalid: "? B" })).toBe("? B");
    expect(formatBytes("12" as unknown as number, { invalid: "?" })).toBe("?");
  });

  it("supports decimal base, unit clamps and the zero label", () => {
    expect(formatBytes(1500, { base: 1000, decimals: { KB: 1 } })).toBe("1.5 KB");
    expect(formatBytes(300_000, { base: 1000, minUnit: "MB" })).toBe("1 MB");
    expect(formatBytes(0, { minUnit: "MB", zero: "0 MB" })).toBe("0 MB");
    expect(formatBytes(5 * 1024 ** 3, { maxUnit: "MB", decimals: { MB: 1 } })).toBe("5120.0 MB");
  });
});

describe("timeAgo", () => {
  const NOW = Date.UTC(2026, 8, 30, 12, 0, 0);
  const ago = (sec: number) => new Date(NOW - sec * 1000).toISOString();

  // The former NotificationBell copy (now the defaults).
  function legacyBell(iso: string, now: number): string {
    const then = new Date(iso).getTime();
    if (Number.isNaN(then)) return "";
    const diffSec = Math.max(0, Math.round((now - then) / 1000));
    if (diffSec < 60) return "just now";
    const diffMin = Math.round(diffSec / 60);
    if (diffMin < 60) return `${diffMin}m ago`;
    const diffHr = Math.round(diffMin / 60);
    if (diffHr < 24) return `${diffHr}h ago`;
    const diffDay = Math.round(diffHr / 24);
    if (diffDay < 30) return `${diffDay}d ago`;
    return new Date(iso).toLocaleDateString();
  }
  // The former build/studio copy.
  function legacyStudio(at: number, now: number): string {
    const s = Math.round((now - at) / 1000);
    if (s < 60) return "just now";
    if (s < 3600) return `${Math.round(s / 60)} min ago`;
    if (s < 86400) return `${Math.round(s / 3600)} h ago`;
    return new Date(at).toLocaleDateString();
  }
  // The former admin/issues + ContactsPanel copies (floor), with English stand-ins for t().
  function legacyFloor(iso: string, now: number): string {
    const diffMs = now - new Date(iso).getTime();
    const mins = Math.floor(diffMs / 60000);
    if (mins < 1) return "now";
    if (mins < 60) return `${mins}m`;
    const hours = Math.floor(mins / 60);
    if (hours < 24) return `${hours}h`;
    return `${Math.floor(hours / 24)}d`;
  }

  const STUDIO: TimeAgoOptions = {
    rounding: "round",
    maxDays: 0,
    labels: { justNow: "just now", minutes: (n) => `${n} min ago`, hours: (n) => `${n} h ago` },
  };
  const FLOOR: TimeAgoOptions = {
    rounding: "floor",
    maxDays: Infinity,
    labels: { justNow: "now", minutes: (n) => `${n}m`, hours: (n) => `${n}h`, days: (n) => `${n}d` },
  };

  const AGES = [
    -30, 0, 29.4, 29.6, 59, 59.6, 60, 89, 90, 119, 3569, 3570, 3599, 3600, 5369, 5370, 5400, 86_369, 86_370, 86_399,
    86_400, 88_200, 129_600, 30 * 86_400 - 1, 29.5 * 86_400, 30 * 86_400, 400 * 86_400,
  ];

  it("defaults reproduce the former NotificationBell output", () => {
    for (const sec of AGES) expect(timeAgo(ago(sec), { now: NOW }), `${sec}s`).toBe(legacyBell(ago(sec), NOW));
  });

  it("the studio preset reproduces the former build/studio output", () => {
    for (const sec of AGES) {
      const at = NOW - sec * 1000;
      expect(timeAgo(at, { ...STUDIO, now: NOW }), `${sec}s`).toBe(legacyStudio(at, NOW));
    }
  });

  it("the floor preset reproduces the former admin/issues and contacts output", () => {
    for (const sec of AGES) expect(timeAgo(ago(sec), { ...FLOOR, now: NOW }), `${sec}s`).toBe(legacyFloor(ago(sec), NOW));
  });

  it("returns the none / invalid labels for missing or unparsable input", () => {
    expect(timeAgo(null)).toBe("");
    expect(timeAgo(undefined, { labels: { ...FLOOR.labels!, none: "-" } })).toBe("-");
    expect(timeAgo("not a date", { labels: { ...FLOOR.labels!, none: "-" } })).toBe("-");
    expect(timeAgo("not a date", { labels: { ...FLOOR.labels!, invalid: "now" } })).toBe("now");
  });

  it("accepts Date and epoch-ms input", () => {
    expect(timeAgo(new Date(NOW - 5 * 60_000), { now: NOW })).toBe("5m ago");
    expect(timeAgo(NOW - 3 * 3_600_000, { now: NOW })).toBe("3h ago");
  });
});

describe("formatDateTime", () => {
  it("formats a timestamp with toLocaleString and blanks missing input", () => {
    const iso = "2026-09-30T12:00:00Z";
    expect(formatDateTime(iso)).toBe(new Date(iso).toLocaleString());
    expect(formatDateTime(null)).toBe("");
    expect(formatDateTime("")).toBe("");
  });
});
