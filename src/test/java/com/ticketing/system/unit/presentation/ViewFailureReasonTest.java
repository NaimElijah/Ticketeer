package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.InboxViewHarness.displayTime;
import static com.ticketing.system.unit.presentation.InboxViewHarness.thread;
import static com.ticketing.system.unit.presentation.ViewHarness.button;
import static com.ticketing.system.unit.presentation.ViewHarness.click;
import static com.ticketing.system.unit.presentation.ViewHarness.field;
import static com.ticketing.system.unit.presentation.ViewHarness.founderOf;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static com.ticketing.system.unit.presentation.ViewHarness.text;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.AppointmentInfoDTO;
import com.ticketing.system.shared.dto.InvitationDTO;
import com.ticketing.system.shared.dto.MarketStateDTO;
import com.ticketing.system.shared.dto.SystemAnalyticsDTO;
import com.ticketing.system.ui.presenters.account.MyInvitationsPresenter;
import com.ticketing.system.ui.presenters.account.MyProfilePresenter;
import com.ticketing.system.ui.presenters.admin.AdminDashboardPresenter;
import com.ticketing.system.ui.presenters.admin.SystemAnalyticsPresenter;
import com.ticketing.system.ui.presenters.auth.AdminLoginPresenter;
import com.ticketing.system.ui.presenters.auth.LoginPresenter;
import com.ticketing.system.ui.presenters.auth.RegisterPresenter;
import com.ticketing.system.ui.presenters.auth.UsernameAvailabilityPresenter;
import com.ticketing.system.ui.presenters.company.EventManagementPresenter;
import com.ticketing.system.ui.presenters.company.ManagerInvitationPresenter;
import com.ticketing.system.ui.presenters.company.ManagerListPresenter;
import com.ticketing.system.ui.presenters.company.MyCompaniesPresenter;
import com.ticketing.system.ui.presenters.messaging.AdminInboxPresenter;
import com.ticketing.system.ui.presenters.messaging.AdminSendMessagesPresenter;
import com.ticketing.system.ui.presenters.messaging.CompanyInquiryInboxPresenter;
import com.ticketing.system.ui.presenters.messaging.NewInquiryPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.account.MyInvitationsView;
import com.ticketing.system.ui.views.account.MyProfileView;
import com.ticketing.system.ui.views.admin.AdminDashboardView;
import com.ticketing.system.ui.views.admin.AdminInboxView;
import com.ticketing.system.ui.views.admin.AdminSendMessagesView;
import com.ticketing.system.ui.views.admin.SystemAnalyticsView;
import com.ticketing.system.ui.views.auth.AdminLoginView;
import com.ticketing.system.ui.views.auth.LoginView;
import com.ticketing.system.ui.views.auth.RegisterView;
import com.ticketing.system.ui.views.company.CompanyInquiryRespondView;
import com.ticketing.system.ui.views.company.EventManagementView;
import com.ticketing.system.ui.views.company.ManagerInvitationView;
import com.ticketing.system.ui.views.company.ManagerListView;
import com.ticketing.system.ui.views.company.OwnerAppointmentView;
import com.ticketing.system.ui.views.messaging.NewInquiryView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;

/**
 * A view shows the reason its presenter's failure carries, instead of a generic "please try again"
 * (#187, UI-A2). Each test makes the presenter fail with a reason and drives the action as the visitor
 * would; the reason must reach the toast or the page.
 *
 * <p>Not reached here: the purchase-policy editor's save (its view needs the presenter's policy-tree
 * logic, which a mock cannot stand in for), the manager list's permission edit (its dialog), and the
 * cancel failures of the company event list and event management views, which
 * {@code EventCancelRefundFailureTest} (UI-A3) covers. The member sign-in's locked outcome is
 * {@code LoginPresenterLockedTest}.
 */
class ViewFailureReasonTest {

    // ---- UI-A2: views drop the presenter's Failure reason (#187) ----

    private static final String REASON = "reason-under-test";
    private static final String LOCKOUT = "Too many failed attempts. Try again in about 7 minutes.";

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    // ---- sign-in and registration ----

    @Test
    void GivenTheSignInFailsWithAReason_WhenAMemberSignsIn_ThenTheToastShowsTheReason() {
        LoginPresenter presenter = mock(LoginPresenter.class);
        when(presenter.attemptLogin(any(), any(), any())).thenReturn(new LoginPresenter.Outcome.Failure(REASON));
        LoginView view = loginView(presenter);
        field(view, TextField.class, "Username").setValue("alice");
        field(view, PasswordField.class, "Password").setValue("secret-pw");

        click(button(view, "Sign in"));

        assertToastShows(REASON);
    }

