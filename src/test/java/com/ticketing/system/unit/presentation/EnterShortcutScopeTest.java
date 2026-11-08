package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_ID;
import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_TOKEN;
import static com.ticketing.system.unit.presentation.ViewHarness.field;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.ui.components.messaging.MdReplyBar;
import com.ticketing.system.ui.presenters.auth.AdminLoginPresenter;
import com.ticketing.system.ui.presenters.auth.LoginPresenter;
import com.ticketing.system.ui.presenters.auth.RegisterPresenter;
import com.ticketing.system.ui.presenters.auth.UsernameAvailabilityPresenter;
import com.ticketing.system.ui.presenters.order.CheckoutPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.auth.AdminLoginView;
import com.ticketing.system.ui.views.auth.LoginView;
import com.ticketing.system.ui.views.auth.RegisterView;
import com.ticketing.system.ui.views.order.CheckoutView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.router.AfterNavigationEvent;

/**
 * An Enter key shortcut listens on its form or its fields, not on the whole page, so Enter on another
 * button or link keeps its own action (#188, A-M1, U-A8; WCAG 2.1.1, 3.2.2). A shortcut that listens on the
 * UI prevents Enter's default everywhere: Enter on "Admin sign in →" signs in instead of following the link,
 * and Enter on the checkout's "Edit Cart" pays.
 *
 * <p>Flow sends a shortcut to the browser as a {@code keydown} listener, filtered on the key, on the
 * element it listens on ({@code ShortcutRegistration.listenOn}); that listener is what the browser acts on,
 * so the tests read where it is. It is registered once the owning view is attached and the response is
 * prepared, which the harness does. {@code ShortcutRegistration.getOwners()} would say the same, but the
 * views do not keep their registrations.
 */
class EnterShortcutScopeTest {

    // ---- F3: page-wide Enter shortcuts (#188) ----

    private static final Pattern ENTER = Pattern.compile("(?i)enter");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-07-15T17:00:00Z"), ZoneOffset.UTC);

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
    void GivenTheMemberSignIn_WhenItIsShown_ThenItsEnterShortcutListensOnTheFormNotThePage() {
        LoginView view = new LoginView(mock(LoginPresenter.class), mock(SessionIdentity.class));

        assertEnterScopedToFormHolding(view, "Sign in",
            field(view, TextField.class, "Username"), field(view, PasswordField.class, "Password"));
    }

    // Where the shortcut listens says nothing of what Enter does there, so press it (#189).
    @Test
    void GivenAFilledSignIn_WhenEnterIsPressedInThePassword_ThenTheSignInIsAttemptedWithWhatWasTyped() {
        LoginPresenter presenter = mock(LoginPresenter.class);
        when(presenter.attemptLogin(any(), any(), any())).thenReturn(new LoginPresenter.Outcome.InvalidCredentials());
        LoginView view = new LoginView(presenter, mock(SessionIdentity.class));
        harness.attach(view);
        field(view, TextField.class, "Username").setValue("alice");
        PasswordField password = field(view, PasswordField.class, "Password");
        password.setValue("secret-pw");

        pressEnterIn(password);

        verify(presenter).attemptLogin(eq("alice"), eq("secret-pw"), any());
    }

    @Test
    void GivenAFilledAdminSignIn_WhenEnterIsPressedInThePassword_ThenTheAdminSignInIsAttempted() {
        AdminLoginPresenter presenter = mock(AdminLoginPresenter.class);
        when(presenter.attemptAdminLogin(any(), any())).thenReturn(new AdminLoginPresenter.Outcome.InvalidCredentials());
        AdminLoginView view = new AdminLoginView(presenter);
        harness.attach(view);
        field(view, TextField.class, "Admin username").setValue("admin");
        PasswordField password = field(view, PasswordField.class, "Password");
        password.setValue("secret-pw");

        pressEnterIn(password);

        verify(presenter).attemptAdminLogin("admin", "secret-pw");
    }

    @Test
    void GivenTheRegistration_WhenItIsShown_ThenItsEnterShortcutListensOnTheFormNotThePage() {
        RegisterView view = new RegisterView(mock(RegisterPresenter.class), mock(UsernameAvailabilityPresenter.class),
            mock(SessionIdentity.class));

        assertEnterScopedToFormHolding(view, "Create account",
            field(view, TextField.class, "Username"), field(view, EmailField.class, "Email"),
            field(view, IntegerField.class, "Age"), field(view, PasswordField.class, "Password"),
            field(view, PasswordField.class, "Confirm password"));
    }

    @Test
    void GivenTheAdminSignIn_WhenItIsShown_ThenItsEnterShortcutListensOnTheFormNotThePage() {
        AdminLoginView view = new AdminLoginView(mock(AdminLoginPresenter.class));

        assertEnterScopedToFormHolding(view, "Sign in",
            field(view, TextField.class, "Admin username"), field(view, PasswordField.class, "Password"));
    }

