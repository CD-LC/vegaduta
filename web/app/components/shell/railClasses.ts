"use client";

// The collapsed / hub-mode class strings the rail, its rows and its footer share. Lifted out of AppShell.tsx unchanged.




/** Class strings for the rail in its collapsible (icon lane) and hub states. */
export function railClasses(collapsibleRail: boolean, hubMode: boolean) {
  // When the rail is collapsible, text labels/badges/lock glyphs fade out in
  // the collapsed 64px state and fade back in on hover; the icons stay put.
  // On mobile the rail is the usual full-width drawer, so this only applies
  // at lg+. `w-0`/`overflow-hidden` (not just opacity) is load-bearing: an
  // invisible-but-full-width label still occupies flex space next to its
  // icon, which is what was pushing icons off-center / clipping them against
  // the rail's `overflow-x-hidden` before this was width-aware (found
  // 2026-09-18 from a real screenshot: the search icon sat outside its own
  // circle and several nav icons were half-cut in the collapsed rail).
  // Hub-mode variant: the rail never widens on hover (the flyout carries
  // labels), so the hover-restore classes must NOT be present - hovering the
  // 64px lane would otherwise flash full-width labels clipped by
  // overflow-x-hidden.
  const railLabelCls = collapsibleRail
    ? hubMode
      ? "lg:w-0 lg:overflow-hidden lg:opacity-0"
      : "lg:w-0 lg:overflow-hidden lg:opacity-0 lg:transition-[opacity,width] lg:duration-200 lg:group-hover/rail:w-auto lg:group-hover/rail:opacity-100"
    : "";

  // Companion to railLabelCls for the ROW itself (nav items, the Jump-to
  // search button): once its label has collapsed to zero width, center the
  // one remaining visible child (the icon) in the 64px lane instead of
  // leaving it pinned at the row's normal left padding. Same technique as
  // the brand mark and footer avatar rows below.
  const railRowCollapse = collapsibleRail
    ? hubMode
      ? "lg:justify-center lg:gap-0 lg:px-0"
      : "lg:justify-center lg:gap-0 lg:px-0 lg:group-hover/rail:justify-start lg:group-hover/rail:gap-3 lg:group-hover/rail:px-3"
    : "";

  // Collapsed-rail footer: at 64px the avatar + sign-out button can't sit side
  // by side, so collapse to a single centered avatar and reveal the name +
  // sign-out only on hover. `w-0`/`gap-0` (not just opacity) keep the hidden
  // name from offsetting the centered avatar; the sign-out is fully hidden
  // until the rail expands.
  const footerRowCollapse = collapsibleRail ? "lg:justify-center" : "";
  const footerIdentityCollapse = collapsibleRail
    ? hubMode
      ? "lg:flex-none lg:justify-center lg:gap-0 lg:px-0"
      : "lg:flex-none lg:justify-center lg:gap-0 lg:px-0 lg:group-hover/rail:flex-1 lg:group-hover/rail:justify-start lg:group-hover/rail:gap-2.5 lg:group-hover/rail:px-2"
    : "";
  const footerNameCollapse = collapsibleRail
    ? hubMode
      ? "lg:w-0 lg:overflow-hidden"
      : "lg:w-0 lg:overflow-hidden lg:group-hover/rail:w-auto"
    : "";
  const footerSignoutCollapse = collapsibleRail ? (hubMode ? "lg:hidden" : "lg:hidden lg:group-hover/rail:flex") : "";
  return { railLabelCls, railRowCollapse, footerRowCollapse, footerIdentityCollapse, footerNameCollapse, footerSignoutCollapse };
}
