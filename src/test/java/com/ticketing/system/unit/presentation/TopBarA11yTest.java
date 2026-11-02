package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.founderOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.ui.layouts.MainLayout;
import com.ticketing.system.ui.layouts.WorkspaceLayout;
import com.ticketing.system.ui.presenters.notifications.NotificationBellPresenter;
import com.ticketing.system.ui.presenters.order.CartBadgePresenter;
import com.ticketing.system.ui.security.SignOutFlow;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.popover.Popover;
import com.vaadin.flow.dom.Element;

/**
 * The top bar's brand is a link home, and the avatar button says it opens the account menu of the member
 * signed in (#188, U-A1, U-A2, A-m4, P-M1, P-m7; WCAG 2.1.1, 2.4.4, 4.1.2). Today the brand is a span with
 * a click listener, out of reach of a keyboard, and the avatar is announced by its initials ("AL").
 */
class TopBarA11yTest {

    // ---- F10: top bar brand and avatar (#188) ----

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-07-15T17:00:00Z"), ZoneOffset.UTC);
    private static final String BRAND = "TicketHub";
    // ViewHarness signs the member in as "alice".
    private static final String MEMBER_NAME = "alice";
    private static final String SIGN_OUT = "Sign Out";

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
        ViewHarness.signInMember();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void GivenTheBuyerShell_WhenItRenders_ThenTheBrandIsALinkHome() {
        assertBrandIsALink(mainLayout());
    }

    @Test
    void GivenTheWorkspaceShell_WhenItRenders_ThenTheBrandIsALinkHome() {
        harness.memberships(founderOf(3, "Acme"));

        assertBrandIsALink(new WorkspaceLayout(mock(SignOutFlow.class), mock(NotificationBellPresenter.class)));
    }

    @Test
    void GivenASignedInMember_WhenTheBuyerShellRenders_ThenTheAvatarIsNamedAccountMenuAndTheMember() {
        Element avatar = accountMenuTrigger(mainLayout());

        assertTrue(A11y.isButton(avatar), "the avatar must be a button: " + A11y.describe(avatar));
        assertEquals("Account menu, " + MEMBER_NAME, A11y.name(avatar),
            "the avatar must be named for what it opens and whose it is, not by the initials");
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static MainLayout mainLayout() {
        CartBadgePresenter badge = mock(CartBadgePresenter.class);
        when(badge.loadBadge()).thenReturn(new CartBadgePresenter.Outcome.Empty());
        return new MainLayout(badge, mock(SignOutFlow.class), mock(NotificationBellPresenter.class), CLOCK);
    }

    private static void assertBrandIsALink(Component layout) {
        boolean brandLink = A11y.elements(layout).stream()
            .anyMatch(e -> A11y.isLink(e) && A11y.name(e).contains(BRAND));
        assertTrue(brandLink, "the brand \"" + BRAND + "\" must be a link (an anchor with an href) home; it renders as: "
            + A11y.renderedAs(layout, BRAND));
    }

    // The account menu is the popover holding "Sign Out"; the avatar is what opens it.
    private static Element accountMenuTrigger(Component layout) {
        Popover menu = ViewHarness.find(layout, Popover.class).stream()
            .filter(p -> A11y.text(p.getElement()).contains(SIGN_OUT))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the shell has no account menu"));
        return menu.getTarget().getElement();
    }
}
