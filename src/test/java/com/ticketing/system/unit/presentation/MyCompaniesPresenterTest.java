package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.organization.application.dto.UserCompanyDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.ui.presenters.company.MyCompaniesPresenter;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.FreshGuestSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import com.ticketing.system.ui.session.SessionIdentity;

/**
 * {@link MyCompaniesPresenter} answers with the signed-in member's companies, and only a member's.
 * The member is resolved through {@link SessionIdentity}: an admin's session stores the admin's id,
 * and admin and member ids share one id space (ADR-012), so the stored id alone would show an admin
 * the companies of the member with the same id, and count the admin as their owner.
 */
class MyCompaniesPresenterTest {

    private static final String MEMBER_TOKEN = "member-jwt";
    private static final String ADMIN_TOKEN = "admin-jwt";
    private static final int MEMBER_USER_ID = 42;
    private static final int COMPANY_ID = 7;
    private static final long TOKEN_EXPIRY_EPOCH_MILLIS = 0L;
    private static final UserCompanyDTO MEMBERS_COMPANY = new UserCompanyDTO(COMPANY_ID, "Acme", "desc",
            "acme@example.com", "Founder", "ACTIVE", 1, 0, List.of());

    private CompanyManagementService companyManagementService;
    private MyCompaniesPresenter presenter;

    @BeforeEach
    void setUp() {
        MapBackedVaadinSession.install();
        companyManagementService = mock(CompanyManagementService.class);
        when(companyManagementService.listForUser(MEMBER_USER_ID)).thenReturn(List.of(MEMBERS_COMPANY));
        when(companyManagementService.isOwnerOf(MEMBER_USER_ID, COMPANY_ID)).thenReturn(true);
        AuthenticationService authenticationService = mock(AuthenticationService.class);
        when(authenticationService.liveMemberId(MEMBER_TOKEN)).thenReturn(OptionalInt.of(MEMBER_USER_ID));
        SessionIdentity identity = new SessionIdentity(authenticationService, new FreshGuestSession(authenticationService));
        presenter = new MyCompaniesPresenter(companyManagementService, identity);
    }

    @AfterEach
    void tearDown() {
        MapBackedVaadinSession.uninstall();
    }

    @Test
    void GivenAdminSessionWithAMembersId_WhenLoad_ThenNotAuthenticated() {
        signInAsAdminWithTheMembersId();

        MyCompaniesPresenter.Outcome outcome = presenter.load();

        assertInstanceOf(MyCompaniesPresenter.Outcome.NotAuthenticated.class, outcome,
                "an admin is no member, so must not see the companies of the member with the admin's id; got "
                        + outcome);
        verify(companyManagementService, never()).listForUser(anyInt());
    }

    @Test
    void GivenAdminSessionWithAMembersId_WhenCurrentCompany_ThenNone() {
        signInAsAdminWithTheMembersId();

        assertNull(presenter.currentCompany(), "an admin has no current company of the member's");
        verify(companyManagementService, never()).listForUser(anyInt());
    }

    @Test
    void GivenAdminSessionWithAMembersId_WhenIsOwnerOfTheMembersCompany_ThenFalse() {
        signInAsAdminWithTheMembersId();

        assertFalse(presenter.isOwnerOf(COMPANY_ID), "an admin does not own the member's company");
        verify(companyManagementService, never()).isOwnerOf(anyInt(), anyInt());
    }

    @Test
    void GivenMemberSession_WhenLoad_ThenTheMembersCompanies() {
        AuthSession.storeAuth(new AuthTokenDTO(MEMBER_TOKEN, TOKEN_EXPIRY_EPOCH_MILLIS, MEMBER_USER_ID, "member"));

        MyCompaniesPresenter.Outcome.Success success =
                assertInstanceOf(MyCompaniesPresenter.Outcome.Success.class, presenter.load());

        assertEquals(List.of(MEMBERS_COMPANY), success.companies());
    }

    private static void signInAsAdminWithTheMembersId() {
        AuthSession.storeAuth(new AuthTokenDTO(ADMIN_TOKEN, TOKEN_EXPIRY_EPOCH_MILLIS, MEMBER_USER_ID, "admin", true));
    }
}
