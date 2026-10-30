"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import BrandName from "../../components/BrandName";
import PublicWorkflowNav from "./PublicWorkflowNav";
import {
  ArrowRight,
  Sparkles,
  Network,
  UserCheck,
  Layers,
  Search,
  X,
  Workflow,
  Boxes,
  ListChecks,
  Loader2,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from "../../components/ui/accordion";
import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardContent } from "../../components/ui/card";
import { Input } from "../../components/ui/input";
import { Separator } from "../../components/ui/separator";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../../components/ui/table";
import { Tooltip, TooltipContent, TooltipTrigger } from "../../components/ui/tooltip";
// Deliberately NOT importing "./recipes-data" — that aggregator pulls in every
// sector pack (~1.9 MB of source) and would ship the whole catalogue to the
// browser on first paint. We load metadata only (recipes-index) and fetch a
// category's recipe bodies on demand (recipes-registry) when it's opened.
import { NODE_META, type Industry } from "./recipes-types";
import { INDUSTRY_INDEX, TOTAL_CATEGORIES, TOTAL_RECIPES, type IndustryMeta } from "./recipes-index";
import { loadIndustry } from "./recipes-registry";
import { LEGEND_ORDER, NODE_DESC, PATTERN_GUIDE, PRINCIPLES } from "./recipes-guide-content";
import { RecipeCard } from "./RecipeCard";
import { useTranslations } from "../../lib/i18n";

// (2026-07-25) AppShell import removed: this route was made PUBLIC (see
// PUBLIC_ROUTES in lib/auth.ts — the Academy's Industry Gauntlet deep-links
// here for learners with no console session), and AuthProvider renders public
// routes WITHOUT the auth context, so AppShell's useAuth() threw and 500'd
// the whole page for everyone. It now renders as a standalone public reading
// surface with the same chrome pattern as /docs; console users get a nav link
// back to the console instead of the sidebar. The nav itself now lives in
// PublicWorkflowNav.tsx, shared with the sibling [industry] pages, which had
// the exact same AppShell/useAuth bug until it was fixed alongside this one.

// Industry workflow recipes — a data-driven "how to build a workflow, by
// industry" guide, rebuilt as an enterprise catalog: a stat hero, a sticky
// search/filter toolbar over 40+ industries, a tooltip-annotated node legend,
// a real data table for the pattern picker, and per-recipe Flow / Build-spec
// tabs. All presentation; the payload is INDUSTRIES from recipes-data.tsx,
// every recipe expressed in VegaDūta's real workflow nodes so it maps 1:1 to
// something buildable in the designer. Inline copy, no i18n keys (same
// precedent as the Help primer). Theme-following via tokens.

// A single stat tile in the hero band.
function StatTile({ icon: Icon, value, label }: { icon: LucideIcon; value: number | string; label: string }) {
  return (
    <div className="flex items-center gap-3 rounded-xl border border-border bg-card/60 px-4 py-3 backdrop-blur-sm">
      <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary ring-1 ring-inset ring-primary/20">
        <Icon className="size-[18px]" aria-hidden="true" />
      </span>
      <div className="min-w-0">
        <div className="text-xl font-semibold tabular-nums tracking-tight text-foreground">{value}</div>
        <div className="truncate font-mono text-[10px] font-semibold uppercase tracking-[0.14em] text-muted-foreground">
          {label}
        </div>
      </div>
    </div>
  );
}

// Split out of page.tsx (structured-data audit, 2026-07-30): this is the
// client island (search/filter state, lazy per-industry pack loading) —
// page.tsx is now a server component wrapping this, carrying `metadata` +
// BreadcrumbList/ItemList JSON-LD (sourced from the same INDUSTRY_INDEX this
// component reads), which a "use client" file cannot export.
const INDUSTRY_PAGE_SIZE = 12;

