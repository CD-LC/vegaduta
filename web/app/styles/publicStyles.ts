// Public-page fixes (public.css and its continuation sheets),
// split by area into plain CSS files. They are imported here
// in CASCADE ORDER: a later file wins ties, so keep the order and add new rules
// to the file that owns the area. They are imported from TS rather than chained
// with CSS @import on purpose: an @import makes Tailwind's PostCSS plugin compile
// the sheet (adding colour-mix fallbacks and, in production, its Lightning CSS
// pass), and these plain unlayered sheets have never gone through that.
import "../public.css"; // 1-3: page root, sticky glass top bar, type roles
import "./public/surfaces.css"; // 4-7: glass cards, pills, tables, phone layout guards
import "./public/hire.css"; // 8: /hire Hiring Papers re-skin
import "./public/signup-complete.css"; // 9: /signup/complete
import "./public/a11y.css"; // A11y pass 2026-09-24
import "./public/standard.css"; // 10: one standard for the public surface
