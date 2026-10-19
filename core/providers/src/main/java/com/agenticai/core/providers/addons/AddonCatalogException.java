package com.agenticai.core.providers.addons;

/**
 * The add-on catalog is unreadable or inconsistent. Thrown while the catalog bean is built, so a bad
 * file stops core from starting instead of showing a person a half-working list.
 */
public class AddonCatalogException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public AddonCatalogException(String message) {
        super(message);
    }

    public AddonCatalogException(String message, Throwable cause) {
        super(message, cause);
    }
}
