// The curated built-in gallery: 8+ Studio templates (complete single-file
// apps) and 3 server-template entries for the Create flow. Pure data, no
// React, so it is unit-testable and importable anywhere.

import { bookingHtml, landingHtml, menuQrHtml, portfolioHtml } from "./templatesBusiness";
import { dashboardHtml, expenseTrackerHtml, linkInBioHtml, quizHtml } from "./templatesPersonal";

export type GalleryCategory = "Business" | "Personal" | "Data" | "Learning" | "Finance" | "Web" | "Mobile" | "Backend";

interface GalleryBase {
  id: string;
  name: string;
  emoji: string;
  blurb: string;
  category: GalleryCategory;
  tags: string[];
}

/** A one-file Studio app you can open instantly, no AI needed. */
export interface StudioGalleryItem extends GalleryBase {
  kind: "studio";
  html: string;
}

/** Metadata for a server template (app-builder/templates/<templateId>). */
export interface ServerGalleryItem extends GalleryBase {
  kind: "server";
  templateId: string;
  stack: string;
}

export type GalleryItem = StudioGalleryItem | ServerGalleryItem;

export const STUDIO_GALLERY: StudioGalleryItem[] = [
  { kind: "studio", id: "g-landing", name: "Landing page", emoji: "🚀", blurb: "Hero, features, pricing and a contact form", category: "Business", tags: ["landing", "website", "marketing", "startup", "pricing", "leads"], html: landingHtml },
  { kind: "studio", id: "g-portfolio", name: "Portfolio", emoji: "🎨", blurb: "Showcase your work with category filters", category: "Personal", tags: ["portfolio", "designer", "freelance", "projects", "resume", "showcase"], html: portfolioHtml },
  { kind: "studio", id: "g-menu-qr", name: "Restaurant menu + QR", emoji: "🍽️", blurb: "Menu, WhatsApp ordering and a printable table QR", category: "Business", tags: ["restaurant", "menu", "qr", "cafe", "food", "whatsapp", "order"], html: menuQrHtml },
  { kind: "studio", id: "g-booking", name: "Booking form", emoji: "📅", blurb: "Pick a service, day and time slot", category: "Business", tags: ["booking", "appointment", "salon", "clinic", "schedule", "slots"], html: bookingHtml },
  { kind: "studio", id: "g-link-in-bio", name: "Link in bio", emoji: "🔗", blurb: "One page for all your links, with tap counts", category: "Personal", tags: ["links", "bio", "instagram", "creator", "social", "profile"], html: linkInBioHtml },
  { kind: "studio", id: "g-dashboard", name: "Sales dashboard", emoji: "📊", blurb: "KPIs and a revenue chart you can update", category: "Data", tags: ["dashboard", "chart", "kpi", "sales", "analytics", "revenue", "report"], html: dashboardHtml },
  { kind: "studio", id: "g-quiz", name: "Quiz", emoji: "🧩", blurb: "Multiple choice with explanations and best score", category: "Learning", tags: ["quiz", "trivia", "test", "exam", "questions", "learn", "score"], html: quizHtml },
  { kind: "studio", id: "g-expense-tracker", name: "Expense tracker", emoji: "💰", blurb: "Monthly budget, categories and recent spending", category: "Finance", tags: ["expense", "budget", "money", "spend", "finance", "kharcha", "tracker"], html: expenseTrackerHtml },
];

export const SERVER_GALLERY: ServerGalleryItem[] = [
  { kind: "server", id: "s-capacitor-app", templateId: "capacitor-app", name: "Mobile app (Android + iOS)", emoji: "📱", blurb: "One Vite codebase, shipped as website, APK and iOS app", category: "Mobile", stack: "Vite + Capacitor", tags: ["mobile", "android", "ios", "apk", "capacitor", "app"] },
  { kind: "server", id: "s-astro-site", templateId: "astro-site", name: "Astro website", emoji: "🌐", blurb: "Fast static site for blogs, docs and marketing pages", category: "Web", stack: "Astro", tags: ["website", "blog", "docs", "static", "seo", "astro"] },
  { kind: "server", id: "s-python-fastapi", templateId: "python-fastapi", name: "Python API", emoji: "🐍", blurb: "Minimal FastAPI service with a JSON route", category: "Backend", stack: "Python + FastAPI", tags: ["api", "backend", "python", "fastapi", "json", "server"] },
];

export const GALLERY: GalleryItem[] = [...STUDIO_GALLERY, ...SERVER_GALLERY];