export default function WorkflowRecipesClient() {
    const tHC = useTranslations("hcHelpWorkflowrecipesWorkflowRecipesClient");
  const [query, setQuery] = useState("");
  const q = query.trim().toLowerCase();
  const searching = q.length > 0;

  // Catalog-wide stats — precomputed in the index, so no pack is loaded to
  // show them.
  const stats = useMemo(() => {
    const patternCount = INDUSTRY_INDEX.find((i) => i.id === "multi-agent-patterns")?.recipeCount ?? 0;
    return {
      industries: TOTAL_CATEGORIES,
      recipes: TOTAL_RECIPES,
      patterns: patternCount,
      blocks: LEGEND_ORDER.length,
    };
  }, []);

  // Search runs against metadata only (name, tagline, recipe titles) — enough
  // to find a category without pulling any recipe bodies over the wire.
  const filtered: IndustryMeta[] = useMemo(() => {
    if (!searching) return INDUSTRY_INDEX;
    return INDUSTRY_INDEX.filter((ind) => {
      if (ind.name.toLowerCase().includes(q) || ind.tagline.toLowerCase().includes(q)) return true;
      return ind.recipeTitles.some((t) => t.toLowerCase().includes(q));
    });
  }, [q, searching]);

  // Show the first PAGE matches, then "Show more": the page lists 89 accordions
  // and rendering all of them at once grows the DOM and the scroll length.
  const [visibleCount, setVisibleCount] = useState(INDUSTRY_PAGE_SIZE);
  useEffect(() => {
    setVisibleCount(INDUSTRY_PAGE_SIZE);
  }, [q]);
  // A jump link (or a #hash on load) to a category past the fold expands the
  // list far enough to include it, then scrolls to it.
  useEffect(() => {
    function reveal() {
      const id = decodeURIComponent(window.location.hash.replace(/^#/, ""));
      if (!id) return;
      const idx = filtered.findIndex((i) => i.id === id);
      if (idx >= visibleCount) {
        setVisibleCount(Math.ceil((idx + 1) / INDUSTRY_PAGE_SIZE) * INDUSTRY_PAGE_SIZE);
        requestAnimationFrame(() => document.getElementById(id)?.scrollIntoView());
      }
    }
    reveal();
    window.addEventListener("hashchange", reveal);
    return () => window.removeEventListener("hashchange", reveal);
  }, [filtered, visibleCount]);
  const shownIndustries = useMemo(() => filtered.slice(0, visibleCount), [filtered, visibleCount]);
  const hiddenCount = filtered.length - shownIndustries.length;

  const matchedRecipes = useMemo(() => filtered.reduce((n, ind) => n + ind.recipeCount, 0), [filtered]);

  // Lazily-fetched recipe bodies, keyed by industry id. A category's pack is
  // requested the first time one of its recipes is expanded, then cached (the
  // registry also caches the module itself, so re-opening costs nothing).
  const [loaded, setLoaded] = useState<Record<string, Industry>>({});
  const [loadingId, setLoadingId] = useState<Record<string, boolean>>({});

  async function ensureLoaded(meta: IndustryMeta) {
    if (loaded[meta.id] || loadingId[meta.id]) return;
    setLoadingId((s) => ({ ...s, [meta.id]: true }));
    try {
      const full = await loadIndustry(meta.pack, meta.id);
      if (full) setLoaded((s) => ({ ...s, [meta.id]: full }));
    } finally {
      setLoadingId((s) => ({ ...s, [meta.id]: false }));
    }
  }

  return (
    <div className="vd-pub relative min-h-screen w-full overflow-x-clip bg-background text-foreground antialiased">
      <PublicWorkflowNav />

      <main className="mx-auto max-w-7xl px-5 pb-16 sm:px-8">
        {/* ---------------------------------------------------------- Hero band */}
        <section className="relative mb-8 overflow-hidden rounded-2xl border border-border bg-gradient-to-br from-primary/10 via-card to-card p-6 sm:p-8">
          {/* Decorative grid glow — pure token colour, low alpha */}
          <div
            className="pointer-events-none absolute -right-16 -top-20 size-64 rounded-full bg-primary/10 blur-3xl"
            aria-hidden="true"
          />
          <div className="relative space-y-5">
            <div className="space-y-2.5">
              <div className="inline-flex items-center gap-1.5 font-mono text-xs font-semibold uppercase tracking-[0.16em] text-primary">
                <Workflow className="size-3.5" aria-hidden="true" />
                {tHC("s37")}
              </div>
              <h1 className="max-w-3xl text-3xl font-semibold tracking-tight text-foreground sm:text-4xl">
                {tHC("s36")}
              </h1>
              <p className="max-w-2xl text-sm leading-relaxed text-muted-foreground sm:text-base">
                {tHC("s35")} <BrandName />
                {tHC("s34")}
              </p>
            </div>

            {/* Stat tiles */}
            <div className="grid grid-cols-2 gap-3 sm:max-w-2xl lg:grid-cols-4">
              <StatTile icon={Boxes} value={stats.industries} label="Industries" />
              <StatTile icon={Workflow} value={stats.recipes} label={tHC("s33")} />
              <StatTile icon={Network} value={stats.patterns} label={tHC("s32")} />
              <StatTile icon={Layers} value={stats.blocks} label={tHC("s31")} />
            </div>

            <div className="flex flex-wrap items-center gap-3 pt-1">
              <Button asChild>
                <Link href="/workflows">
                  {tHC("s30")}
                  <ArrowRight className="size-4" aria-hidden="true" />
                </Link>
              </Button>
              <Button asChild variant="outline">
                <a href="#multi-agent-patterns">
                  <Network className="size-4" aria-hidden="true" />
                  {tHC("s11")}
                </a>
              </Button>
            </div>
          </div>
        </section>

        {/* --------------------------------------------------- Sticky filter bar */}
        <div
          className="sticky z-20 -mx-4 mb-8 border-y border-border bg-background/80 px-4 py-3 backdrop-blur-xl sm:-mx-6 sm:px-6"
          style={{ top: "calc(var(--pub-nav-h) + 0px)" }}
        >
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div className="relative w-full sm:max-w-md">
              <Search
                className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
                aria-hidden="true"
              />
              <Input
                type="search"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder={tHC("s29")}
                aria-label={tHC("s28")}
                className="ps-9 pe-9"
              />
              {searching && (
                <button
                  type="button"
                  onClick={() => setQuery("")}
                  aria-label={tHC("s6")}
                  className="absolute end-2 top-1/2 flex size-6 -translate-y-1/2 items-center justify-center rounded-md border-0 bg-transparent p-0 text-muted-foreground shadow-none transition-colors hover:bg-accent hover:text-foreground"
                >
                  <X className="size-3.5" aria-hidden="true" />
                </button>
              )}
            </div>
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              {searching ? (
                <span className="tabular-nums">
                  <span className="font-semibold text-foreground">{filtered.length}</span> of {stats.industries}{" "}
                  {tHC("s27")} <span className="font-semibold text-foreground">{matchedRecipes}</span> recipes
                </span>
              ) : (
                <Badge variant="secondary" className="gap-1.5">
                  <ListChecks className="size-3" aria-hidden="true" />
                  {stats.recipes} {tHC("s26")} {stats.industries} industries
                </Badge>
              )}
            </div>
          </div>
        </div>

        <div className="grid gap-10 lg:grid-cols-[210px_minmax(0,1fr)]">
          {/* Sticky rail (wide screens) */}
          <nav aria-label={tHC("s25")} className="hidden lg:block">
            <div className="sticky space-y-1" style={{ top: "calc(var(--pub-nav-h) + 64px)" }}>
              <span className="mb-2 block font-mono text-[11px] font-semibold uppercase tracking-[0.14em] text-muted-foreground">
                Industries
              </span>
              {filtered.length === 0 ? (
                <p className="px-3 py-1.5 text-sm text-muted-foreground">{tHC("s24")}</p>
              ) : (
                filtered.map((ind) => (
                  <a
                    key={ind.id}
                    href={`#${ind.id}`}
                    className="group flex items-center gap-2 rounded-md px-3 py-1.5 text-sm text-muted-foreground transition-colors hover:bg-[var(--surface-hover)] hover:text-foreground"
                  >
                    <ind.icon className="size-3.5 shrink-0" aria-hidden="true" />
                    <span className="truncate">{ind.name}</span>
                    <span className="ms-auto shrink-0 font-mono text-[10px] tabular-nums text-muted-foreground group-hover:text-muted-foreground">
                      {ind.recipeCount}
                    </span>
                  </a>
                ))
              )}
            </div>
          </nav>

          <div className="min-w-0">
            {/* Jump pills (narrow screens) */}
            {filtered.length > 0 && (
              <nav aria-label="Industries" className="mb-8 flex flex-wrap gap-2 lg:hidden">
                {filtered.map((ind) => (
                  <Badge key={ind.id} asChild variant="outline">
                    <a href={`#${ind.id}`} className="gap-1.5">
                      <ind.icon className="size-3" aria-hidden="true" />
                      {ind.name}
                    </a>
                  </Badge>
                ))}
              </nav>
            )}

            {/* Reference sections — hidden while searching so results lead. */}
            {!searching && (
              <>
                {/* How to read a recipe — the legend of building blocks */}
                <section className="mb-12 scroll-mt-32 pb-12">
                  <h2 className="mb-3 text-xl font-semibold tracking-tight text-foreground">{tHC("s23")}</h2>
                  <p className="max-w-[68ch] text-sm leading-relaxed text-muted-foreground">
                    {tHC("s22")} <strong className="font-semibold text-foreground">{tHC("s21")}</strong>{" "}
                    {tHC("s20")} <strong className="font-semibold text-foreground">nodes</strong> {tHC("s19")} <strong className="font-semibold text-foreground">Output</strong> {tHC("s18")}
                  </p>
                  <div className="mt-5 flex flex-wrap gap-2">
                    {LEGEND_ORDER.map((kind) => {
                      const meta = NODE_META[kind];
                      const Icon = meta.icon;
                      return (
                        <Tooltip key={kind}>
                          <TooltipTrigger asChild>
                            <span
                              tabIndex={0}
                              className="inline-flex cursor-help items-center gap-1.5 rounded-full border border-border bg-card px-2.5 py-1 text-xs font-medium text-foreground outline-none transition-colors hover:border-primary/40 hover:bg-primary/5 focus-visible:ring-[3px] focus-visible:ring-ring/40"
                            >
                              <Icon className="size-3.5 text-primary" aria-hidden="true" />
                              {meta.label}
                            </span>
                          </TooltipTrigger>
                          <TooltipContent>{NODE_DESC[kind]}</TooltipContent>
                        </Tooltip>
                      );
                    })}
                  </div>

                  <Alert className="mt-6 border-primary/25 bg-primary/5">
                    <UserCheck className="text-primary" aria-hidden="true" />
                    <AlertTitle>{tHC("s17")}</AlertTitle>
                    <AlertDescription>
                      {tHC("s16")}
                    </AlertDescription>
                  </Alert>
                </section>

                <Separator className="mb-12" />

                {/* Design principles — the enterprise "why" every recipe follows */}
                <section className="mb-12 scroll-mt-32 pb-12">
                  <h2 className="mb-3 text-xl font-semibold tracking-tight text-foreground">{tHC("s15")}</h2>
                  <p className="max-w-[68ch] text-sm leading-relaxed text-muted-foreground">
                    {tHC("s14")}
                  </p>
                  <div className="mt-6 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                    {PRINCIPLES.map((p) => {
                      const Icon = p.icon;
                      return (
                        <Card key={p.title} className="gap-0 py-0 transition-colors hover:border-primary/30">
                          <CardContent className="p-4">
                            <div className="flex items-center gap-2">
                              <span className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
                                <Icon className="size-4" aria-hidden="true" />
                              </span>
                              <h3 className="text-sm font-semibold tracking-tight text-foreground">{p.title}</h3>
                            </div>
                            <p className="mt-2 text-xs leading-relaxed text-muted-foreground">{p.body}</p>
                          </CardContent>
                        </Card>
                      );
                    })}
                  </div>
                </section>

                <Separator className="mb-12" />

                {/* Which pattern should I use? — a decision reference, as a table */}
                <section className="mb-12 scroll-mt-32 pb-12">
                  <h2 className="mb-3 text-xl font-semibold tracking-tight text-foreground">
                    {tHC("s13")}
                  </h2>
                  <p className="max-w-[68ch] text-sm leading-relaxed text-muted-foreground">
                    {tHC("s12")}{" "}
                    <a
                      href="#multi-agent-patterns"
                      className="font-medium text-primary underline-offset-4 hover:underline"
                    >
                      {tHC("s11")}
                    </a>{" "}
                    {tHC("s10")}
                  </p>
                  <div className="mt-5 overflow-hidden rounded-xl border border-border">
                    <Table>
                      <TableHeader>
                        <TableRow className="bg-secondary/40 hover:bg-secondary/40">
                          <TableHead className="w-2/5">If you&apos;re…</TableHead>
                          <TableHead>{tHC("s9")}</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {PATTERN_GUIDE.map((row) => (
                          <TableRow key={row.when}>
                            <TableCell className="font-medium text-foreground">{row.when}</TableCell>
                            <TableCell className="text-muted-foreground">{row.use}</TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </div>
                </section>

                <Separator className="mb-14" />
              </>
            )}

            {/* Industries */}
            {filtered.length === 0 ? (
              <div className="flex flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-border py-16 text-center">
                <Search className="size-6 text-muted-foreground" aria-hidden="true" />
                <div className="space-y-1">
                  <p className="text-sm font-medium text-foreground">{tHC("s8")}{query}”.</p>
                  <p className="text-sm text-muted-foreground">
                    {tHC("s7")}
                  </p>
                </div>
                <Button variant="outline" size="sm" onClick={() => setQuery("")}>
                  {tHC("s6")}
                </Button>
              </div>
            ) : (
              <div className="space-y-14">
                {shownIndustries.map((ind) => {
                  const Icon = ind.icon;
                  // Nothing is expanded by default: opening a category is what
                  // triggers its (lazy) pack fetch, so a default-open category
                  // would defeat the whole point of the split.
                  const full = loaded[ind.id];
                  const isLoading = loadingId[ind.id];
                  return (
                    <section key={ind.id} id={ind.id} className="scroll-mt-32">
                      <div className="mb-5 flex items-start gap-3.5">
                        <div className="flex size-11 shrink-0 items-center justify-center rounded-xl bg-primary/10 text-primary ring-1 ring-inset ring-primary/20">
                          <Icon className="size-5" aria-hidden="true" />
                        </div>
                        <div className="min-w-0 space-y-1">
                          <div className="flex flex-wrap items-center gap-2">
                            {/* CRAWLABILITY FIX (2026-08-16). Every industry used to be
                              reachable from this index ONLY through in-page `#${ind.id}`
                              anchors — the sticky rail and the jump pills above — because
                              the index also renders each industry's content inline under
                              id={ind.id}. Those anchors are doing a real job, so they stay;
                              the bug was that NOTHING here ever linked to the 89 dedicated
                              /help/workflow-recipes/[industry] pages. Those pages are
                              statically generated from this exact INDUSTRY_INDEX
                              (generateStaticParams() maps i.id 1:1, so the href below is
                              always a valid route) and they are advertised in sitemap.xml —
                              yet their own hub gave them zero inbound links, leaving them
                              unreachable for crawlers and for anyone navigating by keyboard.
                              The heading is now a REAL <Link>, so the href is present in the
                              server-rendered HTML on first paint. Do NOT convert this back to
                              a `#` anchor or to a router.push() onClick — an onClick is
                              invisible to a crawler and re-creates the exact bug. */}
                            <h3 className="text-lg font-semibold tracking-tight text-foreground">
                              <Link
                                href={`/help/workflow-recipes/${ind.id}`}
                                className="underline-offset-4 hover:text-primary hover:underline"
                              >
                                {ind.name}
                              </Link>
                            </h3>
                            {ind.featured && (
                              <Badge variant="accent" className="gap-1">
                                <Sparkles className="size-3" aria-hidden="true" />
                                Featured
                              </Badge>
                            )}
                            <Badge variant="outline" className="gap-1 text-[10px]">
                              {ind.recipeCount} {ind.recipeCount === 1 ? "recipe" : "recipes"}
                            </Badge>
                          </div>
                          <p className="max-w-[68ch] text-sm leading-relaxed text-muted-foreground">{ind.tagline}</p>
                          {/* Explicit affordance so the dedicated page is discoverable, not
                            just inferable from a hoverable heading. Same target as the
                            heading link; the aria-label carries the industry name so 89
                            of these don't all read as "Open the dedicated page" in a
                            screen-reader's link list. */}
                          <Link
                            href={`/help/workflow-recipes/${ind.id}`}
                            aria-label={`Open the dedicated page: AI agent workflows for ${ind.name}`}
                            className="inline-flex items-center gap-1 text-xs font-medium text-primary underline-offset-4 hover:underline"
                          >
                            {tHC("s5")}
                            <ArrowRight className="size-3" aria-hidden="true" />
                          </Link>
                        </div>
                      </div>

                      <Accordion
                        type="multiple"
                        className="w-full"
                        onValueChange={(open) => {
                          // Any expand in this category pulls its pack once.
                          if (open.length > 0) void ensureLoaded(ind);
                        }}
                      >
                        {/* Titles come from the index, so the full list is
                          browsable (and searchable) before any pack loads.
                          recipeTitles[i] lines up with recipes[i] by construction. */}
                        {ind.recipeTitles.map((title, i) => {
                          const recipe = full?.recipes[i];
                          return (
                            <AccordionItem key={`${ind.id}-${i}`} value={`${ind.id}-${i}`}>
                              <AccordionTrigger className="text-sm font-semibold text-foreground sm:text-base">
                                {title}
                              </AccordionTrigger>
                              <AccordionContent>
                                {recipe ? (
                                  <RecipeCard recipe={recipe} />
                                ) : (
                                  <div
                                    className="flex items-center gap-2.5 px-1 py-6 text-sm text-muted-foreground"
                                    role="status"
                                    aria-live="polite"
                                  >
                                    <Loader2 className="size-4 animate-spin text-primary" aria-hidden="true" />
                                    {isLoading ? "Loading this recipe…" : "Preparing…"}
                                  </div>
                                )}
                              </AccordionContent>
                            </AccordionItem>
                          );
                        })}
                      </Accordion>
                    </section>
                  );
                })}
                {hiddenCount > 0 && (
                  <div className="flex justify-center">
                    <Button
                      variant="outline"
                      onClick={() => setVisibleCount((c) => c + INDUSTRY_PAGE_SIZE)}
                      data-testid="recipes-show-more"
                    >
                      {tHC("s4")}{hiddenCount})
                    </Button>
                  </div>
                )}
              </div>
            )}

            {/* Footer CTA */}
            <div className="mt-16 flex flex-col items-start gap-4 rounded-xl border border-border bg-gradient-to-br from-primary/10 via-card to-card p-6 sm:flex-row sm:items-center sm:justify-between">
              <div className="space-y-1">
                <h3 className="text-base font-semibold tracking-tight text-foreground">{tHC("s3")}</h3>
                <p className="max-w-[52ch] text-sm leading-relaxed text-muted-foreground">
                  {tHC("s2")}
                </p>
              </div>
              <Button asChild className="shrink-0">
                <Link href="/workflows">
                  {tHC("s1")}
                  <ArrowRight className="size-4" aria-hidden="true" />
                </Link>
              </Button>
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}
