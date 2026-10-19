import { describe, expect, it } from "vitest";
import { isCrossSite, previewSandbox, safePreviewUrl } from "./previewUrl";

describe("safePreviewUrl", () => {
  it("accepts https and loopback http", () => {
    expect(safePreviewUrl("https://acme.github.io/app/")).toBe("https://acme.github.io/app/");
    expect(safePreviewUrl("http://localhost:5173/")).toBe("http://localhost:5173/");
  });
  it("rejects other schemes, plain http and credentials", () => {
    expect(safePreviewUrl("javascript:alert(1)")).toBeNull();
    expect(safePreviewUrl("data:text/html,<script>1</script>")).toBeNull();
    expect(safePreviewUrl("http://evil.example/")).toBeNull();
    expect(safePreviewUrl("https://user:pw@evil.example/")).toBeNull();
    expect(safePreviewUrl("not a url")).toBeNull();
    expect(safePreviewUrl(null)).toBeNull();
  });
});

describe("previewSandbox", () => {
  it("never grants allow-same-origin to a same-site preview", () => {
    expect(isCrossSite("https://builder.vegaduta.ai/p/1", "app.vegaduta.ai")).toBe(false);
    expect(previewSandbox("https://app.vegaduta.ai/x", "app.vegaduta.ai")).not.toContain("allow-same-origin");
  });
  it("grants allow-same-origin to a preview on another site", () => {
    expect(previewSandbox("https://acme.github.io/app/", "app.vegaduta.ai")).toContain("allow-same-origin");
  });
  it("always sandboxes scripts", () => {
    expect(previewSandbox("https://acme.github.io/", "app.vegaduta.ai")).toContain("allow-scripts");
  });
});
