package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.button;
import static com.ticketing.system.unit.presentation.ViewHarness.click;
import static com.ticketing.system.unit.presentation.ViewHarness.founderOf;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
import com.ticketing.system.shared.exception.RefundFailedException;
import com.ticketing.system.ui.presenters.ListOutcome;
import com.ticketing.system.ui.presenters.company.CompanyEventListPresenter;
import com.ticketing.system.ui.presenters.company.EventManagementPresenter;
import com.ticketing.system.ui.views.company.CompanyEventListView;
import com.ticketing.system.ui.views.company.EventManagementView;

/**
 * Cancelling an event whose refunds partly fail leaves the event cancelled, so the owner is told the
 * refund failure's reason and the page reloads to show the event's state (#187, UI-A3). Each view's
 * cancel goes through its confirmation first, where it has one.
 */
class EventCancelRefundFailureTest {

    // ---- UI-A3: a partial refund failure on cancelling reads as "could not cancel" (#187) ----

    private static final int EVENT_ID = 9;
    // A refund failure's text, as a cancel's outcome carries it.
    private static final String REFUND_FAILURE =
        new RefundFailedException(12, "the payment gateway timed out").getMessage();
    // What a toast must say when the event is cancelled but some of its refunds failed.
    private static final String CANCELLED = "event is cancelled";
    // The views' hint that cancelling again retries the failed refunds.
    private static final String RETRY_HINT = "cancel it again";

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
        ViewHarness.signInMember();
        harness.memberships(founderOf(3, "Acme"));
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    // ---- My Events ----

    @Test
    void GivenRefundsPartlyFail_WhenTheOwnerCancelsFromMyEvents_ThenTheToastShowsTheRefundFailure() {
        CompanyEventListPresenter presenter = eventListPresenter();

        cancelFromMyEvents(presenter);

        assertToastShows(REFUND_FAILURE);
    }

    @Test
    void GivenRefundsPartlyFail_WhenTheOwnerCancelsFromMyEvents_ThenTheListReloads() {
        CompanyEventListPresenter presenter = eventListPresenter();

        cancelFromMyEvents(presenter);

        verify(presenter, times(2)).load(any(), any(), any());
    }

    // ---- the event's management page ----

    @Test
    void GivenRefundsPartlyFail_WhenTheOwnerCancelsFromTheEventPage_ThenTheToastShowsTheRefundFailure() {
        EventManagementPresenter presenter = eventPagePresenter();

        cancelFromTheEventPage(presenter);

        assertToastShows(REFUND_FAILURE);
    }

    @Test
    void GivenRefundsPartlyFail_WhenTheOwnerCancelsFromTheEventPage_ThenTheEventReloads() {
        EventManagementPresenter presenter = eventPagePresenter();

        cancelFromTheEventPage(presenter);

        verify(presenter, times(2)).load(any(), eq(EVENT_ID));
    }

    // ---- the presenters' distinct outcome: the event is cancelled, some refunds failed ----

    @Test
    void GivenTheCancelledEventsRefundsFail_WhenTheOwnerCancelsFromMyEvents_ThenTheToastSaysItIsCancelledAndWhy() {
        CompanyEventListPresenter presenter = eventListPresenter();
        when(presenter.cancelEvent(any(), anyInt()))
            .thenReturn(new CompanyEventListPresenter.CancelOutcome.RefundFailed(REFUND_FAILURE, true));

        cancelFromMyEvents(presenter);

        assertToastShows(CANCELLED);
        assertToastShows(REFUND_FAILURE);
        verify(presenter, times(2)).load(any(), any(), any());
    }

    @Test
    void GivenTheCancelledEventsRefundsFail_WhenTheOwnerCancelsFromTheEventPage_ThenTheToastSaysItIsCancelledAndWhy() {
        EventManagementPresenter presenter = eventPagePresenter();
        when(presenter.cancel(any(), anyInt()))
            .thenReturn(new EventManagementPresenter.CancelOutcome.RefundFailed(REFUND_FAILURE, true));

        cancelFromTheEventPage(presenter);

        assertToastShows(CANCELLED);
        assertToastShows(REFUND_FAILURE);
        verify(presenter, times(2)).load(any(), eq(EVENT_ID));
    }

