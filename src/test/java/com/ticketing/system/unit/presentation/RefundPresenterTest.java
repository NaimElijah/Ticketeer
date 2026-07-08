package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.Core.Application.dto.RefundResultDTO;
import com.ticketing.system.sales.application.service.RefundService;
import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.shared.exception.EntityNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.Presentation.presenters.account.RefundPresenter;

class RefundPresenterTest {

    private static final String TOKEN = "member-token";
    private static final int ORDER_ID = 7;

    private RefundService refundService;
    private RefundPresenter presenter;

    @BeforeEach
    void setUp() {
        refundService = mock(RefundService.class);
        presenter = new RefundPresenter(refundService);
    }

    @Test
    void requestRefund_success_carriesReferenceAndAmount() {
        RefundResultDTO result = new RefundResultDTO(
                "refund-1", "555", 150.00, LocalDateTime.of(2026, 6, 1, 12, 0), List.of(), List.of());
        when(refundService.requestRefund(TOKEN, ORDER_ID, "reason")).thenReturn(result);

        RefundPresenter.Outcome outcome = presenter.requestRefund(TOKEN, ORDER_ID, "reason");

        RefundPresenter.Outcome.Success ok =
                assertInstanceOf(RefundPresenter.Outcome.Success.class, outcome);
        assertEquals("refund-1", ok.reference());
        assertEquals(150.00, ok.amount());
    }

    @Test
    void requestRefund_alreadyRefunded_returnsNotEligible() {
        when(refundService.requestRefund(TOKEN, ORDER_ID, "reason"))
                .thenThrow(new BusinessRuleViolationException("already refunded"));

        assertInstanceOf(RefundPresenter.Outcome.NotEligible.class,
                presenter.requestRefund(TOKEN, ORDER_ID, "reason"));
    }

    @Test
    void requestRefund_missingOrder_returnsNotFound() {
        when(refundService.requestRefund(TOKEN, ORDER_ID, "reason"))
                .thenThrow(new EntityNotFoundException("OrderReceipt", ORDER_ID));

        assertInstanceOf(RefundPresenter.Outcome.NotFound.class,
                presenter.requestRefund(TOKEN, ORDER_ID, "reason"));
    }

    @Test
    void requestRefund_otherMembersOrder_returnsForbidden() {
        when(refundService.requestRefund(TOKEN, ORDER_ID, "reason"))
                .thenThrow(new UnauthorizedActionException("refund order " + ORDER_ID, 42));

        assertInstanceOf(RefundPresenter.Outcome.Forbidden.class,
                presenter.requestRefund(TOKEN, ORDER_ID, "reason"));
    }

    @Test
    void requestRefund_invalidToken_returnsFailure() {
        when(refundService.requestRefund(TOKEN, ORDER_ID, "reason")).thenThrow(new InvalidTokenException());

        assertInstanceOf(RefundPresenter.Outcome.Failure.class,
                presenter.requestRefund(TOKEN, ORDER_ID, "reason"));
    }
}
