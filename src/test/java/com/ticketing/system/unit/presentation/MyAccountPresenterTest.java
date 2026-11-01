package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

/** The purchase history view's presenter (UC-16): a failed load is shown as one, never as no purchases (#192). */
class MyAccountPresenterTest {

    // ---- Defect 8: member purchase history hides failures (#187, #192) ----

    private static final String TOKEN = "member-token";
    private static final int MEMBER_ID = 42;

    private MemberAccountService memberAccountService;
    private MyAccountPresenter presenter;

    @BeforeEach
    void setUp() {
        MapBackedVaadinSession.install();
        AuthSession.storeAuth(new AuthTokenDTO(TOKEN, 0L, MEMBER_ID, "member42"));
        memberAccountService = mock(MemberAccountService.class);
        presenter = new MyAccountPresenter(memberAccountService);
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
}