    @Test
    void GivenTheAdminAccountIsLocked_WhenTheAdminSignsIn_ThenTheToastShowsTheLockoutAndItsMinutes() {
        AdminLoginPresenter presenter = mock(AdminLoginPresenter.class);
        when(presenter.attemptAdminLogin(any(), any())).thenReturn(new AdminLoginPresenter.Outcome.Locked(LOCKOUT));
        AdminLoginView view = adminSignInFilled(presenter);

        click(button(view, "Sign in"));

        assertToastShows(LOCKOUT);
    }

    @Test
    void GivenTheAdminSignInFailsWithAReason_WhenTheAdminSignsIn_ThenTheToastShowsTheReason() {
        AdminLoginPresenter presenter = mock(AdminLoginPresenter.class);
        when(presenter.attemptAdminLogin(any(), any())).thenReturn(new AdminLoginPresenter.Outcome.Failure(REASON));
        AdminLoginView view = adminSignInFilled(presenter);

        click(button(view, "Sign in"));

        assertToastShows(REASON);
    }

    @Test
    void GivenTheRegistrationFailsWithAReason_WhenTheVisitorRegisters_ThenTheToastShowsTheReason() {
        assertRegistrationShows(new RegisterPresenter.Outcome.Failure(REASON));
    }

    @Test
    void GivenThePasswordIsTooWeakForAReason_WhenTheVisitorRegisters_ThenTheToastShowsTheReason() {
        assertRegistrationShows(new RegisterPresenter.Outcome.WeakPassword(REASON));
    }

    @Test
    void GivenTheDetailsAreInvalidForAReason_WhenTheVisitorRegisters_ThenTheToastShowsTheReason() {
        assertRegistrationShows(new RegisterPresenter.Outcome.InvalidInput(REASON));
    }

    // ---- member account ----

    @Test
    void GivenAcceptingFailsWithAReason_WhenTheMemberAcceptsAnInvitation_ThenTheToastShowsTheReason() {
        MyInvitationsPresenter presenter = invitationsPresenter();
        when(presenter.accept(any(), anyInt())).thenReturn(new MyInvitationsPresenter.ActionOutcome.Failure(REASON));
        MyInvitationsView view = new MyInvitationsView(presenter, displayTime());

        click(button(view, "Accept"));

        assertToastShows(REASON);
    }

    @Test
    void GivenDecliningFailsWithAReason_WhenTheMemberDeclinesAnInvitation_ThenTheToastShowsTheReason() {
        MyInvitationsPresenter presenter = invitationsPresenter();
        when(presenter.decline(any(), anyInt())).thenReturn(new MyInvitationsPresenter.ActionOutcome.Failure(REASON));
        MyInvitationsView view = new MyInvitationsView(presenter, displayTime());

        click(button(view, "Reject"));

        assertToastShows(REASON);
    }

    @Test
    void GivenTheProfileLoadFailsWithAReason_WhenTheMemberOpensTheProfile_ThenThePageShowsTheReason() {
        ViewHarness.signInMember();
        MyProfilePresenter presenter = mock(MyProfilePresenter.class);
        when(presenter.load()).thenReturn(new MyProfilePresenter.Outcome.Failure(REASON));

        MyProfileView view = new MyProfileView(presenter);

        assertPageShows(view, REASON);
    }

    @Test
    void GivenTheCompanySearchFailsWithAReason_WhenTheMemberSearchesForACompany_ThenTheToastShowsTheReason() {
        NewInquiryPresenter presenter = mock(NewInquiryPresenter.class);
        when(presenter.searchCompanies(anyString())).thenReturn(new NewInquiryPresenter.SearchOutcome.Failure(REASON));
        NewInquiryView view = new NewInquiryView(presenter);

        field(view, TextField.class, "Production company").setValue("Acme");

        assertToastShows(REASON);
    }

    // ---- company workspace ----

    @Test
    void GivenTheInvitationFailsWithAReason_WhenTheOwnerInvitesAManager_ThenTheToastShowsTheReason() {
        ViewHarness.signInMember();
        ManagerInvitationPresenter presenter = mock(ManagerInvitationPresenter.class);
        when(presenter.invite(any(), any(), any())).thenReturn(new ManagerInvitationPresenter.Outcome.Failure(REASON));
        ManagerInvitationView view = new ManagerInvitationView(presenter);
        field(view, TextField.class, "Username or email").setValue("carol");

        click(button(view, "Send Invitation"));

        assertToastShows(REASON);
    }

