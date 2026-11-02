package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.founderOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.ui.layouts.PlatformAdminLayout;
import com.ticketing.system.ui.layouts.WorkspaceLayout;
import com.ticketing.system.ui.presenters.notifications.NotificationBellPresenter;
import com.ticketing.system.ui.security.SignOutFlow;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.applayout.DrawerToggle;

/**
 * The workspace and admin shells offer a named button that opens their side navigation, so the drawer
 * stays reachable when a narrow window or 200% zoom collapses it (#188, A-M4, P-M8; WCAG 1.4.10, 2.1.1,
 * 4.1.2). Without a {@code DrawerToggle} the collapsed drawer cannot be opened at all.
 *
 * <p>The toggle must carry its own {@code aria-label}: the label the web component may set in the browser
 * cannot be checked here, and the shells' toggle should say which navigation it opens.
 */
class DrawerToggleTest {

    // ---- F7: DrawerToggle in the workspace and admin shells (#188) ----

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
    void GivenAnOwnerInTheWorkspace_WhenTheShellRenders_ThenItHasANamedDrawerToggle() {
        ViewHarness.signInMember();
        harness.memberships(founderOf(3, "Acme"));

        assertNamedDrawerToggle(new WorkspaceLayout(mock(SignOutFlow.class), mock(NotificationBellPresenter.class)));
    }

    @Test
    void GivenAnAdmin_WhenTheAdminShellRenders_ThenItHasANamedDrawerToggle() {
        ViewHarness.signInAdmin();

        assertNamedDrawerToggle(new PlatformAdminLayout(mock(SignOutFlow.class), mock(NotificationBellPresenter.class)));
    }

    private static void assertNamedDrawerToggle(Component layout) {
        List<DrawerToggle> toggles = ViewHarness.find(layout, DrawerToggle.class);
        assertFalse(toggles.isEmpty(), "the shell must offer a DrawerToggle to open its side navigation");
        for (DrawerToggle toggle : toggles) {
            String label = toggle.getAriaLabel().orElse(toggle.getElement().getProperty("ariaLabel", ""));
            assertTrue(!label.isBlank(),
                "the drawer toggle must carry an aria-label: " + A11y.describe(toggle.getElement()));
        }
    }
}
