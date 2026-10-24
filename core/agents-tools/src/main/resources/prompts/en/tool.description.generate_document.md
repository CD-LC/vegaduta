---
title: Tool description: generate_document
description: The description of the generate_document tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Generates a downloadable document file (Word/DOCX, PDF, Excel/XLSX, PowerPoint/PPTX, CSV, plain text, Markdown, or JSON) from structured content and returns a downloadUrl. A section may carry imageUrl - a public http(s) image (png/jpeg) downloaded and embedded into the deck. This tool cannot return file bytes directly, so to give the user the file you MUST include the returned downloadUrl verbatim in your reply. Use it when the user asks to create, generate, write, export, or produce a document, report, spreadsheet, slide deck, or file they can download.
