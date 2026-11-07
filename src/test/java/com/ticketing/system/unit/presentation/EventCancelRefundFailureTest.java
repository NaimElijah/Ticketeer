package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.button;
import static com.ticketing.system.unit.presentation.ViewHarness.click;
import static com.ticketing.system.unit.presentation.ViewHarness.find;
import static com.ticketing.system.unit.presentation.ViewHarness.founderOf;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
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
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.shared.config.PlatformTimeProperties;
import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.ticketing.system.shared.exception.RefundFailedException;
import com.ticketing.system.shared.exception.RefundGatewayUnreachableException;
import com.ticketing.system.shared.exception.RefundOutcomeUnknownException;
import com.ticketing.system.ui.presenters.ListOutcome;
import com.ticketing.system.ui.presenters.company.CompanyEventListPresenter;
import com.ticketing.system.ui.presenters.company.EventManagementPresenter;
import com.ticketing.system.ui.views.company.CompanyEventListView;
import com.ticketing.system.ui.views.company.EventManagementView;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.notification.Notification;

/**
 * Cancelling an event whose refunds partly fail leaves the event cancelled, so the owner is told the
 * refund failure's reason and the page reloads to show the event's state (#187, UI-A3). Each view's
 * cancel goes through its confirmation first, where it has one.
 */
class EventCancelRefundFailureTest {

    // ---- UI-A3: a partial refund failure on cancelling reads as "could not cancel" (#187) ----

    private static final int EVENT_ID = 9;
    // Why a cancel failed with no refund involved: both presenters read every refund failure as RefundFailed,
    // never as a failed cancel.
    private static final String CANCEL_REFUSAL = "You lack the permission to cancel this event.";
    // What the views put before a failed cancel's reason.
    private static final String COULD_NOT_CANCEL = "Could not cancel the event";
    // What a toast must say when the event is cancelled but some of its refunds failed.
    private static final String CANCELLED = "event is cancelled";
    // The views' hint that cancelling again retries the failed refunds.
    private static final String RETRY_HINT = "cancel it again";
    // The charge a payment-gateway refund failure names in its own wording.
    private static final int GATEWAY_CHARGE_ID = 9001;

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
    void GivenTheCancelFails_WhenTheOwnerCancelsFromMyEvents_ThenTheToastShowsTheReason() {
        CompanyEventListPresenter presenter = eventListPresenter();

        cancelFromMyEvents(presenter);

        assertToastShows(COULD_NOT_CANCEL);
        assertToastShows(CANCEL_REFUSAL);
    }

    @Test
    void GivenTheCancelFails_WhenTheOwnerCancelsFromMyEvents_ThenTheListReloads() {
        CompanyEventListPresenter presenter = eventListPresenter();

        cancelFromMyEvents(presenter);

        verify(presenter, times(2)).load(any(), any(), any());
    }

    // ---- the event's management page ----

    @Test
    void GivenTheCancelFails_WhenTheOwnerCancelsFromTheEventPage_ThenTheToastShowsTheReason() {
        EventManagementPresenter presenter = eventPagePresenter();

        cancelFromTheEventPage(presenter);

        assertToastShows(COULD_NOT_CANCEL);
        assertToastShows(CANCEL_REFUSAL);
    }

    @Test
    void GivenTheCancelFails_WhenTheOwnerCancelsFromTheEventPage_ThenTheEventReloads() {
        EventManagementPresenter presenter = eventPagePresenter();

        cancelFromTheEventPage(presenter);

        verify(presenter, times(2)).load(any(), eq(EVENT_ID));
    }

    // ---- the presenters' distinct outcome: the event is cancelled, some refunds failed ----

