---
title: Unavailable tool: image generation
description: Tool result when the model named generate_image but the tool was not offered to it in this conversation (image generation is not switched on). The tool's own not-configured answer is toolresult.image_generation.not_configured.
risk: STEERS_EVERY_TURN
securityCritical: false
disableable: false
---
Image generation is not switched on for this workspace, so this tool cannot run. It needs an OpenAI API key to be added and activated on the Providers page of the console (image generation reuses the same OpenAI key as chat). Tell the user exactly that, in their own language, and answer the rest of their message normally. Do not call this tool again in this conversation.
