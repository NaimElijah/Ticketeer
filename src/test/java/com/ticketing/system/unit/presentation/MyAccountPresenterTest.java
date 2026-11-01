package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import com.ticketing.system.reporting.application.service.MemberAccountService;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.ui.presenters.account.MyAccountPresenter;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.support.ServiceErrors;

/** The purchase history view's presenter (UC-16): a failed load is shown as one, never as no purchases (#192). */
class MyAccountPresenterTest {

    // ---- Defect 8: member purchase history hides failures (#187, #192) ----

    private static final String TOKEN = "member-token";
    private static final int MEMBER_ID = 42;

    private MemberAccountService memberAccountService;
    private SessionIdentity identity;
    private MyAccountPresenter presenter;

    @BeforeEach
    void setUp() {
        MapBackedVaadinSession.install();
        AuthSession.storeAuth(new AuthTokenDTO(TOKEN, 0L, MEMBER_ID, "member42"));
        memberAccountService = mock(MemberAccountService.class);
        identity = mock(SessionIdentity.class);
        when(identity.memberToken()).thenReturn(TOKEN);
        when(identity.memberUserId()).thenReturn(MEMBER_ID);
        presenter = new MyAccountPresenter(memberAccountService, identity);
    }

    @AfterEach
    void tearDown() {
        MapBackedVaadinSession.uninstall();
    }

    // An empty list reads as "you bought nothing", so the view must be told the load failed.
    @Test
    void GivenHistoryServiceFails_WhenLoadHistory_ThenFailureOutcomeNotAnEmptyHistory() {
        when(memberAccountService.viewMyHistory(any(AuthTokenDTO.class)))
                .thenThrow(new DataAccessResourceFailureException("database unreachable"));

        MyAccountPresenter.Outcome outcome = presenter.loadHistory();

        assertInstanceOf(MyAccountPresenter.Outcome.Failure.class, outcome,
                "a failed load must give a failure outcome, got " + outcome);
    }

    // ---- Review follow-up 4: an admin on "My Tickets" (#187) ----

    // An admin's session stores a token too, and admin and member ids share one space (ADR-012), so the
    // stored token is no member credential: the visitor is resolved through SessionIdentity, which gives
    // an admin no member token. An admin has bought nothing, so the page shows an empty history, not an error.
    @Test
    void GivenAnAdminSession_WhenLoadHistory_ThenEmptySuccessAndTheServiceIsNeverCalled() {
        AuthSession.storeAuth(new AuthTokenDTO("admin-token", 0L, MEMBER_ID, "admin", true));
        when(identity.memberToken()).thenReturn(null);

        MyAccountPresenter.Outcome outcome = presenter.loadHistory();

        verify(memberAccountService, never()).viewMyHistory(any(AuthTokenDTO.class));
        MyAccountPresenter.Outcome.Success success = assertInstanceOf(MyAccountPresenter.Outcome.Success.class, outcome,
                "an admin gets an empty history, got " + outcome);
        assertTrue(success.history().records().isEmpty(), "an admin has no purchases: " + success.history());
    }

    // ---- Review follow-up 7b: the reason shown, signing out, and a successful load (#187, #192) ----

    // An internal failure's own text can name tables and constraints: only ServiceErrors' text may show.
    @Test
    void GivenAnInternalFailure_WhenLoadHistory_ThenTheReasonIsServiceErrorsTextNotTheRawMessage() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException(
                "could not execute statement; constraint [uk_order_receipts_order_key]");
        when(memberAccountService.viewMyHistory(any(AuthTokenDTO.class))).thenThrow(failure);

        MyAccountPresenter.Outcome.Failure outcome =
                assertInstanceOf(MyAccountPresenter.Outcome.Failure.class, presenter.loadHistory());

        assertEquals(ServiceErrors.userMessage(failure), outcome.reason());
        assertFalse(outcome.reason().contains("uk_order_receipts"), "the raw text must not show: " + outcome.reason());
    }

    @Test
    void GivenSignedOut_WhenLoadHistory_ThenEmptySuccess() {
        AuthSession.signOut();
        when(identity.memberToken()).thenReturn(null);

        MyAccountPresenter.Outcome.Success outcome =
                assertInstanceOf(MyAccountPresenter.Outcome.Success.class, presenter.loadHistory());

        assertTrue(outcome.history().records().isEmpty(), "a signed-out visitor has no purchases: " + outcome);
        verify(memberAccountService, never()).viewMyHistory(any(AuthTokenDTO.class));
    }

    @Test
    void GivenTheServiceReturnsAHistory_WhenLoadHistory_ThenSuccessCarriesThatHistory() {
        PurchaseHistoryDTO history = new PurchaseHistoryDTO(List.of());
        when(memberAccountService.viewMyHistory(any(AuthTokenDTO.class))).thenReturn(history);

        MyAccountPresenter.Outcome.Success outcome =
                assertInstanceOf(MyAccountPresenter.Outcome.Success.class, presenter.loadHistory());

        assertSame(history, outcome.history(), "the service's history is passed through unchanged");
    }
}
