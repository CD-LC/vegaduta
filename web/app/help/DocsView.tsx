"use client";

import Link from "next/link";
import { ArrowRight } from "lucide-react";

import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { visibleDocTopics } from "./docs-content";
import { useTranslations } from "../lib/i18n";

// Shared renderer for the help docs — used by BOTH the public /docs page
// (dark marketing chrome, no session) and the in-console /help/docs page
// (inside AppShell, theme-following). Everything is styled with the shadcn
// tokens (foreground/muted-foreground/border/card/primary) so the same markup
// reads correctly on either ground.
//
// Layout mirrors the /help reference page: a sticky "On this page" rail on
// wide screens, jump pills on narrow ones, then one article region per topic.
//
// `showAppLinks` gates the per-topic in-console destinations (e.g. /agents):
// they only render on the authenticated surface — a logged-out /docs visitor
// gets the content without dead-end links into auth-gated routes.
export default function DocsView({ showAppLinks = false }: { showAppLinks?: boolean }) {
    const tHC = useTranslations("hcHelpDocsView");
  const DOC_TOPICS = visibleDocTopics();
  return (
    <div className="grid gap-10 lg:grid-cols-[220px_minmax(0,1fr)]">
      {/* Sticky rail (wide screens) */}
      <nav aria-label={tHC("s1")} className="hidden lg:block">
        {/* top offset clears AppShell's sticky header (h-16 + border, ~65px)
            when this view renders inside the console; no shared nav-height
            CSS variable exists to reference instead (checked public.css
            and AppShell.tsx). Keeps the original top-6 (1.5rem) breathing
            room stacked below that header height. */}
        <div className="sticky top-[5.5rem] space-y-1">
          <span className="mb-2 block font-mono text-[11px] font-semibold uppercase tracking-[0.14em] text-muted-foreground">
            {tHC("s1")}
          </span>
          {DOC_TOPICS.map((topic) => (
            <a
              key={topic.id}
              href={`#${topic.id}`}
              className="block rounded-md px-3 py-1.5 text-sm text-muted-foreground transition-colors hover:bg-primary/10 hover:text-foreground"
            >
              {topic.title}
            </a>
          ))}
        </div>
      </nav>

      <div className="min-w-0">
        {/* Jump pills (narrow screens) */}
        <nav aria-label={tHC("s1")} className="mb-8 flex flex-wrap gap-2 lg:hidden">
          {DOC_TOPICS.map((topic) => (
            <Badge key={topic.id} asChild variant="outline">
              <a href={`#${topic.id}`}>{topic.title}</a>
            </Badge>
          ))}
        </nav>

        <div className="space-y-12">
          {DOC_TOPICS.map((topic) => {
            const Icon = topic.icon;
            return (
              <article key={topic.id} id={topic.id} className="scroll-mt-6 border-b border-border pb-12 last:border-b-0 last:pb-0">
                <div className="mb-4 flex items-start gap-3">
                  <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
                    <Icon className="h-5 w-5" aria-hidden="true" />
                  </span>
                  <div className="min-w-0">
                    <h2 className="text-xl font-semibold tracking-tight text-foreground">{topic.title}</h2>
                    <p className="mt-0.5 text-sm text-muted-foreground">{topic.tagline}</p>
                  </div>
                </div>

                <div className="space-y-5">
                  {topic.sections.map((section, i) => (
                    <section key={i}>
                      {section.heading && (
                        <h3 className="mb-2 text-base font-semibold text-foreground">{section.heading}</h3>
                      )}
                      {section.paras?.map((p, j) => (
                        <p key={j} className="mb-2 max-w-[68ch] leading-relaxed text-muted-foreground">
                          {p}
                        </p>
                      ))}
                      {section.bullets && (
                        <ul className="max-w-[68ch] list-none space-y-2 p-0">
                          {section.bullets.map((b, j) => (
                            <li key={j} className="flex items-start gap-2.5 text-sm leading-relaxed text-muted-foreground">
                              <span
                                aria-hidden="true"
                                className="mt-[0.55em] h-1.5 w-1.5 shrink-0 rounded-full bg-primary/70"
                              />
                              <span>{b}</span>
                            </li>
                          ))}
                        </ul>
                      )}
                      {section.steps && (
                        <ol className="max-w-[68ch] list-none space-y-3 p-0">
                          {section.steps.map((s, j) => (
                            <li key={j} className="flex items-start gap-3 text-sm leading-relaxed text-muted-foreground">
                              <span className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary/10 font-mono text-xs font-semibold text-primary">
                                {j + 1}
                              </span>
                              <span>{s}</span>
                            </li>
                          ))}
                        </ol>
                      )}
                    </section>
                  ))}
                </div>

                {showAppLinks && topic.appLinks && topic.appLinks.length > 0 && (
                  <div className="mt-5 flex flex-wrap gap-2">
                    {topic.appLinks.map((link) => (
                      <Button key={link.href} asChild variant="outline" size="sm">
                        <Link href={link.href}>
                          {link.label}
                          <ArrowRight className="h-3.5 w-3.5" aria-hidden="true" />
                        </Link>
                      </Button>
                    ))}
                  </div>
                )}
              </article>
            );
          })}
        </div>
      </div>
    </div>
  );
}
