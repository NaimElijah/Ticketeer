package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.sales.domain.CartLineItem;

// The hold window is static, process-wide state: restore it so no other test sees this class's value.
class CartLineItemTest {

    private Duration originalLimit;

    @BeforeEach
    void rememberLimit() {
        originalLimit = CartLineItem.getExpirationLimit();
    }

    @AfterEach
    void restoreLimit() {
        CartLineItem.setExpirationLimit(originalLimit);
    }

    @Test
    void GivenPositiveHold_WhenSetExpirationLimit_ThenApplied() {
        CartLineItem.setExpirationLimit(Duration.ofMinutes(25));

        assertEquals(Duration.ofMinutes(25), CartLineItem.getExpirationLimit());
    }

    @Test
    void GivenZeroHold_WhenSetExpirationLimit_ThenThrowsAndKeepsCurrentLimit() {
        assertThrows(IllegalArgumentException.class, () -> CartLineItem.setExpirationLimit(Duration.ZERO));

        assertEquals(originalLimit, CartLineItem.getExpirationLimit());
    }

    @Test
    void GivenNegativeHold_WhenSetExpirationLimit_ThenThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> CartLineItem.setExpirationLimit(Duration.ofMinutes(-5)));
    }

    @Test
    void GivenNullHold_WhenSetExpirationLimit_ThenThrows() {
        assertThrows(IllegalArgumentException.class, () -> CartLineItem.setExpirationLimit(null));
    }
}
