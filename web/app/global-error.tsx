"use client";

import { Home, RotateCcw } from "lucide-react";
import BrandName from "./components/BrandName";
import { useTranslations } from "./lib/i18n";

// Root error boundary (2026-09-24): replaces the whole root layout when it
// throws, so it must render its own <html>/<body> and cannot rely on the
// app's regular stylesheets. Kept self-contained and plain on purpose - a
// small inline <style> block (below) instead of the usual globals.css/
// vd-*.css imports, so this still has real light/dark theming without
// depending on anything that could itself be part of what broke.
//
// Icon sizing here uses lucide's `size` prop (an SVG attribute), not a
// Tailwind class: this boundary renders with NO Tailwind stylesheet, so
// `size-4` would not resolve and the glyphs would fall back to 24px.
//
// Fonts: this used to name 'Plus Jakarta Sans'/'DM Mono' as literal
// font-family values, implying they were loaded here - they are not (this
// boundary can't reach next/font's per-route wiring, which is what actually
// makes those names resolve elsewhere in the app), so every visitor's
// browser silently fell through to the system-font fallback anyway. Naming
// only the fallback stack is honest about what actually renders.
export default function GlobalError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
    const tHC = useTranslations("hcGlobalerror");
  return (
    <html lang="en" dir="auto">
      <body className="ge-body">
        <style>{`
          .ge-body {
            margin: 0;
            min-height: 100vh;
            display: grid;
            place-items: center;
            background: #f4f4f6;
            color: #131318;
            font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
            padding: 1.5rem;
            color-scheme: light dark;
          }
          .ge-mark { display: block; height: 32px; width: auto; margin: 0 auto 12px; }
          .ge-card {
            width: min(100%, 34rem);
            padding: 2rem;
            border-radius: 24px;
            background: rgba(255, 255, 255, 0.7);
            border: 1px solid rgba(255, 255, 255, 0.9);
            box-shadow: 0 0 0 1px rgba(19, 19, 24, 0.06), 0 34px 90px -34px rgba(19, 19, 24, 0.3);
            text-align: center;
          }
          .ge-code {
            font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
            font-size: 64px;
            line-height: 1;
            letter-spacing: -0.04em;
            color: #6d5bff;
          }
          .ge-heading { margin-top: 12px; font-size: 28px; letter-spacing: -0.035em; font-weight: 600; }
          .ge-sub { margin-top: 12px; color: #4a4a54; line-height: 1.6; }
          .ge-actions { display: flex; gap: 10px; justify-content: center; margin-top: 24px; flex-wrap: wrap; }
          .ge-btn {
            min-height: 44px;
            padding: 10px 20px;
            border-radius: 999px;
            font-weight: 600;
            font-size: 15px;
            cursor: pointer;
            display: inline-flex;
            align-items: center;
            gap: 8px;
            text-decoration: none;
          }
          .ge-btn-primary { border: 0; background: #6d5bff; color: #fff; }
          .ge-btn-secondary { border: 1px solid rgba(19, 19, 24, 0.1); background: rgba(255, 255, 255, 0.6); color: #131318; }

          @media (prefers-color-scheme: dark) {
            .ge-body { background: #0e0e12; color: #f4f4f6; }
            .ge-card {
              background: rgba(255, 255, 255, 0.06);
              border-color: rgba(255, 255, 255, 0.12);
              box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.06), 0 34px 90px -34px rgba(0, 0, 0, 0.6);
            }
            .ge-code { color: #a99dff; }
            .ge-sub { color: #b7b7c2; }
            .ge-btn-primary { background: #8a7bff; }
            .ge-btn-secondary { border-color: rgba(255, 255, 255, 0.16); background: rgba(255, 255, 255, 0.08); color: #f4f4f6; }
          }
        `}</style>
        <div className="ge-card">
          {/* eslint-disable-next-line @next/next/no-img-element -- root boundary can't reach next/image's route wiring either */}
          <img className="ge-mark" src="/logo-mark-sm.webp" alt="" />
          <div className="ge-code">500</div>
          <h1 className="ge-heading">
            <BrandName /> {tHC("hcGlobalerror.s3")}
          </h1>
          <p className="ge-sub">{tHC("hcGlobalerror.s2")} {error?.digest ? `Ref ${error.digest}.` : null}</p>
          <div className="ge-actions">
            <button type="button" onClick={reset} className="ge-btn ge-btn-primary">
              <RotateCcw size={16} aria-hidden="true" />
              {tHC("hcGlobalerror.s1")}
            </button>
            {/* eslint-disable-next-line @next/next/no-html-link-for-pages -- root boundary must stay router-free */}
            <a href="/" className="ge-btn ge-btn-secondary">
              <Home size={16} aria-hidden="true" />
              Go home
            </a>
          </div>
        </div>
      </body>
    </html>
  );
}
