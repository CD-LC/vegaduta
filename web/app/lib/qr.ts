// QR encoding for a short string (a deep link). Pure and synchronous: no network, nothing sent anywhere.
// The encoder itself is the vendored qrcode-generator; this file only turns its module matrix into one SVG path.

import qrcode from "./vendor/qrcode-generator";

export type QrPath = {
  /** Modules per side, excluding the quiet zone. */
  size: number;
  /** One SVG path of all dark modules, horizontal runs merged. Draw it inside a viewBox of size + 2 * quiet. */
  d: string;
};

/** Standard quiet zone: four modules of clear space on every side. */
export const QR_QUIET = 4;

/** Level M (about 15% recovery) is the usual choice for a link on a phone screen. Throws if the text will not fit. */
export function qrPath(text: string, level: "L" | "M" | "Q" | "H" = "M"): QrPath {
  const qr = qrcode(0, level);
  qr.addData(text, "Byte");
  qr.make();
  const size: number = qr.getModuleCount();
  const parts: string[] = [];
  for (let r = 0; r < size; r++) {
    let c = 0;
    while (c < size) {
      if (!qr.isDark(r, c)) {
        c++;
        continue;
      }
      let run = 1;
      while (c + run < size && qr.isDark(r, c + run)) run++;
      parts.push(`M${c + QR_QUIET} ${r + QR_QUIET}h${run}v1h-${run}z`);
      c += run;
    }
  }
  return { size, d: parts.join("") };
}
