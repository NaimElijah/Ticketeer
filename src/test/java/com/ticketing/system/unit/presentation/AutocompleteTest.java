package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_ID;
import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_TOKEN;
import static com.ticketing.system.unit.presentation.ViewHarness.field;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.ActiveOrderDTO;
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
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.router.AfterNavigationEvent;

/**
 * The fields that ask for the user's own data say which data, so a browser or password manager can fill
 * them and assistive technology can tell the user what is asked (#188, U-A21; WCAG 1.3.5 Identify Input
 * Purpose). Each sign-in, registration and payment field carries the HTML token for its purpose
 * ({@code setAutocomplete}), so a user with a motor or memory impairment need not type their name, card
 * and password by hand every time.
 */
class AutocompleteTest {

    // ---- F16: autocomplete (#188) ----

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

    // ---- LoginView ----

    @Test
    void GivenTheSignInForm_WhenItRenders_ThenTheUsernameAsksForAUsername() {
        assertAutocomplete(field(login(), TextField.class, "Username"), "username");
    }

    @Test
    void GivenTheSignInForm_WhenItRenders_ThenThePasswordAsksForTheCurrentPassword() {
        assertAutocomplete(field(login(), PasswordField.class, "Password"), "current-password");
    }

    // ---- RegisterView ----

    @Test
    void GivenTheRegistrationForm_WhenItRenders_ThenTheUsernameAsksForAUsername() {
        assertAutocomplete(field(register(), TextField.class, "Username"), "username");
    }

    @Test
    void GivenTheRegistrationForm_WhenItRenders_ThenTheEmailAsksForAnEmail() {
        assertAutocomplete(field(register(), EmailField.class, "Email"), "email");
    }

    @Test
    void GivenTheRegistrationForm_WhenItRenders_ThenBothPasswordFieldsAskForANewPassword() {
        RegisterView view = register();

        assertAutocomplete(field(view, PasswordField.class, "Password"), "new-password");
        assertAutocomplete(field(view, PasswordField.class, "Confirm password"), "new-password");
    }

    // ---- AdminLoginView ----

    @Test
    void GivenTheAdminSignInForm_WhenItRenders_ThenTheUsernameAsksForAUsername() {
        assertAutocomplete(field(adminLogin(), TextField.class, "Admin username"), "username");
    }

    @Test
    void GivenTheAdminSignInForm_WhenItRenders_ThenThePasswordAsksForTheCurrentPassword() {
        assertAutocomplete(field(adminLogin(), PasswordField.class, "Password"), "current-password");
    }

    // ---- CheckoutView ----

    @Test
    void GivenTheCheckout_WhenItRenders_ThenEachCardFieldAsksForItsCardDatum() {
        ViewHarness.signInMember();
        CheckoutView view = checkout(new CheckoutPresenter.Identity(true, MEMBER_TOKEN, null, MEMBER_ID));

        assertAutocomplete(field(view, TextField.class, "Cardholder name"), "cc-name");
        assertAutocomplete(field(view, TextField.class, "Card number"), "cc-number");
        assertAutocomplete(field(view, TextField.class, "Expiry"), "cc-exp");
        assertAutocomplete(field(view, TextField.class, "CVC"), "cc-csc");
    }

    @Test
    void GivenAGuestCheckout_WhenItRenders_ThenTheGuestEmailAsksForAnEmail() {
        CheckoutView view = checkout(new CheckoutPresenter.Identity(false, null, "guest-session", 0));

        assertAutocomplete(field(view, EmailField.class, "Your email"), "email");
    }

    // ---- assertions and arrangement --------------------------------------------------------------

    private static void assertAutocomplete(Component field, String token) {
        Element e = field.getElement();
        String actual = e.hasAttribute("autocomplete") ? e.getAttribute("autocomplete") : e.getProperty("autocomplete");
        assertEquals(token, actual, "the field must say what it asks for with autocomplete=\"" + token + "\": "
            + A11y.describe(e));
    }

    private static LoginView login() {
        return new LoginView(mock(LoginPresenter.class), mock(SessionIdentity.class));
    }

    private static RegisterView register() {
        UsernameAvailabilityPresenter availability = mock(UsernameAvailabilityPresenter.class);
        when(availability.check(any())).thenReturn(new UsernameAvailabilityPresenter.Outcome.Available());
        return new RegisterView(mock(RegisterPresenter.class), availability, mock(SessionIdentity.class));
    }

    private static AdminLoginView adminLogin() {
        return new AdminLoginView(mock(AdminLoginPresenter.class));
    }

    private CheckoutView checkout(CheckoutPresenter.Identity identity) {
        CheckoutPresenter presenter = mock(CheckoutPresenter.class);
        when(presenter.resolveIdentity()).thenReturn(identity);
        LocalDateTime added = LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC);
        ActiveOrderDTO order = new ActiveOrderDTO(MEMBER_ID, null, added, 300, 10.0,
            List.of(new ActiveOrderDTO.CartLineDTO(5, "Coldplay", 8, "A1", 10.0, added)));
        when(presenter.loadOrder()).thenReturn(
            new CheckoutPresenter.LoadOutcome.Loaded(order, new CheckoutPresenter.Pricing(1000, 1000)));
        CheckoutView view = new CheckoutView(presenter, CLOCK);
        harness.attach(view);
        view.beforeEnter(routeTo());
        view.afterNavigation(mock(AfterNavigationEvent.class));
        return view;
    }
}
