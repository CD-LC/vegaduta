export const AGENT_AUDIENCES = [
  { key: "personal" },
  { key: "business" },
  { key: "developer" },
  { key: "industry" },
] as const;

export type AgentAudienceKey = (typeof AGENT_AUDIENCES)[number]["key"];

export const AGENT_TEMPLATE_CATEGORIES = [
  "For students",
  "For families & elders",
  "For work & career",
  "Everyday life",
  "Customer & sales",
  "Internal operations",
  "Engineering",
  "E-commerce & retail",
  "Healthcare & clinics",
  "Real estate",
  "Hospitality & restaurants",
  "Legal services",
  "Logistics & delivery",
  // Industry expansion (V226 alignment pass). The DB-backed community gallery
  // (control_plane.community_template, seeded by V163/V173/V219/V223/V226)
  // groups by its OWN free-form industry strings, and until now the two
  // vocabularies had no overlap: the console had no home for Manufacturing,
  // Wholesale, Agriculture, Insurance, Banking, Pharma, Construction, Events,
  // Textiles, Jewellery, Government or Professional services, so an industry
  // that existed in one gallery was invisible in the other. These entries are
  // ADDITIVE ONLY - nothing above is renamed or removed, because
  // CATEGORY_AUDIENCE, CATEGORY_KEYS, the templates page and AgentWorkbench
  // all key off these exact strings.
  "Manufacturing & MSME",
  "Wholesale & distribution",
  "Agriculture & agritech",
  "Banking & insurance",
  "Accounting, tax & CA",
  "Education & coaching",
  "Construction & development",
  "Events, catering & weddings",
  "Automotive",
  "Travel & tourism",
  "Beauty, salon & fitness",
  "Textiles, apparel & jewellery",
  "Nonprofit & public services",
  "Professional services",
  // Developer-audience expansion (2026-10-10): the engineering shelf was all
  // greenfield (diffs, tests, incidents) and said nothing to the people who
  // keep mainframes, SAP estates, core banking batches and twenty-year-old
  // pipelines running - which is most enterprise engineering. ADDITIVE ONLY,
  // same reasoning as the industry block above.
  "Enterprise systems & modernization",
] as const;

export type AgentTemplateCategory = (typeof AGENT_TEMPLATE_CATEGORIES)[number];

// category -> audience, so the templates page can section templates by
// audience without duplicating this mapping per template. Category values
// here are internal English identifiers (also used as AGENT_TEMPLATES'
// `category` field and as CATEGORY_AUDIENCE's own keys) - see
// categoryLabelKey() below for how they map to actual display text.
export const CATEGORY_AUDIENCE: Record<AgentTemplateCategory, AgentAudienceKey> = {
  "For students": "personal",
  "For families & elders": "personal",
  "For work & career": "personal",
  "Everyday life": "personal",
  "Customer & sales": "business",
  "Internal operations": "business",
  Engineering: "developer",
  // Industry vertical starting points - narrower than the generic
  // "Customer & sales"/"Internal operations" business categories above,
  // each one aimed at a specific line of business rather than a function
  // that applies to any business.
  "E-commerce & retail": "industry",
  "Healthcare & clinics": "industry",
  "Real estate": "industry",
  "Hospitality & restaurants": "industry",
  "Legal services": "industry",
  "Logistics & delivery": "industry",
  "Manufacturing & MSME": "industry",
  "Wholesale & distribution": "industry",
  "Agriculture & agritech": "industry",
  "Banking & insurance": "industry",
  "Accounting, tax & CA": "industry",
  "Education & coaching": "industry",
  "Construction & development": "industry",
  "Events, catering & weddings": "industry",
  Automotive: "industry",
  "Travel & tourism": "industry",
  "Beauty, salon & fitness": "industry",
  "Textiles, apparel & jewellery": "industry",
  "Nonprofit & public services": "industry",
  "Professional services": "industry",
  "Enterprise systems & modernization": "developer",
};

