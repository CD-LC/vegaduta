import { getPublicEnv } from "./env";
// Registered operator behind a VegaDūta deployment. Single source of truth for
// the site footer (DemoLanding.tsx), /about and the Organization JSON-LD.
// Deployment-configured: set these at build time for your own hosted
// instance. CIN/DIN are optional (Indian company/director registration
// numbers); when unset they are simply not rendered.
export const LEGAL_ENTITY = getPublicEnv("NEXT_PUBLIC_LEGAL_ENTITY") || "VegaDūta contributors";
export const CIN_NUMBER = getPublicEnv("NEXT_PUBLIC_LEGAL_CIN") || "";
export const DIN_NUMBER = getPublicEnv("NEXT_PUBLIC_LEGAL_DIN") || "";

// Business contact rows, shown on a contact page only when non-empty (an empty
// row is omitted, never guessed). Empty by default; the email follows the
// deployment's NEXT_PUBLIC_CONTACT_EMAIL.
export const BUSINESS_ADDRESS: string = "";
export const BUSINESS_PHONE: string = "";
export const BUSINESS_EMAIL: string = getPublicEnv("NEXT_PUBLIC_CONTACT_EMAIL") || "";
