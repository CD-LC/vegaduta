import { afterEach, beforeEach, describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { GenerativeUiRenderer } from "./GenerativeUiRenderer";

const spec = (src: string) => ({
  version: "v1",
  root: { type: "image", props: { src, alt: "pic" } },
});

// Platform hosts are deployment config (NEXT_PUBLIC_TRUSTED_IMAGE_HOSTS), not code.
let savedTrusted: string | undefined;
beforeEach(() => {
  savedTrusted = process.env.NEXT_PUBLIC_TRUSTED_IMAGE_HOSTS;
  process.env.NEXT_PUBLIC_TRUSTED_IMAGE_HOSTS = ".example.com";
});
afterEach(() => {
  if (savedTrusted === undefined) delete process.env.NEXT_PUBLIC_TRUSTED_IMAGE_HOSTS;
  else process.env.NEXT_PUBLIC_TRUSTED_IMAGE_HOSTS = savedTrusted;
});

describe("GenerativeUiRenderer image node", () => {
  it("renders a platform-hosted image", () => {
    const { container } = render(<GenerativeUiRenderer spec={spec("https://api.example.com/a.png")} />);
    expect(container.querySelector("img")!.getAttribute("src")).toBe("https://api.example.com/a.png");
  });

  it("does not fetch a third-party image and shows a placeholder", () => {
    const { container } = render(<GenerativeUiRenderer spec={spec("https://evil.example/p.png?d=secret")} />);
    expect(container.querySelector("img")).toBeNull();
    expect(screen.getByTestId("blocked-image").textContent).toContain("evil.example");
  });
});
