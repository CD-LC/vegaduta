import { getPublicEnv } from "./env";

// Optional direct-download Android APK, shared by the channels panel and the
// /try studio's "in your pocket" step. There is no default: an OSS deployment
// that publishes its own build sets NEXT_PUBLIC_MOBILE_APK_URL (and ideally
// _SHA256, _VERSION, _SIZE_MB); when the URL is unset every download panel is
// hidden. Runtime-configurable, see docs/RUNTIME-CONFIG.md.
export type MobileApkDownload = {
  href: string;
  sha256: string;
  version: string;
  /** Approximate size in MB, shown so nobody starts a huge download by surprise. */
  sizeMb: number;
};

export function getMobileApkDownload(): MobileApkDownload | null {
  const href = getPublicEnv("NEXT_PUBLIC_MOBILE_APK_URL");
  if (!href) return null;
  const size = Number(getPublicEnv("NEXT_PUBLIC_MOBILE_APK_SIZE_MB"));
  return {
    href,
    sha256: getPublicEnv("NEXT_PUBLIC_MOBILE_APK_SHA256") || "",
    version: getPublicEnv("NEXT_PUBLIC_MOBILE_APK_VERSION") || "",
    sizeMb: Number.isFinite(size) && size > 0 ? size : 0,
  };
}
