// Pure helpers for the gallery: filtering, search ranking, and the
// export/import JSON bundle used to share a Studio project as a template.
//
// Why a bundle and not the community marketplace: control_plane.community_template
// has a CHECK constraint limiting kind to AGENT/WORKFLOW (V156). A Studio-app
// kind would need a new migration, which this change may not add. So "publish
// as template" is a portable JSON file anyone can import into their gallery.

import type { GalleryCategory, GalleryItem } from "./galleryData";
import { stripConnections } from "../studio/connect";

export interface GalleryFilter {
  query?: string;
  category?: GalleryCategory | "All";
  kind?: "all" | "studio" | "server";
}

export function categoriesOf(items: GalleryItem[]): GalleryCategory[] {
  const seen: GalleryCategory[] = [];
  for (const i of items) if (!seen.includes(i.category)) seen.push(i.category);
  return seen;
}

const tokens = (s: string): string[] => s.toLowerCase().split(/[^a-z0-9]+/).filter(Boolean);

/** Relevance of one item to a query. 0 means "does not match". Every query
 * word must hit something; name hits weigh most, then tags, then blurb. */
export function scoreItem(item: GalleryItem, query: string): number {
  const words = tokens(query);
  if (!words.length) return 1;
  const name = tokens(item.name);
  const tags = item.tags.flatMap(tokens);
  const blurb = tokens(item.blurb);
  const cat = tokens(item.category);
  let total = 0;
  for (const w of words) {
    let s = 0;
    if (name.includes(w)) s = 10;
    else if (name.some((n) => n.startsWith(w))) s = 7;
    else if (tags.includes(w)) s = 6;
    else if (tags.some((t) => t.startsWith(w))) s = 4;
    else if (cat.includes(w)) s = 3;
    else if (blurb.some((b) => b.startsWith(w))) s = 2;
    if (s === 0) return 0;
    total += s;
  }
  return total;
}

/** Filter by category/kind, then rank by query (stable: ties keep catalogue order). */
export function filterGallery(items: GalleryItem[], f: GalleryFilter): GalleryItem[] {
  const q = f.query || "";
  return items
    .map((item, order) => ({ item, order, score: scoreItem(item, q) }))
    .filter(({ item, score }) => {
      if (score === 0) return false;
      if (f.category && f.category !== "All" && item.category !== f.category) return false;
      if (f.kind && f.kind !== "all" && item.kind !== f.kind) return false;
      return true;
    })
    .sort((a, b) => b.score - a.score || a.order - b.order)
    .map((x) => x.item);
}

// ---- template bundle (export / import) ----

export const BUNDLE_FORMAT = "vegaduta.studio-template";
export const BUNDLE_VERSION = 1;
export const MAX_BUNDLE_BYTES = 512 * 1024;

export interface TemplateBundle {
  format: typeof BUNDLE_FORMAT;
  version: number;
  name: string;
  emoji: string;
  blurb: string;
  category: string;
  tags: string[];
  html: string;
  exportedAt: string;
}

export interface BundleSource {
  name: string;
  emoji: string;
  html: string;
  blurb?: string;
  category?: string;
  tags?: string[];
}

const clean = (s: unknown, max: number): string =>
  (typeof s === "string" ? s : "").replace(/[\u0000-\u001f\u007f]/g, " ").trim().slice(0, max);

/** Only the app's source is exported - never its saved data (appData), share
 * links, connections or auth config, which can hold private information. */
export function exportBundle(src: BundleSource, now: Date = new Date()): string {
  const bundle: TemplateBundle = {
    format: BUNDLE_FORMAT,
    version: BUNDLE_VERSION,
    name: clean(src.name, 80) || "Untitled app",
    emoji: clean(src.emoji, 8) || "✨",
    blurb: clean(src.blurb, 140),
    category: clean(src.category, 32) || "Personal",
    tags: (src.tags || []).map((t) => clean(t, 24).toLowerCase()).filter(Boolean).slice(0, 10),
    html: stripConnections(src.html),
    exportedAt: now.toISOString(),
  };
  return JSON.stringify(bundle, null, 2);
}

export function bundleFilename(name: string): string {
  const slug = tokens(name).join("-").slice(0, 40) || "app";
  return `${slug}.vegaduta-template.json`;
}

