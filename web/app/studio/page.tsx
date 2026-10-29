"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ArrowRight, Clapperboard, Download, ImageIcon, Sparkles, Wand2 } from "lucide-react";

import AppShell from "../components/AppShell";
import PageHeader from "../components/ax/PageHeader";
import { Section, EmptyState } from "../components/ax/page-kit";
import Tilt3d from "../components/ax/Tilt3d";
import { Skeleton } from "../components/ui/skeleton";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";
import { useAppChrome } from "../components/AppChromeProvider";
import { edgeEnabled } from "../lib/edge/capabilities";
import { api } from "../lib/api";
import { useTranslations } from "../lib/i18n";
import QuickCreate from "./QuickCreate";
import { playgroundHref, reusePrompt } from "./imagePrompt";

/**
 * Studio — reframed 2026-08-12 (direct feedback on the live page: "what is
 * the use of this... looks very childish and video never gets generated...
 * come up with something which does really make sense").
 *
 * Root cause of that reaction, established before rewriting a single line
 * here: the previous 3-card layout tried to compete with dedicated AI-video
 * tools (Runway/Pika/Sora/Veo) on generic "make a video" — using exactly one
 * provider (Google Vertex Veo), gated behind a BYOK credential almost nobody
 * has, whose own request/response mapping was flagged in
 * VertexVeoVideoGenerationClient.java as NEVER VERIFIED against a live call.
 * Nearly every visitor saw an upgrade wall or a "connect your key" button
 * instead of a working form - that's "video never gets generated." The
 * REMAINING free tier (Ken Burns pan/zoom) read as childish in isolation
 * because nothing on the page said what actually makes this platform
 * different: agents, not a generic content generator.
 *
 * The fix is a reframe, not new backend surface (same "no fiction, no new
 * backend" discipline every prior clip-studio pass followed):
 *   1. The paid Vertex-video card is REMOVED from this primary surface. The
 *      backend (VideoGenerationController, the generate_video agent tool)
 *      is untouched - a BYOK tenant can still ask an agent to make a video
 *      in chat - this page just stops leading with a feature 95%+ of
 *      visitors can never use, and stops taking credibility damage from an
 *      unverified integration. GenerateVideoForm.tsx is deleted as dead code
 *      (its only import site was this file).
 *   2. Motion+ - the one piece that's genuinely agent-driven (an agent
 *      imagines a keyframe sequence, the browser interpolates motion
 *      on-device via WebGPU/WASM) - leads the Clip Studio card whenever
 *      edgeEnabled(), instead of being a footnote hint below the Ken Burns
 *      copy. This is the thing no generic AI content tool can offer: an
 *      agent directing motion, not a person prompting a video model.
 *   3. A real "made by your agents" strip up top shows actual recent
 *      generated images (api.listGeneratedImages + the same
 *      downloadGeneratedImage blob fetch ClipStudio's own picker already
 *      uses - no new endpoint). This is deliberately NOT a duplicate of
 *      /files (which already owns the full searchable table across every
 *      artifact type) - it's a small, visual proof strip: evidence instead
 *      of another description of a capability. Each thumbnail deep-links
 *      into /clips?imageId=<id> (a link ClipStudio already supports) so the
 *      portfolio and the motion tools feel like one story.
 */

const GALLERY_LIMIT = 6;

interface GalleryItem {
  id: any;
  prompt: string;
  url: string;
}

