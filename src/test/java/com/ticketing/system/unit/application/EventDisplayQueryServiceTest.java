package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.application.port.in.EventDisplayInfoDTO;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.application.service.EventDisplayQueryService;
import com.ticketing.system.catalog.domain.DiscountPolicy;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.catalog.domain.StandingZone;
import com.ticketing.system.catalog.domain.VenueMap;
import com.ticketing.system.shared.domain.policy.NoPurchasePolicy;

/** The purchase-history projection carries each show's start at the venue, in the venue's zone (#74). */
class EventDisplayQueryServiceTest {

    private static final int EVENT_ID = 7;
    private static final ZoneId JERUSALEM = ZoneId.of("Asia/Jerusalem");
    private static final LocalDateTime SHOW_START = LocalDateTime.of(2027, 7, 15, 20, 0);

    private final EventRepository events = mock(EventRepository.class);
    private final EventDisplayQueryService service = new EventDisplayQueryService(events);

    @Test
    void GivenJerusalemEvent_WhenDescribed_ThenItStartsAt20LocalInJerusalem() {
        when(events.findById(EVENT_ID)).thenReturn(eventAt(JERUSALEM));

        EventDisplayInfoDTO info = service.describeEvent(EVENT_ID);

        assertEquals(ZonedDateTime.of(SHOW_START, JERUSALEM), info.eventStartsAt());
    }

    // The receipt's pattern ("… HH:mm zzz", Locale.US) labels a summer show IDT, so a buyer abroad
    // knows which clock 20:00 is on.
    @Test
    void GivenSummerShowInJerusalem_WhenFormattedLikeTheReceipt_ThenLabelledIdt() {
        when(events.findById(EVENT_ID)).thenReturn(eventAt(JERUSALEM));

        String shown = service.describeEvent(EVENT_ID).eventStartsAt()
                .format(DateTimeFormatter.ofPattern("EEE d MMM yyyy · HH:mm zzz", Locale.US));

        assertEquals("Thu 15 Jul 2027 · 20:00 IDT", shown);
    }

    // ADR-008: the earliest show is found by instant. Show dates are an unordered element collection,
    // so a reload can return them in any order; the projection must not take the list's first entry.
    @Test
    void GivenShowsListedLaterFirst_WhenDescribed_ThenItStartsAtTheEarliestShow() {
        LocalDateTime laterShow = SHOW_START.plusDays(7);
        when(events.findById(EVENT_ID)).thenReturn(eventWithShows(JERUSALEM, List.of(
                new ShowDate(laterShow, laterShow.plusHours(3)),
                new ShowDate(SHOW_START, SHOW_START.plusHours(3)))));

        EventDisplayInfoDTO info = service.describeEvent(EVENT_ID);

        assertEquals(ZonedDateTime.of(SHOW_START, JERUSALEM), info.eventStartsAt(),
                "the event should start at its earliest show, not at the first show in the list");
    }

    private static Event eventAt(ZoneId timeZone) {
        return eventWithShows(timeZone, List.of(new ShowDate(SHOW_START, SHOW_START.plusHours(3))));
    }

    private static Event eventWithShows(ZoneId timeZone, List<ShowDate> showDates) {
        return new Event(EVENT_ID, "Gala", 4.5, List.of("Artist"), EventCategory.CONCERT, 1, EventStatus.ON_SALE,
                new VenueMap(1, new Location("Israel", "Jerusalem"), List.of(new StandingZone(1, "Floor", 10, 50))),
                showDates, timeZone, new NoPurchasePolicy(), new DiscountPolicy(0));
    }
}
