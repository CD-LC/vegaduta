import type { LucideIcon } from "lucide-react";
import {
  UtensilsCrossed, ShoppingCart, Scissors, Building2, HeartPulse,
  GraduationCap, Dumbbell, Gem, Car, PartyPopper, Briefcase, HandHeart,
} from "lucide-react";

// Prepopulated image/video prompts per industry - the "give them a
// prepopulated set of prompts, set of industry what they belong to" ask.
// Deliberately a small, curated top-level list (12) rather than exposing all
// 89 workflow-recipes categories (web/app/help/workflow-recipes/recipes-index.ts)
// here - those are workflow-BUILD recipes (agent/MCP/tool specs), this is a
// much lighter "what would I even type into the image/video box" starter
// set, reused by both the landing page's Magic Studio and the authenticated
// creative studio (see IndustryPicker.tsx / MagicStudio.tsx).
//
// `recipesIndustryId` cross-links to an id in recipes-index.ts's
// INDUSTRY_INDEX where a reasonably close match exists, so "see workflow
// examples for your industry" can deep-link into /help/workflow-recipes/[industry]
// instead of duplicating that content here. Left undefined where no close
// match exists rather than forcing a bad one.

export type CreativePrompt = { label: string; prompt: string };

export type IndustryCreativePack = {
  id: string;
  name: string;
  icon: LucideIcon;
  recipesIndustryId?: string;
  imagePrompts: CreativePrompt[];
  videoPrompts: CreativePrompt[];
};

