package com.agenticai.core.dbauth;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredentialBuilder;
import java.time.Duration;

/**
 * Microsoft Entra access token for Azure Database for PostgreSQL Flexible Server, used as the
 * password. The credential chain is {@code DefaultAzureCredential} (AKS workload identity, managed
 * identity, environment, {@code az login}). The Entra principal must be created as a database role
 * on the server and its name is {@code POSTGRES_USER}. Loaded only when {@code
 * app.datasource.auth=azure-entra}.
 */
final class AzureEntraTokenSource implements DbTokenSource {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final TokenCredential credential;
    private final TokenRequestContext request;

    private AzureEntraTokenSource(TokenCredential credential, String scope) {
        this.credential = credential;
        this.request = new TokenRequestContext().addScopes(scope);
    }

    static DbTokenSource create(String scope) {
        return new AzureEntraTokenSource(new DefaultAzureCredentialBuilder().build(), scope);
    }

    @Override
    public DbToken fetch() {
        AccessToken token = credential.getToken(request).block(TIMEOUT);
        if (token == null) {
            throw new IllegalStateException("Azure credential returned no access token");
        }
        return new DbToken(token.getToken(), token.getExpiresAt().toInstant());
    }
}
