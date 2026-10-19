# Ad Studio research, October 2026

Backing material for [ADR-0018](ADR-0018-ad-studio.md). Five parallel studies, all dated 2026-10-14.

> **How reliable this is.** The web research used a search tool that returns synthesized summaries; agents mostly did
> not open primary vendor, statute or policy pages. Almost every price, benchmark, licence and legal claim below is
> **secondary** (aggregators, law-firm and vendor blogs, model cards, GitHub) unless marked *primary*. Items marked
> **unverified** could not be confirmed. Prices and benchmark ranks move within weeks. Treat this as a shortlist to
> verify, not a bill of materials, and not legal advice.

## 1. What exists in this repository

Read from source (nothing built or run). Paths relative to the repo root.

| Finding | Where |
|---|---|
| No person-photo, identity, consent, segmentation, try-on, character, ad-template or ad-platform-export code | whole repo |
| Closest existing: Veo image-to-video first-frame input; a browser-only face detect and ArcFace embedding worker; a word-list prompt guard for the anonymous demo | `core/imagegen` (`VideoGenerationClient.SourceImage`), `web/app/lib/edge/face.ts`, `DemoImagePromptGuard` |
| Image generation is text in, image out only (no reference image, mask or edit) | `ImageGenerationClient.generate(apiKey, prompt, model)` and the OpenAI and Gemini clients |
| The authenticated video API accepts a base64 source image (up to about 9 MB) with **no moderation, consent or face check** | `VideoGenerationController` (`/api/videogen/generate`) |
| "Studio" is already three unrelated things: phone-app builder, anonymous demo sandbox, console hub page | `core/app/.../studioapp`, `core/agents-studio/.../demo/studio`, `web/app/studio` |
| Orchestrator hard-codes the BYOK providers (`OPENAI`, `GOOGLE_VERTEX`), ranks by cost, then falls back to a Gemini key pool; video is Veo only | `ImageGenerationOrchestratorService`, `VideoGenerationJobService` |
| Providers are a **closed enum** (`ProviderName`, 209 references); ADR-0011 (proposed, phases 0 to 2 done) wants open string ids | `core/providers/.../ProviderName.java`, `ADR-0011-provider-plugins.md` |
| An **open, string-keyed capability and BYOK registry** exists with one implementation (TypeSafe) | `core/providers/.../capability/*` |
| SSRF guards, tenant-scoped file storage (no presigned URLs), moderation SPI with a no-op external classifier (text-oriented) | `SsrfGuard`, `HostedLlmBaseUrlGuard`, `FileStorageService`, `guardrails/moderation` |
| Side-service pattern to copy: FastAPI, shared-token auth, `/health`, async 202 and poll, chart guards; `app-builder` also calls back into core with a service token | `services/*`, `deploy/helm/charts/*` |
| Job frameworks: per-feature pollers with advisory locks; durable agent jobs with state machine, heartbeat, orphan recovery, approval park; workflow waits and outbox and webhooks | `VideoGenerationJobRunner`, `agents/job/*`, `workflow`, `eventbus` |
| Governance to reuse: four-eyes approvals, tamper-evident audit, effect journal, budgets and kill switch | `core/approval`, `core/audit`, `EffectJournal`, `PlatformFeatureBudgetService` |
| **No GPU anywhere** in deploy (no resource requests, node selectors or tolerations); only a local Ollama-style compose overlay | `deploy/` |
| No brand kit, product catalog, asset library, canvas or mask editor, shared upload widget, or per-asset credit ledger | `web/`, `core/` |
| Erasure gap: the object-key table list for tenant erasure omits the video job table | `TenantDataErasureService.OBJECT_KEY_TABLES` |
| The Veo request mapping was never verified against a live call (documented in its javadoc) | `VertexVeoVideoGenerationClient` |

## 2. Models and APIs (late 2026)

### 2.1 Leaderboard snapshot

Artificial Analysis and arena.ai (secondary summaries; scales changed between snapshots, so ranks are not comparable
across dates). Sources: https://artificialanalysis.ai/image/leaderboard/editing,
https://artificialanalysis.ai/image/leaderboard/editing/open-weights,
https://artificialanalysis.ai/video/leaderboard/image-to-video, https://arena.ai/leaderboard/video/image-to-video
(snapshot 2026-09-21).