    // Enter on "Edit Cart" must open the cart, never pay.
    @Test
    void GivenTheCheckout_WhenItIsShown_ThenItsPayShortcutListensOnThePaymentFormAndNotOnEditCart() {
        ViewHarness.signInMember();
        CheckoutPresenter presenter = mock(CheckoutPresenter.class);
        when(presenter.resolveIdentity()).thenReturn(new CheckoutPresenter.Identity(true, MEMBER_TOKEN, null, MEMBER_ID));
        when(presenter.loadOrder()).thenReturn(new CheckoutPresenter.LoadOutcome.Loaded(order(),
            new CheckoutPresenter.Pricing(1000, 1000)));
        CheckoutView view = new CheckoutView(presenter, CLOCK);
        harness.attach(view);
        view.beforeEnter(routeTo());
        view.afterNavigation(mock(AfterNavigationEvent.class));

        List<Element> hosts = enterHosts();

        assertNotPageWide(hosts);
        Element cardNumber = field(view, TextField.class, "Card number").getElement();
        assertTrue(hosts.stream().anyMatch(host -> A11y.within(cardNumber, host)),
            "the pay shortcut must listen on the payment form that holds the card fields; it listens on: "
                + describe(hosts));
        Element editCart = ViewHarness.button(view, "Edit Cart").getElement();
        assertFalse(hosts.stream().anyMatch(host -> A11y.within(editCart, host)),
            "Enter on \"Edit Cart\" must not pay; the pay shortcut listens on: " + describe(hosts));
    }

    @Test
    void GivenAReplyBar_WhenItIsShown_ThenItsSendShortcutListensOnTheBarNotThePage() {
        MdReplyBar bar = new MdReplyBar();
        harness.attach(bar);

        List<Element> hosts = enterHosts();

        assertNotPageWide(hosts);
        Element input = ViewHarness.find(bar, TextField.class).get(0).getElement();
        assertTrue(hosts.stream().anyMatch(host -> A11y.within(input, host) && A11y.within(host, bar.getElement())),
            "the send shortcut must listen on the reply field or its bar; it listens on: " + describe(hosts));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    // Enter in each field submits, so the shortcut listens on the form or on the fields themselves; and no
    // element it listens on holds another button or link than the form's own submit button, on which
    // Enter would otherwise be taken over.
    private void assertEnterScopedToFormHolding(Component view, String submitLabel, Component... fields) {
        harness.attach(view);
        List<Element> hosts = enterHosts();

        assertNotPageWide(hosts);
        for (Component f : fields) {
            assertTrue(hosts.stream().anyMatch(host -> A11y.within(f.getElement(), host)),
                "Enter in \"" + f.getElement().getProperty("label") + "\" must submit: the shortcut must listen on "
                    + "the form or the field; it listens on: " + describe(hosts));
        }
        List<String> othersTakenOver = hosts.stream()
            .flatMap(host -> A11y.elements(host).stream())
            .filter(e -> A11y.isButtonOrLink(e) && !submitLabel.equals(A11y.name(e)))
            .map(A11y::describe)
            .toList();
        assertTrue(othersTakenOver.isEmpty(),
            "Enter on these must keep its own action, but the shortcut listens around them: "
                + othersTakenOver);
    }

    // A key press in the field, as the browser reports it: a keydown that bubbles from the field up to the
    // element the Enter shortcut listens on.
    private void pressEnterIn(Component field) {
        Element host = enterHosts().stream()
            .filter(e -> A11y.within(field.getElement(), e))
            .reduce((outer, inner) -> inner)
            .orElseThrow(() -> new AssertionError("no Enter shortcut listens around "
                + field.getElement().getProperty("label")));
        A11y.fireBubbling(field.getElement(), host, "keydown");
    }

    private static void assertNotPageWide(List<Element> hosts) {
        assertFalse(hosts.isEmpty(), "no Enter shortcut was sent to the browser at all");
        Element page = UI.getCurrent().getElement();
        assertFalse(hosts.contains(page),
            "an Enter shortcut listens on the whole page (the UI), so Enter on any other button or link runs it");
    }

    // Every element the browser is told to listen on for Enter, once the response is prepared.
    private List<Element> enterHosts() {
        harness.javaScriptCalls();
        return A11y.elements(UI.getCurrent().getElement()).stream()
            .filter(e -> A11y.listensForKey(e, ENTER))
            .toList();
    }

    private static String describe(List<Element> hosts) {
        return hosts.stream().map(e -> e.getTag() + e.getAttributeNames().sorted().toList()).toList().toString();
    }

    private static ActiveOrderDTO order() {
        LocalDateTime added = LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC);
        return new ActiveOrderDTO(MEMBER_ID, null, added, 300, 10.0,
            List.of(new ActiveOrderDTO.CartLineDTO(1, "Coldplay", 8, "A1", 10.0, added)));
    }
}
