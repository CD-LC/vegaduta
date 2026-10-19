// Console paper & ink + glass fixes (console.css and its continuation sheets),
// split by area into plain CSS files. They are imported here
// in CASCADE ORDER: a later file wins ties, so keep the order and add new rules
// to the file that owns the area. They are imported from TS rather than chained
// with CSS @import on purpose: an @import makes Tailwind's PostCSS plugin compile
// the sheet (adding colour-mix fallbacks and, in production, its Lightning CSS
// pass), and these plain unlayered sheets have never gone through that.
import "../console.css"; // 1-3: legacy token aliases, type, glass panels
import "./console/controls-layout.css"; // 4-8: controls, pinned chrome, page header, tables, phone fixes, a11y
import "./console/standard.css"; // 9-10: one console standard, workbench summary, /admin overview
