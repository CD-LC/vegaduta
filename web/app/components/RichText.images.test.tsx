import { afterEach, beforeEach, describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import RichText from "./RichText";
import { isTrustedImageUrl } from "../lib/safeImageUrl";

// Platform hosts are deployment config (NEXT_PUBLIC_TRUSTED_IMAGE_HOSTS), not code.
let savedTrusted: string | undefined;
beforeEach(() => {
  savedTrusted = process.env.NEXT_PUBLIC_TRUSTED_IMAGE_HOSTS;
  process.env.NEXT_PUBLIC_TRUSTED_IMAGE_HOSTS = ".example.com,.staging.example.com";
});
afterEach(() => {
  if (savedTrusted === undefined) delete process.env.NEXT_PUBLIC_TRUSTED_IMAGE_HOSTS;
  else process.env.NEXT_PUBLIC_TRUSTED_IMAGE_HOSTS = savedTrusted;
});

describe("isTrustedImageUrl", () => {
  it("allows platform hosts, data:image, blob: and same-origin paths", () => {
    expect(isTrustedImageUrl("https://api.example.com/api/x.png")).toBe(true);
    expect(isTrustedImageUrl("https://app.staging.example.com/x.png")).toBe(true);
    expect(isTrustedImageUrl("https://i.ytimg.com/vi/a/hqdefault.jpg")).toBe(true);
    expect(isTrustedImageUrl("data:image/png;base64,AAAA")).toBe(true);
    expect(isTrustedImageUrl("blob:https://app.example.com/uuid")).toBe(true);
    expect(isTrustedImageUrl("/logo-mark.png")).toBe(true);
  });

  it("rejects third-party, lookalike, insecure and protocol-relative URLs", () => {
    expect(isTrustedImageUrl("https://evil.example/p.png?d=secret")).toBe(false);
    expect(isTrustedImageUrl("https://example.com.evil.example/p.png")).toBe(false);
    expect(isTrustedImageUrl("https://notexample.com/p.png")).toBe(false);
    expect(isTrustedImageUrl("http://api.example.com/p.png")).toBe(false);
    expect(isTrustedImageUrl("//evil.example/p.png")).toBe(false);
    expect(isTrustedImageUrl("javascript:alert(1)")).toBe(false);
    expect(isTrustedImageUrl("data:text/html,<script>")).toBe(false);
    expect(isTrustedImageUrl("")).toBe(false);
  });
});

describe("RichText markdown images", () => {
  it("renders a trusted image as an <img>", () => {
    const { container } = render(<RichText text="![logo](https://api.example.com/a.png)" />);
    const img = container.querySelector("img");
    expect(img).not.toBeNull();
    expect(img!.getAttribute("src")).toBe("https://api.example.com/a.png");
  });

  it("never emits an <img> for a remote host and shows a placeholder instead", () => {
    const { container } = render(<RichText text="![x](https://evil.example/p.png?d=secret)" />);
    expect(container.querySelector("img")).toBeNull();
    expect(screen.getByTestId("blocked-image").textContent).toContain("evil.example");
  });
});

describe("RichText markdown image edge cases", () => {
  it("accepts one level of parentheses in a trusted image URL", () => {
    const { container } = render(<RichText text="![a](https://api.example.com/x_(1).png)" />);
    expect(container.querySelector("img")!.getAttribute("src")).toBe("https://api.example.com/x_(1).png");
  });

  it("never fetches a remote host even when the URL contains parentheses", () => {
    const { container } = render(<RichText text="![a](https://evil.example/x_(1).png?d=s)" />);
    expect(container.querySelector("img")).toBeNull();
  });

  it("handles alt text with parentheses and an empty alt", () => {
    const { container } = render(
      <RichText text="![chart (Q1)](https://api.example.com/a.png) and ![](https://api.example.com/b.png)" />,
    );
    expect(container.querySelectorAll("img")).toHaveLength(2);
  });

  it("renders a bare URL after an image as a link, not an image", () => {
    const { container } = render(<RichText text="![x](https://evil.example/p.png) see https://example.com/page" />);
    expect(container.querySelector("img")).toBeNull();
    const links = Array.from(container.querySelectorAll("a")).map((a) => a.getAttribute("href"));
    expect(links).toContain("https://example.com/page");
  });

  it("treats a javascript: image source as blocked with no open link", () => {
    const { container } = render(<RichText text="![x](javascript:alert(1))" />);
    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector("a")).toBeNull();
  });

  it("trusts the apex of a configured platform domain", () => {
    expect(isTrustedImageUrl("https://example.com/x.png")).toBe(true);
    expect(isTrustedImageUrl("https://staging.example.com/x.png")).toBe(true);
  });

  it("trusts nothing beyond the always-allowed hosts when no platform domain is configured", () => {
    delete process.env.NEXT_PUBLIC_TRUSTED_IMAGE_HOSTS;
    expect(isTrustedImageUrl("https://api.example.com/x.png")).toBe(false);
    expect(isTrustedImageUrl("https://i.ytimg.com/vi/a/hqdefault.jpg")).toBe(true);
  });
});
