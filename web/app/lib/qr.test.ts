import { describe, expect, it } from "vitest";
import { QR_QUIET, qrPath } from "./qr";

describe("qrPath", () => {
  it("encodes a Telegram pairing deep link into a square matrix with a valid path", () => {
    const qr = qrPath("https://t.me/VegaDutaDemoBot?start=VD-7F3K");
    expect(qr.size).toBeGreaterThanOrEqual(21);
    expect((qr.size - 17) % 4).toBe(0); // QR sizes are 21, 25, 29 ... 17 + 4 * version
    expect(qr.d.startsWith("M")).toBe(true);
    expect(qr.d).toMatch(/^(M\d+ \d+h\d+v1h-\d+z)+$/);
  });

  it("keeps every dark module inside the quiet zone", () => {
    const qr = qrPath("https://wa.me/919999999999?text=VD-7F3K");
    for (const m of qr.d.matchAll(/M(\d+) (\d+)h(\d+)v1/g)) {
      const [x, y, run] = [Number(m[1]), Number(m[2]), Number(m[3])];
      expect(x).toBeGreaterThanOrEqual(QR_QUIET);
      expect(y).toBeGreaterThanOrEqual(QR_QUIET);
      expect(x + run).toBeLessThanOrEqual(QR_QUIET + qr.size);
      expect(y + 1).toBeLessThanOrEqual(QR_QUIET + qr.size);
    }
  });

  it("is deterministic and grows with the payload", () => {
    const a = qrPath("https://t.me/x?start=VD-AAAA");
    expect(qrPath("https://t.me/x?start=VD-AAAA").d).toBe(a.d);
    expect(qrPath("https://t.me/x?start=" + "A".repeat(120)).size).toBeGreaterThan(a.size);
  });
});
