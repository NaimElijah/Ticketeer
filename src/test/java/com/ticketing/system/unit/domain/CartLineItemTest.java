package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.ValueSource;

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

    // ---- what a cart line must be (#189) ----

    private static final int EVENT_ID = 10;
    private static final int ZONE_ID = 5;
    private static final double PRICE = 120.0;
    private static final LocalDateTime ADDED_AT = LocalDateTime.of(2027, 7, 15, 17, 0);

    @Test
    void GivenANegativePrice_WhenConstructed_ThenRefused() {
        assertThrows(IllegalStateException.class, () -> new CartLineItem(EVENT_ID, ZONE_ID, "A1", -0.01, ADDED_AT));
    }

    // A free ticket is a ticket: its refund completes with no money moved (OrderReceipt).
    @Test
    void GivenAZeroPrice_WhenConstructed_ThenAccepted() {
        CartLineItem free = new CartLineItem(EVENT_ID, ZONE_ID, "A1", 0.0, ADDED_AT);

        assertEquals(0.0, free.getPriceAtReservation());
    }

    @ParameterizedTest(name = "eventId={0}")
    @ValueSource(ints = {0, -1})
    void GivenAnEventIdBelowOne_WhenConstructed_ThenRefused(int eventId) {
        assertThrows(IllegalStateException.class, () -> new CartLineItem(eventId, ZONE_ID, "A1", PRICE, ADDED_AT));
    }

    @Test
    void GivenANegativeZoneId_WhenConstructed_ThenRefused() {
        assertThrows(IllegalStateException.class, () -> new CartLineItem(EVENT_ID, -1, "A1", PRICE, ADDED_AT));
    }

    // The hold is measured from addedAt, so a line without it would never expire.
    @Test
    void GivenNoAddedAt_WhenConstructed_ThenRefused() {
        assertThrows(IllegalStateException.class, () -> new CartLineItem(EVENT_ID, ZONE_ID, "A1", PRICE, null));
    }

    // A null seat is a standing place; a blank one names no seat.
    @ParameterizedTest(name = "seat=''{0}''")
    @EmptySource
    @ValueSource(strings = {"   "})
    void GivenABlankSeat_WhenConstructed_ThenRefused(String blankSeat) {
        assertThrows(IllegalStateException.class,
                () -> new CartLineItem(EVENT_ID, ZONE_ID, blankSeat, PRICE, ADDED_AT));
    }
}
