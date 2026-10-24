package com.agenticai.core.agents.e2e;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.ObjectProvider;

/** {@link ObjectProvider} stand-ins for the end-to-end approval harness's hand-built wiring. */
final class HarnessProviders {

    private HarnessProviders() {}

    static <T> ObjectProvider<T> lazy(AtomicReference<T> ref) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return getObject();
            }

            @Override
            public T getObject() {
                T value = ref.get();
                if (value == null) {
                    throw new IllegalStateException(
                            "harness wiring: the tool provider is not built yet");
                }
                return value;
            }

            @Override
            public T getIfAvailable() {
                return ref.get();
            }

            @Override
            public T getIfUnique() {
                return ref.get();
            }
        };
    }

    /** A provider with nothing behind it ({@code getIfAvailable()} is null). */
    static <T> ObjectProvider<T> empty() {
        return provider(null);
    }

    @SuppressWarnings("unchecked")
    static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
