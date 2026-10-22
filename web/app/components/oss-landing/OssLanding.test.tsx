import { render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// next/image and the Logo's image loader are Next internals; a plain <img> is enough here.
vi.mock("next/image", () => ({
  // eslint-disable-next-line @next/next/no-img-element
  default: (props: { alt?: string; src?: string }) => <img alt={props.alt ?? ""} src={String(props.src ?? "")} />,
}));

import OssAbout from "./OssAbout";
import OssLanding from "./OssLanding";
import { REPO_URL } from "./ossSite";

function setEnv(env: Record<string, string>) {
  window.__ENV = env;
}

function hrefs(root: HTMLElement): string[] {
  return within(root)
    .getAllByRole("link")
    .map((a) => a.getAttribute("href") ?? "");
}

beforeEach(() => {
  delete window.__ENV;
});
afterEach(() => {
  delete window.__ENV;
});

describe("OSS landing accessibility", () => {
  it("makes every scrollable code block keyboard-focusable and named", () => {
    const { container } = render(<OssLanding />);
    const blocks = Array.from(container.querySelectorAll("pre"));
    expect(blocks.length).toBeGreaterThan(0);
    for (const pre of blocks) {
      expect(pre.getAttribute("tabindex"), pre.textContent?.slice(0, 40)).toBe("0");
      expect(pre.getAttribute("role")).toBe("region");
      expect(pre.getAttribute("aria-label")).toBeTruthy();
    }
  });
});

describe("OSS landing wiring", () => {
  it("links docs to the in-app help and the SDK to GitHub when no docs site is configured", () => {
    render(<OssLanding />);
    const all = hrefs(document.body);
    expect(all).toContain("/docs");
    expect(all).toContain(`${REPO_URL}/blob/main/sdk/java/README.md`);
    expect(all).toContain(`${REPO_URL}/blob/main/docs/plugins-and-apps.md`);
    expect(all).toContain("/plugins");
    expect(all).toContain("/#dependencies");
    // No hosted offering configured: nothing commercial is advertised.
    expect(screen.queryByText("Hosted version")).toBeNull();
  });

  it("points docs, the SDK and plugins pages at the published docs site when configured", () => {
    setEnv({ NEXT_PUBLIC_DOCS_URL: "https://docs.example.com/vegaduta/" });
    render(<OssLanding />);
    const all = hrefs(document.body);
    expect(all).toContain("https://docs.example.com/vegaduta");
    expect(all).toContain("https://docs.example.com/vegaduta/sdk/java-agent-sdk/");
    expect(all).toContain("https://docs.example.com/vegaduta/sdk/");
    expect(all).toContain("https://docs.example.com/vegaduta/plugins-and-apps/");
    expect(all).not.toContain("/docs");
  });

  it("states the SDK's real availability and lists every dependency", () => {
    render(<OssLanding />);
    const section = document.getElementById("dependencies") as HTMLElement;
    expect(section).toBeTruthy();
    const text = section.textContent ?? "";
    expect(text).toContain("io.vegaduta");
    expect(text).toContain("vegaduta-agent-spring-boot-starter");
    expect(text).toContain("Maven Central publishing is on the roadmap");
    expect(text).toContain("Java Agent SDK");
    expect(text).toContain("TypeScript client");
    expect(text).toContain("Python client");
  });

  it("lists every surface from the single status source, including mobile", () => {
    render(<OssLanding />);
    const text = document.getElementById("surfaces")?.textContent ?? "";
    for (const name of ["VS Code", "JetBrains IDEs", "Chrome", "Android app", "Wear OS watch", "iPhone app", "MCP server"]) {
      expect(text).toContain(name);
    }
    expect(text).toContain("In development");
  });

  it("shows a hosted-version link only when a commercial URL is configured", () => {
    setEnv({ NEXT_PUBLIC_COMMERCIAL_URL: "https://app.example.com/" });
    render(<OssLanding />);
    const links = screen.getAllByText("Hosted version");
    expect(links.length).toBeGreaterThan(0);
    expect(hrefs(document.body)).toContain("https://app.example.com");
  });

  it("ignores an invalid commercial URL", () => {
    setEnv({ NEXT_PUBLIC_COMMERCIAL_URL: "javascript:alert(1)" });
    render(<OssLanding />);
    expect(screen.queryByText("Hosted version")).toBeNull();
  });
});

describe("OSS about wiring", () => {
  it("links documentation, the Java SDK and plugins", () => {
    render(<OssAbout />);
    const all = hrefs(document.body);
    expect(all).toContain("/docs");
    expect(all).toContain(`${REPO_URL}/blob/main/sdk/java/README.md`);
    expect(all).toContain(`${REPO_URL}/blob/main/docs/plugins-and-apps.md`);
    expect(screen.queryByRole("heading", { name: "Hosted version" })).toBeNull();
  });

  it("describes the hosted version when one is configured", () => {
    setEnv({ NEXT_PUBLIC_COMMERCIAL_URL: "https://app.example.com" });
    render(<OssAbout />);
    expect(screen.getByRole("heading", { name: "Hosted version" })).toBeTruthy();
  });
});
