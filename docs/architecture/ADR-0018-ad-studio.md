# ADR-0018: Ad Studio, a separate-service ad-creative platform

- **Status:** proposed, design only. Nothing in this document is built or measured.
- **Date:** 2026-10-14
- **Research behind it:** [Ad Studio research, October 2026](ad-studio-research-2026-10.md) (five parallel studies:
  repo map, models and APIs, competitors, law and safety, the low-GPU route). Almost all external facts there come
  from secondary sources and are labelled with how well they are verified. **Prices, licences and legal claims must
  be re-verified against primary pages before anything is built on them.**

## 1. The goal

Extend the platform's Studio into a complete AI advertising-creative platform in which a customer can:

1. upload photos of real people (themselves, staff, hired models, influencers) and products,
2. mask and segment them (person, clothing, product, background),
3. change a person's clothing (virtual try-on),
4. create photoreal characters and digital twins that stay the same person across many ads,
5. generate ad images (with correct text) and ad videos from them, in many formats and languages,
6. review, approve and publish, with a full record of what was made and with whose permission.

Requirements stated by the product owner: it must be the best in its class rather than another me-too tool; it must be
built as **separate services wired into the platform**; it must be **cheap to run** (prefer free and open models, the
user's browser and CPU over GPU wherever quality allows); and it must be **provider-flexible** (any API or provider can
be plugged in, including the customer's own).

## 2. What we found (summary)

**In this repository** (full map in the appendix):

- There is **no** person-photo, identity, consent, segmentation, try-on, character or ad-template code. The only related
  code is Veo image-to-video first-frame conditioning, a browser-only face-embedding worker (`web/app/lib/edge/face.ts`)
  and a word-list prompt guard for the anonymous demo.
- Image generation is text-in, image-out only (`ImageGenerationClient.generate(apiKey, prompt, model)`). There are no
  reference-image, edit or mask contracts. The authenticated video path accepts a base64 source image with **no
  moderation, consent or face check**.
- "Studio" already names three unrelated things (the phone-app builder, the anonymous demo sandbox, the console hub
  page), and `web/app/build/studio/StudioSurface.tsx` is already over the file-size ratchet. The new area needs a new
  name to avoid entity and bean collisions (rules 3 and 9 in `AGENTS.md`).
- Useful foundations exist: the side-service pattern (`services/app-builder`, `services/sidecar`, `services/tool-sandbox`:
  FastAPI, shared-token auth, async 202-and-poll, chart guards), the durable job framework (`agents/job/*`), approvals with
  four-eyes (`core/approval`), a tamper-evident audit log, a string-keyed capability and BYOK registry
  (`core/providers/.../capability/*`), SSRF guards, tenant-scoped file storage, and a moderation SPI.
- There is **no GPU anywhere** in the deploy tooling, and no per-asset credit ledger.

**Outside** (appendix, with sources and caveats):

- Quality leaders change within weeks (image editing and video leaderboards moved several times in September 2026), and
  public leaderboards do **not** measure identity fidelity on a specific person or garment fidelity.
- Hosted model APIs may **refuse or restrict editing photos of real people** (Google reportedly tightened this in
  2026), and they log inputs. A real-person workflow must not depend on a single hosted model.
- Many popular open models are **not free for commercial use**: CatVTON and IDM-VTON (try-on), InsightFace pretrained
  weights (and anything built on them), RMBG-2.0, FLUX dev, SUPIR, CodeFormer, and reportedly Qwen-Image 2.1. Others are
  revenue-capped (SD 3.5, LTX). Cleanly usable: FLUX.1 schnell, FLUX.2 klein 4B, Qwen-Image and Qwen-Image-Edit (2025),
  SAM 2, BiRefNet, Wan 2.2.
- Competitors are weak on: paying credits for failed generations, distorted logos and packaging text, rights and
  consent evidence, governance gated behind top tiers. The native platforms (Meta, Google, TikTok, Amazon) now offer
  free generative ad tools, so "video from a URL" alone cannot win.
- 2026 law makes provenance and consent core features: EU AI Act Article 50 (from 2026-08-02), New York's synthetic
  performer ad law (2026-06-09), India's synthetic-media labelling rules (2026-02-20) and ASCI's guidelines, biometric
  privacy exposure for any face matching, and a January 2026 mass-"undressing" incident that defines the misuse a photo
  editor must be built to refuse.

## 3. Decision

### 3.1 Name and shape

- The area is called **Ad Studio**: module `core/adstudio`, API base `/api/ad-studio`, tables prefixed `adstudio_`, web
  route `/ad-studio`. It does **not** reuse `Studio*` names.
- It is a **set of separate services plus one thin core module**, never part of the chat turn. This is allowed by
  `AGENTS.md` rule 6 (new services are for async work, untrusted code, or a different runtime) and adds no network hop
  to the chat path.
- **Core owns truth:** jobs, assets, consent, audit and billing state live in core's database. Services are stateless
  workers. (The sidecar's in-memory render jobs force a single replica; we do not repeat that.)

### 3.2 Services

| Service | Runtime | Does | Scales |
|---|---|---|---|
| `core/adstudio` (in core API and jobs shapes) | Java | Projects, assets, subjects and consent, jobs, router, gates, approvals, audit, credits, publishing | With core |
| `services/adstudio-media` | Python, CPU | Deterministic work: compositing, text and layout rendering, template engine, format and safe-zone presets, ffmpeg video assembly, 2.5D motion from stills, C2PA signing and watermarking, thumbnails, upscaling that is licence-clear | Horizontal, CPU |
| `services/adstudio-vision` | Python, CPU or small GPU | Segmentation (SAM 2), background removal (BiRefNet), face and age detection, public-figure and NSFW screening adapters, OCR, identity and garment scoring (the quality gates) | Horizontal |
| `services/adstudio-gen` | Python, GPU workers | Self-hosted open-weights generation (image edit, character LoRA training and inference, short video) behind the same job contract as every external provider | Serverless GPU or in-cluster GPU pool, scale to zero |
| Browser (web worker tier) | TypeScript | Masking preview, matting, landmarks, crop and layout, compositing preview, on-device face embedding for the consent check | The user's device |

Each service copies the `app-builder` shape: FastAPI, hash-locked requirements, non-root, `/health`, dual auth
(`X-Internal-Token` from core, service-token calls back into core for governed generation), async `202` plus poll plus
optional signed callback. GPU deployment adds what the repo lacks today: GPU resource requests, node selectors and
tolerations in `vegaduta-lib`, a model-weight cache volume, concurrency caps and per-job GPU-second metering. The
`tool-sandbox` chart guards (tainted pool, runtime class) are the model for the scheduling rules.

### 3.3 Provider flexibility (the central design element)

Do **not** add media vendors to the closed `ProviderName` enum (209 references; the ADR-0011 refactor is only partly
done). Instead:

- Add `Capability` values to the existing string-keyed capability registry: `IMAGE_GENERATION`, `IMAGE_EDIT`,
  `SEGMENTATION`, `BACKGROUND_REMOVAL`, `TRY_ON`, `CHARACTER`, `VIDEO_GENERATION`, `AVATAR_VIDEO`, `UPSCALE`, `VOICE`.
  Tenant provider keys are **strings** (`"fal"`, `"replicate"`, `"openai"`, `"vertex"`, `"fashn"`, `"custom-http"`),
  stored through the existing encrypted `ProviderCredential` with per-vendor credential records (key, optional base URL,
  region, webhook secret).
- Define a **`MediaProvider` SPI**, one contract for every provider, hosted or self-hosted:
  `submit(request) -> jobRef`, `poll(jobRef)`, `cancel(jobRef)`, optional signed webhook, an idempotency key, a
  declared **capability descriptor** (accepted inputs: reference images, mask, garment, pose; outputs; limits; sync or
  async) and a declared **policy descriptor** (see 3.4). Existing OpenAI, Vertex and Veo clients become adapters later;
  the current `generate_image` tool and orchestrator stay untouched.
- Ways to plug in a provider, in increasing effort: (1) a catalogued vendor adapter with the customer's own key (BYOK);
  (2) **custom HTTP provider**, configured with an OpenAPI-style request template and a result mapping, no code;
  (3) **OpenAI-compatible or ComfyUI endpoint** (a customer's or operator's own server); (4) an **MCP server** exposing
  `segment` or `try_on` tools (already pluggable today with the existing review-status gate); (5) a signed provider
  package under the ADR-0011 operator-trusted model.
- Every outbound fetch, including **downloads of provider-returned result URLs** and webhook targets, goes through
  `SsrfGuard`. The existing clients only talk to fixed vendor hosts, so there is no precedent for fetching
  provider-returned URLs; this must be built and tested first.
- A **provider review status** (modelled on `McpReviewStatus`) lets an operator or tenant admin approve a provider
  before it may receive face images, and the tenant is shown "this provider receives photos of people".

### 3.4 Provider and model registry, with policy metadata

A new `adstudio_model` registry (operator-curated, extending `media_model_cost`) records for each provider and model:
licence and **commercial-use status**, revenue caps and review date, data-retention and training-on-your-data terms,
**real-person-editing policy** (allowed, restricted, refused), minors policy, region and residency, unit price (per
image, megapixel, or second), typical latency, version pin, and a measured quality score from our own evaluation set.
CI fails if a model that is not on the approved list is referenced by a default route.

### 3.5 Router

Per job step, in this order:

1. **Hard filters:** licence is commercially cleared; provider is approved for the tenant; the provider's data terms
   match the tenant's policy; residency matches; **the provider's real-person policy fits the step**; **the consent gate
   (3.6) has passed**.
2. **Tier:** pick the cheapest tier that has a candidate meeting the quality bar: browser, then CPU worker, then
   serverless GPU with open weights, then premium API. Customers' own keys count as the lowest platform cost.
3. **Quality gates** (3.7) run on the output; failure escalates to the next tier or provider, with a per-job retry and
   cost cap.
4. **Refusal-aware fallback:** if a provider's safety filter refuses a legitimate, consented real-person edit, retry on
   the next eligible provider. A refusal that our own gate would also have made is **not** retried elsewhere.
5. Every decision is logged: provider, model version, input hashes, consent record id, gate scores, cost.

### 3.6 The likeness and consent gate (launch-blocking)

One service, `LikenessGate`, is called by **every** upload and generation path before a provider is chosen. It replaces
the unguarded base64 path in the current video API for the new area.

- **Subject and consent records** (versioned): who is depicted, who granted consent, scope (uses, channels, edits,
  voice), term, territory, revocation. Every output references an active record. Consent is captured **by the subject**:
  a recorded challenge with a random spoken passcode and a scope statement, with the face in the video matched to every
  uploaded photo. There is **no** "skip by indemnity" path and no consent flag a customer can set by API alone.
- **Blocks at launch:** photos containing minors (age estimation on every detected face), public figures and celebrities
  (with a dispute and human-review path), sexualised or body-altering edits of real people, third parties in photos
  (obscure or require their consent), fake endorsements and fake customer testimonials.
- **Moderation and CSAM:** image classifiers on inputs and outputs through the existing `ExternalSafetyClassifier` SPI
  (extended to image inputs); hash matching where legally appropriate with a block, preserve and report workflow;
  human review queue for CSAM suspicion, face-match mismatches and disputes, with access logging. Text found inside an
  uploaded image is untrusted data, never instructions.
- **Biometric handling:** face embeddings for the consent check are computed **in the browser** by default
  (`web/app/lib/edge`) and the server stores only a consent verdict and a salted hash, unless legal review approves
  server-side storage. Faces, embeddings and character models are encrypted per tenant, are never used for training by us
  or any vendor (contractual and technical settings), have a published retention schedule, and are deletable.
- **Revocation and deletion:** revoking consent blocks new generation immediately and queues deletion of derived assets,
  character models and embeddings (target: block in under 24 hours). A takedown intake works without an account.
  Every new table is added to `TenantDataErasureService` and `TenantDataExportService` (the repo's erasure list already
  misses the video job table; fix that as part of this work).

### 3.7 Fidelity and quality gates (the product differentiator)

No ad ships unless it passes automated checks against the customer's real inputs:

- product, logo and packaging match the SKU photo (segmentation plus texture comparison),
- text in the creative equals the required copy by OCR, per language and script (**text is composited by the template
  engine, never rendered by a diffusion model**),
- identity score (face similarity) against the consented source photos,
- garment fidelity for try-on (pattern, colour, logo),
- artifact detectors (hands, faces), NSFW and safety, and brand rules.

Failures auto-repair or escalate; **failed generations are not billed to the customer** (a stated competitor pain
point). Quality is also measured on our own evaluation set (3.10), because public arenas do not test identity or garment
fidelity.

### 3.8 Provenance and labelling

Every output carries **two layers**: a signed C2PA manifest (action assertions, IPTC digital-source-type) and an
invisible watermark with a soft-binding record so credentials can be recovered after re-encoding. A verifier endpoint
confirms whether a file came from us. Export offers a **visible label** and platform-specific disclosure checklists
(EU, India, New York, Meta, Google, TikTok), on by default for photoreal people in regulated regions. There is **no
feature to strip metadata or labels**. We test watermark survival through real social re-encoding.

### 3.9 Governance reuse

- **Approvals** (four-eyes): action types `adstudio.use_likeness`, `adstudio.publish`, `adstudio.export_to_ad_platform`
  via `HumanApprovalGateService` and `ApprovalActionExecutor`. Note the existing tenant-admin self-approval exception.
- **Audit:** consent events, uploads, generations (input hash, model, prompt, output hash), gate decisions, deletions,
  through `AuditLogService` and the seal.
- **Effect journal:** publishing is classified as an irreversible, consequential tool so agents cannot double-publish.
- **Budgets:** a per-job **credit ledger** (reserve, then settle) with caps per plan, modality and tenant, on top of
  the existing quota and `PlatformFeatureBudgetService` patterns, plus the kill switch.
- **Agents:** the planning agent that builds a campaign from a product URL uses these services as tools, inside the same
  governance.

### 3.10 Evaluation

A fixed evaluation set (consenting test subjects, garments, products in several scripts) and a harness that scores
identity similarity, garment fidelity, OCR accuracy, realism preference (VLM judge calibrated by periodic human pairwise
studies) and cost. It runs before any model enters or changes a default route and monthly, because leaderboard leaders
moved within weeks. FID and CLIP score are sanity checks only.

### 3.11 Data model (new tables, tenant schema, prefix `adstudio_`)

`subject`, `consent`, `consent_evidence`, `likeness_use_log`, `asset` (sha256, mime, lineage to parent assets, subject
links, licence, safety verdict, retention class), `character` (reference set, LoRA reference, consent link, status),
`brand_kit` (colours, fonts, voice, logo rules), `product` (SKU photos, claims), `template`, `campaign`, `creative`,
`variant`, `job`, `job_step` (type, provider, model version, cost, gate scores), `publish_target`, `credit_ledger`.
Migration numbers are taken from the single Flyway namespace (`scripts/ci/flyway-lint.sh`).

### 3.12 Web console

A new `/ad-studio` area using the existing tokens and components (no new UI framework): shared upload-and-crop widget,
a **mask and brush canvas** (none exists today), a before and after compare slider, a consent capture flow, a brand-kit
and product library, a campaign board with variants and gate results, an approval and publish step, and live job
progress by SSE (media jobs are polled today). Heavy local steps run in web workers following the existing
`web/app/lib/edge` pattern, with capability detection (WebGPU, buffer limits) and automatic server fallback. The i18n
route namespaces are regenerated for the new route.

## 4. Compute tiers per capability

Cheapest option that is genuinely good enough in late 2026, and where paying is unavoidable (all unmeasured here; see
section 7):

| Capability | Cheapest adequate | Pay for GPU or premium when |
|---|---|---|
| Mask and segmentation | Browser small model (SAM 2 class, Apache) | Fine hair, transparent fabric, crowds: server SAM 2 |
| Background removal | Browser BiRefNet-lite (MIT); not RMBG-2.0 | Hair edges at high resolution |
| Try-on | No clean open commercial model exists. Prompted open edit model (Qwen-Image-Edit-2511, FLUX.2 klein 4B) on serverless GPU, or our own trained model; FASHN's open weights (Apache, per its own comparison) | Fabric, logo and pattern fidelity: dedicated try-on API |
| Character and twin | Multi-reference edit models; per-person LoRA on an Apache base for a consenting subject | Strict identity across scenes |
| Ad image | Template composite plus a cheap generated background | Hero photoreal people shots: premium edit model |
| Text in the image | Template engine (never a model) | Never |
| Video (15 s) | 2.5D motion from stills in browser or CPU; open video model on serverless GPU | Realistic people motion and lip-sync: premium video and avatar APIs |
| Upscaling | Licence-clear model on CPU or small GPU | Print or 4K delivery |

Rough cost per finished item, from secondary prices and **our own arithmetic** (wide uncertainty, excludes retries of
2 to 5 times on people shots): free or browser-heavy about zero server cost; balanced a fraction of a cent for an image
and cents for a short clip on serverless GPU; premium roughly 0.3 to 0.5 dollars per approved image and 3 to 12 dollars
per 15-second video.

## 5. Product scope

| Version | Scope |
|---|---|
| **v1 (small-business wedge plus trust)** | Product URL or phone photos to static ads and short vertical video; templates, brand kit, formats and safe zones; fidelity gate (OCR, logo, product); automatic labels and two-layer provenance; Hindi plus a few Indian languages; approvals and audit; export and publish to Meta; UPI billing; budget caps; BYOK and custom-provider support. **No photoreal talking-head avatars until the consent registry ships.** |
| **v2 (rights and agents)** | Consent registry and verified digital twins; try-on with garment-fidelity scoring; masking canvas; multi-channel planning agent (Meta, Google and YouTube, TikTok); rule packs (ASCI, New York, California, EU); closed-loop testing with holdouts; agency multi-brand workspaces; WhatsApp approvals |
| **v3 (enterprise and platform)** | Air-gapped deployment with customer-hosted models; lift measurement; marketplace of brand rule packs and signed providers; more ad-platform connectors; MCP surface; optional human-review network |

## 6. Phasing and kill criteria

1. **Phase 0 (foundations, no people yet):** the `MediaProvider` SPI and registry, the model registry with licence and
   policy metadata, `SsrfGuard` for result-URL downloads, the job table and runner, the credit ledger, GPU scheduling in
   the Helm library, and the evaluation harness. *Kill if:* the SPI cannot express two real providers and a custom HTTP
   provider without core changes.
2. **Phase 1 (v1 without faces):** product-photo ads end to end, template engine, fidelity gate, provenance, approvals,
   Meta publish. *Kill if:* the fidelity gate cannot hold a measured text-and-logo accuracy target on the evaluation set.
3. **Phase 2 (people):** `LikenessGate`, consent capture, moderation, minors and public-figure blocks, revocation and
   deletion, then try-on and characters. **No customer access until counsel has verified the launch-blocker legal
   claims against primary text.** *Kill if:* consent capture can be defeated in the red-team suite.
4. **Phase 3:** video and avatars, agents, more connectors, v3 items.

## 7. What is not proven

- No prototype exists. Every number in this document is from secondary research or our own arithmetic.
- Prices, licences and platform terms were mostly read from aggregators and blogs. Several items could not be
  verified at all (listed in the appendix). Licences must be read on the primary page, with counsel for anything
  ambiguous (for example the Meta SAM licence, FLUX terms, and Qwen-Image 2.1).
- No in-browser latency was found for segmentation or diffusion models on mid-range phones, and no CPU timings for the
  large models.
- Whether hosted providers will accept a given consented real-person flow is unknown until tested; the router
  must be built assuming refusals.
- Legal conclusions are not legal advice. The applicability of the US TAKE IT DOWN Act and the New York knowledge
  standard to this product is open.
- The quality of open try-on and identity methods for photoreal ads is unverified.

## 8. Decisions needed from the product owner

1. Approve the name and shape (Ad Studio, separate services, core stays source of truth).
2. Approve **v1 without people** and the consent registry before any real-face workflow ships.
3. Choose the GPU strategy for Phase 0 and 1: serverless GPU vendor first (recommended) or in-cluster GPU pool.
4. Name counsel and a date for verifying the launch-blocker legal items.
5. Decide whether server-side storage of face embeddings is ever allowed (default: no; browser-only).
6. Confirm the first publishing target (Meta, per the research) and the first languages.

## 9. Alternatives considered

- **Extend the existing Studio and orchestrator in place.** Rejected: the orchestrator hard-codes two BYOK providers and
  is a heavily commented hot path; `Studio*` names collide.
- **One premium vendor end to end.** Rejected: leaders move within weeks, real-person policies differ and change, and it
  makes both cost and consent dependent on one provider.
- **Browser-only generation.** Rejected as the default: phone memory and model size limit it to perception-style steps;
  it is kept as a tier and a privacy feature.
- **Self-host everything on owned GPUs.** Rejected for v1: utilisation is low for bursty ad work and it puts all safety
  and licence risk on us at once; the router keeps it as an option.
- **Add media vendors to `ProviderName`.** Rejected: closed enum, wide blast radius, contrary to ADR-0011's direction.
