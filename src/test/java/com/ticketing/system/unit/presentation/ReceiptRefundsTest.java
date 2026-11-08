package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_TOKEN;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static com.ticketing.system.unit.presentation.ViewHarness.text;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.RefundStatus;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.TransactionRecordDTO;
import com.ticketing.system.reporting.application.service.MemberAccountService;
import com.ticketing.system.ui.presenters.account.ReceiptPresenter;
import com.ticketing.system.ui.presenters.account.RefundPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.account.ReceiptView;

/**
 * A receipt refunded twice, for example by two cancelled events of one order, counts both refunds
 * (#187, UI-A5). The receipt goes through the real {@link ReceiptPresenter}, so the refunds may be
 * summed in the presenter or in the view; either way the second one shows.
 */
class ReceiptRefundsTest {

    // ---- UI-A5: a receipt shows only its first refund (#187) ----

    private static final int RECEIPT_ID = 42;
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2027, 7, 1, 12, 0);

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void GivenAReceiptWithTwoRefunds_WhenTheMemberOpensIt_ThenTheSecondRefundCountsToo() {
        MemberAccountService accountService = mock(MemberAccountService.class);
        when(accountService.viewMyReceipt(MEMBER_TOKEN, RECEIPT_ID)).thenReturn(new PurchaseRecordDTO(
            RECEIPT_ID, 7, null, PAID_AT, 100.00, RefundStatus.PARTIAL,
            List.of(
                new TransactionRecordDTO("PAYMENT_CHARGE", "WSEP", "TX-1", 100.00, "USD", PAID_AT),
                new TransactionRecordDTO("REFUND", "WSEP", "RF-1", 20.00, "USD", PAID_AT.plusDays(1)),
                new TransactionRecordDTO("REFUND", "WSEP", "RF-2", 25.00, "USD", PAID_AT.plusDays(2))),
            List.of(), "alice"));
        SessionIdentity identity = mock(SessionIdentity.class);
        when(identity.memberToken()).thenReturn(MEMBER_TOKEN);
        ReceiptView view = new ReceiptView(new ReceiptPresenter(accountService), mock(RefundPresenter.class),
            identity, InboxViewHarness.displayTime());

        view.beforeEnter(routeTo("receiptId", String.valueOf(RECEIPT_ID)));

        String shown = text(view);
        // ReceiptPresenter sums the refunds. The sum, $45.00, differs from what stays paid, $55.00, so a page
        // showing the remainder as the refund fails (#189).
        assertTrue(shown.contains("$45.00"), "expected the $45.00 refunded in all, in: " + shown);
    }

    // ---- the receipt's other refund states (#189) ----

    @Test
    void GivenAFullyRefundedReceipt_WhenTheMemberOpensIt_ThenItReadsRefundedAndItsTotalIsTheRefund() {
        ReceiptView view = receiptView(new PurchaseRecordDTO(
            RECEIPT_ID, 7, null, PAID_AT, 100.00, RefundStatus.FULL,
            List.of(
                new TransactionRecordDTO("PAYMENT_CHARGE", "WSEP", "TX-1", 100.00, "USD", PAID_AT),
                new TransactionRecordDTO("REFUND", "WSEP", "RF-1", 100.00, "USD", PAID_AT.plusDays(1))),
            List.of(), "alice"));

        String shown = text(view);
        assertTrue(shown.contains("Refund status"), "a refunded receipt says how it was refunded, in: " + shown);
        assertTrue(shown.contains("Refunded"), "a fully refunded receipt reads Refunded, in: " + shown);
        assertFalse(shown.contains("Partly refunded"), "a full refund is not a partial one, in: " + shown);
        assertTrue(shown.contains("Total refunded"), "its total is the amount refunded, in: " + shown);
    }

    @Test
    void GivenAnUnrefundedReceipt_WhenTheMemberOpensIt_ThenNoRefundStatusShowsAndItsTotalIsWhatWasPaid() {
        ReceiptView view = receiptView(new PurchaseRecordDTO(
            RECEIPT_ID, 7, null, PAID_AT, 100.00, RefundStatus.NONE,
            List.of(new TransactionRecordDTO("PAYMENT_CHARGE", "WSEP", "TX-1", 100.00, "USD", PAID_AT)),
            List.of(), "alice"));

        String shown = text(view);
        assertTrue(shown.contains("Total paid"), "its total is what was paid, in: " + shown);
        assertFalse(shown.contains("Refund status"), "nothing was refunded, so no refund status shows: " + shown);
        assertFalse(shown.contains("Refunded"), "nothing was refunded, in: " + shown);
    }

    private static ReceiptView receiptView(PurchaseRecordDTO receipt) {
        MemberAccountService accountService = mock(MemberAccountService.class);
        when(accountService.viewMyReceipt(MEMBER_TOKEN, RECEIPT_ID)).thenReturn(receipt);
        SessionIdentity identity = mock(SessionIdentity.class);
        when(identity.memberToken()).thenReturn(MEMBER_TOKEN);
        ReceiptView view = new ReceiptView(new ReceiptPresenter(accountService), mock(RefundPresenter.class),
            identity, InboxViewHarness.displayTime());
        view.beforeEnter(routeTo("receiptId", String.valueOf(RECEIPT_ID)));
        return view;
    }
}