    // These go through the real presenters, so the toast is what the owner reads for a refund failure the
    // payment gateway words: the event is cancelled, never the gateway's operator text (#187).
    @Test
    void GivenTheGatewayCannotBeReachedForTheCancelsRefunds_WhenTheOwnerCancelsFromMyEvents_ThenTheToastSaysItIsCancelledInItsOwnWords() {
        EventManagementService eventService = eventServiceWhoseRefundsFail(gatewayUnreachable());
        CompanyEventListView view = new CompanyEventListView(realEventListPresenter(eventService),
            InboxViewHarness.displayTime());
        harness.attach(view);
        long readsBeforeTheCancel = eventListReads(eventService);

        click(button(view, "Cancel event"));
        assertTrue(harness.confirmOpenDialog(), "cancelling from My Events asks for confirmation");

        assertToastShows(CANCELLED);
        assertToastShows(RETRY_HINT);
        assertNoGatewayTextInToasts();
        assertTrue(eventListReads(eventService) > readsBeforeTheCancel, "the list must reload after the cancel");
    }

    @Test
    void GivenTheGatewayCannotBeReachedForTheCancelsRefunds_WhenTheOwnerCancelsFromTheEventPage_ThenTheToastSaysItIsCancelledInItsOwnWords() {
        EventManagementService eventService = eventServiceWhoseRefundsFail(gatewayUnreachable());

        cancelFromTheEventPage(realEventPagePresenter(eventService));

        assertToastShows(CANCELLED);
        assertToastShows(RETRY_HINT);
        assertNoGatewayTextInToasts();
        verify(eventService, times(2)).getEventDetail(any(), eq(EVENT_ID));
    }

    // A refund whose outcome is unknown stays pending, and cancelling again skips it, so no hint says it retries.
    @Test
    void GivenACancelRefundWithAnUnknownOutcome_WhenTheOwnerCancelsFromMyEvents_ThenTheToastHasNoRetryHint() {
        EventManagementService eventService = eventServiceWhoseRefundsFail(outcomeUnknown());

        cancelFromMyEvents(realEventListPresenter(eventService));

        assertToastShows(CANCELLED);
        assertNoToastShows(RETRY_HINT);
        assertNoGatewayTextInToasts();
    }

    @Test
    void GivenACancelRefundWithAnUnknownOutcome_WhenTheOwnerCancelsFromTheEventPage_ThenTheToastHasNoRetryHint() {
        EventManagementService eventService = eventServiceWhoseRefundsFail(outcomeUnknown());

        cancelFromTheEventPage(realEventPagePresenter(eventService));

        assertToastShows(CANCELLED);
        assertNoToastShows(RETRY_HINT);
        assertNoGatewayTextInToasts();
    }

    // The owner must have time to read which refunds failed and what to do, so the message stays until it is
    // dismissed (#187).
    @Test
    void GivenTheCancelsRefundsFail_WhenTheOwnerCancelsFromMyEvents_ThenTheMessageStaysUntilDismissed() {
        cancelFromMyEvents(realEventListPresenter(eventServiceWhoseRefundsFail(gatewayUnreachable())));

        assertEquals(0, cancelledToast().getDuration(), "the refund-failure message must not close by itself");
    }

    @Test
    void GivenTheCancelsRefundsFail_WhenTheOwnerCancelsFromTheEventPage_ThenTheMessageStaysUntilDismissed() {
        cancelFromTheEventPage(realEventPagePresenter(eventServiceWhoseRefundsFail(outcomeUnknown())));

        assertEquals(0, cancelledToast().getDuration(), "the refund-failure message must not close by itself");
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
            .thenReturn(new CompanyEventListPresenter.CancelOutcome.Failure(CANCEL_REFUSAL));
        return presenter;
    }

    private static EventManagementPresenter eventPagePresenter() {
        EventManagementPresenter presenter = mock(EventManagementPresenter.class);
        when(presenter.load(any(), eq(EVENT_ID)))
            .thenReturn(new EventManagementPresenter.LoadOutcome.Success(onSaleEvent()));
        when(presenter.cancel(any(), anyInt()))
            .thenReturn(new EventManagementPresenter.CancelOutcome.Failure(CANCEL_REFUSAL));
        return presenter;
    }

    private static RefundFailedException gatewayUnreachable() {
        return RefundGatewayUnreachableException.forPaymentTransaction(GATEWAY_CHARGE_ID,
            "refund gateway unreachable: Connection refused", new RuntimeException("refused"));
    }

