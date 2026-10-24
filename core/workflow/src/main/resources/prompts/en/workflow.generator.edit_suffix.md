---
title: Workflow generator: edit request suffix
description: Appended to the user's message when the generator changes an existing workflow: the instruction is a change request for {current_graph}, which must be returned complete.
risk: SECONDARY_CALL
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
placeholders: current_graph!
placeholder.current_graph: The current graph as JSON (credentials already hidden)
---


CURRENT GRAPH - the instruction above is a CHANGE REQUEST for this existing workflow, not a description of a new one. Modify this graph: keep every node and edge the request does not concern exactly as it is (same "id", "type", "position", "label" and "config"), reuse existing ids, give new nodes and edges new unique ids, and return the COMPLETE updated graph in the usual JSON shape (not a diff):
{current_graph}
