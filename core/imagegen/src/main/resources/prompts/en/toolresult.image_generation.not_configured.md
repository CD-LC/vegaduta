---
title: Tool result: image generation not switched on
description: The error text generate_image returns when the tool ran but no image provider (OpenAI or Google Vertex AI) is active or configured for this workspace. The chat module has its own key for a generate_image call that was never offered.
risk: STEERS_EVERY_TURN
securityCritical: false
disableable: false
retired: false
maxLength: 4000
---
Image generation is not switched on for this workspace. It needs an OpenAI or Google Vertex AI credential to be added and activated on the Providers page of the console (image generation reuses whichever of those keys is already active for chat). Tell the user exactly that, and do not retry the tool.