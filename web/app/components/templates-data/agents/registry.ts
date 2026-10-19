import type { AgentTemplateEntry } from "./types";
import { forStudentsTemplates } from "./forStudents";
import { forFamiliesAndEldersTemplates } from "./forFamiliesAndElders";
import { forWorkAndCareerTemplates } from "./forWorkAndCareer";
import { everydayLifeTemplates } from "./everydayLife";
import { customerAndSalesTemplates } from "./customerAndSales";
import { internalOperationsTemplates } from "./internalOperations";
import { engineeringTemplates } from "./engineering";
import { eCommerceAndRetailTemplates } from "./eCommerceAndRetail";
import { healthcareAndClinicsTemplates } from "./healthcareAndClinics";
import { realEstateTemplates } from "./realEstate";
import { hospitalityAndRestaurantsTemplates } from "./hospitalityAndRestaurants";
import { legalServicesTemplates } from "./legalServices";
import { logisticsAndDeliveryTemplates } from "./logisticsAndDelivery";
import { manufacturingAndMsmeTemplates } from "./manufacturingAndMsme";
import { wholesaleAndDistributionTemplates } from "./wholesaleAndDistribution";
import { agricultureAndAgritechTemplates } from "./agricultureAndAgritech";
import { bankingAndInsuranceTemplates } from "./bankingAndInsurance";
import { accountingTaxAndCaTemplates } from "./accountingTaxAndCa";
import { educationAndCoachingTemplates } from "./educationAndCoaching";
import { constructionAndDevelopmentTemplates } from "./constructionAndDevelopment";
import { eventsCateringAndWeddingsTemplates } from "./eventsCateringAndWeddings";
import { automotiveTemplates } from "./automotive";
import { travelAndTourismTemplates } from "./travelAndTourism";
import { beautySalonAndFitnessTemplates } from "./beautySalonAndFitness";
import { textilesApparelAndJewelleryTemplates } from "./textilesApparelAndJewellery";
import { nonprofitAndPublicServicesTemplates } from "./nonprofitAndPublicServices";
import { professionalServicesTemplates } from "./professionalServices";
import { enterpriseSystemsAndModernizationTemplates } from "./enterpriseSystemsAndModernization";

/** Category -> its templates. One data file per category; add a category here and in categories.ts. */
export const AGENT_TEMPLATES_BY_CATEGORY = {
  "For students": forStudentsTemplates,
  "For families & elders": forFamiliesAndEldersTemplates,
  "For work & career": forWorkAndCareerTemplates,
  "Everyday life": everydayLifeTemplates,
  "Customer & sales": customerAndSalesTemplates,
  "Internal operations": internalOperationsTemplates,
  "Engineering": engineeringTemplates,
  "E-commerce & retail": eCommerceAndRetailTemplates,
  "Healthcare & clinics": healthcareAndClinicsTemplates,
  "Real estate": realEstateTemplates,
  "Hospitality & restaurants": hospitalityAndRestaurantsTemplates,
  "Legal services": legalServicesTemplates,
  "Logistics & delivery": logisticsAndDeliveryTemplates,
  "Manufacturing & MSME": manufacturingAndMsmeTemplates,
  "Wholesale & distribution": wholesaleAndDistributionTemplates,
  "Agriculture & agritech": agricultureAndAgritechTemplates,
  "Banking & insurance": bankingAndInsuranceTemplates,
  "Accounting, tax & CA": accountingTaxAndCaTemplates,
  "Education & coaching": educationAndCoachingTemplates,
  "Construction & development": constructionAndDevelopmentTemplates,
  "Events, catering & weddings": eventsCateringAndWeddingsTemplates,
  "Automotive": automotiveTemplates,
  "Travel & tourism": travelAndTourismTemplates,
  "Beauty, salon & fitness": beautySalonAndFitnessTemplates,
  "Textiles, apparel & jewellery": textilesApparelAndJewelleryTemplates,
  "Nonprofit & public services": nonprofitAndPublicServicesTemplates,
  "Professional services": professionalServicesTemplates,
  "Enterprise systems & modernization": enterpriseSystemsAndModernizationTemplates,
} as const;

/** Every template, in category order (AGENT_TEMPLATE_CATEGORIES). */
export const AGENT_TEMPLATES: readonly AgentTemplateEntry[] = [
  ...forStudentsTemplates,
  ...forFamiliesAndEldersTemplates,
  ...forWorkAndCareerTemplates,
  ...everydayLifeTemplates,
  ...customerAndSalesTemplates,
  ...internalOperationsTemplates,
  ...engineeringTemplates,
  ...eCommerceAndRetailTemplates,
  ...healthcareAndClinicsTemplates,
  ...realEstateTemplates,
  ...hospitalityAndRestaurantsTemplates,
  ...legalServicesTemplates,
  ...logisticsAndDeliveryTemplates,
  ...manufacturingAndMsmeTemplates,
  ...wholesaleAndDistributionTemplates,
  ...agricultureAndAgritechTemplates,
  ...bankingAndInsuranceTemplates,
  ...accountingTaxAndCaTemplates,
  ...educationAndCoachingTemplates,
  ...constructionAndDevelopmentTemplates,
  ...eventsCateringAndWeddingsTemplates,
  ...automotiveTemplates,
  ...travelAndTourismTemplates,
  ...beautySalonAndFitnessTemplates,
  ...textilesApparelAndJewelleryTemplates,
  ...nonprofitAndPublicServicesTemplates,
  ...professionalServicesTemplates,
  ...enterpriseSystemsAndModernizationTemplates,
];
