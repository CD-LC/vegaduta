import { afterEach, describe, expect, it } from "vitest";
import {
  REPO_URL,
  commercialUrl,
  docPageHref,
  docsHref,
  docsPageHref,
  docsSiteUrl,
  hasDocsSite,
  isExternalHref,
  parseHttpUrl,
} from "./siteLinks";

// jsdom provides window, so getPublicEnv reads window.__ENV first.
function setEnv(env: Record<string, string>) {
  window.__ENV = env;
}

afterEach(() => {
  delete window.__ENV;
  delete process.env.NEXT_PUBLIC_DOCS_URL;
  delete process.env.NEXT_PUBLIC_COMMERCIAL_URL;
});

describe("parseHttpUrl", () => {
  it("accepts http(s) URLs and strips trailing slashes", () => {
    expect(parseHttpUrl("https://docs.example.com/")).toBe("https://docs.example.com");
    expect(parseHttpUrl("  https://o.github.io/vegaduta/  ")).toBe("https://o.github.io/vegaduta");
    expect(parseHttpUrl("http://localhost:8000")).toBe("http://localhost:8000");
  });

  it("rejects empty, relative and non-http schemes", () => {
    expect(parseHttpUrl(undefined)).toBeNull();
    expect(parseHttpUrl("")).toBeNull();
    expect(parseHttpUrl("docs.example.com")).toBeNull();
    expect(parseHttpUrl("/docs")).toBeNull();
    expect(parseHttpUrl("javascript:alert(1)")).toBeNull();
    expect(parseHttpUrl("ftp://example.com")).toBeNull();
  });
});

describe("docs links", () => {
  it("falls back to the in-app help and GitHub when no docs site is configured", () => {
    expect(docsSiteUrl()).toBeNull();
    expect(hasDocsSite()).toBe(false);
    expect(docsHref()).toBe("/docs");
    expect(docsPageHref("sdk/java-agent-sdk", "sdk/java/README.md")).toBe(`${REPO_URL}/blob/main/sdk/java/README.md`);
    expect(docPageHref("pluginsAndApps")).toBe(`${REPO_URL}/blob/main/docs/plugins-and-apps.md`);
  });

  it("points at the published site when configured", () => {
    setEnv({ NEXT_PUBLIC_DOCS_URL: "https://docs.example.com/vegaduta/" });
    expect(hasDocsSite()).toBe(true);
    expect(docsHref()).toBe("https://docs.example.com/vegaduta");
    expect(docPageHref("javaSdk")).toBe("https://docs.example.com/vegaduta/sdk/java-agent-sdk/");
    expect(docPageHref("pluginsAndApps")).toBe("https://docs.example.com/vegaduta/plugins-and-apps/");
  });

  it("ignores an invalid docs URL instead of rendering it", () => {
    setEnv({ NEXT_PUBLIC_DOCS_URL: "javascript:alert(1)" });
    expect(docsHref()).toBe("/docs");
  });
});

describe("commercial link", () => {
  it("is null unless a valid URL is configured", () => {
    expect(commercialUrl()).toBeNull();
    setEnv({ NEXT_PUBLIC_COMMERCIAL_URL: "not a url" });
    expect(commercialUrl()).toBeNull();
    setEnv({ NEXT_PUBLIC_COMMERCIAL_URL: "https://app.example.com/" });
    expect(commercialUrl()).toBe("https://app.example.com");
  });
});

describe("isExternalHref", () => {
  it("distinguishes absolute URLs from in-app paths", () => {
    expect(isExternalHref("https://a.example")).toBe(true);
    expect(isExternalHref("/docs")).toBe(false);
    expect(isExternalHref("#capabilities")).toBe(false);
  });
});
