import { getPublicEnv } from "./env";

// Where the public pages link to. One module so the OSS pages, the classic
// marketing pages and the in-app help agree, and so a deployment changes a link
// by setting an environment variable, not by editing components.
//
//   NEXT_PUBLIC_DOCS_URL        absolute URL of the published documentation site
//                               (docs/ built by mkdocs.yml, deployed by
//                               .github/workflows/docs.yml). Unset = the in-app
//                               help at /docs.
//   NEXT_PUBLIC_COMMERCIAL_URL  absolute URL of a hosted/commercial offering of
//                               this software. Unset = the OSS pages show no
//                               such link (self-hosted deployments have none).
//
// Both are read at RUNTIME through getPublicEnv (window.__ENV > build-time), so
// one image serves any deployment. Values that are not http(s) URLs are ignored
// rather than rendered: a mistyped variable must not become a broken or
// javascript: link.

export const REPO_URL = "https://github.com/CD-LC/vegaduta";

/** Returns the URL without a trailing slash, or null unless it is an absolute http(s) URL. */
export function parseHttpUrl(value: string | undefined | null): string | null {
  const raw = value?.trim();
  if (!raw) return null;
  try {
    const url = new URL(raw);
    if (url.protocol !== "https:" && url.protocol !== "http:") return null;
    return url.toString().replace(/\/+$/, "");
  } catch {
    return null;
  }
}

/** Base URL of the published documentation site, or null when none is configured. */
export function docsSiteUrl(): string | null {
  return parseHttpUrl(getPublicEnv("NEXT_PUBLIC_DOCS_URL"));
}

export function hasDocsSite(): boolean {
  return docsSiteUrl() !== null;
}

/** The "Docs" link: the published site when configured, else the in-app help. */
export function docsHref(): string {
  return docsSiteUrl() ?? "/docs";
}

/**
 * Link to one documentation page. With a docs site it is `<site>/<sitePath>/`
 * (MkDocs directory URLs); without one it is the Markdown file on GitHub, which
 * is always reachable. `sitePath` is the page path without extension
 * (`sdk/java-agent-sdk`); `repoPath` is the file in the repository.
 */
export function docsPageHref(sitePath: string, repoPath: string): string {
  const site = docsSiteUrl();
  if (site) return `${site}/${sitePath.replace(/^\/+|\/+$/g, "")}/`;
  return `${REPO_URL}/blob/main/${repoPath.replace(/^\/+/, "")}`;
}

/** Hosted/commercial offering, or null when this deployment links to none. */
export function commercialUrl(): string | null {
  return parseHttpUrl(getPublicEnv("NEXT_PUBLIC_COMMERCIAL_URL"));
}

/** True for links that leave this origin (render as <a>, not next/link). */
export function isExternalHref(href: string): boolean {
  return /^https?:\/\//i.test(href);
}

// Pages that have a home in the documentation site. Keep in step with mkdocs.yml.
export const DOC_PAGES = {
  javaSdk: { sitePath: "sdk/java-agent-sdk", repoPath: "sdk/java/README.md" },
  sdkOverview: { sitePath: "sdk", repoPath: "docs/sdk/README.md" },
  pluginsAndApps: { sitePath: "plugins-and-apps", repoPath: "docs/plugins-and-apps.md" },
  siteModes: { sitePath: "site-modes", repoPath: "docs/site-modes.md" },
  installation: { sitePath: "installation", repoPath: "docs/installation.md" },
  gettingStarted: { sitePath: "getting-started", repoPath: "docs/getting-started.md" },
} as const;

export function docPageHref(page: keyof typeof DOC_PAGES): string {
  const p = DOC_PAGES[page];
  return docsPageHref(p.sitePath, p.repoPath);
}
