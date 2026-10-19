import type { WorkflowTemplate } from "./types";
import { eCommerceAndRetailTemplates } from "./eCommerceAndRetail";
import { healthcareAndClinicsTemplates } from "./healthcareAndClinics";
import { realEstateTemplates } from "./realEstate";
import { hospitalityAndRestaurantsTemplates } from "./hospitalityAndRestaurants";
import { legalServicesTemplates } from "./legalServices";
import { logisticsAndDeliveryTemplates } from "./logisticsAndDelivery";
import { contentAndMediaTemplates } from "./contentAndMedia";
import { orchestrationAndIntegrationsTemplates } from "./orchestrationAndIntegrations";
import { smallBusinessTemplates } from "./smallBusiness";
import { salesAndMarketingTemplates } from "./salesAndMarketing";
import { customerSupportTemplates } from "./customerSupport";
import { peopleAndFinanceTemplates } from "./peopleAndFinance";
import { dataAndReportingTemplates } from "./dataAndReporting";
import { engineeringAndModernizationTemplates } from "./engineeringAndModernization";

/** Category -> its templates. One data file per category; add a category here and in types.ts. */
export const WORKFLOW_TEMPLATES_BY_CATEGORY = {
  "E-commerce & retail": eCommerceAndRetailTemplates,
  "Healthcare & clinics": healthcareAndClinicsTemplates,
  "Real estate": realEstateTemplates,
  "Hospitality & restaurants": hospitalityAndRestaurantsTemplates,
  "Legal services": legalServicesTemplates,
  "Logistics & delivery": logisticsAndDeliveryTemplates,
  "Content & media": contentAndMediaTemplates,
  "Orchestration & integrations": orchestrationAndIntegrationsTemplates,
  "Small business": smallBusinessTemplates,
  "Sales & marketing": salesAndMarketingTemplates,
  "Customer support": customerSupportTemplates,
  "People & finance": peopleAndFinanceTemplates,
  "Data & reporting": dataAndReportingTemplates,
  "Engineering & modernization": engineeringAndModernizationTemplates,
} as const;

/** Every template, in category order (WORKFLOW_TEMPLATE_CATEGORIES). */
export const WORKFLOW_TEMPLATES: WorkflowTemplate[] = [
  ...eCommerceAndRetailTemplates,
  ...healthcareAndClinicsTemplates,
  ...realEstateTemplates,
  ...hospitalityAndRestaurantsTemplates,
  ...legalServicesTemplates,
  ...logisticsAndDeliveryTemplates,
  ...contentAndMediaTemplates,
  ...orchestrationAndIntegrationsTemplates,
  ...smallBusinessTemplates,
  ...salesAndMarketingTemplates,
  ...customerSupportTemplates,
  ...peopleAndFinanceTemplates,
  ...dataAndReportingTemplates,
  ...engineeringAndModernizationTemplates,
];
