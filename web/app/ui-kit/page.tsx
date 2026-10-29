"use client";

import { useState } from "react";
import { ArrowRight, BarChart3, Bot, Database, Mail, MessageCircle, Search, Sparkles, Wand2, Zap } from "lucide-react";
import BrandName from "../components/BrandName";
import { Button } from "../components/ui/button";
import { Badge } from "../components/ui/badge";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "../components/ui/card";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import { Separator } from "../components/ui/separator";
import { Skeleton } from "../components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../components/ui/table";
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from "../components/ui/accordion";
import { useTranslations } from "../lib/i18n";

// ShadCN living style guide. Renders every canonical shadcn/ui primitive
// (app/components/ui/*) in its variants and states, so the design system is
// verifiable at a glance and stays the single reference for the ShadCN
// migration. Public route (AuthProvider.PUBLIC_ROUTES) — renders without a
// Keycloak session. Dark-scoped like the rest of the public funnel (the
// shadcn token layer in app/shadcn-theme.css is defined under `.dark`).

const BUTTON_VARIANTS = ["default", "secondary", "outline", "ghost", "link", "destructive"] as const;
const BADGE_VARIANTS = ["default", "secondary", "accent", "outline", "destructive"] as const;

// The 2026-07-29 elevation/category/state system (docs/DESIGN-SYSTEM-CONTRACT-2026-07-29.md).
// Showcased here on purpose: every screen that consumes it in anger sits behind
// Keycloak, so without this section the new primitives cannot be seen at all
// without signing in. This page is public, so it is the one place the system
// stays visually reviewable.
const ELEVATION_RUNGS = [
  { token: "--surface-sunken", label: "sunken", use: "recessed wells, logs", lstar: "2.77" },
  { token: "--surface-page", label: "page", use: "app canvas", lstar: "4.42" },
  { token: "--surface-card-alt", label: "card-alt", use: "well inside a card", lstar: "6.90" },
  { token: "--surface-card", label: "card", use: "default panel", lstar: "9.95" },
  { token: "--surface-raised", label: "raised", use: "popovers, hover", lstar: "13.39" },
  { token: "--surface-hover", label: "hover", use: "hovered rows, active nav", lstar: "16.36" },
] as const;

// Icon is mandatory, not decorative: simulating protanopia collapses the
// assistant and comms hues to a perceptual distance of 6.7, so hue alone
// cannot carry category. See the contract, section 2.
const CATEGORIES = [
  { token: "--cat-assistant", label: "Assistant", Icon: Bot },
  { token: "--cat-comms", label: "Comms", Icon: MessageCircle },
  { token: "--cat-data", label: "Data", Icon: Database },
  { token: "--cat-analysis", label: "Analysis", Icon: BarChart3 },
  { token: "--cat-automation", label: "Automation", Icon: Zap },
  { token: "--cat-creative", label: "Creative", Icon: Wand2 },
] as const;

const STATES = [
  { token: "--state-running", label: "Running", live: true },
  { token: "--state-queued", label: "Queued", live: true },
  { token: "--state-success", label: "Succeeded", live: false },
  { token: "--state-warning", label: "Warning", live: false },
  { token: "--state-error", label: "Failed", live: false },
  { token: "--state-idle", label: "Idle", live: false },
] as const;

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="space-y-5">
      <div className="flex items-center gap-3">
        <h2 className="font-mono text-xs font-semibold uppercase tracking-[0.18em] text-primary">{title}</h2>
        <Separator className="flex-1" />
      </div>
      {children}
    </section>
  );
}

