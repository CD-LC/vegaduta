// Derives every Android/iOS icon size from the single source assets/icon.png
// using @capacitor/assets. Run AFTER `npx cap add <platform>` (CI does this).
//
//   node scripts/generate-icons.mjs [--android] [--ios]
//
// @capacitor/assets looks for icon-only.png / icon-foreground.png in the
// asset dir, so this stages copies of icon.png under those names (never
// overwriting a file you supplied yourself). A splash screen is generated
// only if you add assets/splash.png (2732x2732 recommended).
//
// The script is deterministic: same icon.png in, same resources out.

import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

const dir = "assets";
const icon = path.join(dir, "icon.png");
if (!fs.existsSync(icon)) {
  console.log("assets/icon.png not found - skipping icon generation");
  process.exit(0);
}

for (const name of ["icon-only.png", "icon-foreground.png"]) {
  const dest = path.join(dir, name);
  if (!fs.existsSync(dest)) fs.copyFileSync(icon, dest);
}

const platforms = process.argv.slice(2).filter((a) => a === "--android" || a === "--ios");
const args = [
  "capacitor-assets", "generate",
  "--assetPath", dir,
  "--iconBackgroundColor", "#4f46e5",
  "--iconBackgroundColorDark", "#4f46e5",
  "--splashBackgroundColor", "#4f46e5",
  "--splashBackgroundColorDark", "#4f46e5",
  ...platforms,
];
const res = spawnSync("npx", args, { stdio: "inherit", shell: process.platform === "win32" });
process.exit(res.status ?? 1);
