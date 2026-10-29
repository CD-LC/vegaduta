# Licenses

VegaDūta itself is licensed under the Business Source License 1.1
([../LICENSE](../LICENSE), also [BUSL-1.1.txt](BUSL-1.1.txt)): free for any
use except offering it to third parties as a competing hosted service, and it
converts to Apache-2.0 four years after each version's release. The `sdk/`
directory is separately licensed under Apache-2.0. This folder
holds the full text of every license that applies to this project and to
third-party files redistributed in this repository or copied into the release
images. Copyright lines and attributions are in [../NOTICE](../NOTICE).

| License | Text | Applies to |
|---|---|---|
| BUSL-1.1 | [BUSL-1.1.txt](BUSL-1.1.txt) | this project outside `sdk/` |
| Apache-2.0 | [Apache-2.0.txt](Apache-2.0.txt) | the `sdk/` directory; `@mlc-ai/web-llm`, `kokoro-js` and `@huggingface/transformers` copied into `web/public/vendored/` at build time; the `hyperframes`, `hyperframes-cli` and `media-use` built-in agent skills under `core/agents-turn/src/main/resources/skills/` (Copyright HeyGen, github.com/heygen-com/hyperframes) |
| MIT | [MIT.txt](MIT.txt) | `web/app/lib/vendor/qrcode-generator.js` (Copyright (c) 2009 Kazuhiko Arase); `@babel/standalone` and `onnxruntime-web` (inside `kokoro.web.js`) copied into `web/public/vendored/`; the `security-audit` built-in agent skill under `core/agents-turn/src/main/resources/skills/` (Copyright (c) 2025-2026 Cloudflare, Inc., github.com/cloudflare/security-audit-skill) |
| OFL-1.1 | [OFL-1.1.txt](OFL-1.1.txt) | every font file listed below |

The MIT text here is the SPDX template: the copyright line of each MIT
component is the one in NOTICE and in the component's own header.

## Font files

All fonts are licensed under the SIL Open Font License 1.1. The OFL lets the
fonts be bundled and redistributed with software, provided the copyright
notice and the license travel with them and they are not sold on their own.

| Files | Family | Copyright |
|---|---|---|
| `deploy/keycloak/themes/agentic-ai/account/resources/fonts/geist-latin.woff2`, `geist-latin-ext.woff2` | Geist | Copyright 2024 The Geist Project Authors (https://github.com/vercel/geist-font) |
| `deploy/keycloak/themes/agentic-ai/account/resources/fonts/geist-mono-latin.woff2`, `geist-mono-latin-ext.woff2` | Geist Mono | Copyright 2024 The Geist Project Authors (https://github.com/vercel/geist-font) |
| `deploy/keycloak/themes/agentic-ai/account/resources/fonts/yatra-one-latin.woff2`, `yatra-one-latin-ext.woff2` | Yatra One | Copyright 2014 The Yatra Project Authors |
| `web/scripts/landing-media/fonts/Inter-{400,500,600}-latin.woff2` | Inter | Copyright 2016 The Inter Project Authors (https://github.com/rsms/inter) |
| `web/scripts/landing-media/fonts/JetBrainsMono-{400,500}-latin.woff2` | JetBrains Mono | Copyright 2020 The JetBrains Mono Project Authors (https://github.com/JetBrains/JetBrainsMono) |
| `web/scripts/landing-media/fonts/NotoSansDevanagari-{400,500,600}-{latin,devanagari}.woff2` | Noto Sans Devanagari | Copyright 2022 The Noto Project Authors (https://github.com/notofonts/devanagari) |
| `web/scripts/landing-media/fonts/Sora-{600,700}-latin.woff2` | Sora | Copyright 2019 The Sora Project Authors (https://github.com/sora-xor/sora-font) |
| `web/app/fonts/geist.woff2`, `geist-mono.woff2`; `web-admin/app/fonts/geist.woff2`, `geist-mono.woff2` | Geist, Geist Mono | Copyright 2024 The Geist Project Authors (https://github.com/vercel/geist-font) |
| `web/app/fonts/yatra-one.woff2`, `web-admin/app/fonts/yatra-one.woff2` | Yatra One | Copyright 2014 The Yatra Project Authors |
| `web/app/fonts/noto-sans-devanagari.woff2` | Noto Sans Devanagari | Copyright 2022 The Noto Project Authors (https://github.com/notofonts/devanagari) |
| `web/app/fonts/plus-jakarta-sans.woff2` | Plus Jakarta Sans | Copyright 2020 The Plus Jakarta Sans Project Authors (https://github.com/tokotype/PlusJakartaSans) |
| `web/app/fonts/dm-mono-400.woff2`, `dm-mono-500.woff2` | DM Mono | Copyright 2020 The DM Mono Project Authors (https://www.github.com/googlefonts/dm-mono) |

The `web/app/fonts/` and `web-admin/app/fonts/` files are subsets of the upstream fonts in
[google/fonts](https://github.com/google/fonts), built by `scripts/dev/build-web-fonts.py`
(pinned commit; the OFL copyright and license name records are kept in each file).

The copyright lines above were read from each file's embedded `name` table.

## Trademarks

The logos in `web/public/logos/` are trademarks of their owners and are not
licensed under any of the licenses here; see NOTICE.
