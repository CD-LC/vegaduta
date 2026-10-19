import type { Metadata } from "next";
import Link from "next/link";
import {
  Apple,
  BookOpen,
  Boxes,
  Container,
  Download,
  Github,
  Laptop,
  Package,
  ShieldCheck,
  Terminal,
  type LucideIcon,
} from "lucide-react";
import TemplatesPublicChrome from "../components/templates/TemplatesPublicChrome";
import { REPO_URL, docsHref, docPageHref } from "../lib/siteLinks";
import { fetchLatestRelease, pickAssets, type DownloadSet, type ReleaseAsset } from "../components/downloads/releaseInfo";

// PUBLIC page: the install artifacts of the latest GitHub Release, plus the
// source tarball, container images, the Helm chart and the SDKs. The asset
// links are resolved from the release itself (fetchLatestRelease) because the
// dmg's file name carries its version. With no release yet - or the API
// unreachable - every card falls back to the releases page and says so,
// rather than rendering dead links.

const RELEASES = `${REPO_URL}/releases`;
// The repo's container packages, filtered list on the org's Packages tab.
// (github.com/<owner>/<repo>/pkgs is not a page; ?repo_name= is the supported
// repo-scoped view.)
const PACKAGES = `https://github.com/orgs/${REPO_URL.split("/")[3]}/packages?repo_name=${REPO_URL.split("/")[4]}`;

export const metadata: Metadata = {
  title: "Download VegaDūta — macOS, Windows and Linux installers",
  description:
    "Download the VegaDūta Setup app for macOS, Windows and Linux, the source tarball for the one-line installer, container images, the Helm chart and the agent SDKs.",
  alternates: { canonical: "/downloads" },
  openGraph: {
    title: "Download VegaDūta",
    description:
      "The VegaDūta Setup app for macOS, Windows and Linux, the source tarball, container images, Helm chart and SDKs.",
    url: "/downloads",
    type: "website",
    images: ["/og-image.png"],
  },
};

function size(a: ReleaseAsset | undefined): string {
  return a && a.sizeMb > 0 ? ` · ${a.sizeMb} MB` : "";
}

function AssetButton({ asset, fallback, label }: { asset?: ReleaseAsset; fallback: string; label: string }) {
  // asset missing (pre-release, or a leg failed): land the visitor on the
  // release page where the full asset list is, not on a 404.
  const href = asset?.url ?? fallback;
  return (
    <a
      href={href}
      className="inline-flex items-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90"
    >
      <Download className="size-4" aria-hidden="true" />
      {label}
      <span className="sr-only"> — {asset?.name ?? "latest release"}</span>
    </a>
  );
}

function OsCard({
  icon: Icon,
  title,
  file,
  body,
  children,
}: {
  icon: LucideIcon;
  title: string;
  file: string;
  body: string;
  children: React.ReactNode;
}) {
  return (
    <section className="rounded-xl border border-border bg-card p-5">
      <p className="flex items-center gap-2 text-sm font-semibold text-foreground">
        <Icon className="size-4 text-primary" aria-hidden="true" />
        {title}
      </p>
      <p className="mt-3 text-sm leading-relaxed text-muted-foreground">{body}</p>
      <div className="mt-4 flex flex-wrap gap-2">{children}</div>
      <p className="mt-3 break-all font-mono text-[11px] text-muted-foreground">{file}</p>
    </section>
  );
}

