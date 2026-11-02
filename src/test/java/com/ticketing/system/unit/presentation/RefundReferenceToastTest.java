package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_ID;
import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_TOKEN;
import static com.ticketing.system.unit.presentation.ViewHarness.button;
import static com.ticketing.system.unit.presentation.ViewHarness.click;
import static com.ticketing.system.unit.presentation.ViewHarness.text;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.RefundStatus;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.TicketRecordDTO;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.ui.presenters.account.MyAccountPresenter;
import com.ticketing.system.ui.presenters.account.RefundPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.account.MyAccountView;

/**
 * A member who requests a refund from My account is shown its reference, and still sees it once the
 * page has refreshed to show the refunded order (#187, UI-A21). A refresh by browser reload drops every
 * toast, so the test reloads the page when the view asks for one, rebuilds the view in the same
 * session, and looks for the reference there.
 */
class RefundReferenceToastTest {

    // ---- UI-A21: a refund reference toast is lost to the reload (#187) ----

    private static final String REFERENCE = "RF-2027-0042";
    private static final int ORDER_ID = 42;

    private ViewHarness harness;
    private MyAccountPresenter accountPresenter;
    private RefundPresenter refundPresenter;
    private SessionIdentity identity;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
        ViewHarness.signInMember();
        accountPresenter = mock(MyAccountPresenter.class);
        when(accountPresenter.loadHistory()).thenReturn(new MyAccountPresenter.Outcome.Success(
            new PurchaseHistoryDTO(List.of(refundableOrder()))));
        refundPresenter = mock(RefundPresenter.class);
        when(refundPresenter.requestRefund(any(), anyInt(), any()))
            .thenReturn(new RefundPresenter.Outcome.Success(REFERENCE, 75.0));
        identity = mock(SessionIdentity.class);
        when(identity.memberToken()).thenReturn(MEMBER_TOKEN);
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void GivenARefundableOrder_WhenTheMemberRequestsItsRefund_ThenTheReferenceIsStillShownAfterTheRefresh() {
        MyAccountView view = myAccount();

        click(button(view, "Request Refund"));
        assertTrue(harness.confirmOpenDialog(), "a refund asks for confirmation first");

        String seen;
        if (harness.pageReloadRequested()) {
            harness.reloadPage();
            MyAccountView reloaded = myAccount();
            seen = harness.toasts() + " " + text(reloaded);
        } else {
            seen = harness.toasts() + " " + text(view);
        }
        assertTrue(seen.contains(REFERENCE), "the refund reference must still show after the refresh: " + seen);
    }

    private MyAccountView myAccount() {
        return new MyAccountView(accountPresenter, refundPresenter, identity, InboxViewHarness.displayTime());
    }

    private static PurchaseRecordDTO refundableOrder() {
        LocalDateTime paidAt = LocalDateTime.of(2027, 7, 1, 12, 0);
        TicketRecordDTO ticket = new TicketRecordDTO(1, 8, 5, ORDER_ID, "A1", 75.0, TicketStatus.ISSUED, "Coldplay",
            "Floor", "Acme", "CONCERT", null, "Tel Aviv", "TIX-1", false);
        return new PurchaseRecordDTO(ORDER_ID, MEMBER_ID, null, paidAt, 75.0, RefundStatus.NONE, List.of(),
            List.of(ticket), "alice");
    }
}
