// Tiny store-only (no compression) ZIP encoder for "Download as ZIP". No
// dependencies: a CRC-32 table, local file headers, a central directory and
// the end-of-central-directory record. Text only (UTF-8, flagged as such).

const encoder = new TextEncoder();

let table: Uint32Array | null = null;
function crcTable(): Uint32Array {
  if (table) return table;
  table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
}

export function crc32(bytes: Uint8Array): number {
  const t = crcTable();
  let c = 0xffffffff;
  for (let i = 0; i < bytes.length; i++) c = t[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

/** Keep archive paths relative and inside the archive (no "..", no drive letters). */
export function safeZipPath(p: string): string {
  const parts = String(p || "")
    .replace(/\\/g, "/")
    .split("/")
    .filter((s) => s && s !== "." && s !== "..");
  return parts.join("/").replace(/^[A-Za-z]:/, "");
}

// Fixed DOS timestamp (2020-01-01 00:00) keeps the output deterministic.
const DOS_TIME = 0;
const DOS_DATE = ((2020 - 1980) << 9) | (1 << 5) | 1;

export interface ZipEntry {
  path: string;
  content: string;
}

export function buildZip(files: ZipEntry[]): Uint8Array {
  const seen = new Set<string>();
  const entries: { name: Uint8Array; data: Uint8Array; crc: number; offset: number }[] = [];
  const chunks: Uint8Array[] = [];
  let offset = 0;

  for (const f of files) {
    const path = safeZipPath(f.path);
    if (!path || seen.has(path)) continue;
    seen.add(path);
    const name = encoder.encode(path);
    const data = encoder.encode(f.content ?? "");
    const crc = crc32(data);
    const header = new Uint8Array(30 + name.length);
    const v = new DataView(header.buffer);
    v.setUint32(0, 0x04034b50, true);
    v.setUint16(4, 20, true); // version needed
    v.setUint16(6, 0x0800, true); // UTF-8 names
    v.setUint16(8, 0, true); // store
    v.setUint16(10, DOS_TIME, true);
    v.setUint16(12, DOS_DATE, true);
    v.setUint32(14, crc, true);
    v.setUint32(18, data.length, true);
    v.setUint32(22, data.length, true);
    v.setUint16(26, name.length, true);
    v.setUint16(28, 0, true);
    header.set(name, 30);
    entries.push({ name, data, crc, offset });
    chunks.push(header, data);
    offset += header.length + data.length;
  }

  const cdStart = offset;
  for (const e of entries) {
    const rec = new Uint8Array(46 + e.name.length);
    const v = new DataView(rec.buffer);
    v.setUint32(0, 0x02014b50, true);
    v.setUint16(4, 20, true);
    v.setUint16(6, 20, true);
    v.setUint16(8, 0x0800, true);
    v.setUint16(10, 0, true);
    v.setUint16(12, DOS_TIME, true);
    v.setUint16(14, DOS_DATE, true);
    v.setUint32(16, e.crc, true);
    v.setUint32(20, e.data.length, true);
    v.setUint32(24, e.data.length, true);
    v.setUint16(28, e.name.length, true);
    v.setUint32(42, e.offset, true);
    rec.set(e.name, 46);
    chunks.push(rec);
    offset += rec.length;
  }

  const end = new Uint8Array(22);
  const ev = new DataView(end.buffer);
  ev.setUint32(0, 0x06054b50, true);
  ev.setUint16(8, entries.length, true);
  ev.setUint16(10, entries.length, true);
  ev.setUint32(12, offset - cdStart, true);
  ev.setUint32(16, cdStart, true);
  chunks.push(end);

  const out = new Uint8Array(offset + end.length);
  let pos = 0;
  for (const c of chunks) {
    out.set(c, pos);
    pos += c.length;
  }
  return out;
}

/** Browser-only: trigger a download of the files as `<name>.zip`. */
export function downloadZip(name: string, files: ZipEntry[]): void {
  const blob = new Blob([buildZip(files) as BlobPart], { type: "application/zip" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `${name.replace(/[^\w.-]+/g, "_") || "app"}.zip`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
