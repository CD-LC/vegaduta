import { render, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next/image", () => ({
  // eslint-disable-next-line @next/next/no-img-element
  default: (props: { alt?: string; src?: string }) => <img alt={props.alt ?? ""} src={String(props.src ?? "")} />,
}));
// Translations are not what this guards: return the key, so link labels from t() are stable.
vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => "en",
}));
vi.mock("../LanguageLinks", () => ({ default: (): null => null }));

import SiteFooter from "./SiteFooter";
import { REPO_URL } from "../../lib/siteLinks";

function footerLinks(): Record<string, string> {
  const footer = document.querySelector("footer") as HTMLElement;
  const map: Record<string, string> = {};
  for (const a of within(footer).getAllByRole("link")) {
    map[a.textContent?.trim() ?? ""] = a.getAttribute("href") ?? "";
  }
  return map;
}

beforeEach(() => {
  delete window.__ENV;
});
afterEach(() => {
  delete window.__ENV;
});

describe("classic footer cross-links to the open-source side", () => {
  it("links plugins, the Java SDK, the source and the self-host guide", () => {
    render(<SiteFooter />);
    const links = footerLinks();
    expect(links["Plugins & apps"]).toBe("/plugins");
    expect(links["Java Agent SDK"]).toBe(`${REPO_URL}/blob/main/sdk/java/README.md`);
    expect(links["Open source"]).toBe(REPO_URL);
    expect(links["Self-host guide"]).toBe(`${REPO_URL}/blob/main/docs/installation.md`);
    expect(links["footer_helpDocs"]).toBe("/docs");
  });

  it("uses the published docs site when configured", () => {
    window.__ENV = { NEXT_PUBLIC_DOCS_URL: "https://docs.example.com/vegaduta" };
    render(<SiteFooter />);
    const links = footerLinks();
    expect(links["footer_helpDocs"]).toBe("https://docs.example.com/vegaduta");
    expect(links["Java Agent SDK"]).toBe("https://docs.example.com/vegaduta/sdk/java-agent-sdk/");
    expect(links["Self-host guide"]).toBe("https://docs.example.com/vegaduta/installation/");
  });
});
