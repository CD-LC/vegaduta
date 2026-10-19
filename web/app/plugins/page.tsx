import type { Metadata } from "next";
import { Puzzle } from "lucide-react";
import TemplatesPublicChrome from "../components/templates/TemplatesPublicChrome";
import PluginsGallery from "../components/plugins/PluginsGallery";
import { fetchPluginCatalogServer } from "../components/plugins/serverFetch";
import AllSurfaces from "../components/plugins/AllSurfaces";
import { docPageHref } from "../lib/siteLinks";

// PUBLIC page for every first-party client: the downloadable IDE/browser
// plugins (catalog-driven gallery) plus AllSurfaces, the honest status list of
// the apps, wearables and tools the catalog does not hold. Server component per the /templates pattern: the
// catalog is best-effort fetched during SSR so crawlers see real HTML, and
// PluginsGallery takes over client-side when the backend was unreachable.
// Route is in PUBLIC_ROUTES (lib/auth.ts) - renders for logged-out visitors,
// so it reuses the templates pages' standalone chrome instead of AppShell.

export const metadata: Metadata = {
  title: "Plugins, Apps & Wearables — VS Code, JetBrains, Chrome, Android, Wear OS | VegaDūta",
  description:
    "Every place VegaDūta runs, with where each one really stands: VS Code, JetBrains and Chrome plugins to download, the Android app in beta, the Wear OS companion, Eclipse, Edge, the desktop agent and the MCP server.",
  alternates: { canonical: "/plugins" },
  openGraph: {
    title: "Plugins, Apps & Wearables — VS Code, JetBrains, Chrome, Android, Wear OS | VegaDūta",
    description:
      "Every place VegaDūta runs, with where each one really stands: editor and browser plugins, the Android app, the Wear OS companion, the desktop agent and the MCP server.",
    url: "/plugins",
  },
};

export default async function PluginsMarketplacePage() {
  const initialCatalog = await fetchPluginCatalogServer();

  return (
    <TemplatesPublicChrome
      footer={
        <p>
          Plugin downloads are official first-party builds. Verify any download against the SHA-256
          checksum shown on its card. Signing in from a plugin uses the same VegaDūta account and
          plan you already have.{" "}
          <a href={docPageHref("pluginsAndApps")} className="underline underline-offset-2 hover:text-foreground">
            How the catalog, downloads and the mobile app work
          </a>
          .
        </p>
      }
    >
      <header className="mb-8 flex flex-col gap-4 border-b border-border pb-8">
        <div className="space-y-2">
          <p className="flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.14em] text-primary">
            <Puzzle className="size-4" aria-hidden="true" />
            Plugins, apps and wearables
          </p>
          <h1 className="max-w-2xl text-2xl font-semibold tracking-tight text-foreground sm:text-4xl">
            VegaDūta in your editor, your browser, your phone and on your wrist
          </h1>
          <p className="max-w-2xl text-sm leading-relaxed text-muted-foreground sm:text-base">
            Chat with your agents, run workflows, and use free on-device AI without leaving VS Code,
            JetBrains IDEs, or Chrome. Heavy work runs on the platform; quick, private work can run
            entirely on your machine. Below the downloads is every other place VegaDūta runs,
            including the Android app and the Wear OS companion, with what is ready and what is not.
          </p>
        </div>
      </header>

      <PluginsGallery initialCatalog={initialCatalog} />
      <AllSurfaces catalog={initialCatalog} />
    </TemplatesPublicChrome>
  );
}
