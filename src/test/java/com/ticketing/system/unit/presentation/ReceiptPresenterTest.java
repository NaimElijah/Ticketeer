package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.reporting.application.service.MemberAccountService;
import com.ticketing.system.shared.exception.EntityNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.ui.presenters.account.ReceiptPresenter;
import com.ticketing.system.testutil.LogCapture;
import com.ticketing.system.ui.support.ServiceErrors;
import ch.qos.logback.classic.Level;
import jakarta.persistence.PersistenceException;

class ReceiptPresenterTest {

    private static final String TOKEN = "member-token";
    private static final int RECEIPT_ID = 7;

    private MemberAccountService memberAccountService;
    private ReceiptPresenter presenter;

    @BeforeEach
    void setUp() {
        memberAccountService = mock(MemberAccountService.class);
        presenter = new ReceiptPresenter(memberAccountService);
    }

    private static PurchaseRecordDTO record() {
        return new PurchaseRecordDTO(RECEIPT_ID, 42, null,
                java.time.LocalDateTime.of(2026, 6, 1, 12, 0), 150.00, PurchaseHistoryDTO.RefundStatus.NONE,
                List.of(), List.of(), "member42");
    }

    @Test
    void load_success_carriesReceipt() {
        PurchaseRecordDTO record = record();
        when(memberAccountService.viewMyReceipt(TOKEN, RECEIPT_ID)).thenReturn(record);

        ReceiptPresenter.Outcome outcome = presenter.load(TOKEN, RECEIPT_ID);

        ReceiptPresenter.Outcome.Success ok =
                assertInstanceOf(ReceiptPresenter.Outcome.Success.class, outcome);
        assertSame(record, ok.receipt());
    }

    @Test
    void load_missingReceipt_returnsNotFound() {
        when(memberAccountService.viewMyReceipt(TOKEN, RECEIPT_ID))
                .thenThrow(new EntityNotFoundException("OrderReceipt", RECEIPT_ID));

        ReceiptPresenter.Outcome outcome = presenter.load(TOKEN, RECEIPT_ID);

        assertInstanceOf(ReceiptPresenter.Outcome.NotFound.class, outcome);
    }

    @Test
    void load_otherMembersReceipt_returnsNotFound() {
        when(memberAccountService.viewMyReceipt(TOKEN, RECEIPT_ID))
                .thenThrow(new UnauthorizedActionException("view receipt " + RECEIPT_ID, 42));

        ReceiptPresenter.Outcome outcome = presenter.load(TOKEN, RECEIPT_ID);

        assertInstanceOf(ReceiptPresenter.Outcome.NotFound.class, outcome);
    }

    @Test
    void GivenAnotherMembersReceiptId_WhenView_ThenSameOutcomeAsAMissingId() {
        // Answering differently would tell a member which receipt ids exist on other accounts.
        int missingId = RECEIPT_ID;
        int otherMembersId = RECEIPT_ID + 1;
        when(memberAccountService.viewMyReceipt(TOKEN, missingId))
                .thenThrow(new EntityNotFoundException("OrderReceipt", missingId));
        when(memberAccountService.viewMyReceipt(TOKEN, otherMembersId))
                .thenThrow(new UnauthorizedActionException("view receipt " + otherMembersId, 42));

        ReceiptPresenter.Outcome missing = presenter.load(TOKEN, missingId);
        ReceiptPresenter.Outcome otherMembers = presenter.load(TOKEN, otherMembersId);

        assertInstanceOf(ReceiptPresenter.Outcome.NotFound.class, missing);
        assertEquals(missing, otherMembers,
                "another member's receipt must look exactly like a missing one");
    }

    @Test
    void load_invalidToken_returnsFailure() {
        when(memberAccountService.viewMyReceipt(TOKEN, RECEIPT_ID)).thenThrow(new InvalidTokenException());

        ReceiptPresenter.Outcome outcome = presenter.load(TOKEN, RECEIPT_ID);

        assertInstanceOf(ReceiptPresenter.Outcome.Failure.class, outcome);
    }

    // ---- UI-B5: an internal failure goes through ServiceErrors (#187) ----

    @Test
    void GivenAnInternalFailure_WhenLoad_ThenFailureCarriesTheGenericMessageAndServiceErrorsLogsIt() {
        when(memberAccountService.viewMyReceipt(TOKEN, RECEIPT_ID))
                .thenThrow(new PersistenceException("could not prepare statement; table [order_receipts]"));

        try (LogCapture serviceErrorsLog = LogCapture.of(ServiceErrors.class)) {
            ReceiptPresenter.Outcome outcome = presenter.load(TOKEN, RECEIPT_ID);

            ReceiptPresenter.Outcome.Failure failure = assertInstanceOf(ReceiptPresenter.Outcome.Failure.class, outcome);
            assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, failure.message());
            assertEquals(List.of(PersistenceException.class.getName()),
                    serviceErrorsLog.throwableClassNamesAt(Level.ERROR),
                    "the internal failure must be logged at ERROR with its exception by ServiceErrors");
        }
    }
}
