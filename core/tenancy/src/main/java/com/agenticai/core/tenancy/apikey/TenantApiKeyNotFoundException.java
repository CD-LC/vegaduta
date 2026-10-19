package com.agenticai.core.tenancy.apikey;

import com.agenticai.core.security.http.HttpStatusMapped;
import java.util.UUID;

/** A tenant API key id that does not resolve in the current tenant schema - 404 via {@link HttpStatusMapped}. */
public class TenantApiKeyNotFoundException extends RuntimeException implements HttpStatusMapped {

    public TenantApiKeyNotFoundException(UUID id) {
        super("API key not found: " + id);
    }

    @Override
    public int httpStatus() {
        return 404;
    }

    @Override
    public String errorCode() {
        return "api_key_not_found";
    }
}
