"use client";

import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import DocsView from "../DocsView";
import { DOCS_TITLE, DOCS_SUBTITLE } from "../docs-content";

// In-console help docs — the authenticated twin of the public /docs page.
// Same shared content module (help/docs-content.tsx) and renderer (DocsView),
// but wrapped in AppShell (theme-following, not forced-dark) and with the
// per-topic in-console deep links enabled (showAppLinks) since every reader
// here has a session and can jump straight to /agents, /knowledge, etc.
export default function HelpDocsPage() {
  return (
    <AppShell eyebrow="Help & Docs">
      <div className="mb-8">
        <PageHeader title={DOCS_TITLE} subtitle={DOCS_SUBTITLE} />
      </div>
      <DocsView showAppLinks />
    </AppShell>
  );
}
