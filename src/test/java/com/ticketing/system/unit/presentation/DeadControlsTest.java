package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.text;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.MemberDTO;
import com.ticketing.system.ui.layouts.PlatformAdminLayout;
import com.ticketing.system.ui.presenters.account.MyProfilePresenter;
import com.ticketing.system.ui.presenters.auth.LoginPresenter;
import com.ticketing.system.ui.presenters.auth.RegisterPresenter;
import com.ticketing.system.ui.presenters.auth.UsernameAvailabilityPresenter;
import com.ticketing.system.ui.presenters.landing.LandingPresenter;
import com.ticketing.system.ui.presenters.notifications.NotificationBellPresenter;
import com.ticketing.system.ui.security.SignOutFlow;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.account.MyProfileView;
import com.ticketing.system.ui.views.auth.LoginView;
import com.ticketing.system.ui.views.auth.RegisterView;
import com.ticketing.system.ui.views.landing.LandingView;
import com.vaadin.flow.component.Component;

/**
 * Controls that do nothing, and figures that are made up, are removed (#187, UI-A19): remember-me,
 * forgot-password (#104) and the Google and Apple sign-ins; the terms checkbox nothing checks; the
 * admin menu's Admin Settings; the profile's Verified badge (#103); the landing page's event counts.
 */
class DeadControlsTest {

    // ---- UI-A19: dead controls (#187) ----

    private static final Pattern MADE_UP_COUNT = Pattern.compile("\\d+ events");

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void GivenTheSignInPage_WhenItShows_ThenItOffersNoRememberMe() {
        assertAbsent(loginView(), "Remember me");
    }

    @Test
    void GivenTheSignInPage_WhenItShows_ThenItOffersNoForgotPassword() {
        assertAbsent(loginView(), "Forgot password");
    }

    @Test
    void GivenTheSignInPage_WhenItShows_ThenItOffersNoGoogleOrAppleSignIn() {
        LoginView view = loginView();

        assertAbsent(view, "Google");
        assertAbsent(view, "Apple");
    }

    @Test
    void GivenTheRegistrationPage_WhenItShows_ThenItHasNoTermsCheckbox() {
        assertAbsent(registerView(), "I agree to the Terms");
    }

    @Test
    void GivenTheAdminShell_WhenItShows_ThenTheAdminMenuHasNoAdminSettings() {
        ViewHarness.signInAdmin();

        PlatformAdminLayout layout = new PlatformAdminLayout(mock(SignOutFlow.class),
            mock(NotificationBellPresenter.class));

        assertAbsent(layout, "Admin Settings");
    }

    @Test
    void GivenAMemberProfile_WhenItShows_ThenItClaimsNoVerification() {
        ViewHarness.signInMember();
        MyProfilePresenter presenter = mock(MyProfilePresenter.class);
        when(presenter.load()).thenReturn(new MyProfilePresenter.Outcome.Success(
            new MemberDTO(ViewHarness.MEMBER_ID, "alice", "alice@example.com")));

        MyProfileView view = new MyProfileView(presenter);

        assertAbsent(view, "Verified");
    }

    @Test
    void GivenTheLanding_WhenItShows_ThenItShowsNoMadeUpEventCounts() {
        LandingPresenter presenter = mock(LandingPresenter.class);
        when(presenter.load()).thenReturn(new LandingPresenter.Outcome.Success(List.of(), List.of()));

        String shown = text(new LandingView(presenter));

        assertFalse(MADE_UP_COUNT.matcher(shown).find(), "the landing shows made-up event counts: " + shown);
    }

    // One place per sign-in view to build it, so a constructor change touches one line.
    private static LoginView loginView() {
        return new LoginView(mock(LoginPresenter.class), mock(SessionIdentity.class));
    }

    private static RegisterView registerView() {
        return new RegisterView(mock(RegisterPresenter.class), mock(UsernameAvailabilityPresenter.class),
            mock(SessionIdentity.class));
    }

    private static void assertAbsent(Component root, String control) {
        String shown = text(root);
        assertFalse(shown.contains(control), "\"" + control + "\" still shows: " + shown);
    }
}
