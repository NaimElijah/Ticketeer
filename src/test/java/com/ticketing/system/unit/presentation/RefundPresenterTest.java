package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.ticketing.system.shared.dto.RefundResultDTO;
import com.ticketing.system.sales.application.service.RefundService;
import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.shared.exception.EntityNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.RefundFailedException;
import com.ticketing.system.shared.exception.RefundGatewayUnreachableException;
import com.ticketing.system.shared.exception.RefundOutcomeUnknownException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.ui.presenters.account.RefundPresenter;
import com.ticketing.system.testutil.LogCapture;
import com.ticketing.system.ui.support.ServiceErrors;
import ch.qos.logback.classic.Level;
import jakarta.persistence.PersistenceException;

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

    // ---- a refund failure never shows the payment gateway's operator text (#187) ----

    static Stream<RefundFailedException> gatewayRefundFailures() {
        return Stream.of(
            RefundFailedException.forPaymentTransaction(9001, "refund rejected by gateway"),
            RefundGatewayUnreachableException.forPaymentTransaction(9001,
                "refund gateway unreachable: Connection refused", new RuntimeException("refused")),
            RefundOutcomeUnknownException.forPaymentTransaction(9001,
                "no answer from the refund gateway: Read timed out", new RuntimeException("timeout")));
    }

    @ParameterizedTest
    @MethodSource("gatewayRefundFailures")
    void GivenTheRefundFailsWithGatewayWording_WhenRequestRefund_ThenTheFailureShowsAFixedText(RefundFailedException failure) {
        when(refundService.requestRefund(TOKEN, ORDER_ID, "reason")).thenThrow(failure);

        RefundPresenter.Outcome.Failure shown = assertInstanceOf(RefundPresenter.Outcome.Failure.class,
            presenter.requestRefund(TOKEN, ORDER_ID, "reason"));

        assertFalse(shown.message().isBlank(), "the member must be told the refund did not go through");
        for (String operatorText : List.of("gateway", "Connection refused", "Read timed out", "payment transaction", "9001")) {
            assertFalse(shown.message().contains(operatorText),
                "the message shows operator text '" + operatorText + "': " + shown.message());
        }
    }

    // A refund the gateway failed leaves the member's money in an unknown or unreturned state: that is a
    // degraded service an operator must see, not a refusal, so it is logged at WARN with its exception.
    @ParameterizedTest
    @MethodSource("gatewayRefundFailures")
    void GivenTheRefundFailsAtTheGateway_WhenRequestRefund_ThenOneWarnCarriesTheException(RefundFailedException failure) {
        when(refundService.requestRefund(TOKEN, ORDER_ID, "reason")).thenThrow(failure);

        try (LogCapture presenterLog = LogCapture.of(RefundPresenter.class);
             LogCapture serviceErrorsLog = LogCapture.of(ServiceErrors.class)) {
            presenter.requestRefund(TOKEN, ORDER_ID, "reason");

            List<String> warned = Stream.concat(presenterLog.throwableClassNamesAt(Level.WARN).stream(),
                    serviceErrorsLog.throwableClassNamesAt(Level.WARN).stream()).toList();
            assertEquals(List.of(failure.getClass().getName()), warned,
                "the gateway's refund failure must be logged once at WARN with its exception");
        }
    }

    @Test
    void GivenARefundThatSucceeds_WhenRequestRefund_ThenCarriesReferenceAndAmount() {
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
    void GivenAnAlreadyRefundedOrder_WhenRequestRefund_ThenReturnsNotEligible() {
        when(refundService.requestRefund(TOKEN, ORDER_ID, "reason"))
                .thenThrow(new BusinessRuleViolationException("already refunded"));

        assertInstanceOf(RefundPresenter.Outcome.NotEligible.class,
                presenter.requestRefund(TOKEN, ORDER_ID, "reason"));
    }

    @Test
    void GivenAMissingOrder_WhenRequestRefund_ThenReturnsNotFound() {
        when(refundService.requestRefund(TOKEN, ORDER_ID, "reason"))
                .thenThrow(new EntityNotFoundException("OrderReceipt", ORDER_ID));

        assertInstanceOf(RefundPresenter.Outcome.NotFound.class,
                presenter.requestRefund(TOKEN, ORDER_ID, "reason"));
    }

    @Test
    void GivenAnotherMembersOrder_WhenRequestRefund_ThenReturnsNotFound() {
        when(refundService.requestRefund(TOKEN, ORDER_ID, "reason"))
                .thenThrow(new UnauthorizedActionException("refund order " + ORDER_ID, 42));

        assertInstanceOf(RefundPresenter.Outcome.NotFound.class,
                presenter.requestRefund(TOKEN, ORDER_ID, "reason"));
    }

    @Test
    void GivenAnotherMembersReceiptId_WhenRequestRefund_ThenSameOutcomeAsAMissingId() {
        // Answering differently would tell a member which order ids exist on other accounts.
        int missingId = ORDER_ID;
        int otherMembersId = ORDER_ID + 1;
        when(refundService.requestRefund(TOKEN, missingId, "reason"))
                .thenThrow(new EntityNotFoundException("OrderReceipt", missingId));
        when(refundService.requestRefund(TOKEN, otherMembersId, "reason"))
                .thenThrow(new UnauthorizedActionException("refund order " + otherMembersId, 42));

        RefundPresenter.Outcome missing = presenter.requestRefund(TOKEN, missingId, "reason");
        RefundPresenter.Outcome otherMembers = presenter.requestRefund(TOKEN, otherMembersId, "reason");

        assertInstanceOf(RefundPresenter.Outcome.NotFound.class, missing);
        assertEquals(missing, otherMembers,
                "another member's order must look exactly like a missing one");
    }

    @Test
    void GivenAnInvalidToken_WhenRequestRefund_ThenReturnsFailure() {
        when(refundService.requestRefund(TOKEN, ORDER_ID, "reason")).thenThrow(new InvalidTokenException());

        assertInstanceOf(RefundPresenter.Outcome.Failure.class,
                presenter.requestRefund(TOKEN, ORDER_ID, "reason"));
    }

    // ---- UI-B5: an internal failure goes through ServiceErrors (#187) ----

    @Test
    void GivenAnInternalFailure_WhenRequestRefund_ThenFailureCarriesTheGenericMessageAndServiceErrorsLogsIt() {
        when(refundService.requestRefund(TOKEN, ORDER_ID, "reason"))
                .thenThrow(new PersistenceException("could not execute statement; constraint [fk_refund_receipt]"));

        try (LogCapture serviceErrorsLog = LogCapture.of(ServiceErrors.class)) {
            RefundPresenter.Outcome outcome = presenter.requestRefund(TOKEN, ORDER_ID, "reason");

            RefundPresenter.Outcome.Failure failure = assertInstanceOf(RefundPresenter.Outcome.Failure.class, outcome);
            assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, failure.message());
            assertEquals(List.of(PersistenceException.class.getName()),
                    serviceErrorsLog.throwableClassNamesAt(Level.ERROR),
                    "the internal failure must be logged at ERROR with its exception by ServiceErrors");
        }
    }
}
