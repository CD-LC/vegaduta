// Focus ring for the chart period-switch pills (7d / 30d / 90d). The Button default is a 3px ring at 50% opacity,
// which disappears against the pill's own border and the card behind it (found in local QA). A solid ring with an
// offset reads on both the filled (selected) and outlined (unselected) pill, in light and dark.
export const PERIOD_PILL_FOCUS_CLASS =
  "focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background";
