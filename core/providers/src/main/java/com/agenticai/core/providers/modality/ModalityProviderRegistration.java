package com.agenticai.core.providers.modality;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Shared registration step for the modality provider configurations (ADR-0011
 * phase 2). Registers every discovered provider bean with its factory, keyed by
 * the provider's own id, and fails fast at startup if two beans claim the same
 * id - with named constructor wiring that could not happen, with discovery by
 * type it could, and a silent last-one-wins would make routing depend on bean
 * iteration order.
 */
final class ModalityProviderRegistration {

    private ModalityProviderRegistration() {}

    static <P, K> void registerAll(String kind, List<P> providers, Function<P, K> idOf, Consumer<P> register) {
        Map<K, P> seen = new HashMap<>();
        for (P provider : providers) {
            K id = idOf.apply(provider);
            P previous = seen.putIfAbsent(id, provider);
            if (previous != null) {
                throw new IllegalStateException("Two " + kind + " beans claim provider id " + id + ": "
                    + previous.getClass().getName() + " and " + provider.getClass().getName());
            }
            register.accept(provider);
        }
    }
}
