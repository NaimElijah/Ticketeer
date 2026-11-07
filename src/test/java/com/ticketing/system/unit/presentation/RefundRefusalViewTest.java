package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_ID;
import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_TOKEN;
import static com.ticketing.system.unit.presentation.ViewHarness.button;
import static com.ticketing.system.unit.presentation.ViewHarness.click;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.ticketing.system.reporting.application.service.MemberAccountService;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.RefundStatus;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.TicketRecordDTO;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.ui.presenters.account.MyAccountPresenter;
import com.ticketing.system.ui.presenters.account.ReceiptPresenter;
import com.ticketing.system.ui.presenters.account.RefundPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.account.MyAccountView;
import com.ticketing.system.ui.views.account.ReceiptView;

/**
 * A refund the service refuses, from My account or from the receipt: the member reads why, and the order is
 * still offered for a refund, since nothing was refunded (#189). Each of the presenter's refusals is driven
 * through the view, as {@code RefundPresenterTest} drives them through the presenter.
 */
class RefundRefusalViewTest {

    private static final int ORDER_ID = 42;
    private static final String REQUEST_REFUND = "Request Refund";

    private ViewHarness harness;
    private RefundPresenter refundPresenter;
    private SessionIdentity identity;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
        ViewHarness.signInMember();
        refundPresenter = mock(RefundPresenter.class);
        identity = mock(SessionIdentity.class);
        when(identity.memberToken()).thenReturn(MEMBER_TOKEN);
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
            Arguments.of(new RefundPresenter.Outcome.NotEligible("This order is past its refund window."),
                "This order is past its refund window."),
            Arguments.of(new RefundPresenter.Outcome.NotFound("Order not found."), "Order not found."),
            Arguments.of(new RefundPresenter.Outcome.Failure("The refund could not be completed."),
                "The refund could not be completed."));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("refusals")
    void GivenARefusedRefund_WhenTheMemberConfirmsItOnMyAccount_ThenTheReasonShowsAndTheOrderStaysRefundable(
            RefundPresenter.Outcome refusal, String reason) {
        when(refundPresenter.requestRefund(any(), anyInt(), any())).thenReturn(refusal);
        MyAccountPresenter accountPresenter = mock(MyAccountPresenter.class);
        when(accountPresenter.loadHistory()).thenReturn(new MyAccountPresenter.Outcome.Success(
            new PurchaseHistoryDTO(List.of(refundableOrder()))));
        MyAccountView view = new MyAccountView(accountPresenter, refundPresenter, identity,
            InboxViewHarness.displayTime());
        harness.attach(view);

        click(button(view, REQUEST_REFUND));
        assertTrue(harness.confirmOpenDialog(), "a refund asks for confirmation first");

        verify(refundPresenter).requestRefund(any(), eq(ORDER_ID), any());
        assertEquals(List.of(reason), harness.toasts());
        assertTrue(ViewHarness.shown(button(view, REQUEST_REFUND)), "the order must still offer its refund");
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("refusals")
    void GivenARefusedRefund_WhenTheMemberConfirmsItOnTheReceipt_ThenTheReasonShowsAndTheOrderStaysRefundable(
            RefundPresenter.Outcome refusal, String reason) {
        when(refundPresenter.requestRefund(any(), anyInt(), any())).thenReturn(refusal);
        MemberAccountService accountService = mock(MemberAccountService.class);
        when(accountService.viewMyReceipt(MEMBER_TOKEN, ORDER_ID)).thenReturn(refundableOrder());
        ReceiptView view = new ReceiptView(new ReceiptPresenter(accountService), refundPresenter, identity,
            InboxViewHarness.displayTime());
        harness.attach(view);
        view.beforeEnter(routeTo("receiptId", String.valueOf(ORDER_ID)));

        click(button(view, REQUEST_REFUND));
        assertTrue(harness.confirmOpenDialog(), "a refund asks for confirmation first");

        verify(refundPresenter).requestRefund(any(), eq(ORDER_ID), any());
        assertEquals(List.of(reason), harness.toasts());
        verify(accountService, times(1)).viewMyReceipt(MEMBER_TOKEN, ORDER_ID);
        assertTrue(ViewHarness.shown(button(view, REQUEST_REFUND)), "the order must still offer its refund");
    }

    private static PurchaseRecordDTO refundableOrder() {
        LocalDateTime paidAt = LocalDateTime.of(2027, 7, 1, 12, 0);
        TicketRecordDTO ticket = new TicketRecordDTO(1, 8, 5, ORDER_ID, "A1", 75.0, TicketStatus.ISSUED, "Coldplay",
            "Floor", "Acme", "CONCERT", null, "Tel Aviv", "TIX-1", false);
        return new PurchaseRecordDTO(ORDER_ID, MEMBER_ID, null, paidAt, 75.0, RefundStatus.NONE, List.of(),
            List.of(ticket), "alice");
    }
}
