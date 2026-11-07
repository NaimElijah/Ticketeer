package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.button;
import static com.ticketing.system.unit.presentation.ViewHarness.click;
import static com.ticketing.system.unit.presentation.ViewHarness.founderOf;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.ui.presenters.company.EventManagementPresenter;
import com.ticketing.system.ui.views.company.EventManagementView;

/**
 * Cancelling from the event's management page refunds every holder and cannot be undone, so it asks for
 * confirmation first, as My Events does (#187, UI-A4).
 */
class EventPageCancelConfirmationTest {

    // ---- UI-A4: EventManagementView cancels without confirmation (#187) ----

    private static final int EVENT_ID = 9;

    private ViewHarness harness;
    private EventManagementPresenter presenter;
    private EventManagementView view;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
        ViewHarness.signInMember();
        harness.memberships(founderOf(3, "Acme"));
        presenter = mock(EventManagementPresenter.class);
        when(presenter.load(any(), eq(EVENT_ID))).thenReturn(new EventManagementPresenter.LoadOutcome.Success(
            new EventDetailDTO(String.valueOf(EVENT_ID), "Coldplay", 4.5, "Live", EventCategory.CONCERT, null, "3",
                "Acme", EventStatus.ON_SALE, List.of(), ZoneOffset.UTC, List.of("Coldplay"), 50.0)));
        when(presenter.cancel(any(), anyInt())).thenReturn(new EventManagementPresenter.CancelOutcome.Success());
        view = new EventManagementView(presenter);
        harness.attach(view);
        view.beforeEnter(routeTo("eventId", String.valueOf(EVENT_ID)));
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void GivenAnOnSaleEvent_WhenTheOwnerClicksCancel_ThenAConfirmationOpensAndNothingIsCancelledYet() {
        click(button(view, "Cancel This Event"));

        verify(presenter, never()).cancel(any(), anyInt());
        assertFalse(harness.dialogs().isEmpty(), "cancelling must ask for confirmation first");
    }

    @Test
    void GivenTheCancelConfirmation_WhenTheOwnerConfirmsIt_ThenTheEventIsCancelled() {
        click(button(view, "Cancel This Event"));

        assertTrue(harness.confirmOpenDialog(), "cancelling must ask for confirmation first");
        verify(presenter).cancel(any(), eq(EVENT_ID));
    }
}
