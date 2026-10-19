import BrandName from "../BrandName";
import { CIN_NUMBER, DIN_NUMBER, LEGAL_ENTITY } from "../../lib/legalEntity";
import { OssFooter, OssHeader, SmartLink } from "./OssChrome";
import { commercialUrl, docPageHref, docsHref } from "../../lib/siteLinks";
import { COMMUNITY_LINKS, CONTACT_EMAIL, LICENSE_NAME, REPO_URL, STACK, STANDARDS } from "./ossSite";

// /about in "oss" site mode (lib/siteMode.ts): project, stack, governance and
// operator details. The classic /about stays in app/about/page.tsx.
export default function OssAbout() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <OssHeader />
      <main className="mx-auto max-w-3xl space-y-10 px-4 py-14">
        <header>
          <h1 className="m-0 text-3xl font-bold tracking-tight">
            About <BrandName />
          </h1>
          <p className="mb-0 mt-3 text-muted-foreground">
            <BrandName /> is an open-source, multi-tenant platform for building AI agents, wiring them into workflows
            with human approval, grounding them in your own data, and reaching them over the channels your users
            already use. It is self-hostable and released under the {LICENSE_NAME} license.
          </p>
        </header>

        <section>
          <h2 className="text-xl font-semibold">Technology</h2>
          <ul className="mt-3 space-y-1 pl-5 text-sm text-muted-foreground">
            {STACK.map((s) => (
              <li key={s.name}>
                <strong className="text-foreground">{s.name}:</strong> {s.detail}
              </li>
            ))}
          </ul>
        </section>

        <section>
          <h2 className="text-xl font-semibold">Standards</h2>
          <ul className="mt-3 space-y-1 pl-5 text-sm text-muted-foreground">
            {STANDARDS.map((s) => (
              <li key={s.name}>
                <strong className="text-foreground">{s.name}:</strong> {s.detail}
              </li>
            ))}
          </ul>
        </section>

        <section>
          <h2 className="text-xl font-semibold">Documentation and dependencies</h2>
          <ul className="mt-3 space-y-1 pl-5 text-sm text-muted-foreground">
            <li>
              <SmartLink href={docsHref()} className="text-primary hover:underline">
                Documentation
              </SmartLink>
              : install, configure, extend, architecture.
            </li>
            <li>
              <SmartLink href={docPageHref("javaSdk")} className="text-primary hover:underline">
                Java Agent SDK
              </SmartLink>
              : governance for agents inside your own Java application.
            </li>
            <li>
              <SmartLink href={docPageHref("sdkOverview")} className="text-primary hover:underline">
                TypeScript and Python clients
              </SmartLink>
              : call the platform API from your code.
            </li>
            <li>
              <SmartLink href={docPageHref("pluginsAndApps")} className="text-primary hover:underline">
                Plugins, apps and mobile
              </SmartLink>
              : editors, browsers, the Android app and the MCP server, with their real status.
            </li>
          </ul>
        </section>

        {commercialUrl() && (
          <section>
            <h2 className="text-xl font-semibold">Hosted version</h2>
            <p className="mt-3 text-sm text-muted-foreground">
              A hosted offering of this software is operated at{" "}
              <a className="text-primary hover:underline" href={commercialUrl() ?? undefined} rel="noopener noreferrer">
                {commercialUrl()}
              </a>
              . The open-source project and the hosted offering run the same code.
            </p>
          </section>
        )}

        <section>
          <h2 className="text-xl font-semibold">Project and governance</h2>
          <p className="mt-3 text-sm text-muted-foreground">
            Source, issues and releases live on{" "}
            <a className="text-primary hover:underline" href={REPO_URL} rel="noopener noreferrer">
              GitHub
            </a>
            . Decision-making is described in{" "}
            <a className="text-primary hover:underline" href={COMMUNITY_LINKS[2].href} rel="noopener noreferrer">
              GOVERNANCE.md
            </a>
            ; security reports follow{" "}
            <a className="text-primary hover:underline" href={COMMUNITY_LINKS[3].href} rel="noopener noreferrer">
              SECURITY.md
            </a>
            .
          </p>
        </section>

        <section>
          <h2 className="text-xl font-semibold">Operator</h2>
          <dl className="mt-3 text-sm text-muted-foreground">
            <dt className="font-medium text-foreground">Maintained by</dt>
            <dd className="m-0 mb-2">{LEGAL_ENTITY}</dd>
            {CIN_NUMBER && (
              <>
                <dt className="font-medium text-foreground">CIN</dt>
                <dd className="m-0 mb-2">{CIN_NUMBER}</dd>
              </>
            )}
            {DIN_NUMBER && (
              <>
                <dt className="font-medium text-foreground">DIN</dt>
                <dd className="m-0 mb-2">{DIN_NUMBER}</dd>
              </>
            )}
            {CONTACT_EMAIL && (
              <>
                <dt className="font-medium text-foreground">Contact</dt>
                <dd className="m-0">
                  <a className="text-primary hover:underline" href={`mailto:${CONTACT_EMAIL}`}>
                    {CONTACT_EMAIL}
                  </a>
                </dd>
              </>
            )}
          </dl>
        </section>
      </main>
      <OssFooter />
    </div>
  );
}