export type BundleResult = { ok: true; bundle: TemplateBundle } | { ok: false; error: string };

const FORBIDDEN = /<script[^>]+\bsrc\s*=|<(?:iframe|object|embed)\b|<link[^>]+rel\s*=\s*["']?(?:stylesheet|preload|prefetch|modulepreload)/i;
const NETWORK = /\bfetch\s*\(|XMLHttpRequest|WebSocket|EventSource|sendBeacon|importScripts|@import|\bimport\s*\(|<(?:img|source|video|audio|input)\b[^>]+\bsrc\s*=\s*["']?(?:https?:)?\/\/|<form\b[^>]+\baction\s*=\s*["']?(?:https?:)?\/\/|url\(\s*["']?(?:https?:)?\/\//i;
const LOCAL = /(?:localhost|127\.0\.0\.1|\[::1\])/i;

/** Validate untrusted text as a template bundle. Imported apps run in the
 * sandboxed preview, but we still refuse ones that pull remote code. */
export function parseBundle(text: string): BundleResult {
  if (typeof text !== "string" || !text.trim()) return { ok: false, error: "The file is empty." };
  if (text.length > MAX_BUNDLE_BYTES) return { ok: false, error: "The file is too large (limit 512 KB)." };
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch {
    return { ok: false, error: "This is not valid JSON." };
  }
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return { ok: false, error: "Not a template bundle." };
  const o = raw as Record<string, unknown>;
  if (o.format !== BUNDLE_FORMAT) return { ok: false, error: "Not a VegaDuta template bundle." };
  if (typeof o.version !== "number" || o.version > BUNDLE_VERSION) return { ok: false, error: "This bundle was made by a newer version." };
  const name = clean(o.name, 80);
  if (!name) return { ok: false, error: "The bundle has no name." };
  // Never import someone else's connection ids / app wiring.
  const html = typeof o.html === "string" ? stripConnections(o.html) : "";
  if (!/<html[\s>]|<!doctype html/i.test(html) || !/<\/html>/i.test(html)) return { ok: false, error: "The bundle does not contain a complete HTML app." };
  if (FORBIDDEN.test(html)) return { ok: false, error: "The app loads external scripts, frames or stylesheets, which templates may not do." };
  if (NETWORK.test(html)) return { ok: false, error: "The app makes network requests or loads remote files, which templates may not do." };
  if (LOCAL.test(html)) return { ok: false, error: "The app references localhost, which templates may not do." };
  return {
    ok: true,
    bundle: {
      format: BUNDLE_FORMAT,
      version: o.version,
      name,
      emoji: clean(o.emoji, 8) || "✨",
      blurb: clean(o.blurb, 140),
      category: clean(o.category, 32) || "Personal",
      tags: Array.isArray(o.tags) ? o.tags.map((t) => clean(t, 24).toLowerCase()).filter(Boolean).slice(0, 10) : [],
      html,
      exportedAt: clean(o.exportedAt, 40),
    },
  };
}

/** Every http(s) host an app's HTML mentions (lower-cased, de-duplicated). */
export function externalHosts(html: string): string[] {
  const out = new Set<string>();
  for (const m of html.matchAll(/https?:\/\/([a-z0-9.-]+)/gi)) out.add(m[1].toLowerCase());
  return [...out];
}

// ---- "Make it yours" remix chips ----

export const REMIX_TARGETS: { id: string; label: string; emoji: string }[] = [
  { id: "bakery", label: "Bakery", emoji: "🥐" },
  { id: "clinic", label: "Clinic", emoji: "🩺" },
  { id: "salon", label: "Salon", emoji: "💇" },
  { id: "tuition", label: "Tuition centre", emoji: "📚" },
];

/** The adapt request a remix chip pre-fills into the Studio request box. It
 * ends mid-sentence on purpose: the person finishes it with their own name. */
export function remixPrompt(appName: string, targetLabel: string): string {
  const t = targetLabel.toLowerCase();
  return `Make "${appName}" mine: adapt it for my ${t}. Change the title, headings and labels to fit, and replace every pre-filled sample item with realistic ones for a ${t} - none of the original samples may remain. My ${t}'s name is: `;
}
