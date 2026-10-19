import { describe, expect, it } from "vitest";
import {
  DEFAULT_PACKAGE_ID,
  PLACEHOLDER_ICON_BYTES,
  checklistProgress,
  deriveChecklist,
  validateStoreForm,
  type StoreConfig,
} from "./storeChecklist";

const META = [
  "fastlane/metadata/android/en-US/title.txt",
  "fastlane/metadata/android/en-US/short_description.txt",
  "fastlane/metadata/android/en-US/full_description.txt",
];
const GOOD: StoreConfig = {
  app_name: "Acme Shop",
  package_id: "com.acme.shop",
  version_name: "1.0.0",
  short_description: "Order anything.",
  full_description: "Long text.",
};
const DEFAULT_CFG: StoreConfig = { ...GOOD, app_name: "My App", package_id: DEFAULT_PACKAGE_ID };
const state = (items: ReturnType<typeof deriveChecklist>, id: string) => items.find((i) => i.id === id)?.state;

describe("validateStoreForm", () => {
  it("accepts a good config", () => expect(validateStoreForm(GOOD)).toEqual({}));
  it("enforces Play limits", () => {
    const e = validateStoreForm({
      ...GOOD,
      app_name: "a".repeat(31),
      short_description: "s".repeat(81),
      full_description: "f".repeat(4001),
    });
    expect(Object.keys(e).sort()).toEqual(["app_name", "full_description", "short_description"]);
    expect(validateStoreForm({ ...GOOD, app_name: "a".repeat(30), short_description: "s".repeat(80) })).toEqual({});
  });
  it("rejects bad package ids and versions", () => {
    for (const bad of ["myapp", "Com.Acme", "com.acme.1x", "com.acme.class", "com..x"]) {
      expect(validateStoreForm({ ...GOOD, package_id: bad }).package_id).toBeTruthy();
    }
    expect(validateStoreForm({ ...GOOD, version_name: "v1" }).version_name).toBeTruthy();
    expect(validateStoreForm({ ...GOOD, version_name: "1.0.0-beta.1" }).version_name).toBeUndefined();
  });
});

describe("deriveChecklist", () => {
  it("fresh scaffold: only the checklist doc is done; icon is a placeholder", () => {
    const items = deriveChecklist({
      paths: ["assets/icon.png", "STORE-CHECKLIST.md", "PRIVACY-POLICY.md", ...META],
      config: DEFAULT_CFG,
      hasStoreConfig: true,
      assets: [{ path: "assets/icon.png", size: PLACEHOLDER_ICON_BYTES }],
    });
    expect(state(items, "icon")).toBe("todo");
    expect(state(items, "package")).toBe("todo");
    expect(state(items, "metadata")).toBe("todo");
    expect(state(items, "checklist-doc")).toBe("done");
    expect(state(items, "privacy")).toBe("manual");
    expect(state(items, "signing-secrets")).toBe("manual");
  });

  it("filled-in project: icon, package and metadata done", () => {
    const items = deriveChecklist({
      paths: ["assets/icon.png", "STORE-CHECKLIST.md", "PRIVACY-POLICY.md", ...META],
      config: GOOD,
      hasStoreConfig: true,
      assets: [{ path: "assets/icon.png", size: 90210 }],
    });
    expect(state(items, "icon")).toBe("done");
    expect(state(items, "package")).toBe("done");
    expect(state(items, "metadata")).toBe("done");
    expect(checklistProgress(items)).toEqual({ done: 4, total: 7 });
  });

  it("missing files flip items to todo", () => {
    const items = deriveChecklist({ paths: [], config: GOOD, hasStoreConfig: false });
    expect(state(items, "icon")).toBe("todo");
    expect(state(items, "metadata")).toBe("todo");
    expect(state(items, "checklist-doc")).toBe("todo");
    expect(state(items, "privacy")).toBe("todo");
  });

  it("manual items are never reported as done", () => {
    const items = deriveChecklist({ paths: ["STORE-CHECKLIST.md"], config: GOOD, hasStoreConfig: true });
    expect(items.filter((i) => i.state === "done").map((i) => i.id)).not.toContain("signing-secrets");
    expect(state(items, "store-console")).toBe("manual");
  });
});

describe("icon needs positive evidence", () => {
  it("a file in the tree with no size info is not proof of a real icon", () => {
    const items = deriveChecklist({ paths: ["assets/icon.png"], config: GOOD, hasStoreConfig: true });
    expect(state(items, "icon")).toBe("todo");
    const unknown = deriveChecklist({ paths: ["assets/icon.png"], config: GOOD, hasStoreConfig: true, assets: [{ path: "assets/icon.png", size: null }] });
    expect(state(unknown, "icon")).toBe("todo");
    const zero = deriveChecklist({ paths: ["assets/icon.png"], config: GOOD, hasStoreConfig: true, assets: [{ path: "assets/icon.png", size: 0 }] });
    expect(state(zero, "icon")).toBe("todo");
  });
});