function useAgentGallery() {
  const [items, setItems] = useState<GalleryItem[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [hasAny, setHasAny] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const urls: string[] = [];

    api
      .listGeneratedImages()
      .then(async (rows: any) => {
        if (cancelled) return;
        const list = Array.isArray(rows) ? rows : [];
        setHasAny(list.length > 0);
        // Newest first, capped - a small visual strip, not a second Files
        // table. No thumbnail endpoint exists (ClipStudio's own picker has
        // the same constraint), so this fetches full images for a bounded,
        // small set rather than inventing new backend surface for one.
        const recent = [...list]
          .sort((a: any, b: any) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
          .slice(0, GALLERY_LIMIT);

        const settled = await Promise.allSettled(
          recent.map(async (row: any) => {
            const { blob } = await api.downloadGeneratedImage(row.id);
            const url = URL.createObjectURL(blob);
            urls.push(url);
            return { id: row.id, prompt: row.prompt || "Generated image", url } as GalleryItem;
          })
        );
        if (cancelled) return;
        setItems(
          settled.filter((r): r is PromiseFulfilledResult<GalleryItem> => r.status === "fulfilled").map((r) => r.value)
        );
      })
      .catch(() => {
        // Best-effort, same posture as every other list fetch in this app -
        // the strip just falls back to its empty state.
      })
      .finally(() => {
        if (!cancelled) setLoaded(true);
      });

    return () => {
      cancelled = true;
      urls.forEach((u) => URL.revokeObjectURL(u));
    };
  }, []);

  return { items, loaded, hasAny };
}

