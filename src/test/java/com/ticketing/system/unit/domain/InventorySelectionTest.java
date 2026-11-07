package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.ticketing.system.catalog.domain.InventorySelection;

/**
 * {@link InventorySelection}'s factories refuse a selection no zone could serve, before any zone sees it: a
 * standing quantity below 1, and a seated selection that is empty, repeats a seat or names a blank one. The
 * zones' own quantity and duplicate checks are therefore unreachable through the factories (#189).
 */
class InventorySelectionTest {

    private static final String ORDER_KEY = "order-1";

    @ParameterizedTest(name = "quantity={0}")
    @ValueSource(ints = {0, -1})
    void GivenAQuantityBelowOne_WhenStandingSelectionBuilt_ThenRefused(int quantity) {
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> InventorySelection.standing(quantity));

        assertEquals("Quantity must be positive", refused.getMessage());
    }

    @ParameterizedTest(name = "quantity={0}")
    @ValueSource(ints = {0, -1})
    void GivenAQuantityBelowOne_WhenStandingSelectionBuiltForAnOrder_ThenRefused(int quantity) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> InventorySelection.standing(quantity, ORDER_KEY));

        assertEquals("Quantity must be positive", refused.getMessage());
    }

    @Test
    void GivenOnePlace_WhenStandingSelectionBuiltForAnOrder_ThenItIsAStandingSelectionOfOneForThatOrder() {
        InventorySelection selection = InventorySelection.standing(1, ORDER_KEY);

        assertTrue(selection.isStandingSelection());
        assertEquals(1, selection.getQuantity());
        assertEquals(ORDER_KEY, selection.getOrderKey());
        assertEquals(List.of(), selection.getSeatNumbers());
    }

    @Test
    void GivenTheSameSeatTwice_WhenSeatedSelectionBuilt_ThenRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> InventorySelection.seated(List.of("A1", "A1")));

        assertEquals("Duplicate seat numbers are not allowed", refused.getMessage());
    }

    @Test
    void GivenTheSameSeatTwice_WhenSeatedSelectionBuiltForAnOrder_ThenRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> InventorySelection.seated(List.of("A1", "A1"), ORDER_KEY));

        assertEquals("Duplicate seat numbers are not allowed", refused.getMessage());
    }

    @ParameterizedTest
    @NullAndEmptySource
    void GivenNoSeats_WhenSeatedSelectionBuiltForAnOrder_ThenRefused(List<String> seats) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> InventorySelection.seated(seats, ORDER_KEY));

        assertEquals("Seat numbers must be non-empty", refused.getMessage());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void GivenABlankSeat_WhenSeatedSelectionBuiltForAnOrder_ThenRefused(String blankSeat) {
        List<String> seats = Arrays.asList("A1", blankSeat);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> InventorySelection.seated(seats, ORDER_KEY));

        assertEquals("Seat number must be non-blank", refused.getMessage());
    }

    @Test
    void GivenTwoSeats_WhenSeatedSelectionBuiltForAnOrder_ThenItCarriesBothAndAQuantityOfTwo() {
        InventorySelection selection = InventorySelection.seated(List.of("A2", "A1"), ORDER_KEY);

        assertTrue(selection.isSeatedSelection());
        assertEquals(List.of("A2", "A1"), selection.getSeatNumbers());
        assertEquals(2, selection.getQuantity());
        assertEquals(ORDER_KEY, selection.getOrderKey());
    }
}
