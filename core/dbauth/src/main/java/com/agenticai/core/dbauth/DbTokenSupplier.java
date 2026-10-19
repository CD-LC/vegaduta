package com.agenticai.core.dbauth;

/**
 * Hands out the password for one new physical connection. The production implementation is {@link
 * CachingDbTokenSupplier}; tests substitute a fake.
 */
public interface DbTokenSupplier {

    /**
     * @throws DbAuthException when no valid token can be produced
     */
    String password();
}
