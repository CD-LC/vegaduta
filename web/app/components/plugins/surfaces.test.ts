import { describe, expect, it } from "vitest";
import { GROUP_ORDER, STATUS_LABEL, SURFACES, surfacesByGroup } from "./surfaces";

describe("surfaces", () => {
  it("lists every plugin, app and wearable that exists in the repo", () => {
    const ids = SURFACES.map((s) => s.id);
    for (const id of ["vscode", "jetbrains", "eclipse", "chrome", "edge", "android", "wear-os", "ios", "desktop-agent", "mcp-server"]) {
      expect(ids, id).toContain(id);
    }
    expect(new Set(ids).size).toBe(ids.length);
  });

  it("puts every surface in a rendered group and gives every status a label", () => {
    const rendered = surfacesByGroup().flatMap((g) => g.items.map((s) => s.id));
    expect(rendered.sort()).toEqual(SURFACES.map((s) => s.id).sort());
    for (const s of SURFACES) {
      expect(GROUP_ORDER).toContain(s.group);
      expect(STATUS_LABEL[s.status]).toBeTruthy();
    }
  });

  it("never presents an unpublished surface as being on its store", () => {
    // None of these is on its public store (clients/PUBLISHING.md); the copy
    // may only mention a store to say it is NOT there yet.
    for (const s of SURFACES) {
      const text = `${s.does} ${s.where}`;
      for (const store of ["Marketplace", "Web Store", "Google Play", "Edge Add-ons", "App Store"]) {
        if (text.includes(store)) expect(text, `${s.id}: ${store}`).toMatch(/not (on|submitted to)/i);
      }
    }
  });

  it("marks only really obtainable things as available, and never the unbuilt iPhone app", () => {
    const available = SURFACES.filter((s) => s.status === "available").map((s) => s.id).sort();
    expect(available).toEqual(["chrome", "jetbrains", "mcp-server", "vscode"]);
    expect(SURFACES.find((s) => s.id === "ios")?.status).toBe("in-development");
  });

  it("does not claim Android Studio before the plugin verifier has been run against it", () => {
    expect(JSON.stringify(SURFACES)).not.toMatch(/Android Studio/i);
  });

  it("keeps in-site links internal", () => {
    for (const s of SURFACES) if (s.href) expect(s.href.startsWith("/")).toBe(true);
  });
});
