package com.agenticai.core.security.oauth;

/** SEC-018: an OAuth callback's state is not bound to (or no longer bound to) the session that started it. */
public class OAuthStateBindingException extends RuntimeException {

    public OAuthStateBindingException(String message) {
        super(message);
    }
}