- **Image editing, closed:** GPT Image 2.5 variants lead, then MAI-Image-2.6 and Nano Banana 2.1, roughly tied.
- **Image editing, open weights:** Qwen-Image-2.1, HunyuanImage 3.0 Instruct and Qwen Image Edit Plus 2511 lead, about
  100 Elo behind the closed leader.
- **Image to video:** MiniMax H3, Vidu Q4, Gemini Omni, Seedance 2.x near the top on one board, with Wan 3.0 and Kling
  v3 elsewhere; Veo 3.1 mid-table. Third-party blogs claiming a different #1 conflict with the official tables.
- **Sora 2's API reportedly shut down on 2026-09-24.** Do not build on it.
- **Caveat:** these measure general preference. They do **not** measure identity fidelity on a specific person or
  garment fidelity. We need our own evaluation set.

### 2.2 Options and commercial status, by capability

| Capability | Options found | Commercial and terms notes |
|---|---|---|
| Segmentation | SAM 3 (Meta, custom SAM License, secondary summaries say commercial use is allowed with attribution; text **not read**); SAM 2 (Apache-2.0); fal-hosted SAM 3 | Legal review of the SAM License needed |
| Background removal | BiRefNet (MIT) | **RMBG-2.0 is CC BY-NC**; commercial needs a BRIA agreement |
| Virtual try-on | FASHN v1.6 API (about $0.075 per image on demand; outputs licensed commercially; inputs deleted after 72 h per a third-party review); Google Vertex Virtual Try-On (about $0.06 per tracker; GA 2026-01-23; one tracker marks it deprecating, check successor); Kling Kolors VTON (about $0.07 on fal); Leffa (MIT code, about $0.10 on fal); FASHN VTON v1.5 open weights (Apache-2.0 per FASHN's own table) | **CatVTON (CC BY-NC-SA) and IDM-VTON (non-commercial) cannot be used.** No independent try-on benchmark found; rankings come from vendor blogs |
| Characters and twins | Multi-reference edit models (GPT Image 2.5, Nano Banana 2.x, FLUX.2, Qwen-Image-Edit); per-person LoRA on a commercially licensed base | **InstantID and IP-Adapter-FaceID depend on InsightFace weights, which are non-commercial.** FLUX.2 [dev] weights are non-commercial; [klein] 4B is Apache-2.0. Whether [dev] outputs are commercially usable is disputed |
| Ad image and in-image text | GPT Image 2 and 2.5 (token-billed; about $0.006 / $0.053 / $0.211 per 1024 low / medium / high per an aggregator); Nano Banana 2 and 2.1 (about $0.045 to $0.15 per image by size); FLUX.2 pro and max; Seedream | Prices unverified against vendor pages. Ideogram and Recraft **not researched** |
| Video | Seedance 2.x, Kling 3, Veo 3.1, Wan 2.x, MiniMax H3; per-second prices from aggregators range roughly $0.03 to $0.40 and conflict | Resellers mark up two to three times. Wan 2.2 is Apache-2.0 open; later open-weight claims conflict |
| Talking avatars and voice | HeyGen (consent endpoint with webcam flow; skip only with an indemnity agreement), Synthesia, OmniHuman, Hedra; ElevenLabs; Sarvam Bulbul v3 (11 languages) | Credit prices conflict; get quotes. Lip-sync vendor Sync **not researched** |
| Upscale and face restore | Topaz API; Magnific (no official API price found) | **SUPIR and CodeFormer are non-commercial**; GFPGAN licence unconfirmed. Generative upscalers can alter faces |

### 2.3 Inference platforms

fal.ai, Replicate, RunPod, Modal host these models per output or per second. Cold starts reported at 5 to 60+ seconds
(developer-reported); idle and cold-start overhead reportedly adds 20 to 40 percent to serverless cost. Marketplace
spot GPUs are cheaper but interruptible. Together, Bedrock and Vertex Model Garden were **not researched**.

### 2.4 Real-person policies of hosted providers

| Provider | Finding (secondary unless stated) |
|---|---|
| Google (Gemini image, Imagen, Veo, Virtual Try-On) | Restrictions on real people reportedly tightened in Feb 2026; users report selfies refused as "public figure". Imagen person-generation has block, adult-only and all-ages levels; Veo defaults to adults only; Try-On filters uploads against the Prohibited Use Policy (docs, primary-ish) |
| OpenAI | Heightened restrictions on real-person edits and a hard stop on photoreal minors per the launch post; current API behaviour **unverified** |
| Black Forest Labs | Usage policy (revised 2026-08-04) prohibits misleading or sexual depictions of real people without verified consent; may spot-check deployers (primary: https://bfl.ai/legal/usage-policy) |
| HeyGen | Consent required for digital twins |
| Others (ByteDance, Alibaba, Kling, MiniMax) | **Unverified** |

**Consequence:** test every candidate with the real consented flow and record refusal rates; build routing that assumes
refusals.

## 3. Open and free models, and the low-GPU route

### 3.1 Licence status (secondary; read the primary page before use)

| Model | Status |
|---|---|
| FLUX.1 schnell, FLUX.2 klein 4B | Apache-2.0 |
| FLUX.1 dev, FLUX.2 dev, FLUX.2 klein 9B | Non-commercial; paid commercial licence exists |
| Stable Diffusion 3.5 | Free under $1M annual revenue, registration required |
| SDXL Turbo | Sources conflict (research-only versus community licence with the same cap) |
| Qwen-Image and Qwen-Image-Edit (2025) | Apache-2.0 |
| Qwen-Image 2.1 | Reported as a research licence (non-commercial) by secondary sources, **not checked against the official card** |
| HiDream-I1 | MIT, but depends on the Llama 3.1 text encoder so Llama terms apply |
| Wan 2.1 and 2.2 | Apache-2.0 (later versions unclear) |
| LTX-2.x | Free under $10M ARR (affiliates counted) |
| HunyuanVideo | Tencent Community License **excludes the EU, UK and South Korea** |
| CatVTON, IDM-VTON | Non-commercial |
| InsightFace pretrained weights | Non-commercial research; commercial licence on request |
| SAM 2 (Apache-2.0), BiRefNet (MIT) | Commercial use fine |
| RMBG-2.0 | Non-commercial |

The only fully clean path confirmed: FLUX.1 schnell, FLUX.2 klein 4B, Qwen-Image and Edit (2025), SAM 2, BiRefNet,
Wan 2.2, and HiDream with Llama terms. **There is no clean open try-on model**, and the face-identity layer has a
second licensing hole.

### 3.2 What runs without server GPUs

| Where | Works for | Evidence and limits |
|---|---|---|
| Browser | Masking, background removal, landmarks, depth, compositing, layout and text, video assembly | WebGPU about 85 to 88 percent global (caniuse); Safari only from 26; Firefox patchy; WebNN not usable yet. iOS tabs reportedly about 1.5 GB (anecdotal), so models must be small (about 200 to 400 MB). BiRefNet-lite ONNX is 94 MB (fp16) to 183 MB. **No in-browser latency numbers found** for SAM or diffusion on mid-range devices |
| CPU worker | Few-step small diffusion (SDXS about 0.8 s, SD-Turbo about 1.7 s at 512 px on a desktop CPU with OpenVINO, vendor repo), ffmpeg, compositing | Large models take minutes per image on CPU |
| Serverless GPU | Open edit and video models, LoRA training and inference | Few-step 4B image about 2 to 5 s on a cheap GPU (estimate); video clips tens of seconds to minutes |
| Free tiers | Prototypes only | ZeroGPU about 5 GPU-minutes a day free; Gemini free tier content may be used to improve products and reviewed by humans; Colab and Kaggle terms forbid use as a production backend. None offers no-training and retention guarantees for customer faces |

Techniques that cut generation and often look more real: template compositing for text and layout (never let a diffusion
model render ad copy), licensed model or asset libraries, 2D garment warping for flat products, relighting and shadows,
depth-based 2.5D motion from a still, reuse of generated backgrounds, characters and masks, and one identity training
run reused across ads.

## 4. Competitors and market

Facts below were fetched from primary pages for Creatify and HeyGen only; everything else is secondary.

| Player | Notes |
|---|---|
| Creatify | URL to video ads, AI actors, Ad Clone, Ad Agent; Standard $12, Pro $44, Max $76, Business $156 per month (creatify.ai/pricing, fetched 2026-10-14); SOC 2 only at Enterprise |
| HeyGen | Avatar and multilingual video; Creator $29, Business $149 plus seats (heygen.com/pricing, fetched 2026-10-14); SSO on Business, SCIM on Enterprise |
| Arcads | UGC-style talking heads; Trustpilot about 2.5 to 2.8 of 5 (secondary); complaints about retry cost, poor lip-sync, support |
| AdCreative.ai, Synthesia, Canva, Pencil (Brandtech), Photoroom, Pebblely, Higgsfield | Pricing conflicting or unverified. Photoroom's own guide admits regenerating can distort logos, colours and packaging text |
| Native tools | Meta Advantage+ (video generation GA reported 2026-10-06), Google Ads Asset Studio plus Veo (announced 2026-03-24), TikTok Symphony (free; auto-labelled), Amazon Creative Agent. Free and increasingly agentic |

**Where incumbents are weak:** credit-and-retry pricing, product and logo fidelity, consent and rights evidence, and
governance gated behind top tiers. **Differentiators for us:** (1) a fidelity gate, (2) a likeness-rights and brand-safety
layer, (3) provenance by default, (4) agentic campaigns from a product URL, (5) verified-consent digital twins, (6)
evidence-based creative testing, (7) vernacular-first India and Southeast Asia, (8) predictable pricing with no billing
for failed fidelity, (9) self-host and air-gapped, (10) agency governance.

Ad formats (secondary): common vertical master 1080x1920, 9:16, MP4 H.264, 30 fps; Meta Reels and Stories safe-zone
figures conflict across sources. Publishing APIs exist for Meta, Google, TikTok, Snap, LinkedIn, Pinterest and Amazon, with
access tiers **not verified**. Indian placements (ShareChat, Moj, WhatsApp) not researched.

## 5. Law, platform policy and provenance

### 5.1 Key rules (secondary law-firm summaries; counsel must verify against primary text)

| Area | Finding |
|---|---|
| EU AI Act Article 50 | Applies from 2026-08-02: deployers must disclose deepfakes, providers must mark outputs machine-readably. Code of Practice (final 2026-06-10) asks for at least two layers (signed metadata plus an imperceptible watermark). Existing systems have until 2026-12-02 for marking |
| New York synthetic performer law (GBL s.396-b) | Effective 2026-06-09: conspicuous disclosure of an AI-created performer in ads; $1,000 then $5,000 penalties. Targets invented people; twins of real people fall under publicity and consent law |
| California | AB 2602 (digital-replica contract terms) and AB 1836 (deceased performers) in effect since 2025-01-01; a synthetic-performer ad disclosure law reported effective 2027-01-01 |
| US federal | NO FAKES Act is **not law** (Senate calendar); TAKE IT DOWN Act removal duty enforceable since 2026-05-19, **applicability to this product unverified**; FTC Rule 16 CFR 465 bars testimonials that misrepresent a reviewer's existence or experience |
| Biometrics | Face matching is biometric processing: GDPR Article 9 (explicit consent), Illinois BIPA ($1,000 or $5,000 per person; amendment held retroactive in April 2026), Texas CUBI (billion-dollar settlements). Written consent and retention schedule needed |
| India | IT Rules amendment (notified 2026-02-10, in force 2026-02-20): labels and embedded provenance, 2 to 3 hour takedown; DPDP Rules (main duties about May 2027, under-18 needs verifiable parental consent); ASCI draft (May 2026) treats unauthorised deepfakes and fabricated endorsements as prohibited even if labelled, final reported 2026-09-29; Delhi and Bombay High Court personality-rights injunctions through 2026 |
| Other | China labelling measures in force since 2025-09-01; UK offence for creating sexually explicit deepfakes since 2026-02-06; Denmark likeness bill introduced 2026-10-08 (not passed) |
| Platforms | Meta and TikTok read C2PA and IPTC and apply labels you cannot remove; Google (primary help page) allows in-creative AI labels from July 2026; YouTube and Pinterest auto-label from provenance signals; Amazon requires sellers to label AI-generated people. Social re-encoding often strips metadata |
| Abuse precedent | The January 2026 Grok "undressing" incident (mass non-consensual edits of real photos; regulators opened investigations) is the central misuse a photo editor must be built to refuse |

### 5.2 Provenance

C2PA specification 2.4; the Interim Trust List was frozen at the start of 2026 and the conformance programme and Trust
List are now the path. A durable approach uses a watermark as a soft binding to recover a stored manifest after
metadata is stripped. SynthID is proprietary and cannot be embedded by third parties; open options include TrustMark and
Meta's Pixel Seal and Video Seal, none robust to every attack, which is why two layers are expected. Certificate cost is
**unverified**.

### 5.3 Engineering requirements (33; 16 launch blockers)

**Launch blockers (LB):**

1. Likeness rights registry with versioned consent (grantor, scope, term, territory, revocation).
2. Hard gate on every real-face workflow: no consent record, no edit, try-on, twin or video.
3. Consent captured by the subject: recorded liveness challenge with a random passcode, face-matched to every uploaded
   photo; no skip-by-indemnity path.
4. Block minors (age estimation on every face; refuse real-person minors in v1).
5. CSAM pipeline (hash match plus classifier on uploads and outputs; block, preserve and report workflow).
6. NSFW and sexualisation block (refuse undressing, lingerie, swimwear and body-altering edits of real people).
7. Public-figure and celebrity detection and block, with a dispute path and human review.
8. Protection of third parties in photos (obscure or require consent).
9. Two-layer provenance on every output (signed C2PA manifest plus invisible watermark with soft binding).
10. No metadata-stripping or watermark-removal feature, and no forged consent flags.
11. Disclosure at export: visible label option, default on for photoreal people in the EU, India, New York and China.
12. Fake endorsement and testimonial guard.
13. Revocation and takedown: block new generation immediately (under 24 hours), delete derived assets, character models
    and embeddings, public intake without an account.
14. Biometric consent and a published retention schedule.
15. Per-tenant isolation and encryption of faces, embeddings and character models.
16. No training on customer faces, by us or by vendors.

**Must / should (selected):** immutable audit trail; human review queue; abuse and account enforcement (rate limits,
strikes, departing-employee avatar disablement); input-image prompt-injection defence (OCR text is data); a jailbreak and
red-team suite in CI; provider abstraction with per-model policy flags and fail-closed routing; region-aware compliance
profiles and data residency; trademark and logo detection; separate voice consent scope; posthumous and estate flag; DPIA
before EU launch; C2PA conformance enrolment; watermark robustness matrix across real re-encodes; a public verifier;
model-release templates; incident runbook.

## 6. Gaps and what to verify next

- Primary pricing and terms: OpenAI, Google Vertex, ByteDance, Kling, BFL, HeyGen, FASHN; Meta's SAM licence text;
  current real-person policies of Google and OpenAI.
- Not researched: Runway, Luma, Pika, Ideogram, Recraft, Grounded-SAM, LoRA training tooling and cost, lip-sync vendors,
  Replicate and Together prices, Real-ESRGAN licence, OpenRouter, Groq, NIM and Cloudflare data terms.
- Measurements: in-browser latency for segmentation and diffusion on mid-range phones; CPU timings for large models;
  GPU timings for video on small GPUs.
- Legal: applicability of the TAKE IT DOWN Act and the New York knowledge standard; Meta, TikTok, LinkedIn, Snap and
  Amazon official policy text; ASCI final text and date; NCMEC treatment of synthetic imagery; certificate costs.
- Competitors: Arcads, AdCreative.ai, Synthesia, Canva and Runway pricing pages; independent reviews on fidelity.
