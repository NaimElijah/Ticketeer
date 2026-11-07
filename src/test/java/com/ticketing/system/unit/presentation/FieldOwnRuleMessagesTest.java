package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.field;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.ui.presenters.auth.AdminLoginPresenter;
import com.ticketing.system.ui.presenters.auth.LoginPresenter;
import com.ticketing.system.ui.presenters.auth.RegisterPresenter;
import com.ticketing.system.ui.presenters.auth.UsernameAvailabilityPresenter;
import com.ticketing.system.ui.presenters.company.CompanyRegistrationPresenter;
import com.ticketing.system.ui.presenters.company.ManagerInvitationPresenter;
import com.ticketing.system.ui.presenters.company.MyCompaniesPresenter;
import com.ticketing.system.ui.presenters.messaging.AdminComplaintQueuePresenter;
import com.ticketing.system.ui.presenters.messaging.AdminSendMessagesPresenter;
import com.ticketing.system.ui.presenters.messaging.NewInquiryPresenter;
import com.ticketing.system.ui.presenters.messaging.SubmitComplaintPresenter;
import com.ticketing.system.ui.presenters.order.CheckoutPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.admin.AdminComplaintRespondView;
import com.ticketing.system.ui.views.admin.AdminSendMessagesView;
import com.ticketing.system.ui.views.auth.AdminLoginView;
import com.ticketing.system.ui.views.auth.LoginView;
import com.ticketing.system.ui.views.auth.RegisterView;
import com.ticketing.system.ui.views.company.CompanyRegistrationView;
import com.ticketing.system.ui.views.company.ManagerInvitationView;
import com.ticketing.system.ui.views.company.OwnerAppointmentView;
import com.ticketing.system.ui.views.messaging.NewInquiryView;
import com.ticketing.system.ui.views.messaging.SubmitComplaintView;
import com.ticketing.system.ui.views.order.CheckoutView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.shared.HasValidationProperties;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.AfterNavigationEvent;

/**
 * A field that its own rule (required, a range, a length, a format) marks invalid says what is wrong (#189,
 * WCAG 3.3.1). A Vaadin field validates itself on every change, and without i18n text for the rule it breaks
 * it turns red with a blank message. Each test attaches the page, gives one field a value its own rule refuses,
 * and checks that the field is invalid and says why.
 */
class FieldOwnRuleMessagesTest {

    private static final String TYPED = "x";
    // Well formed, and one character over the 254 registration accepts.
    private static final String EMAIL_OF_255_CHARACTERS = "a".repeat(64) + "@" + "b".repeat(186) + ".com";
    private static final String MALFORMED_EMAIL = "bob@";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-07-15T17:00:00Z"), ZoneOffset.UTC);
    private static final String GUEST_SESSION = "guest-1";
    // What the complaint presenter allows in the details, well above anything these tests type.
    private static final int COMPLAINT_DETAILS_LIMIT = 1000;

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    // ---- RegisterView ----

    @ParameterizedTest
    @ValueSource(strings = {"Password", "Confirm password"})
    void GivenTheRegisterPage_WhenARequiredTextFieldIsEmptied_ThenItSaysWhatToEnter(String label) {
        Component field = field(registerPage(), Component.class, label);

        typeThenEmpty(field);

        assertSaysWhatIsWrong(field, label);
    }

    @Test
    void GivenTheRegisterPage_WhenTheEmailIsEmptied_ThenItSaysWhatToEnter() {
        EmailField email = field(registerPage(), EmailField.class, "Email");

        typeThenEmpty(email);

        assertSaysWhatIsWrong(email, "Email");
    }

    @Test
    void GivenTheRegisterPage_WhenTheEmailIsLongerThanItsMaximum_ThenItSaysWhatIsWrong() {
        EmailField email = field(registerPage(), EmailField.class, "Email");

        email.setValue(EMAIL_OF_255_CHARACTERS);

        assertSaysWhatIsWrong(email, "an email of 255 characters");
    }

    @Test
    void GivenTheRegisterPage_WhenTheAgeIsBelowItsMinimum_ThenItSaysWhatIsWrong() {
        IntegerField age = field(registerPage(), IntegerField.class, "Age");

        age.setValue(RegisterPresenter.MIN_AGE - 1);

        assertSaysWhatIsWrong(age, "an age below the minimum");
    }

    @Test
    void GivenTheRegisterPage_WhenTheAgeIsEmptied_ThenItSaysWhatToEnter() {
        IntegerField age = field(registerPage(), IntegerField.class, "Age");
        age.setValue(RegisterPresenter.MIN_AGE);

        age.clear();

        assertSaysWhatIsWrong(age, "an emptied age");
    }

