package com.agenticai.core.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * HTTP polling trigger config (V482), written via {@code PUT
 * /api/workflows/{id}/nodes/{nodeId}/poll-trigger}. The service validates the
 * URL through the SSRF guard, the interval against {@code
 * app.workflow.poll.min/max-interval-seconds}, and the paths against the
 * column caps - this record only carries the raw fields.
 *
 * @param url            http(s) endpoint to poll (SSRF-guarded; redirects are
 *                       never followed, so configure the final URL)
 * @param method         GET (default) or POST
 * @param headers        request headers - can carry an Authorization header or
 *                       API key; stored AES-256-GCM-encrypted and never
 *                       returned by any read API
 * @param intervalSeconds poll cadence (default 300, minimum 60 - the ticker's
 *                       tick floor)
 * @param itemsPath      dot path to the item array in the response
 *                       ({@code "data.items"}); empty = the response itself
 *                       (a bare array, or a single object as one item)
 * @param idPath         dot path to each item's dedup id (default {@code
 *                       "id"}); missing/unresolvable falls back to a content
 *                       hash of the item
 * @param seedOnEnable   when true (default) the FIRST poll records the current
 *                       items without firing runs - only items appearing
 *                       after that fire. Set false to fire on the existing
 *                       backlog once.
 */
public record WorkflowPollTriggerRequest(
    @NotBlank @Size(max = 4096) String url,
    @Size(max = 8) String method,
    Map<String, String> headers,
    Integer intervalSeconds,
    @Size(max = 256) String itemsPath,
    @Size(max = 256) String idPath,
    Boolean seedOnEnable
) {
}
