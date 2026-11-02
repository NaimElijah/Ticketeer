package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.click;
import static com.ticketing.system.unit.presentation.ViewHarness.find;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.ui.components.kit.LkMenu;
import com.ticketing.system.ui.layouts.PlatformAdminLayout;
import com.ticketing.system.ui.presenters.auth.LoginPresenter;
import com.ticketing.system.ui.presenters.notifications.NotificationBellPresenter;
import com.ticketing.system.ui.security.SignOutFlow;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.auth.AdminLoginView;
import com.ticketing.system.ui.views.auth.LoginView;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.server.VaadinService;

/**
 * Members and administrators sign in at separate pages (ADR-012): the member sign-in points an
 * administrator to the admin one, and an administrator who signs out lands on the admin one (#187).
 */
class AdminSignInLinksTest {

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
    void GivenASignedInAdmin_WhenTheAdminSignsOut_ThenTheAdminSignInOpens() {
        ViewHarness.signInAdmin();
        SignOutFlow signOutFlow = mock(SignOutFlow.class);
        PlatformAdminLayout layout = new PlatformAdminLayout(signOutFlow, mock(NotificationBellPresenter.class));
        LkMenu.Item signOut = find(layout, LkMenu.Item.class).stream()
            .filter(item -> item.getElement().getTextRecursively().contains("Sign Out"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the admin menu has no Sign Out"));

        click(signOut);

        verify(signOutFlow).execute();
        assertEquals(List.of(AdminLoginView.class.getSimpleName()), harness.navigations());
    }

    @Test
    void GivenTheMemberSignIn_WhenItShows_ThenItLinksToTheAdminSignIn() {
        LoginView view = new LoginView(mock(LoginPresenter.class), mock(SessionIdentity.class));

        RouterLink adminLink = find(view, RouterLink.class).stream()
            .filter(link -> link.getText().contains("Admin sign in"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the member sign-in has no admin sign-in link"));

        // A RouterLink asks the router for its target's URL; the harness's router records which target.
        assertTrue(adminLink.isVisible(), "the admin sign-in link must show");
        assertTrue(mockingDetails(VaadinService.getCurrent().getRouter().getRegistry()).getInvocations().stream()
                .anyMatch(call -> List.of(call.getArguments()).contains(AdminLoginView.class)),
            "the admin sign-in link must point to AdminLoginView");
    }
}
