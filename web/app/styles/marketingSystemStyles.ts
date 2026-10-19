// Marketing system "Aurora" (marketing-system.css and its continuation sheets),
// split by area into plain CSS files. They are imported here
// in CASCADE ORDER: a later file wins ties, so keep the order and add new rules
// to the file that owns the area. They are imported from TS rather than chained
// with CSS @import on purpose: an @import makes Tailwind's PostCSS plugin compile
// the sheet (adding colour-mix fallbacks and, in production, its Lightning CSS
// pass), and these plain unlayered sheets have never gone through that.
import "../marketing-system.css"; // 1-2: tokens, atmosphere backdrop
import "./marketing/surfaces.css"; // 3-6: band rhythm, surfaces, buttons, chips/pills/status
import "./marketing/nav-typography.css"; // 7-10: marquee, scroll reveal, nav, typography
import "./marketing/flow-states.css"; // 11-15: flow motif, button/card microstates, links, skeletons
import "./marketing/forms-contrast-print.css"; // 16-18: form controls, high contrast, print
import "./marketing/ui-scope.css"; // 19-19d: .mkt-ui type scope, view switch, rail items, pane swap
import "./marketing/ui-roles.css"; // 19e-19f: semantic role colour, role-carrying parts
import "./marketing/ui-motion-metrics.css"; // 19g-19h: live motion, metrics panel, environment overrides
import "./marketing/term.css"; // 20: .mkt-term hero build console