export default function StudioPage() {
  const t = useTranslations("studioPage");
  const { tenantSettings } = useAppChrome();
  const plan = tenantSettings?.plan || "FREE";
  const motionPlusAvailable = edgeEnabled();
  const gallery = useAgentGallery();

  const allowanceRows = [
    { key: "imageAllowanceFree", current: plan === "FREE" },
    { key: "imageAllowancePaid", current: plan === "PREMIUM" },
    { key: "imageAllowanceUnlimited", current: plan === "UNLIMITED" },
  ];

  return (
    <AppShell eyebrow={t("pageEyebrow")}>
      <PageHeader title={t("pageTitle")} subtitle={t("pageSubtitle")} />

      <QuickCreate />

      <Section title={t("galleryTitle")} className="mb-6">
        {gallery.loaded && !gallery.hasAny ? (
          <Card className="p-2">
            <EmptyState
              icon={Sparkles}
              title={t("galleryEmptyTitle")}
              description={t("galleryEmptyBody")}
              action={
                <Button asChild size="sm">
                  <Link href="/playground">{t("galleryEmptyCta")}</Link>
                </Button>
              }
            />
          </Card>
        ) : (
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-6">
            {(gallery.loaded
              ? gallery.items
              : (Array.from({ length: GALLERY_LIMIT }) as (GalleryItem | undefined)[])
            ).map((item, i) => (
              <Tilt3d key={item?.id ?? i} maxTilt={3}>
                {item ? (
                  <div className="card-interactive card-sheen group overflow-hidden rounded-xl border border-border bg-card">
                    <Link href={`/clips?imageId=${item.id}`} className="block" title={item.prompt}>
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img
                        src={item.url}
                        alt={item.prompt}
                        width={160}
                        height={160}
                        className="aspect-square w-full object-cover"
                      />
                      <div className="flex items-center gap-1 px-2 py-1.5 text-xs text-muted-foreground">
                        {/* Resting at a low opacity instead of 0 so the "make a
                          clip" cue is discoverable without hovering (touch has
                          no hover) - full opacity still lands on hover/focus. */}
                        <Clapperboard
                          className="size-3 shrink-0 opacity-60 transition-opacity group-hover:opacity-100"
                          aria-hidden="true"
                        />
                        <span className="truncate">{item.prompt}</span>
                      </div>
                    </Link>
                    <div className="flex items-center gap-1 border-t border-border px-1.5 py-1">
                      <Button asChild variant="ghost" size="sm" className="h-9 flex-1 justify-start px-1.5 text-xs">
                        <Link href={playgroundHref(reusePrompt(item.prompt))}>
                          <Wand2 className="size-3" aria-hidden="true" /> {t("tileReuse")}
                        </Link>
                      </Button>
                      <Button asChild variant="ghost" size="sm" className="h-9 px-2" title={t("tileDownload")}>
                        <a href={item.url} download={`studio-${item.id}.png`} aria-label={t("tileDownload")}>
                          <Download className="size-3" aria-hidden="true" />
                        </a>
                      </Button>
                    </div>
                  </div>
                ) : (
                  <div className="overflow-hidden rounded-xl border border-border bg-card">
                    <Skeleton className="aspect-square rounded-none" />
                    <div className="flex items-center gap-1 px-2 py-1.5">
                      <Skeleton className="h-3 w-3/4" />
                    </div>
                  </div>
                )}
              </Tilt3d>
            ))}
          </div>
        )}
        {gallery.loaded && gallery.hasAny && (
          <div className="mt-2 text-end">
            <Button asChild variant="link" size="sm">
              <Link href="/files">{t("galleryViewAll")}</Link>
            </Button>
          </div>
        )}
      </Section>

      <div className="grid gap-4 lg:grid-cols-2">
        {/* --------------------------------------------------------- Clip Studio
            Leads with Motion+ (agent-directed keyframe animation) whenever the
            build flag is on; falls back to describing only Still (Ken Burns)
            when it's off, matching the honest-ceiling rule - never describe a
            capability that isn't actually reachable. */}
        <Card className="flex flex-col">
          <CardHeader>
            <div className="mb-2 flex items-center justify-between gap-2">
              <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
                <Clapperboard className="h-5 w-5 shrink-0" aria-hidden="true" />
              </span>
              <Badge
                variant="outline"
                className="border-[var(--success-border)] bg-[var(--success-bg)] text-[var(--success-text)]"
              >
                {t("clipBadge")}
              </Badge>
            </div>
            <CardTitle>{t("clipTitle")}</CardTitle>
            <CardDescription className="leading-relaxed">
              {motionPlusAvailable ? t("clipBodyMotion") : t("clipBodyStill")}
            </CardDescription>
          </CardHeader>
          <CardContent className="mt-auto">
            <Button asChild className="w-full">
              <Link href="/clips">
                {t("clipCta")}
                <ArrowRight className="h-4 w-4 shrink-0" aria-hidden="true" />
              </Link>
            </Button>
          </CardContent>
        </Card>

        {/* --------------------------------------------------------- Image gen
            Unchanged mechanism (agent chat via generate_image, real and
            working today) - copy now ties explicitly into the gallery strip
            and Clip Studio above instead of reading as an isolated tile. */}
        <Card className="flex flex-col">
          <CardHeader>
            <div className="mb-2 flex items-center justify-between gap-2">
              <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
                <ImageIcon className="h-5 w-5 shrink-0" aria-hidden="true" />
              </span>
              <Badge variant="outline">{t("imageBadge")}</Badge>
            </div>
            <CardTitle>{t("imageTitle")}</CardTitle>
            <CardDescription className="leading-relaxed">{t("imageBody")}</CardDescription>
          </CardHeader>
          <CardContent className="mt-auto space-y-4">
            <div>
              <div className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                {t("imageAllowanceHeading")}
              </div>
              <ul className="space-y-1 text-sm">
                {allowanceRows.map((row) => (
                  <li key={row.key} className={row.current ? "font-medium text-foreground" : "text-muted-foreground"}>
                    {t(row.key)}
                    {row.current && (
                      <span className="ms-1.5 text-xs font-normal text-primary">{t("imageAllowanceCurrent")}</span>
                    )}
                  </li>
                ))}
              </ul>
              <p className="mt-1.5 text-xs text-muted-foreground">{t("imageAllowanceNote")}</p>
            </div>
            <div className="flex flex-col gap-2 sm:flex-row">
              <Button asChild className="flex-1">
                <Link href="/playground">
                  {t("imageCtaPlayground")}
                  <ArrowRight className="h-4 w-4 shrink-0" aria-hidden="true" />
                </Link>
              </Button>
              <Button asChild variant="outline" className="flex-1">
                <Link href="/files">{t("imageCtaFiles")}</Link>
              </Button>
            </div>
          </CardContent>
        </Card>
      </div>
    </AppShell>
  );
}