    @Test
    void GivenTheAppointmentFailsWithAReason_WhenTheOwnerAppointsACoOwner_ThenTheToastShowsTheReason() {
        MyCompaniesPresenter presenter = mock(MyCompaniesPresenter.class);
        when(presenter.appoint(any())).thenReturn(new MyCompaniesPresenter.AppointOutcome.Failure(REASON));
        OwnerAppointmentView view = new OwnerAppointmentView(presenter);
        field(view, TextField.class, "Invitee username or email").setValue("carol");

        click(button(view, "Send Invitation"));

        assertToastShows(REASON);
    }

    @Test
    void GivenRevokingFailsWithAReason_WhenTheOwnerConfirmsRevokingAManager_ThenTheToastShowsTheReason() {
        ViewHarness.signInMember();
        harness.memberships(founderOf(3, "Acme"));
        ManagerListPresenter presenter = mock(ManagerListPresenter.class);
        AppointmentInfoDTO manager = new AppointmentInfoDTO("a-1", 3, "Acme", 9, "carol", 42, "Manager", "ACTIVE",
            List.of("VIEW_SALES"), LocalDateTime.of(2027, 7, 1, 12, 0));
        when(presenter.loadRoster(any())).thenReturn(
            new ManagerListPresenter.Outcome.Success("Acme", List.of(manager), List.of()));
        when(presenter.revoke(any(), anyInt(), anyInt())).thenReturn(new ManagerListPresenter.ActionOutcome.Failure(REASON));
        ManagerListView view = new ManagerListView(presenter, displayTime());

        click(button(view, "Revoke"));
        assertTrue(harness.confirmOpenDialog(), "revoking asks for confirmation first");

        assertToastShows(REASON);
    }

    @Test
    void GivenTheEventLoadFailsWithAReason_WhenTheOwnerOpensTheEvent_ThenTheToastShowsTheReason() {
        ViewHarness.signInMember();
        EventManagementPresenter presenter = mock(EventManagementPresenter.class);
        when(presenter.load(any(), anyInt())).thenReturn(new EventManagementPresenter.LoadOutcome.Failure(REASON));
        EventManagementView view = new EventManagementView(presenter);

        view.beforeEnter(routeTo("eventId", "9"));

        assertToastShows(REASON);
    }

    @Test
    void GivenClosingFailsWithAReason_WhenTheCompanyClosesAnInquiry_ThenTheToastShowsTheReason() {
        ViewHarness.signInMember();
        CompanyInquiryInboxPresenter presenter = mock(CompanyInquiryInboxPresenter.class);
        when(presenter.loadOne(any(), any())).thenReturn(
            new CompanyInquiryInboxPresenter.SingleOutcome.Success(thread("c1", "INQUIRY", "COMPANY")));
        when(presenter.close(any(), any())).thenReturn(new CompanyInquiryInboxPresenter.ActionOutcome.Failure(REASON));
        CompanyInquiryRespondView view = new CompanyInquiryRespondView(presenter, displayTime());
        view.beforeEnter(routeTo("id", "c1"));

        click(button(view, "Close Inquiry"));

        assertToastShows(REASON);
    }

    // ---- platform admin ----

    @Test
    void GivenTheOverviewLoadFailsWithAReason_WhenTheAdminOpensTheDashboard_ThenThePageShowsTheReason() {
        AdminDashboardPresenter presenter = mock(AdminDashboardPresenter.class);
        when(presenter.load()).thenReturn(new AdminDashboardPresenter.Outcome.Failure(REASON));

        AdminDashboardView view = new AdminDashboardView(presenter);

        assertPageShows(view, REASON);
    }

    @Test
    void GivenClosingFailsWithAReason_WhenTheAdminClosesAConversation_ThenTheToastShowsTheReason() {
        ViewHarness.signInAdmin();
        AdminInboxPresenter presenter = mock(AdminInboxPresenter.class);
        when(presenter.load(any())).thenReturn(
            new AdminInboxPresenter.Outcome.Success(List.of(thread("c1", "DIRECT", "MEMBER"))));
        when(presenter.badge(any(), any())).thenReturn(Optional.empty());
        when(presenter.close(any(), any())).thenReturn(new AdminInboxPresenter.ActionOutcome.Failure(REASON));
        AdminInboxView view = new AdminInboxView(presenter, displayTime());

        click(button(view, "Close Conversation"));

        assertToastShows(REASON);
    }

    @Test
    void GivenTheMemberSearchFailsWithAReason_WhenTheAdminSearchesForRecipients_ThenTheToastShowsTheReason() {
        ViewHarness.signInAdmin();
        AdminSendMessagesPresenter presenter = mock(AdminSendMessagesPresenter.class);
        when(presenter.load(any())).thenReturn(new AdminSendMessagesPresenter.Outcome.Success(List.of()));
        when(presenter.searchUsers(any(), any())).thenReturn(new AdminSendMessagesPresenter.SearchOutcome.Failure(REASON));
        AdminSendMessagesView view = new AdminSendMessagesView(presenter, displayTime());

        field(view, TextField.class, "Recipients").setValue("al");

        assertToastShows(REASON);
    }

