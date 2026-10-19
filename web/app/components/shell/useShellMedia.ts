"use client";

// Desktop breakpoint + hover-capable pointer media queries. Lifted out of AppShell.tsx unchanged.

import { useCallback, useSyncExternalStore } from "react";


export function useShellMedia() {
  const subscribeDesktop = useCallback((cb: () => void) => {
    const mq = window.matchMedia("(min-width: 64rem)");
    mq.addEventListener("change", cb);
    return () => mq.removeEventListener("change", cb);
  }, []);
  const isDesktop = useSyncExternalStore(
    subscribeDesktop,
    () => window.matchMedia("(min-width: 64rem)").matches,
    () => false
  );
  // Hub mode additionally needs a pointer that can actually hover (a11y/
  // responsive audit follow-up): it was gated on width alone, so a touch
  // tablet at a desktop-sized viewport got the hover/focus-only flyout with
  // no way to open it via touch at all. Kept separate from `isDesktop`
  // above, which still just tracks the `lg:` CSS breakpoint used for the
  // rail's own in-flow-vs-drawer layout.
  const subscribeHoverCapable = useCallback((cb: () => void) => {
    const mq = window.matchMedia("(hover: hover) and (pointer: fine)");
    mq.addEventListener("change", cb);
    return () => mq.removeEventListener("change", cb);
  }, []);
  const isHoverCapable = useSyncExternalStore(
    subscribeHoverCapable,
    () => window.matchMedia("(hover: hover) and (pointer: fine)").matches,
    () => false
  );
  return { isDesktop, isHoverCapable };
}
