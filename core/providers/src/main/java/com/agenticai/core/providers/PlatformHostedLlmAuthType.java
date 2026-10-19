package com.agenticai.core.providers;

/**
 * The three ways a {@link PlatformHostedLlmProvider} row can be activated -
 * stored as plain text in {@link PlatformHostedLlmProvider#getAuthType()},
 * deliberately NOT a JPA {@code @Enumerated} type, same "an unrecognized
 * value never fails to load a row" posture {@link
 * PlatformHostedLlmProvider#getProviderName()} already establishes.
 *
 * <ul>
 *   <li>{@link #API_KEY} - the original/default behaviour: a pasted bearer
 *   token, validated with a live {@code GET {baseUrl}/v1/models} probe. Every
 *   pre-existing row (and every row with a {@code null}/blank {@code
 *   auth_type}, e.g. one written before this column existed) is treated as
 *   this type - see {@link #normalize}.
 *   <li>{@link #VERTEX_ADC} - Google Vertex AI, authenticated via whatever
 *   Application Default Credentials the running container already has
 *   (workload identity, attached service account, or a local {@code gcloud
 *   auth application-default login} file) - no secret stored on the row at
 *   all. Requires {@link PlatformHostedLlmProvider#getVertexProjectId()}.
 *   <li>{@link #VERTEX_SERVICE_ACCOUNT} - Google Vertex AI, authenticated via
 *   a pasted GCP service-account JSON key. The JSON is encrypted and stored
 *   in the same {@code encrypted_api_key} column a plain API key would use
 *   (see {@link PlatformHostedLlmProviderService#create}) rather than a
 *   second secret column. Also requires {@link
 *   PlatformHostedLlmProvider#getVertexProjectId()}.
 * </ul>
 */
public final class PlatformHostedLlmAuthType {

    public static final String API_KEY = "API_KEY";
    public static final String VERTEX_ADC = "VERTEX_ADC";
    public static final String VERTEX_SERVICE_ACCOUNT = "VERTEX_SERVICE_ACCOUNT";

    /** Sensible default location for a Vertex row that didn't specify one - see {@link PlatformHostedLlmProvider#getVertexLocation()}. */
    public static final String DEFAULT_VERTEX_LOCATION = "us-central1";

    private PlatformHostedLlmAuthType() {
        // constants only
    }

    /** Null/blank collapses to {@link #API_KEY} - the full-backward-compat default for every row that predates this column. */
    public static String normalize(String rawAuthType) {
        return (rawAuthType == null || rawAuthType.isBlank()) ? API_KEY : rawAuthType.trim();
    }

    public static boolean isVertex(String rawAuthType) {
        String normalized = normalize(rawAuthType);
        return VERTEX_ADC.equals(normalized) || VERTEX_SERVICE_ACCOUNT.equals(normalized);
    }
}