    private static RefundFailedException outcomeUnknown() {
        return RefundOutcomeUnknownException.forPaymentTransaction(GATEWAY_CHARGE_ID,
            "no answer from the refund gateway: Read timed out", new RuntimeException("timeout"));
    }

    private static EventManagementService eventServiceWhoseRefundsFail(RefundFailedException failure) {
        EventManagementService eventService = mock(EventManagementService.class);
        when(eventService.listEventsForCompany(any(), anyInt(), any())).thenReturn(List.of(onSaleEvent()));
        when(eventService.getEventDetail(any(), eq(EVENT_ID))).thenReturn(onSaleEvent());
        doThrow(failure).when(eventService).cancelEventAndRefund(any(), eq(EVENT_ID));
        return eventService;
    }

    private static CompanyEventListPresenter realEventListPresenter(EventManagementService eventService) {
        CompanyManagementService companies = mock(CompanyManagementService.class);
        when(companies.findMyCompanies(any())).thenReturn(List.of(new MyCompanyDTO(3, "Acme", "Founder")));
        return new CompanyEventListPresenter(eventService, companies);
    }

    private static EventManagementPresenter realEventPagePresenter(EventManagementService eventService) {
        return new EventManagementPresenter(eventService, mock(CompanyManagementService.class),
            new PlatformTimeProperties(ZoneOffset.UTC));
    }

    // The list reads the company's events for its rows and for its filter options, so count the reads.
    private static long eventListReads(EventManagementService eventService) {
        return mockingDetails(eventService).getInvocations().stream()
            .filter(invocation -> "listEventsForCompany".equals(invocation.getMethod().getName()))
            .count();
    }

    private Notification cancelledToast() {
        harness.toasts();
        return find(UI.getCurrent(), Notification.class).stream()
            .filter(toast -> toast.getElement().getTextRecursively().contains(CANCELLED))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no toast says the event is cancelled"));
    }

    private void assertNoGatewayTextInToasts() {
        for (String operatorText : List.of("gateway", "Connection refused", "Read timed out", "payment transaction",
                String.valueOf(GATEWAY_CHARGE_ID))) {
            assertNoToastShows(operatorText);
        }
    }

    private void cancelFromMyEvents(CompanyEventListPresenter presenter) {
        CompanyEventListView view = new CompanyEventListView(presenter, InboxViewHarness.displayTime());
        harness.attach(view);
        click(button(view, "Cancel event"));
        assertTrue(harness.confirmOpenDialog(), "cancelling from My Events asks for confirmation");
    }

    // The event page asks for confirmation first, as My Events does (UI-A4).
    private void cancelFromTheEventPage(EventManagementPresenter presenter) {
        EventManagementView view = new EventManagementView(presenter);
        harness.attach(view);
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

    // ---- a declined refund: the refund service's own summary, and no promise that cancelling again retries ----

    // A plain refund failure can mix declined receipts with pending ones; its own summary says which, so the
    // toast shows that summary and adds no retry hint of its own (#189).
    private static final RefundFailedException DECLINED = new RefundFailedException(List.of(12),
        "not refunded for cancelled event 9 (declined: [12], still pending: [])");

    @Test
    void GivenADeclinedRefundOfTheCancel_WhenTheOwnerCancelsFromMyEvents_ThenTheToastSaysCancelledWithTheSummaryAndNoRetryHint() {
        cancelFromMyEvents(realEventListPresenter(eventServiceWhoseRefundsFail(DECLINED)));

        assertToastShows(CANCELLED);
        assertToastShows(DECLINED.getMessage());
        assertNoToastShows(RETRY_HINT);
        assertNoToastShows(COULD_NOT_CANCEL);
    }

    @Test
    void GivenADeclinedRefundOfTheCancel_WhenTheOwnerCancelsFromTheEventPage_ThenTheToastSaysCancelledWithTheSummaryAndNoRetryHint() {
        cancelFromTheEventPage(realEventPagePresenter(eventServiceWhoseRefundsFail(DECLINED)));

        assertToastShows(CANCELLED);
        assertToastShows(DECLINED.getMessage());
        assertNoToastShows(RETRY_HINT);
        assertNoToastShows(COULD_NOT_CANCEL);
    }
}
