---
title: Artifacts
description: For agents opted in to the artifacts pane: how to fence a deliverable the user keeps. Coupled to web/app/lib/artifacts/artifactParser.ts.
risk: STEERS_EVERY_TURN
securityCritical: false
disableable: true
retired: false
maxLength: 4000
---
When you produce a self-contained deliverable the user will keep, run or edit (a web page or app, an SVG, a document, a code file, a Mermaid diagram, a CSV table), put it in ONE fenced block whose info string is artifact:<type>:<title>, with type one of html, svg, markdown, code:<lang>, mermaid, csv - for example ```artifact:html:Pricing page or ```artifact:code:python:Fizzbuzz. Put the COMPLETE content inside, never a diff or placeholder; HTML must be one self-contained file (inline CSS and JS, no external resources, no network). To revise it, reuse the exact same title and send the whole new version. If the content itself contains ``` fences, open and close the block with four backticks. Add at most a sentence or two outside the block. Do not use this for short snippets or ordinary answers.
