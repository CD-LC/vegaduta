// Pure derivation of the mobile store-readiness checklist and the client-side
// mirror of app-builder's store-config validation (app/mobile_store.py).
// DOM-free so it is unit-tested directly.

export const STORE_LIMITS = { title: 30, short: 80, full: 4000 };
export const DEFAULT_PACKAGE_ID = "app.builder.generated";
// Byte size of the placeholder assets/icon.png the template ships. An icon
// asset of exactly this size is treated as "not replaced yet".
export const PLACEHOLDER_ICON_BYTES = 6769;

const PACKAGE_RX = /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/;
const VERSION_RX = /^\d{1,4}\.\d{1,4}\.\d{1,4}(-[0-9A-Za-z.]{1,20})?$/;
const JAVA_RESERVED = new Set([
  "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
  "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
  "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
  "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
  "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
  "volatile", "while", "true", "false", "null",
]);

export interface StoreConfig {
  app_name: string;
  package_id: string;
  version_name: string;
  short_description: string;
  full_description: string;
}

export interface StoreStatusInput {
  paths: string[];
  config: StoreConfig;
  hasStoreConfig: boolean;
  /** [{path,size}] from GET /assets; optional. */
  assets?: { path: string; size?: number | null }[];
}

export type CheckState = "done" | "todo" | "manual";
export interface ChecklistItem {
  id: string;
  label: string;
  state: CheckState;
  hint: string;
}

const ANDROID_META = [
  "fastlane/metadata/android/en-US/title.txt",
  "fastlane/metadata/android/en-US/short_description.txt",
  "fastlane/metadata/android/en-US/full_description.txt",
];

export function validateStoreForm(cfg: StoreConfig): Partial<Record<keyof StoreConfig, string>> {
  const errs: Partial<Record<keyof StoreConfig, string>> = {};
  const name = cfg.app_name.trim();
  if (!name) errs.app_name = "Required";
  else if (name.length > STORE_LIMITS.title) errs.app_name = `Max ${STORE_LIMITS.title} characters (Google Play)`;
  else if (name.includes("\n")) errs.app_name = "Single line only";

  const pkg = cfg.package_id.trim();
  if (!PACKAGE_RX.test(pkg) || pkg.length > 155) {
    errs.package_id = "Reverse-DNS like com.acme.myapp (lowercase, digits, underscores)";
  } else if (pkg.split(".").some((s) => JAVA_RESERVED.has(s))) {
    errs.package_id = "A segment is a reserved Java keyword";
  }

  if (!VERSION_RX.test(cfg.version_name.trim())) errs.version_name = "Use 1.0.0 or 1.0.0-beta.1";

  const short = cfg.short_description.trim();
  if (!short) errs.short_description = "Required";
  else if (short.length > STORE_LIMITS.short) errs.short_description = `Max ${STORE_LIMITS.short} characters (Google Play)`;
  else if (short.includes("\n")) errs.short_description = "Single line only";

  const full = cfg.full_description.trim();
  if (!full) errs.full_description = "Required";
  else if (full.length > STORE_LIMITS.full) errs.full_description = `Max ${STORE_LIMITS.full} characters (Google Play)`;
  return errs;
}

export function deriveChecklist(input: StoreStatusInput): ChecklistItem[] {
  const has = (p: string) => input.paths.includes(p);
  const cfg = input.config;

  const iconAsset = input.assets?.find((a) => a.path === "assets/icon.png");
  const iconPresent = has("assets/icon.png") || !!iconAsset;
  // Positive evidence only: we must SEE a real size that differs from the
  // placeholder. A file in the tree with no size information proves nothing.
  const iconReplaced =
    !!iconAsset && typeof iconAsset.size === "number" && iconAsset.size > 0 && iconAsset.size !== PLACEHOLDER_ICON_BYTES;

  const packageOk = cfg.package_id !== DEFAULT_PACKAGE_ID && !validateStoreForm(cfg).package_id;

  const metaFilesPresent = ANDROID_META.every(has);
  const formErrs = validateStoreForm(cfg);
  const listingWritten =
    input.hasStoreConfig &&
    metaFilesPresent &&
    !formErrs.app_name &&
    !formErrs.short_description &&
    !formErrs.full_description &&
    cfg.app_name.trim() !== "My App";

  return [
    {
      id: "icon",
      label: "App icon replaced",
      state: iconReplaced ? "done" : "todo",
      hint: iconPresent
        ? "assets/icon.png is the placeholder, or its size could not be confirmed - upload a 1024x1024 PNG named icon.png in Design."
        : "Add assets/icon.png (1024x1024 PNG). CI derives every icon size from it.",
    },
    {
      id: "package",
      label: "Package id set",
      state: packageOk ? "done" : "todo",
      hint: "Choose a permanent reverse-DNS id like com.acme.myapp. It cannot change after first publish.",
    },
    {
      id: "metadata",
      label: "Store listing written",
      state: listingWritten ? "done" : "todo",
      hint: "Fill in name, short and full description below and save; this writes fastlane/metadata.",
    },
    {
      id: "checklist-doc",
      label: "Secrets and steps documented",
      state: has("STORE-CHECKLIST.md") ? "done" : "todo",
      hint: "STORE-CHECKLIST.md lists the four GitHub Secrets the signed release build needs.",
    },
    {
      id: "privacy",
      label: "Privacy policy drafted",
      state: has("PRIVACY-POLICY.md") ? "manual" : "todo",
      hint: "A stub exists. You must complete it, host it at a public URL, and enter it in both stores.",
    },
    {
      id: "signing-secrets",
      label: "Signing secrets added to GitHub",
      state: "manual",
      hint: "Add ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD in the repo settings. Not visible from here.",
    },
    {
      id: "store-console",
      label: "Screenshots, data safety, upload",
      state: "manual",
      hint: "Done by you in Google Play Console / App Store Connect. VegaDuta never uploads for you.",
    },
  ];
}

export function checklistProgress(items: ChecklistItem[]): { done: number; total: number } {
  return { done: items.filter((i) => i.state === "done").length, total: items.length };
}