    // A refund whose outcome is unknown stays pending, and cancelling again skips it, so no hint says it retries.
    @Test
    void GivenACancelRefundThatCancellingAgainDoesNotRetry_WhenTheOwnerCancelsFromMyEvents_ThenTheToastHasNoRetryHint() {
        CompanyEventListPresenter presenter = eventListPresenter();
        when(presenter.cancelEvent(any(), anyInt()))
            .thenReturn(new CompanyEventListPresenter.CancelOutcome.RefundFailed(REFUND_FAILURE, false));

        cancelFromMyEvents(presenter);

        assertToastShows(CANCELLED);
        assertNoToastShows(RETRY_HINT);
    }

    @Test
    void GivenACancelRefundThatCancellingAgainDoesNotRetry_WhenTheOwnerCancelsFromTheEventPage_ThenTheToastHasNoRetryHint() {
        EventManagementPresenter presenter = eventPagePresenter();
        when(presenter.cancel(any(), anyInt()))
            .thenReturn(new EventManagementPresenter.CancelOutcome.RefundFailed(REFUND_FAILURE, false));

        cancelFromTheEventPage(presenter);

        assertToastShows(CANCELLED);
        assertNoToastShows(RETRY_HINT);
    }

    // ---- arrangement ----

    private static EventDetailDTO onSaleEvent() {
        return new EventDetailDTO(String.valueOf(EVENT_ID), "Coldplay", 4.5, "Live", EventCategory.CONCERT, null,
            "3", "Acme", EventStatus.ON_SALE, List.of(), ZoneOffset.UTC, List.of("Coldplay"), 50.0);
    }

    private static CompanyEventListPresenter eventListPresenter() {
        CompanyEventListPresenter presenter = mock(CompanyEventListPresenter.class);
        when(presenter.load(any(), any(), any()))
            .thenReturn(new CompanyEventListPresenter.Outcome.Success(List.of(onSaleEvent())));
        when(presenter.countries(any(), any())).thenReturn(new ListOutcome.Loaded<>(List.of()));
        when(presenter.cancelEvent(any(), anyInt()))
            .thenReturn(new CompanyEventListPresenter.CancelOutcome.Failure(REFUND_FAILURE));
        return presenter;
    }

    private static EventManagementPresenter eventPagePresenter() {
        EventManagementPresenter presenter = mock(EventManagementPresenter.class);
        when(presenter.load(any(), eq(EVENT_ID)))
            .thenReturn(new EventManagementPresenter.LoadOutcome.Success(onSaleEvent()));
        when(presenter.cancel(any(), anyInt()))
            .thenReturn(new EventManagementPresenter.CancelOutcome.Failure(REFUND_FAILURE));
        return presenter;
    }

    private void cancelFromMyEvents(CompanyEventListPresenter presenter) {
        CompanyEventListView view = new CompanyEventListView(presenter, InboxViewHarness.displayTime());
        click(button(view, "Cancel event"));
        assertTrue(harness.confirmOpenDialog(), "cancelling from My Events asks for confirmation");
    }

    // The event page asks for confirmation first, as My Events does (UI-A4).
    private void cancelFromTheEventPage(EventManagementPresenter presenter) {
        EventManagementView view = new EventManagementView(presenter);
        view.beforeEnter(routeTo("eventId", String.valueOf(EVENT_ID)));
        click(button(view, "Cancel This Event"));
        assertTrue(harness.confirmOpenDialog(), "cancelling from the event page asks for confirmation");
    }

    private void assertNoToastShows(String text) {
        List<String> toasts = harness.toasts();
        assertTrue(toasts.stream().noneMatch(toast -> toast.contains(text)),
            "expected no toast with \"" + text + "\", got: " + toasts);
    }

    private void assertToastShows(String reason) {
        List<String> toasts = harness.toasts();
        assertTrue(toasts.stream().anyMatch(toast -> toast.contains(reason)),
            "expected a toast with \"" + reason + "\", got: " + toasts);
    }
}