    private RegisterView registerPage() {
        UsernameAvailabilityPresenter availability = mock(UsernameAvailabilityPresenter.class);
        when(availability.check(any())).thenReturn(new UsernameAvailabilityPresenter.Outcome.Empty());
        RegisterView view = new RegisterView(mock(RegisterPresenter.class), availability, mock(SessionIdentity.class));
        harness.attach(view);
        return view;
    }

    // ---- CheckoutView, as a guest ----

    @ParameterizedTest
    @ValueSource(strings = {"Cardholder name", "Card number", "Expiry", "CVC"})
    void GivenTheGuestCheckout_WhenARequiredCardFieldIsEmptied_ThenItSaysWhatToEnter(String label) {
        Component field = field(guestCheckout(), Component.class, label);

        typeThenEmpty(field);

        assertSaysWhatIsWrong(field, label);
    }

    @Test
    void GivenTheGuestCheckout_WhenTheEmailIsEmptied_ThenItSaysWhatToEnter() {
        EmailField email = field(guestCheckout(), EmailField.class, "Your email");

        typeThenEmpty(email);

        assertSaysWhatIsWrong(email, "an emptied guest email");
    }

    @Test
    void GivenTheGuestCheckout_WhenTheEmailIsMalformed_ThenItSaysWhatIsWrong() {
        EmailField email = field(guestCheckout(), EmailField.class, "Your email");

        email.setValue(MALFORMED_EMAIL);

        assertSaysWhatIsWrong(email, "\"" + MALFORMED_EMAIL + "\"");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 121})
    void GivenTheGuestCheckout_WhenTheAgeIsOutsideItsRange_ThenItSaysWhatIsWrong(int age) {
        IntegerField field = field(guestCheckout(), IntegerField.class, "Your age");

        field.setValue(age);

        assertSaysWhatIsWrong(field, "an age of " + age);
    }

    @Test
    void GivenTheGuestCheckout_WhenTheAgeIsEmptied_ThenItSaysWhatToEnter() {
        IntegerField field = field(guestCheckout(), IntegerField.class, "Your age");
        field.setValue(RegisterPresenter.MIN_AGE);

        field.clear();

        assertSaysWhatIsWrong(field, "an emptied guest age");
    }

    private CheckoutView guestCheckout() {
        CheckoutPresenter presenter = mock(CheckoutPresenter.class);
        when(presenter.resolveIdentity()).thenReturn(new CheckoutPresenter.Identity(false, null, GUEST_SESSION, 0));
        LocalDateTime added = LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC);
        ActiveOrderDTO order = new ActiveOrderDTO(0, GUEST_SESSION, added, 300, 10.0,
            List.of(new ActiveOrderDTO.CartLineDTO(5, "Coldplay", 8, "A1", 10.0, added)));
        when(presenter.loadOrder()).thenReturn(
            new CheckoutPresenter.LoadOutcome.Loaded(order, new CheckoutPresenter.Pricing(1000, 1000)));
        CheckoutView view = new CheckoutView(presenter, CLOCK);
        harness.attach(view);
        view.beforeEnter(routeTo());
        view.afterNavigation(mock(AfterNavigationEvent.class));
        return view;
    }

    // ---- LoginView ----

    @ParameterizedTest
    @ValueSource(strings = {"Username", "Password"})
    void GivenTheSignInPage_WhenARequiredFieldIsEmptied_ThenItSaysWhatToEnter(String label) {
        LoginView view = new LoginView(mock(LoginPresenter.class), mock(SessionIdentity.class));
        harness.attach(view);
        Component field = field(view, Component.class, label);

        typeThenEmpty(field);

        assertSaysWhatIsWrong(field, label);
    }

    // ---- AdminLoginView ----

    @ParameterizedTest
    @ValueSource(strings = {"Admin username", "Password"})
    void GivenTheAdminSignInPage_WhenARequiredFieldIsEmptied_ThenItSaysWhatToEnter(String label) {
        AdminLoginView view = new AdminLoginView(mock(AdminLoginPresenter.class));
        harness.attach(view);
        Component field = field(view, Component.class, label);

        typeThenEmpty(field);

        assertSaysWhatIsWrong(field, label);
    }

    // ---- AdminComplaintRespondView ----

    @Test
    void GivenTheComplaintResponsePage_WhenTheResponseIsEmptied_ThenItSaysWhatToWrite() {
        ViewHarness.signInAdmin();
        AdminComplaintQueuePresenter presenter = mock(AdminComplaintQueuePresenter.class);
        when(presenter.loadOne(any(), anyString())).thenReturn(new AdminComplaintQueuePresenter.SingleOutcome.Success(
            InboxViewHarness.thread("c1", "COMPLAINT", "ADMIN_GROUP")));
        AdminComplaintRespondView view = new AdminComplaintRespondView(presenter, InboxViewHarness.displayTime());
        harness.attach(view);
        view.beforeEnter(routeTo("id", "c1"));
        TextArea reply = field(view, TextArea.class, "Your response");

        typeThenEmpty(reply);

        assertSaysWhatIsWrong(reply, "an emptied response");
    }

    // ---- AdminSendMessagesView ----

    @ParameterizedTest
    @ValueSource(strings = {"Subject", "Body"})
    void GivenTheSendMessagesPage_WhenARequiredFieldIsEmptied_ThenItSaysWhatToEnter(String label) {
        ViewHarness.signInAdmin();
        AdminSendMessagesPresenter presenter = mock(AdminSendMessagesPresenter.class);
        when(presenter.load(any())).thenReturn(new AdminSendMessagesPresenter.Outcome.Success(List.of()));
        AdminSendMessagesView view = new AdminSendMessagesView(presenter, InboxViewHarness.displayTime());
        harness.attach(view);
        Component field = field(view, Component.class, label);

        typeThenEmpty(field);

        assertSaysWhatIsWrong(field, label);
    }

    // ---- CompanyRegistrationView ----

    @ParameterizedTest
    @ValueSource(strings = {"Company name", "Description"})
    void GivenTheCompanyRegistrationPage_WhenARequiredFieldIsEmptied_ThenItSaysWhatToEnter(String label) {
        CompanyRegistrationView view = new CompanyRegistrationView(mock(CompanyRegistrationPresenter.class));
        harness.attach(view);
        Component field = field(view, Component.class, label);

        typeThenEmpty(field);

        assertSaysWhatIsWrong(field, label);
    }

    // ---- ManagerInvitationView ----

    @Test
    void GivenTheManagerInvitationPage_WhenTheInviteeIsEmptied_ThenItSaysWhatToEnter() {
        ManagerInvitationView view = new ManagerInvitationView(mock(ManagerInvitationPresenter.class));
        harness.attach(view);
        Component invitee = field(view, Component.class, "Username or email");

        typeThenEmpty(invitee);

        assertSaysWhatIsWrong(invitee, "an emptied invitee");
    }

    // ---- OwnerAppointmentView ----

    @Test
    void GivenTheOwnerAppointmentPage_WhenTheInviteeIsEmptied_ThenItSaysWhatToEnter() {
        OwnerAppointmentView view = new OwnerAppointmentView(mock(MyCompaniesPresenter.class));
        harness.attach(view);
        Component invitee = field(view, Component.class, "Invitee username or email");

        typeThenEmpty(invitee);

        assertSaysWhatIsWrong(invitee, "an emptied invitee");
    }

    // ---- NewInquiryView ----

    @ParameterizedTest
    @ValueSource(strings = {"Title", "Description"})
    void GivenTheNewInquiryPage_WhenARequiredFieldIsEmptied_ThenItSaysWhatToEnter(String label) {
        NewInquiryView view = new NewInquiryView(mock(NewInquiryPresenter.class));
        harness.attach(view);
        Component field = field(view, Component.class, label);

        typeThenEmpty(field);

        assertSaysWhatIsWrong(field, label);
    }

    // ---- SubmitComplaintView ----

    @ParameterizedTest
    @ValueSource(strings = {"Subject", "Details"})
    void GivenTheComplaintPage_WhenARequiredFieldIsEmptied_ThenItSaysWhatToEnter(String label) {
        SubmitComplaintPresenter presenter = mock(SubmitComplaintPresenter.class);
        when(presenter.maxDetailsLength(any())).thenReturn(COMPLAINT_DETAILS_LIMIT);
        SubmitComplaintView view = new SubmitComplaintView(presenter);
        harness.attach(view);
        Component field = field(view, Component.class, label);

        typeThenEmpty(field);

        assertSaysWhatIsWrong(field, label);
    }

    // ---- shared ----

    @SuppressWarnings("unchecked") // every field these tests type into holds text
    private static void typeThenEmpty(Component field) {
        HasValue<?, String> text = (HasValue<?, String>) field;
        text.setValue(TYPED);
        text.clear();
    }

    private static void assertSaysWhatIsWrong(Component component, String what) {
        HasValidationProperties field = (HasValidationProperties) component;
        assertTrue(field.isInvalid(), what + " must be invalid by the field's own rule");
        String message = field.getErrorMessage();
        assertTrue(message != null && !message.isBlank(),
            what + " must say what is wrong; its message is \"" + message + "\"");
    }
}
