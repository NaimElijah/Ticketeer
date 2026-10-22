package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.domain.ShowDate;

class ShowDateTest {

    private static final LocalDateTime START = LocalDateTime.of(2027, 7, 15, 20, 0);
    private static final LocalDateTime END = LocalDateTime.of(2027, 7, 15, 23, 0);

    // A show that has already happened is still a well-formed show; only scheduling one is refused.
    @Test
    void GivenTimesInThePast_WhenConstructed_ThenAccepted() {
        assertDoesNotThrow(() -> new ShowDate(LocalDateTime.of(2001, 1, 1, 20, 0), LocalDateTime.of(2001, 1, 1, 23, 0)));
    }

    @Test
    void GivenEndNotAfterStart_WhenConstructed_ThenThrowsIllegalArgument() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> new ShowDate(END, START));
        assertEquals("End time must be after start time", ex.getMessage());
    }

    @Test
    void GivenNoStart_WhenConstructed_ThenThrowsIllegalState() {
        assertThrows(IllegalStateException.class, () -> new ShowDate(null, END));
    }

    @Test
    void GivenSummerShowInJerusalem_WhenAskedForInstants_ThenReadInIdt() {
        ShowDate show = new ShowDate(START, END);
        ZoneId jerusalem = ZoneId.of("Asia/Jerusalem");

        assertEquals(Instant.parse("2027-07-15T17:00:00Z"), show.startsAt(jerusalem));
        assertEquals(Instant.parse("2027-07-15T20:00:00Z"), show.endsAt(jerusalem));
    }

    @Test
    void GivenSameWallTimeInUtc_WhenAskedForStart_ThenThreeHoursLaterThanInJerusalem() {
        ShowDate show = new ShowDate(START, END);

        assertEquals(Instant.parse("2027-07-15T20:00:00Z"), show.startsAt(ZoneId.of("UTC")));
    }

    @Test
    void GivenShowStartingExactlyNow_WhenRequireStartsAfter_ThenRejected() {
        ShowDate show = new ShowDate(START, END);

        assertThrows(IllegalArgumentException.class,
                () -> show.requireStartsAfter(Instant.parse("2027-07-15T17:00:00Z"), ZoneId.of("Asia/Jerusalem")));
    }
}
