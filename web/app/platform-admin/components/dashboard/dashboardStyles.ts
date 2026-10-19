// Platform-admin Mission Control styles (dashboard.css and its continuation sheets),
// split by area into plain CSS files. They are imported here
// in CASCADE ORDER: a later file wins ties, so keep the order and add new rules
// to the file that owns the area. They are imported from TS rather than chained
// with CSS @import on purpose: an @import makes Tailwind's PostCSS plugin compile
// the sheet (adding colour-mix fallbacks and, in production, its Lightning CSS
// pass), and these plain unlayered sheets have never gone through that.
import "./dashboard.css"; // hero screen, scanline, HUD cards
import "./dashboard-telemetry.css"; // system status + connected platforms panels
import "./dashboard-bento.css"; // bento module grid, drifting grid, reactor rim
import "./dashboard-module-icons.css"; // per-module icon animations
import "./dashboard-bento-layout.css"; // bento card layout, breakpoints, reduced motion
