package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_TOKEN;
import static com.ticketing.system.unit.presentation.ViewHarness.button;
import static com.ticketing.system.unit.presentation.ViewHarness.click;
import static com.ticketing.system.unit.presentation.ViewHarness.field;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static com.ticketing.system.unit.presentation.ViewHarness.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.catalog.config.VenueLimitsProperties;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.sales.config.ReservationHoldProperties;
import com.ticketing.system.shared.dto.GridPlacementDTO;
import com.ticketing.system.shared.dto.InventoryZoneDTO;
import com.ticketing.system.shared.dto.VenueLayoutDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO;
import com.ticketing.system.shared.dto.ZoneDetailDTO;
import com.ticketing.system.ui.presenters.catalog.EventDetailsPresenter;
import com.ticketing.system.ui.presenters.company.VenueMapPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.catalog.EventDetailsView;
import com.ticketing.system.ui.views.company.VenueMapEditorView;
import com.vaadin.flow.component.textfield.TextField;

/**
 * A zone's price keeps its cents in the venue editor and on the event page (#187, UI-A1): the editor's
 * price field holds the exact price, so applying the zone and saving the map stores what was loaded,
 * and the buyer's event page shows the price with its cents.
 */
class ZonePriceCentsTest {

    // ---- UI-A1: prices are cast to int (#187) ----

    private static final int EVENT_ID = 7;
    private static final int COMPANY_ID = 3;
    private static final double PRICE_WITH_CENTS = 49.99;
    private static final VenueLimitsProperties LIMITS = new VenueLimitsProperties(12, 49_500, 1_000_000, 100_000);
    // A dollar amount not followed by cents, such as "$49" on its own.
    private static final Pattern TRUNCATED_PRICE = Pattern.compile("\\$49(?![.\\d])");

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
        ViewHarness.signInMember();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void GivenAZonePricedWithCents_WhenTheVenueEditorLoadsIt_ThenThePriceFieldHoldsTheExactPrice() {
        VenueMapPresenter presenter = mock(VenueMapPresenter.class);
        when(presenter.loadZones(anyString(), eq(EVENT_ID)))
            .thenReturn(new VenueMapPresenter.LoadOutcome.Success(layoutWithOneZone()));
        VenueMapEditorView view = new VenueMapEditorView(presenter, LIMITS);

        view.beforeEnter(routeTo("eventId", String.valueOf(EVENT_ID)));

        assertEquals("49.99", field(view, TextField.class, "Price").getValue(),
            "the editor's price field must hold the loaded price with its cents");
    }

    @Test
    void GivenAZonePricedWithCents_WhenTheVenueEditorLoadsIt_ThenTheMapPreviewShowsTheCents() {
        VenueMapPresenter presenter = mock(VenueMapPresenter.class);
        when(presenter.loadZones(anyString(), eq(EVENT_ID)))
            .thenReturn(new VenueMapPresenter.LoadOutcome.Success(layoutWithOneZone()));
        VenueMapEditorView view = new VenueMapEditorView(presenter, LIMITS);

        view.beforeEnter(routeTo("eventId", String.valueOf(EVENT_ID)));

        String shown = text(view);
        assertTrue(shown.contains("$49.99"), "expected the preview to show $49.99 in: " + shown);
        assertFalse(TRUNCATED_PRICE.matcher(shown).find(), "a price must never lose its cents: " + shown);
    }

    // The real presenter validates the field's text, so this is the path an owner takes: open the
    // editor, apply the zone unchanged, save the map.
    @Test
    void GivenAZonePricedWithCentsReloaded_WhenTheZoneIsAppliedAndTheMapSaved_ThenThePriceSavedKeepsItsCents() {
        EventManagementService eventService = mock(EventManagementService.class);
        when(eventService.getEventZones(MEMBER_TOKEN, EVENT_ID)).thenReturn(layoutWithOneZone());
        when(eventService.getEventDetail(MEMBER_TOKEN, EVENT_ID)).thenReturn(eventOfCompany());
        VenueMapEditorView view = new VenueMapEditorView(new VenueMapPresenter(eventService), LIMITS);
        view.beforeEnter(routeTo("eventId", String.valueOf(EVENT_ID)));

        click(button(view, "Apply Zone"));
        click(button(view, "Save Map"));

        ArgumentCaptor<VenueMapConfigDTO> saved = ArgumentCaptor.forClass(VenueMapConfigDTO.class);
        verify(eventService).configureVenueMap(eq(MEMBER_TOKEN), eq(COMPANY_ID), saved.capture());
        assertEquals(PRICE_WITH_CENTS, saved.getValue().zones().get(0).pricePerTicket(), 1e-9,
            "saving an unchanged zone must store the price it was loaded with");
    }

    @Test
    void GivenAZonePricedWithCents_WhenTheBuyerOpensTheEventPage_ThenEveryPriceShowsItsCents() {
        EventDetailsPresenter presenter = mock(EventDetailsPresenter.class);
        InventoryZoneDTO zone = new InventoryZoneDTO(1, "Floor", "STANDING", 100, 50, 0, 50,
            PRICE_WITH_CENTS, null, null);
        when(presenter.load(any(), anyInt())).thenReturn(
            new EventDetailsPresenter.Outcome.Success(eventOfCompany(), List.of(zone), 3, 3, null));
        SessionIdentity identity = mock(SessionIdentity.class);
        when(identity.credential()).thenReturn(MEMBER_TOKEN);
        EventDetailsView view = new EventDetailsView(presenter, identity, new ReservationHoldProperties(10));

        view.beforeEnter(routeTo("eventId", String.valueOf(EVENT_ID)));

        String shown = text(view);
        assertTrue(shown.contains("$49.99"), "expected the zone's price $49.99 in: " + shown);
        assertFalse(TRUNCATED_PRICE.matcher(shown).find(), "a price must never lose its cents: " + shown);
    }

    private static VenueLayoutDTO layoutWithOneZone() {
        return new VenueLayoutDTO(3, 3, List.of(
            new ZoneDetailDTO("Floor", false, 0, 0, 500, PRICE_WITH_CENTS, new GridPlacementDTO(1, 1, 1, 1))));
    }

    private static EventDetailDTO eventOfCompany() {
        return new EventDetailDTO(String.valueOf(EVENT_ID), "Coldplay", null, "Live", EventCategory.MUSIC, null,
            String.valueOf(COMPANY_ID), "Acme", EventStatus.ON_SALE, List.of(), ZoneOffset.UTC, List.of(),
            PRICE_WITH_CENTS);
    }
}
