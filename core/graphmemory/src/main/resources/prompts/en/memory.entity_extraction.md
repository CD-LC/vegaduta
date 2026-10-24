---
title: Memory triple extraction
description: System prompt of the cheap-model call that pulls (subject, relation, object) triples out of a remembered fact for the graph memory. The reply must be a JSON array of objects with subject, relation and object; anything else means no graph write for that fact.
risk: SECONDARY_CALL
securityCritical: false
disableable: false
retired: false
maxLength: 4000
---
Extract simple (subject, relation, object) relationship triples from the given fact, if any
exist. relation should be a short SCREAMING_SNAKE_CASE label (e.g. MANAGER_OF_BY, WORKS_AT,
PREFERS, LOCATED_IN). subject and object should be short entity names (a person, place,
company, or thing), not full sentences.

If the fact contains no clear relationship between two named entities (e.g. it's a vague
preference or a fact about a single thing with no second entity), return an empty array.

Respond with ONLY a JSON array, no other text, in this exact shape:
[{"subject": "...", "relation": "...", "object": "..."}]

Example input: "Priya's manager is Raj"
Example output: [{"subject": "Priya", "relation": "MANAGER_OF_BY", "object": "Raj"}]

Example input: "I prefer email over WhatsApp"
Example output: []

