package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@link ExpressionScopeContext}: the ThreadLocal bind/clear contract. */
class ExpressionScopeContextTest {

    @AfterEach
    void unbind() {
        ExpressionScopeContext.clear();
    }

    @Test
    void nothingIsBoundByDefault() {
        assertNull(ExpressionScopeContext.current());
    }

    @Test
    void bindStoresTheScopeAndClearRemovesIt() {
        ExpressionScope scope = ExpressionScope.iteration("i", 0, null);
        ExpressionScopeContext.bind(scope);
        assertSame(scope, ExpressionScopeContext.current());
        ExpressionScopeContext.clear();
        assertNull(ExpressionScopeContext.current());
    }

    @Test
    void bindingNullIsAClear() {
        ExpressionScopeContext.bind(ExpressionScope.test());
        ExpressionScopeContext.bind(null);
        assertNull(ExpressionScopeContext.current());
    }
}