export const INDUSTRY_CREATIVE_PACKS: IndustryCreativePack[] = [
  {
    id: "restaurant",
    name: "Restaurant & cloud kitchen",
    icon: UtensilsCrossed,
    recipesIndustryId: "cloud-kitchen",
    imagePrompts: [
      { label: "Festive sale poster", prompt: "A festive poster for our restaurant's Diwali weekend offer, warm gold and red tones, appetizing food photography style, space at the top for a headline" },
      { label: "New menu launch", prompt: "A clean, modern menu-launch graphic for our new weekend brunch menu, minimal design, soft natural lighting, top-down food shot" },
      { label: "Instagram feed post", prompt: "A vibrant Instagram square post announcing 20% off on all orders above ₹499 this weekend, bold typography, appetizing color palette" },
      { label: "Delivery app banner", prompt: "A wide banner for our Zomato/Swiggy storefront highlighting our best-selling thali, warm inviting colors, clear price callout" },
    ],
    videoPrompts: [
      { label: "15s Diwali sale teaser", prompt: "A 15-second vertical video teaser for our sweet shop's Diwali sale, warm festive lighting, close-up shots of mithai being packed, upbeat and inviting mood" },
      { label: "New dish reveal", prompt: "A short video revealing our new signature dish, steam rising, slow motion plating shot, warm kitchen lighting" },
      { label: "Weekend offer announcement", prompt: "A quick, energetic video announcing our weekend combo offer, bold on-screen text callouts, appetizing close-ups" },
    ],
  },
  {
    id: "retail",
    name: "Retail & D2C brand",
    icon: ShoppingCart,
    recipesIndustryId: "ecommerce",
    imagePrompts: [
      { label: "New collection banner", prompt: "A clean e-commerce hero banner announcing our new collection launch, minimal studio background, product-forward composition, modern sans-serif headline space" },
      { label: "Flash sale graphic", prompt: "A bold flash-sale graphic, 'Sale ends midnight' urgency, high contrast colors, clear percentage-off callout" },
      { label: "Instagram carousel cover", prompt: "A stylish carousel cover slide for our latest arrivals post, soft pastel background, elegant product styling" },
      { label: "Festive gifting banner", prompt: "A festive gifting-season banner for our online store, warm celebratory tones, gift-box styling, space for a discount code" },
    ],
    videoPrompts: [
      { label: "15s product showcase", prompt: "A 15-second product showcase video, slow rotating turntable shot on a clean studio background, soft even lighting, minimal and premium feel" },
      { label: "Unboxing-style teaser", prompt: "A short unboxing-style video of our packaging, hands opening a branded box, satisfying reveal moment, warm natural light" },
      { label: "Sale countdown video", prompt: "A fast-paced countdown video for our 48-hour flash sale, bold on-screen numbers, energetic pacing" },
    ],
  },
  {
    id: "salon",
    name: "Salon, spa & beauty",
    icon: Scissors,
    recipesIndustryId: "salon",
    imagePrompts: [
      { label: "Bridal package poster", prompt: "An elegant poster for our bridal makeup package, soft golden tones, beauty-editorial styling, space for package details" },
      { label: "Monthly offer graphic", prompt: "A clean Instagram post announcing our monthly spa package offer, calming pastel palette, minimal spa-themed icons" },
      { label: "Before/after style banner", prompt: "A tasteful before-and-after style promotional banner for our hair transformation services, soft studio lighting" },
      { label: "New service launch", prompt: "A refined announcement graphic for our new skincare facial service, clean beauty-brand aesthetic, calming colors" },
    ],
    videoPrompts: [
      { label: "15s salon ambience", prompt: "A 15-second calm, aesthetic video of our salon interior and a relaxing treatment in progress, soft warm lighting, gentle slow pans" },
      { label: "Service highlight reel", prompt: "A short highlight video of a hairstyling transformation, smooth cuts between before and after, soft studio lighting" },
      { label: "Festive offer teaser", prompt: "A quick, elegant video teaser for our festive season beauty package, warm golden tones, soft transitions" },
    ],
  },
  {
    id: "real-estate",
    name: "Real estate",
    icon: Building2,
    recipesIndustryId: "real-estate",
    imagePrompts: [
      { label: "New listing banner", prompt: "A polished real-estate listing banner for a 3BHK apartment, clean architectural photography style, space for price and contact details" },
      { label: "Open house announcement", prompt: "A professional open-house announcement graphic, modern property photo styling, clear date/time callout area" },
      { label: "Project launch poster", prompt: "An aspirational poster for our new residential project launch, golden hour exterior render style, premium feel" },
      { label: "Site visit offer", prompt: "A clean graphic inviting enquiries to book a free site visit, trustworthy corporate palette, clear call-to-action space" },
    ],
    videoPrompts: [
      { label: "15s property walkthrough", prompt: "A 15-second smooth walkthrough-style video of a modern apartment interior, natural daylight, steady gliding camera motion through rooms" },
      { label: "Project drone-style flyover", prompt: "A short cinematic drone-style flyover of a residential project exterior, golden hour lighting, sweeping aerial motion" },
      { label: "Amenities highlight", prompt: "A quick highlight video of premium building amenities - pool, gym, lobby - smooth transitions, bright natural lighting" },
    ],
  },
  {
    id: "healthcare",
    name: "Healthcare & clinics",
    icon: HeartPulse,
    recipesIndustryId: "healthcare",
    imagePrompts: [
      { label: "Health camp announcement", prompt: "A clean, trustworthy announcement graphic for our free health check-up camp, calming medical-blue palette, clear date and venue space" },
      { label: "New doctor introduction", prompt: "A professional introduction graphic for a new specialist joining our clinic, clean corporate healthcare styling" },
      { label: "Vaccination drive poster", prompt: "A reassuring poster for our seasonal vaccination drive, soft trustworthy colors, clear scheduling call-to-action" },
      { label: "Wellness tip graphic", prompt: "A simple, friendly wellness-tip social post, calming pastel healthcare palette, clean iconography" },
    ],
    videoPrompts: [
      { label: "15s clinic welcome", prompt: "A 15-second calm, reassuring video welcoming patients to our clinic, clean bright interior, gentle steady camera motion" },
      { label: "Health camp invite", prompt: "A short, warm invitation video for our community health check-up camp, friendly and trustworthy tone, soft daylight" },
      { label: "Facility highlight", prompt: "A quick highlight video of our clinic's modern facilities and equipment, clean bright lighting, steady smooth pans" },
    ],
  },
  {
    id: "education",
    name: "Education & tutoring",
    icon: GraduationCap,
    recipesIndustryId: "tutors",
    imagePrompts: [
      { label: "Admissions open poster", prompt: "An energetic poster announcing admissions open for the new academic year, bright optimistic colors, clear enrollment call-to-action" },
      { label: "New batch announcement", prompt: "A clean graphic announcing a new weekend batch for exam preparation, motivating color palette, clear schedule space" },
      { label: "Results celebration post", prompt: "A celebratory social post congratulating our top-scoring students, warm confetti-style design, space for names/scores" },
      { label: "Free demo class offer", prompt: "A friendly graphic inviting parents to book a free demo class, approachable education-brand palette" },
    ],
    videoPrompts: [
      { label: "15s classroom energy", prompt: "A 15-second energetic video of an engaging classroom or tutoring session, bright natural lighting, dynamic but warm pacing" },
      { label: "Student success story", prompt: "A short, uplifting video style piece celebrating a student's achievement, warm optimistic tone, gentle camera motion" },
      { label: "New course announcement", prompt: "A quick, motivating announcement video for our new course launch, bold on-screen text, energetic pacing" },
    ],
  },
  {
    id: "fitness",
    name: "Fitness & wellness studios",
    icon: Dumbbell,
    recipesIndustryId: "fitness",
    imagePrompts: [
      { label: "Membership offer poster", prompt: "A high-energy poster for our new-year membership offer, bold dynamic colors, strong motivational typography space" },
      { label: "New class launch", prompt: "A vibrant graphic announcing our new HIIT class schedule, energetic gym-photography style" },
      { label: "Transformation showcase", prompt: "A motivating before/after style transformation showcase graphic, bold confident design" },
      { label: "Referral offer", prompt: "A punchy 'bring a friend' referral offer graphic, high-energy fitness-brand palette" },
    ],
    videoPrompts: [
      { label: "15s workout energy", prompt: "A 15-second high-energy video of a group workout class in progress, dynamic camera motion, bright gym lighting, motivating pace" },
      { label: "Trainer intro", prompt: "A short, confident introduction-style video of a personal trainer demonstrating a signature move, clean gym backdrop" },
      { label: "New year offer teaser", prompt: "A fast-paced motivational teaser video for our new-year membership offer, bold on-screen text, energetic transitions" },
    ],
  },
  {
    id: "jewellery",
    name: "Jewellery & fashion",
    icon: Gem,
    recipesIndustryId: "jewellery",
    imagePrompts: [
      { label: "Festive collection poster", prompt: "A luxurious poster for our festive jewellery collection launch, warm golden studio lighting, elegant close-up styling" },
      { label: "Bridal collection banner", prompt: "An opulent banner for our bridal jewellery collection, rich jewel-toned palette, premium editorial photography feel" },
      { label: "New arrivals post", prompt: "A refined Instagram post showcasing new arrivals, soft studio lighting, minimal elegant composition" },
      { label: "Exchange offer graphic", prompt: "A trustworthy graphic announcing our gold exchange offer, premium warm-gold palette, clear terms callout space" },
    ],
    videoPrompts: [
      { label: "15s jewellery close-up", prompt: "A 15-second slow, elegant close-up video of jewellery pieces catching the light, soft golden studio lighting, gentle rotating motion" },
      { label: "Collection reveal", prompt: "A short cinematic reveal video of our new festive collection, warm luxurious lighting, smooth graceful camera motion" },
      { label: "Bridal lookbook teaser", prompt: "A quick elegant teaser video for our bridal jewellery lookbook, rich warm tones, refined slow pacing" },
    ],
  },
  {
    id: "automotive",
    name: "Automotive & dealerships",
    icon: Car,
    recipesIndustryId: "automotive",
    imagePrompts: [
      { label: "New model launch poster", prompt: "A bold poster announcing the launch of a new car model, dynamic studio lighting, premium automotive photography style" },
      { label: "Service reminder graphic", prompt: "A clean, professional graphic reminding customers of their upcoming service due date, trustworthy corporate palette" },
      { label: "Festive offer banner", prompt: "A festive-season offer banner for our dealership, warm celebratory tones over a clean car showroom backdrop" },
      { label: "Test drive invite", prompt: "An inviting graphic promoting a weekend test-drive event, energetic automotive-brand colors" },
    ],
    videoPrompts: [
      { label: "15s vehicle showcase", prompt: "A 15-second dynamic showcase video of a car, slow rotating turntable shot, dramatic studio lighting, premium automotive feel" },
      { label: "Test drive event teaser", prompt: "A short energetic teaser video for our weekend test-drive event, dynamic camera motion, bright showroom lighting" },
      { label: "New model reveal", prompt: "A cinematic reveal-style video of our newest model, dramatic lighting sweep, confident slow camera motion" },
    ],
  },
  {
    id: "events",
    name: "Events & wedding planning",
    icon: PartyPopper,
    recipesIndustryId: "events",
    imagePrompts: [
      { label: "Wedding season poster", prompt: "An elegant poster promoting our wedding planning services for the season, soft romantic color palette, premium event styling" },
      { label: "Venue showcase banner", prompt: "A stunning banner showcasing our event venue decorated for a celebration, warm ambient lighting, aspirational styling" },
      { label: "Early-bird offer graphic", prompt: "A graceful graphic promoting an early-bird booking discount for weddings, soft elegant palette" },
      { label: "Corporate event promo", prompt: "A polished promotional graphic for our corporate event management services, clean professional styling" },
    ],
    videoPrompts: [
      { label: "15s venue ambience", prompt: "A 15-second warm, romantic video of an elegantly decorated event venue, soft ambient lighting, gentle sweeping camera motion" },
      { label: "Wedding highlight style", prompt: "A short cinematic highlight-style video capturing celebration decor and details, warm golden hour lighting, graceful pacing" },
      { label: "Booking offer teaser", prompt: "A quick, elegant teaser video promoting our seasonal booking offer, soft romantic tones, smooth transitions" },
    ],
  },
  {
    id: "professional-services",
    name: "Professional services",
    icon: Briefcase,
    recipesIndustryId: "professional-services",
    imagePrompts: [
      { label: "Service launch banner", prompt: "A clean, trustworthy banner announcing a new professional service offering, corporate blue palette, minimal confident design" },
      { label: "Client testimonial graphic", prompt: "A polished graphic template for showcasing a client testimonial, professional and credible design" },
      { label: "Free consultation offer", prompt: "A clean graphic inviting prospects to book a free consultation, trustworthy corporate styling, clear call-to-action space" },
      { label: "Team introduction post", prompt: "A professional social post introducing our team, clean modern corporate photography style" },
    ],
    videoPrompts: [
      { label: "15s office/team intro", prompt: "A 15-second professional video of a modern office environment and team at work, clean bright lighting, steady confident camera motion" },
      { label: "Service explainer teaser", prompt: "A short, clear explainer-style teaser video introducing our core service, clean corporate visual style, calm pacing" },
      { label: "Consultation offer invite", prompt: "A quick, trustworthy invitation video for a free consultation offer, professional tone, clean office backdrop" },
    ],
  },
  {
    id: "nonprofit",
    name: "Nonprofit & community",
    icon: HandHeart,
    recipesIndustryId: "nonprofit",
    imagePrompts: [
      { label: "Fundraiser campaign poster", prompt: "A warm, hopeful poster for our fundraising campaign, community-focused imagery style, clear donation call-to-action space" },
      { label: "Volunteer drive graphic", prompt: "An inviting graphic recruiting volunteers for our upcoming community drive, warm optimistic palette" },
      { label: "Impact report visual", prompt: "A clean infographic-style visual summarizing our program's impact this year, trustworthy nonprofit color palette" },
      { label: "Thank-you donor post", prompt: "A heartfelt thank-you graphic for our donors and supporters, warm grateful tone, gentle design" },
    ],
    videoPrompts: [
      { label: "15s community moment", prompt: "A 15-second warm, hopeful video of community members engaged in a program activity, natural daylight, gentle documentary-style camera motion" },
      { label: "Volunteer recruitment teaser", prompt: "A short, inviting teaser video recruiting volunteers, warm optimistic tone, natural handheld-style motion" },
      { label: "Campaign highlight", prompt: "A quick highlight video of our fundraising campaign's community impact, warm hopeful tone, gentle pacing" },
    ],
  },
];

export function findIndustryPack(id: string): IndustryCreativePack | undefined {
  return INDUSTRY_CREATIVE_PACKS.find((p) => p.id === id);
}
