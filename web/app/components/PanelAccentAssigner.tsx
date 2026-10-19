"use client";

import { useEffect } from "react";

// "Apply it app-wide, every page/panel, not just the dashboard" - rather
// than hand-editing a `p-cyan`/`p-teal`/... className onto every .panel and
// .glass-card across 30+ page files (Agents, Playground, Knowledge,
// Providers, Tools, Workflows, every Admin sub-page...), this runs once per
// page (mounted in AppShell, which every authenticated route renders fresh -
// see AppShell.js's own comment on why a plain per-mount effect is safe
// here) and tags every panel it finds with the next accent family in the
// curated cyan/teal/violet/amber/blue/magenta rotation, plus a
// data-tick instrument code ("SYS-01", "SYS-02"...) that .panel::before
// renders in the corner. A MutationObserver keeps tagging newly-added panels
// too, since a lot of pages render their .panel/.glass-card list only after
// an async fetch resolves.
const ACCENTS = ["p-cyan", "p-teal", "p-violet", "p-amber", "p-blue", "p-magenta"];

export default function PanelAccentAssigner(): null {
  useEffect(() => {
    const root = document.querySelector(".app-content");
    if (!root) return undefined;

    let index = 0;
    function tag(el: Element) {
      const target = el as HTMLElement;
      if (target.dataset.accentTagged) return;
      target.dataset.accentTagged = "1";
      target.classList.add(ACCENTS[index % ACCENTS.length]);
      target.setAttribute("data-tick", `SYS-${String(index + 1).padStart(2, "0")}`);
      index += 1;
    }

    function scan() {
      root.querySelectorAll(".panel, .glass-card").forEach(tag);
    }

    scan();
    const observer = new MutationObserver(scan);
    observer.observe(root, { childList: true, subtree: true });
    return () => observer.disconnect();
  }, []);

  return null;
}