    @Test
    void GivenOpeningTheMarketIsRejectedForAReason_WhenTheAdminOpensIt_ThenTheToastShowsTheReason() {
        SystemAnalyticsPresenter presenter = analyticsPresenter();
        when(presenter.openMarket(any(), any())).thenReturn(new SystemAnalyticsPresenter.MarketActionOutcome.Rejected(REASON));
        SystemAnalyticsView view = new SystemAnalyticsView(presenter, displayTime());

        click(button(view, "Open Market"));

        assertToastShows(REASON);
    }

    @Test
    void GivenClosingTheMarketFailsWithAReason_WhenTheAdminClosesIt_ThenTheToastShowsTheReason() {
        SystemAnalyticsPresenter presenter = analyticsPresenter();
        when(presenter.closeMarket(any(), any())).thenReturn(new SystemAnalyticsPresenter.MarketActionOutcome.Failure(REASON));
        SystemAnalyticsView view = new SystemAnalyticsView(presenter, displayTime());

        click(button(view, "Close Market"));

        assertToastShows(REASON);
    }

    // ---- arrangement ----

    // One place per view to build it, so a constructor change touches one line.
    private static LoginView loginView(LoginPresenter presenter) {
        return new LoginView(presenter, mock(SessionIdentity.class));
    }

    private static RegisterView registerView(RegisterPresenter presenter, UsernameAvailabilityPresenter availability) {
        return new RegisterView(presenter, availability, mock(SessionIdentity.class));
    }

    private static AdminLoginView adminSignInFilled(AdminLoginPresenter presenter) {
        AdminLoginView view = new AdminLoginView(presenter);
        field(view, TextField.class, "Admin username").setValue("admin");
        field(view, PasswordField.class, "Password").setValue("secret-pw");
        return view;
    }

    private void assertRegistrationShows(RegisterPresenter.Outcome outcome) {
        RegisterPresenter presenter = mock(RegisterPresenter.class);
        when(presenter.attemptRegister(any(), any(), any(), anyInt(), any())).thenReturn(outcome);
        UsernameAvailabilityPresenter availability = mock(UsernameAvailabilityPresenter.class);
        when(availability.check(any())).thenReturn(new UsernameAvailabilityPresenter.Outcome.Available());
        RegisterView view = registerView(presenter, availability);
        field(view, TextField.class, "Username").setValue("alice");
        field(view, EmailField.class, "Email").setValue("alice@example.com");
        field(view, IntegerField.class, "Age").setValue(30);
        field(view, PasswordField.class, "Password").setValue("Secret-pw-123");
        field(view, PasswordField.class, "Confirm password").setValue("Secret-pw-123");

        click(button(view, "Create account"));

        assertToastShows(REASON);
    }

    private static MyInvitationsPresenter invitationsPresenter() {
        MyInvitationsPresenter presenter = mock(MyInvitationsPresenter.class);
        InvitationDTO invitation = new InvitationDTO("a-1", 3, "Acme", "Manager", "bob", List.of("VIEW_SALES"),
            "PENDING", LocalDateTime.of(2027, 7, 1, 12, 0));
        when(presenter.load(any())).thenReturn(new MyInvitationsPresenter.Outcome.Success(List.of(invitation), List.of()));
        return presenter;
    }

    private static SystemAnalyticsPresenter analyticsPresenter() {
        SystemAnalyticsPresenter presenter = mock(SystemAnalyticsPresenter.class);
        MarketStateDTO market = new MarketStateDTO("OPEN", null, null, true, true, null, true);
        SystemAnalyticsDTO analytics = new SystemAnalyticsDTO(0L, 0d, 0d, 0d, 0d, 0d, 0L, 0L, 0L, 0L, 0L, 5);
        when(presenter.load()).thenReturn(new SystemAnalyticsPresenter.Outcome.Success(market, analytics));
        return presenter;
    }

    private void assertToastShows(String reason) {
        List<String> toasts = harness.toasts();
        assertTrue(toasts.stream().anyMatch(toast -> toast.contains(reason)),
            "expected a toast with \"" + reason + "\", got: " + toasts);
    }

    private static void assertPageShows(Component view, String reason) {
        String shown = text(view);
        assertTrue(shown.contains(reason), "expected \"" + reason + "\" on the page: " + shown);
    }
}