export default async function DownloadsPage() {
  const release = await fetchLatestRelease();
  const assets: DownloadSet = pickAssets(release?.assets ?? []);
  const released = release != null;
  const date = released && release.publishedAt ? new Date(release.publishedAt).toLocaleDateString("en", { dateStyle: "long" }) : null;

  return (
    <TemplatesPublicChrome
      footer={
        <p>
          Every release asset is checksummed and signed by the release workflow; verify downloads with
          the SHA256SUMS file before running them.{" "}
          <Link href={docsHref()} className="underline underline-offset-2 hover:text-foreground">
            Documentation
          </Link>
          .
        </p>
      }
    >
      <header className="mb-8 flex flex-col gap-4 border-b border-border pb-8">
        <div className="space-y-2">
          <p className="flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.14em] text-primary">
            <Download className="size-4" aria-hidden="true" />
            Downloads
          </p>
          <h1 className="max-w-2xl text-2xl font-semibold tracking-tight text-foreground sm:text-4xl">
            Download VegaDūta
          </h1>
          <p className="max-w-2xl text-sm leading-relaxed text-muted-foreground sm:text-base">
            {released ? (
              <>
                Latest release <span className="font-mono text-foreground">{release.tag}</span>
                {date ? `, published ${date}` : ""}. Pick the Setup app for your machine - it checks
                Docker, downloads the verified release and opens the console - or run the one-line
                terminal installer.{" "}
                <a href={RELEASES} className="underline underline-offset-2 hover:text-foreground" rel="noopener noreferrer">
                  All releases
                </a>
                .
              </>
            ) : (
              <>
                VegaDūta installs from source today; the first tagged release with signed installers
                is on its way. The build-from-source path below is the same one the release tarballs
                run.{" "}
                <a href={RELEASES} className="underline underline-offset-2 hover:text-foreground" rel="noopener noreferrer">
                  Releases on GitHub
                </a>
                .
              </>
            )}
          </p>
        </div>
      </header>

      <div className="space-y-10">
        <section aria-labelledby="setup-app">
          <h2 id="setup-app" className="mb-1 flex items-center gap-2 text-lg font-semibold text-foreground">
            <Laptop className="size-5 text-primary" aria-hidden="true" />
            VegaDūta Setup - the guided installer
          </h2>
          <p className="mb-4 max-w-2xl text-sm text-muted-foreground">
            A small app that checks your machine, starts Docker if needed, downloads the signed
            release and starts VegaDūta, then opens the console in your browser. Pick your platform.
          </p>
          <div className="grid gap-4 md:grid-cols-3">
            <OsCard
              icon={Apple}
              title="macOS"
              file={assets.macos?.name ?? "VegaDuta-Setup-<version>.dmg"}
              body="Universal disk image - one .dmg covers Apple Silicon and Intel. Unsigned for now: Gatekeeper asks once, right-click and Open."
            >
              <AssetButton asset={assets.macos} fallback={release?.url ?? RELEASES} label="macOS .dmg" />
              {(assets.darwinArm64 || assets.darwinAmd64) && (
                <AssetButton
                  asset={assets.darwinArm64 ?? assets.darwinAmd64}
                  fallback={release?.url ?? RELEASES}
                  label="Terminal binary"
                />
              )}
            </OsCard>
            <OsCard
              icon={Package}
              title="Windows"
              file={assets.windows?.name ?? "vegaduta-setup-windows-amd64.zip"}
              body="x64 zip with vegaduta-setup.exe and a first-run note. Not signed yet: SmartScreen asks once - 'More info' then 'Run anyway'."
            >
              <AssetButton asset={assets.windows} fallback={release?.url ?? RELEASES} label="Windows .zip" />
            </OsCard>
            <OsCard
              icon={Terminal}
              title="Linux"
              file={`${assets.linuxAmd64?.name ?? "vegaduta-setup-linux-amd64.tar.gz"} · arm64 too`}
              body="Tarballs for amd64 and arm64, with a first-run README. Needs Docker with Compose v2."
            >
              <AssetButton asset={assets.linuxAmd64} fallback={release?.url ?? RELEASES} label="Linux amd64" />
              <AssetButton asset={assets.linuxArm64} fallback={release?.url ?? RELEASES} label="Linux arm64" />
            </OsCard>
          </div>
        </section>

        <section aria-labelledby="terminal" className="rounded-xl border border-border bg-card p-5">
          <h2 id="terminal" className="mb-1 flex items-center gap-2 text-lg font-semibold text-foreground">
            <Terminal className="size-5 text-primary" aria-hidden="true" />
            One line in a terminal
          </h2>
          <p className="mb-3 max-w-2xl text-sm text-muted-foreground">
            Downloads the latest release tarball and walks you through the same install the Setup app
            drives. Works on macOS and Linux; on Windows use the zip above.
          </p>
          <pre className="overflow-x-auto rounded-md bg-muted px-4 py-3 font-mono text-xs text-foreground sm:text-sm">
            curl -fsSL {REPO_URL.replace("github.com", "raw.githubusercontent.com")}/main/install.sh | sh
          </pre>
        </section>

        <div className="grid gap-4 md:grid-cols-2">
          <section aria-labelledby="source" className="rounded-xl border border-border bg-card p-5">
            <h2 id="source" className="mb-3 flex items-center gap-2 text-lg font-semibold text-foreground">
              <Github className="size-5 text-primary" aria-hidden="true" />
              Source and signatures
            </h2>
            <ul className="m-0 list-none space-y-2 p-0 text-sm text-muted-foreground">
              <li>
                <a
                  href={assets.sourceTar?.url ?? RELEASES}
                  className="text-primary underline underline-offset-2 hover:opacity-80"
                  rel="noopener noreferrer"
                >
                  {assets.sourceTar?.name ?? "vegaduta-<version>.tar.gz"}
                </a>
                {size(assets.sourceTar)} - the full tagged source; the same tree install.sh downloads
              </li>
              <li>
                <a
                  href={assets.sums?.url ?? RELEASES}
                  className="text-primary underline underline-offset-2 hover:opacity-80"
                  rel="noopener noreferrer"
                >
                  SHA256SUMS
                </a>
                {assets.sumsSig && (
                  <>
                    {" "}
                    +{" "}
                    <a
                      href={assets.sumsSig.url}
                      className="text-primary underline underline-offset-2 hover:opacity-80"
                      rel="noopener noreferrer"
                    >
                      sigstore bundle
                    </a>
                  </>
                )}{" "}
                - checksums and cosign signatures for every asset
              </li>
              <li className="flex items-start gap-2 pt-1">
                <ShieldCheck className="mt-0.5 size-4 shrink-0 text-primary" aria-hidden="true" />
                <span>
                  Verify a download: <code className="font-mono text-xs">sha256sum -c SHA256SUMS --ignore-missing</code>
                </span>
              </li>
            </ul>
          </section>

          <section aria-labelledby="deploy" className="rounded-xl border border-border bg-card p-5">
            <h2 id="deploy" className="mb-3 flex items-center gap-2 text-lg font-semibold text-foreground">
              <Boxes className="size-5 text-primary" aria-hidden="true" />
              Images and the Helm chart
            </h2>
            <ul className="m-0 list-none space-y-2 p-0 text-sm text-muted-foreground">
              <li className="flex items-start gap-2">
                <Container className="mt-0.5 size-4 shrink-0 text-primary" aria-hidden="true" />
                <span>
                  <a href={PACKAGES} className="text-primary underline underline-offset-2 hover:opacity-80" rel="noopener noreferrer">
                    Container images on GHCR
                  </a>{" "}
                  - multi-arch, signed with cosign, SBOM and SLSA provenance attached
                </span>
              </li>
              <li>
                Helm chart:{" "}
                <code className="break-all font-mono text-xs">oci://ghcr.io/cd-lc/charts/vegaduta</code>
                {released ? ` (version ${release.tag.replace(/^v/, "")})` : ""}
              </li>
              <li>
                <Link href={docPageHref("installation")} className="text-primary underline underline-offset-2 hover:opacity-80">
                  Installation guide
                </Link>
              </li>
            </ul>
          </section>
        </div>

        <section aria-labelledby="sdk" className="rounded-xl border border-border bg-card p-5">
          <h2 id="sdk" className="mb-3 flex items-center gap-2 text-lg font-semibold text-foreground">
            <BookOpen className="size-5 text-primary" aria-hidden="true" />
            SDKs - VegaDūta from your own code
          </h2>
          <ul className="m-0 list-none space-y-3 p-0 text-sm text-muted-foreground">
            <li>
              <span className="font-medium text-foreground">Java Agent SDK</span>{" "}
              <code className="font-mono text-xs">io.vegaduta:vegaduta-agent-*</code> - governance
              (approvals, budgets, audit, guardrails) around Spring AI tools.{" "}
              <Link href={docPageHref("javaSdk")} className="text-primary underline underline-offset-2 hover:opacity-80">
                Docs and build
              </Link>
            </li>
            <li>
              <span className="font-medium text-foreground">Python Agent SDK</span>{" "}
              <code className="font-mono text-xs">pip install vegaduta-agent</code> - the same
              governance contracts, dependency-free.{" "}
              <Link href={docPageHref("sdkOverview")} className="text-primary underline underline-offset-2 hover:opacity-80">
                SDK overview
              </Link>
            </li>
            <li>
              <span className="font-medium text-foreground">TypeScript / Python REST clients</span> -
              generated from the platform&apos;s OpenAPI document.{" "}
              <a href={`${REPO_URL}/tree/main/sdk`} className="text-primary underline underline-offset-2 hover:opacity-80" rel="noopener noreferrer">
                sdk/ in the repository
              </a>
            </li>
          </ul>
        </section>
      </div>
    </TemplatesPublicChrome>
  );
}
