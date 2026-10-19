import type { Metadata } from "next";
import { ExternalLink, Fingerprint } from "lucide-react";
import LibraryChrome from "../components/library/LibraryChrome";
import { academyLessonUrl } from "../learn/curriculum";
import DirectoryBrowser from "../components/directory/DirectoryBrowser";
import { fetchDirectoryServer } from "../components/directory/serverFetch";

// The PUBLIC agent directory - every agent a VegaDūta tenant has chosen to
// publish, as a signed snapshot (A2A card + capability manifest) served from
// control_plane. Route is in PUBLIC_ROUTES (lib/publicRoutes.ts): discovery
// necessarily precedes authentication - an external agent or a prospective
// customer must be able to enumerate published agents before they hold a
// credential for any of them.

export const metadata: Metadata = {
  title: "Agent Directory — discoverable, verifiable agents | VegaDūta",
  description:
    "Every agent published by a VegaDūta workspace, with its signed capability manifest and A2A card. Verify the signature against the platform JWKS before you call one.",
  alternates: { canonical: "/directory" },
  openGraph: {
    title: "Agent Directory — discoverable, verifiable agents | VegaDūta",
    description:
      "Every agent published by a VegaDūta workspace, with its signed capability manifest and A2A card.",
    url: "/directory",
  },
};

export default async function DirectoryPage() {
  const initial = await fetchDirectoryServer();

  return (
    <LibraryChrome
      eyebrowKey="eyebrowDirectory"
      footer={
        <p>
          Every listing is a signed snapshot the owning workspace published. Verify the manifest
          signature against the platform JWKS before trusting a card&apos;s declared capabilities.
        </p>
      }
    >
      <header className="mb-8 flex flex-col gap-4 border-b border-border pb-8">
        <div className="space-y-2">
          <p className="flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.14em] text-primary">
            <Fingerprint className="size-4" aria-hidden="true" />
            Agent directory
          </p>
          <h1 className="max-w-2xl text-2xl font-semibold tracking-tight text-foreground sm:text-4xl">
            Agents that can prove who they are
          </h1>
          <p className="max-w-2xl text-sm leading-relaxed text-muted-foreground sm:text-base">
            Every agent below was published by a VegaDūta workspace and ships with a signed
            capability manifest and a signed A2A agent card - the Know-Your-Agent layer that lets a
            caller verify identity and declared capabilities before delegating work to one.
          </p>
          {/* External anchor to the Academy's own origin (same convention as
              the per-entry homepage links in DirectoryBrowser): the public
              Know-Your-Agent lesson explains what a signed manifest proves. */}
          <a
            href={academyLessonUrl("l18-11", "/directory")}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1.5 text-sm font-medium text-primary underline-offset-4 hover:underline"
          >
            Learn how agent trust works in the Academy
            <ExternalLink className="size-3.5" aria-hidden="true" />
          </a>
        </div>
      </header>

      <DirectoryBrowser initialEntries={initial?.entries ?? null} initialTotal={initial?.total} initialHasMore={initial?.hasMore} />
    </LibraryChrome>
  );
}
