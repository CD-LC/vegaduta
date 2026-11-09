package com.agenticai.core.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;

/**
 * The settings advice must resolve before the app's generic advice. That one is {@code @Order(LOWEST_PRECEDENCE)}
 * and handles {@code Exception}, so an unordered settings advice (also lowest precedence) can lose the tie and a
 * {@code SettingInvalidException} comes back as a 500 instead of the documented 400 {@code SETTING_INVALID}.
 */
class SettingsExceptionHandlerOrderTest {

    @Test
    void theSettingsAdviceDeclaresAnExplicitHighestPrecedenceOrder() {
        Integer order = OrderUtils.getOrder(SettingsExceptionHandler.class);

        assertNotNull(order, "SettingsExceptionHandler must declare an explicit @Order");
        assertEquals(Ordered.HIGHEST_PRECEDENCE, order.intValue());
    }
}