export default function UiKitPage() {
    const tHC = useTranslations("hcUikitPage");
  const [loading, setLoading] = useState(false);

  return (
    <div className="vd-pub min-h-screen w-full bg-background text-foreground antialiased">
      <div className="mx-auto max-w-5xl space-y-14 px-6 py-16">
        {/* Header */}
        <header className="space-y-3">
          <Badge variant="accent" className="gap-1.5">
            <Sparkles className="size-3" aria-hidden="true" /> {tHC("hcUikitPage.s17")}
          </Badge>
          <h1 className="text-4xl font-semibold tracking-tight">{tHC("hcUikitPage.s16")}</h1>
          <p className="max-w-2xl text-muted-foreground">
            {tHC("hcUikitPage.s15")} <BrandName /> {tHC("hcUikitPage.s14")}{" "}
            <code className="rounded bg-secondary px-1.5 py-0.5 font-mono text-xs">app/components/ui/*</code>.
          </p>
        </header>

        {/* Elevation ladder — depth is carried by lightness, since shadows do
            not read on dark surfaces. Each swatch paints its real token. */}
        <Section title="Elevation">
          <div className="overflow-hidden rounded-xl border border-border">
            {ELEVATION_RUNGS.map((r) => (
              <div
                key={r.token}
                className="flex flex-wrap items-center justify-between gap-x-6 gap-y-1 border-b border-border/60 px-4 py-3 last:border-b-0"
                style={{ background: `var(${r.token})` }}
              >
                <div className="flex min-w-0 flex-col">
                  <span className="font-mono text-sm">{r.label}</span>
                  <span className="text-xs text-muted-foreground">{r.use}</span>
                </div>
                <span className="font-mono text-xs tabular-nums text-muted-foreground">
                  {r.token} · L* {r.lstar}
                </span>
              </div>
            ))}
          </div>
        </Section>

        {/* Category — identity. Always icon + tint, never tint alone. */}
        <Section title="Category">
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {CATEGORIES.map(({ token, label, Icon }) => (
              <div
                key={token}
                className="card-interactive flex items-center gap-3 rounded-xl border border-border bg-card p-3"
                style={{ ["--cat" as string]: `var(${token})` }}
              >
                <span className="cat-chip">
                  <Icon size={15} />
                </span>
                <div className="flex min-w-0 flex-col">
                  <span className="text-sm font-medium">{label}</span>
                  <span className="font-mono text-xs text-muted-foreground">{token}</span>
                </div>
              </div>
            ))}
          </div>
        </Section>

        {/* State — execution status. Circle/pill + motion, so it never reads
            as a category even where the two share a hue. */}
        <Section title="State">
          <div className="flex flex-wrap gap-2">
            {STATES.map(({ token, label, live }) => (
              <span key={token} className="state-pill" style={{ ["--state" as string]: `var(${token})` }}>
                <span className={live ? "state-dot state-dot-live" : "state-dot"} />
                {label}
              </span>
            ))}
          </div>
          <div className="max-w-sm space-y-2">
            <span className="font-mono text-xs text-muted-foreground">state-meter</span>
            <div className="state-meter" style={{ ["--state" as string]: "var(--state-running)" }}>
              <span style={{ width: "62%" }} />
            </div>
          </div>
        </Section>

        {/* Buttons */}
        <Section title="Buttons">
          <div className="flex flex-wrap gap-3">
            {BUTTON_VARIANTS.map((v) => (
              <Button key={v} variant={v}>
                {v[0].toUpperCase() + v.slice(1)}
              </Button>
            ))}
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <Button size="sm">Small</Button>
            <Button size="default">Default</Button>
            <Button size="lg">
              Large <ArrowRight className="size-4" aria-hidden="true" />
            </Button>
            <Button size="icon" aria-label="Search">
              <Search className="size-4" aria-hidden="true" />
            </Button>
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <Button>
              <Mail className="size-4" aria-hidden="true" /> {tHC("hcUikitPage.s13")}
            </Button>
            <Button disabled>Disabled</Button>
            <Button
              onClick={() => {
                setLoading(true);
                setTimeout(() => setLoading(false), 1600);
              }}
              disabled={loading}
            >
              {loading ? "Loading…" : "Click to load"}
            </Button>
          </div>
        </Section>

        {/* Badges */}
        <Section title="Badges">
          <div className="flex flex-wrap gap-3">
            {BADGE_VARIANTS.map((v) => (
              <Badge key={v} variant={v}>
                {v[0].toUpperCase() + v.slice(1)}
              </Badge>
            ))}
          </div>
        </Section>

        {/* Inputs & labels */}
        <Section title={tHC("hcUikitPage.s12")}>
          <div className="grid gap-6 sm:grid-cols-2">
            <div className="space-y-2">
              <Label htmlFor="uk-email">Email</Label>
              <div className="relative">
                <Mail
                  className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
                  aria-hidden="true"
                />
                <Input id="uk-email" type="email" placeholder="you@anywhere.com" className="ps-9" />
              </div>
            </div>
            <div className="space-y-2">
              <Label htmlFor="uk-pass">Password</Label>
              <Input id="uk-pass" type="password" placeholder="••••••••" />
            </div>
            <div className="space-y-2">
              <Label htmlFor="uk-search">Search</Label>
              <Input id="uk-search" placeholder={tHC("hcUikitPage.s11")} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="uk-dis">Disabled</Label>
              <Input id="uk-dis" disabled placeholder="Unavailable" />
            </div>
          </div>
        </Section>

        {/* Cards */}
        <Section title="Cards">
          <div className="grid gap-6 sm:grid-cols-3">
            <Card>
              <CardHeader>
                <CardTitle>{tHC("hcUikitPage.s10")}</CardTitle>
                <CardDescription>{tHC("hcUikitPage.s9")}</CardDescription>
              </CardHeader>
              <CardFooter>
                <Button variant="outline" size="sm" className="w-full">
                  {tHC("hcUikitPage.s8")}
                </Button>
              </CardFooter>
            </Card>
            <Card>
              <CardHeader>
                <CardTitle>{tHC("hcUikitPage.s7")}</CardTitle>
                <CardDescription>{tHC("hcUikitPage.s6")}</CardDescription>
              </CardHeader>
            </Card>
            <Card>
              <CardContent className="space-y-3">
                <Skeleton className="h-3.5 w-3/5" />
                <Skeleton className="h-3 w-full" />
                <Skeleton className="h-3 w-4/5" />
              </CardContent>
            </Card>
          </div>
        </Section>

        {/* Accordion */}
        <Section title="Accordion">
          <Accordion type="single" collapsible className="max-w-2xl rounded-xl border border-border bg-card/60 px-5">
            <AccordionItem value="a">
              <AccordionTrigger>{tHC("hcUikitPage.s5")}</AccordionTrigger>
              <AccordionContent>
                {tHC("hcUikitPage.s4")} <BrandName />{" "}
                {tHC("hcUikitPage.s3")}
              </AccordionContent>
            </AccordionItem>
            <AccordionItem value="b">
              <AccordionTrigger>{tHC("hcUikitPage.s2")}</AccordionTrigger>
              <AccordionContent>
                {tHC("hcUikitPage.s1")}
              </AccordionContent>
            </AccordionItem>
          </Accordion>
        </Section>

        {/* Table */}
        <Section title="Table">
          <div className="max-w-2xl rounded-xl border border-border bg-card/60">
            <Table>
              <TableHeader>
                <TableRow className="hover:bg-transparent">
                  <TableHead>Primitive</TableHead>
                  <TableHead>Kind</TableHead>
                  <TableHead>Status</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {[
                  { p: "Button", k: "shadcn + Slot", s: "Live" },
                  { p: "Accordion", k: "Radix", s: "Live" },
                  { p: "Table", k: "shadcn", s: "Live" },
                ].map((r) => (
                  <TableRow key={r.p}>
                    <TableCell className="font-medium">{r.p}</TableCell>
                    <TableCell className="text-muted-foreground">{r.k}</TableCell>
                    <TableCell>
                      <Badge variant="accent">{r.s}</Badge>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        </Section>
      </div>
    </div>
  );
}
