package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.persistence.PersistenceException;

import com.ticketing.system.identity.application.service.MemberQueryService;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.ui.presenters.account.MyProfilePresenter;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * {@link MyProfilePresenter} shows the signed-in member's profile. A failure that is not a domain
 * rule is an internal error, and its text (SQL, constraint names, NPE details) must never reach the
 * member (CWE-209): the failure carries the generic text instead.
 */
class MyProfilePresenterTest {

    private static final String MEMBER_TOKEN = "member-jwt";
    private static final int MEMBER_USER_ID = 42;
    private static final String INTERNAL_FAILURE_TEXT =
            "could not extract ResultSet [select u.email from users u where u.user_id=?]";

    private MemberQueryService memberQueryService;
    private MyProfilePresenter presenter;

    @BeforeEach
    void setUp() {
        MapBackedVaadinSession.install();
        AuthSession.storeAuth(new AuthTokenDTO(MEMBER_TOKEN, 0L, MEMBER_USER_ID, "member"));
        memberQueryService = mock(MemberQueryService.class);
        presenter = new MyProfilePresenter(memberQueryService);
    }

    @AfterEach
    void tearDown() {
        MapBackedVaadinSession.uninstall();
    }

    @Test
    void GivenTheProfileLookupFailsInternally_WhenLoad_ThenTheReasonIsTheGenericText() {
        when(memberQueryService.getMemberProfile(MEMBER_USER_ID))
                .thenThrow(new PersistenceException(INTERNAL_FAILURE_TEXT));

        MyProfilePresenter.Outcome.Failure failure =
                assertInstanceOf(MyProfilePresenter.Outcome.Failure.class, presenter.load());

        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, failure.reason());
    }
}
