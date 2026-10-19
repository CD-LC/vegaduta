package com.agenticai.core.dbauth;

/**
 * Fetches a brand-new token from the cloud (one network or signing call). Implementations do not
 * cache.
 */
public interface DbTokenSource {

    /**
     * @throws RuntimeException when the cloud credential chain or the token call fails
     */
    DbToken fetch();
}
