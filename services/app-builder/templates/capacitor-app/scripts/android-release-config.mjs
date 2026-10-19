// Patches the Capacitor-generated android/ project for a STORE release.
//
// `npx cap add android` regenerates android/ in CI on every run (it is not
// committed), so the release settings live here as a deterministic patch:
//
//   * targetSdk / compileSdk 36 (Google Play's current target-API floor)
//   * versionCode / versionName from VERSION_CODE / VERSION_NAME env vars
//   * 16 KB page-size friendly packaging (uncompressed, page-aligned native
//     libs; needs AGP 8.5.1+, which Capacitor 7 ships)
//   * an upload signing config read from env (never from files in git):
//       ANDROID_KEYSTORE_PATH, ANDROID_KEYSTORE_PASSWORD,
//       ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD
//
// Usage: node scripts/android-release-config.mjs [androidDir]
// Idempotent: running it twice leaves the files unchanged the second time.

import fs from "node:fs";
import path from "node:path";

const TARGET_SDK = 36;
const MARK = "// appbuilder:release-config";

export function patchVariables(src) {
  return src
    .replace(/(targetSdkVersion\s*=\s*)\d+/, `$1${TARGET_SDK}`)
    .replace(/(compileSdkVersion\s*=\s*)\d+/, `$1${TARGET_SDK}`);
}

export function patchAppGradle(src, { versionCode, versionName }) {
  let out = src;
  out = out.replace(/versionCode\s+\d+/, `versionCode ${Number(versionCode)}`);
  out = out.replace(/versionName\s+"[^"]*"/, `versionName "${String(versionName).replace(/"/g, "")}"`);
  if (out.includes(MARK)) return out;

  const signing = `
    ${MARK}
    signingConfigs {
        release {
            def ksPath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (ksPath) {
                storeFile file(ksPath)
                storePassword System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias System.getenv("ANDROID_KEY_ALIAS")
                keyPassword System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    packaging {
        jniLibs {
            // 16 KB page-size friendly: store native libs uncompressed so
            // they can be page-aligned by the Android Gradle Plugin.
            useLegacyPackaging = false
        }
    }
`;
  // Wire signing into buildTypes.release FIRST: once the signingConfigs
  // block is inserted, its own inner `release {` would be the first match.
  out = out.replace(/(buildTypes\s*\{\s*release\s*\{)/, `$1\n            signingConfig signingConfigs.release`);
  out = out.replace(/(\n\s*)buildTypes\s*\{/, `${signing}$1buildTypes {`);
  return out;
}

function main() {
  const dir = process.argv[2] || "android";
  const versionCode = process.env.VERSION_CODE || "1";
  const versionName = process.env.VERSION_NAME || "1.0.0";
  const vars = path.join(dir, "variables.gradle");
  const app = path.join(dir, "app", "build.gradle");
  fs.writeFileSync(vars, patchVariables(fs.readFileSync(vars, "utf8")));
  fs.writeFileSync(
    app,
    patchAppGradle(fs.readFileSync(app, "utf8"), { versionCode, versionName }),
  );
  console.log(`android release config: targetSdk=${TARGET_SDK} versionCode=${versionCode} versionName=${versionName}`);
}

if (import.meta.url === `file://${process.argv[1].replace(/\\/g, "/")}` ||
    process.argv[1]?.endsWith("android-release-config.mjs")) {
  main();
}
