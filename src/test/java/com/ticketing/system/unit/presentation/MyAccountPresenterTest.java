package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.ticketing.system.reporting.application.service.MemberAccountService;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.ui.presenters.account.MyAccountPresenter;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import com.ticketing.system.ui.session.SessionIdentity;

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
}
