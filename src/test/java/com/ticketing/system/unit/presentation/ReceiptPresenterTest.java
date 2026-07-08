package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.Core.Application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.identity.application.service.MemberAccountService;
import com.ticketing.system.shared.exception.EntityNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.ui.presenters.account.ReceiptPresenter;

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
                java.time.LocalDateTime.of(2026, 6, 1, 12, 0), 150.00, false,
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
    void load_otherMembersReceipt_returnsForbidden() {
        when(memberAccountService.viewMyReceipt(TOKEN, RECEIPT_ID))
                .thenThrow(new UnauthorizedActionException("view receipt " + RECEIPT_ID, 42));

        ReceiptPresenter.Outcome outcome = presenter.load(TOKEN, RECEIPT_ID);

        assertInstanceOf(ReceiptPresenter.Outcome.Forbidden.class, outcome);
    }

    @Test
    void load_invalidToken_returnsFailure() {
        when(memberAccountService.viewMyReceipt(TOKEN, RECEIPT_ID)).thenThrow(new InvalidTokenException());

        ReceiptPresenter.Outcome outcome = presenter.load(TOKEN, RECEIPT_ID);

        assertInstanceOf(ReceiptPresenter.Outcome.Failure.class, outcome);
    }
}
