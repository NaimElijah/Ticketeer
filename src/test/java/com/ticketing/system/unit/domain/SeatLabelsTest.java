package com.ticketing.system.unit.domain;

import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.domain.SeatLabels;

import static org.junit.jupiter.api.Assertions.*;

public class SeatLabelsTest {

    @Test
    void GivenSingleLetterRange_WhenRowLabel_ThenReturnsAToZ() {
        assertEquals("A", SeatLabels.rowLabel(0));
        assertEquals("B", SeatLabels.rowLabel(1));
        assertEquals("Z", SeatLabels.rowLabel(25));
    }

    @Test
    void GivenIndexPastZ_WhenRowLabel_ThenRollsOverToDoubleLetters() {
        assertEquals("AA", SeatLabels.rowLabel(26));
        assertEquals("AB", SeatLabels.rowLabel(27));
        assertEquals("AZ", SeatLabels.rowLabel(51));
        assertEquals("BA", SeatLabels.rowLabel(52));
        assertEquals("ZZ", SeatLabels.rowLabel(701));
        assertEquals("AAA", SeatLabels.rowLabel(702));
    }

    @Test
    void GivenNegativeIndex_WhenRowLabel_ThenThrows() {
        assertThrows(IllegalArgumentException.class, () -> SeatLabels.rowLabel(-1));
    }
}
