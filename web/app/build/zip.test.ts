import { describe, expect, it } from "vitest";
import { buildZip, crc32, safeZipPath } from "./zip";

describe("zip", () => {
  it("crc32 matches the standard check value", () => {
    expect(crc32(new TextEncoder().encode("123456789"))).toBe(0xcbf43926);
    expect(crc32(new Uint8Array())).toBe(0);
  });

  it("sanitises paths", () => {
    expect(safeZipPath("../../etc/passwd")).toBe("etc/passwd");
    expect(safeZipPath("/src\\app.css")).toBe("src/app.css");
    expect(safeZipPath("./")).toBe("");
  });

  it("writes a well-formed archive with stored entries", () => {
    const z = buildZip([
      { path: "a.txt", content: "hello" },
      { path: "src/b.css", content: "é" },
      { path: "a.txt", content: "dup is dropped" },
      { path: "..", content: "dropped" },
    ]);
    const v = new DataView(z.buffer, z.byteOffset, z.byteLength);
    expect(v.getUint32(0, true)).toBe(0x04034b50);
    const eocd = z.length - 22;
    expect(v.getUint32(eocd, true)).toBe(0x06054b50);
    expect(v.getUint16(eocd + 10, true)).toBe(2);
    const cdOffset = v.getUint32(eocd + 16, true);
    expect(v.getUint32(cdOffset, true)).toBe(0x02014b50);
    expect(v.getUint32(14, true)).toBe(crc32(new TextEncoder().encode("hello")));
    expect(v.getUint32(18, true)).toBe(5);
    expect(new TextDecoder().decode(z.subarray(30 + 5, 30 + 5 + 5))).toBe("hello");
  });

  it("handles an empty file list", () => {
    expect(buildZip([]).length).toBe(22);
  });
});
