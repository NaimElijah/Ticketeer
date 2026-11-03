package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.InboxViewHarness.displayTime;
import static com.ticketing.system.unit.presentation.InboxViewHarness.thread;
import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_ID;
import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_TOKEN;
import static com.ticketing.system.unit.presentation.ViewHarness.button;
import static com.ticketing.system.unit.presentation.ViewHarness.click;
import static com.ticketing.system.unit.presentation.ViewHarness.field;
import static com.ticketing.system.unit.presentation.ViewHarness.find;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.config.VenueLimitsProperties;
import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.ui.components.kit.LkTextRows;
import com.ticketing.system.ui.presenters.auth.AdminLoginPresenter;
import com.ticketing.system.ui.presenters.auth.LoginPresenter;
import com.ticketing.system.ui.presenters.auth.RegisterPresenter;
import com.ticketing.system.ui.presenters.auth.UsernameAvailabilityPresenter;
import com.ticketing.system.ui.presenters.company.CompanyRegistrationPresenter;
import com.ticketing.system.ui.presenters.company.EventManagementPresenter;
import com.ticketing.system.ui.presenters.company.ManagerInvitationPresenter;
import com.ticketing.system.ui.presenters.company.MyCompaniesPresenter;
import com.ticketing.system.ui.presenters.company.VenueMapPresenter;
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
import com.ticketing.system.ui.views.company.EventManagementView;
import com.ticketing.system.ui.views.company.ManagerInvitationView;
import com.ticketing.system.ui.views.company.OwnerAppointmentView;
import com.ticketing.system.ui.views.company.VenueMapEditorView;
import com.ticketing.system.ui.views.messaging.NewInquiryView;
import com.ticketing.system.ui.views.messaging.SubmitComplaintView;
import com.ticketing.system.ui.views.order.CheckoutView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.router.AfterNavigationEvent;

/**
 * A refused submit tells the user which field is wrong, on the field itself (#188, U-A7, P-M5, A-m3, U-B2;
 * WCAG 3.3.1, 3.3.2, 4.1.2), not only in a toast that vanishes after a few seconds and is tied to no field,
 * which a screen reader user hears pass before landing nowhere.
 * Each refused field must be marked invalid with an error message that says what is wrong, focus must move
 * to the first such field, and a field the form insists on must show a required indicator. A toast may
 * still show; it is just not the only carrier.
 *
 * <p>"Invalid" is Vaadin's {@code invalid} property (what {@code setInvalid} sets, rendered as
 * {@code aria-invalid}) or an {@code aria-invalid="true"}; the message is the field's {@code errorMessage}.
 * Focus is read from the focus calls the harness captures.
 */
class FieldValidationTest {

    // ---- F14: field-level validation (#188) ----

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-07-15T17:00:00Z"), ZoneOffset.UTC);
    private static final long TOTAL_CENTS = 1000;
    private static final String PAY_LABEL_PREFIX = "Pay ";

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
    void GivenAnEmptyUsername_WhenTheMemberSignsIn_ThenTheUsernameFieldIsInvalidAndFocused() {
        LoginView view = attached(new LoginView(mock(LoginPresenter.class), mock(SessionIdentity.class)));
        field(view, PasswordField.class, "Password").setValue("secret-pw");

        click(button(view, "Sign in"));

        assertRefusedAt(field(view, TextField.class, "Username"), "Username");
    }

    @Test
    void GivenAnEmptyPassword_WhenTheMemberSignsIn_ThenThePasswordFieldIsInvalidAndFocused() {
        LoginView view = attached(new LoginView(mock(LoginPresenter.class), mock(SessionIdentity.class)));
        field(view, TextField.class, "Username").setValue("alice");

        click(button(view, "Sign in"));

        assertRefusedAt(field(view, PasswordField.class, "Password"), "Password");
    }

    // ---- RegisterView ----

    @Test
    void GivenPasswordsThatDoNotMatch_WhenRegistering_ThenTheConfirmFieldIsInvalidAndFocused() {
        RegisterView view = registerView();
        field(view, TextField.class, "Username").setValue("alice");
        field(view, EmailField.class, "Email").setValue("alice@example.com");
        field(view, IntegerField.class, "Age").setValue(30);
        field(view, PasswordField.class, "Password").setValue("Secret-pw-123");
        field(view, PasswordField.class, "Confirm password").setValue("Other-pw-456");

        click(button(view, "Create account"));

        assertRefusedAt(field(view, PasswordField.class, "Confirm password"), "Confirm password");
    }

    @Test
    void GivenAnEmptyForm_WhenRegistering_ThenEveryEmptyFieldIsInvalidAndTheFirstIsFocused() {
        RegisterView view = registerView();

        click(button(view, "Create account"));

        assertInvalidWithMessage(field(view, EmailField.class, "Email"), "Email");
        assertInvalidWithMessage(field(view, IntegerField.class, "Age"), "Age");
        assertInvalidWithMessage(field(view, PasswordField.class, "Password"), "Password");
        assertInvalidWithMessage(field(view, PasswordField.class, "Confirm password"), "Confirm password");
        assertRefusedAt(field(view, TextField.class, "Username"), "Username");
    }

    // ---- AdminLoginView ----

    @Test
    void GivenAnEmptyAdminUsername_WhenTheAdminSignsIn_ThenTheUsernameFieldIsInvalidAndFocused() {
        AdminLoginView view = attached(new AdminLoginView(mock(AdminLoginPresenter.class)));
        field(view, PasswordField.class, "Password").setValue("secret-pw");

        click(button(view, "Sign in"));

        assertRefusedAt(field(view, TextField.class, "Admin username"), "Admin username");
    }

    // ---- CheckoutView ----

    @Test
    void GivenEmptyPaymentFields_WhenTheMemberPays_ThenEachIsInvalidAndTheCardholderIsFocused() {
        CheckoutView view = memberCheckout();

        click(payButton(view));

        assertInvalidWithMessage(field(view, TextField.class, "Card number"), "Card number");
        assertInvalidWithMessage(field(view, TextField.class, "Expiry"), "Expiry");
        assertInvalidWithMessage(field(view, TextField.class, "CVC"), "CVC");
        assertRefusedAt(field(view, TextField.class, "Cardholder name"), "Cardholder name");
    }

    @Test
    void GivenOnlyTheCvcIsEmpty_WhenTheMemberPays_ThenTheCvcFieldIsInvalidAndFocused() {
        CheckoutView view = memberCheckout();
        field(view, TextField.class, "Cardholder name").setValue("Alice Example");
        field(view, TextField.class, "Card number").setValue("4111 1111 1111 1111");
        field(view, TextField.class, "Expiry").setValue("12/30");

        click(payButton(view));

        assertRefusedAt(field(view, TextField.class, "CVC"), "CVC");
    }

    @Test
    void GivenACardNumberShorterThan16Digits_WhenTheMemberPays_ThenTheCardNumberIsInvalidAndFocused() {
        CheckoutView view = memberCheckout();
        field(view, TextField.class, "Cardholder name").setValue("Alice Example");
        field(view, TextField.class, "Card number").setValue("4111 1111");
        field(view, TextField.class, "Expiry").setValue("12/30");
        field(view, TextField.class, "CVC").setValue("123");

        click(payButton(view));

        assertRefusedAt(field(view, TextField.class, "Card number"), "Card number");
    }

    @Test
    void GivenTheCheckout_WhenItRenders_ThenTheExpiryFieldShowsItsFormatAsHelperText() {
        CheckoutView view = memberCheckout();

        String helper = field(view, TextField.class, "Expiry").getHelperText();

        assertTrue(helper != null && helper.replace(" ", "").toUpperCase(Locale.ROOT).contains("MM/YY"),
            "the expiry format must stay visible once the placeholder is gone (helper text \"MM / YY\"); "
                + "the helper text is: " + helper);
    }

    @Test
    void GivenAGuestWithNoEmail_WhenTheGuestPays_ThenTheEmailFieldIsInvalidAndFocused() {
        CheckoutView view = guestCheckout();
        fillCard(view);
        field(view, IntegerField.class, "Your age").setValue(30);

        click(payButton(view));

        assertRefusedAt(field(view, EmailField.class, "Your email"), "Your email");
    }

    @Test
    void GivenAGuestWithNoAge_WhenTheGuestPays_ThenTheAgeFieldIsInvalidAndFocused() {
        CheckoutView view = guestCheckout();
        fillCard(view);
        field(view, EmailField.class, "Your email").setValue("guest@example.com");

        click(payButton(view));

        assertRefusedAt(field(view, IntegerField.class, "Your age"), "Your age");
    }

    // ---- NewInquiryView ----

    @Test
    void GivenNoCompanyChosen_WhenTheMemberSubmitsAnInquiry_ThenTheCompanyFieldIsInvalidAndFocused() {
        ViewHarness.signInMember();
        NewInquiryView view = attached(new NewInquiryView(mock(NewInquiryPresenter.class)));
        field(view, TextField.class, "Title").setValue("Step-free access?");
        field(view, TextArea.class, "Description").setValue("Is there step-free access to the hall?");

        click(button(view, "Submit Inquiry"));

        assertRefusedAt(field(view, TextField.class, "Production company"), "Production company");
    }

    @Test
    void GivenTheInquiryForm_WhenItRenders_ThenTheCompanyFieldShowsARequiredIndicator() {
        NewInquiryView view = new NewInquiryView(mock(NewInquiryPresenter.class));

        assertRequired(field(view, TextField.class, "Production company"), "Production company");
    }

    // ---- SubmitComplaintView ----

    @Test
    void GivenAnEmptySubject_WhenTheMemberSubmitsAComplaint_ThenTheSubjectIsInvalidAndFocused() {
        ViewHarness.signInMember();
        SubmitComplaintView view = attached(new SubmitComplaintView(complaintPresenter()));
        field(view, TextArea.class, "Details").setValue("Charged twice for one ticket.");

        click(button(view, "Submit Complaint"));

        assertRefusedAt(field(view, TextField.class, "Subject"), "Subject");
    }

    @Test
    void GivenEmptyDetails_WhenTheMemberSubmitsAComplaint_ThenTheDetailsAreInvalidAndFocused() {
        ViewHarness.signInMember();
        SubmitComplaintView view = attached(new SubmitComplaintView(complaintPresenter()));
        field(view, TextField.class, "Subject").setValue("Charged twice");

        click(button(view, "Submit Complaint"));

        assertRefusedAt(field(view, TextArea.class, "Details"), "Details");
    }

    // ---- AdminSendMessagesView ----

    @Test
    void GivenAnEmptySubject_WhenTheAdminSendsAMessage_ThenTheSubjectIsInvalidAndFocused() {
        AdminSendMessagesView view = attached(sendMessagesView());
        field(view, TextArea.class, "Body").setValue("The venue moved.");

        click(button(view, "Send Message"));

        assertRefusedAt(field(view, TextField.class, "Subject"), "Subject");
    }

    @Test
    void GivenAnEmptyBody_WhenTheAdminSendsAMessage_ThenTheBodyIsInvalidAndFocused() {
        AdminSendMessagesView view = attached(sendMessagesView());
        field(view, TextField.class, "Subject").setValue("Venue change");

        click(button(view, "Send Message"));

        assertRefusedAt(field(view, TextArea.class, "Body"), "Body");
    }

    @Test
    void GivenTheComposer_WhenItRenders_ThenSubjectAndBodyShowARequiredIndicator() {
        AdminSendMessagesView view = sendMessagesView();

        assertRequired(field(view, TextField.class, "Subject"), "Subject");
        assertRequired(field(view, TextArea.class, "Body"), "Body");
    }

    // ---- AdminComplaintRespondView ----

    @Test
    void GivenAnEmptyReply_WhenTheAdminSendsTheResponse_ThenTheReplyIsInvalidAndFocused() {
        AdminComplaintRespondView view = attached(respondView());

        click(button(view, "Send & Resolve"));

        assertRefusedAt(field(view, TextArea.class, "Your response"), "Your response");
    }

    @Test
    void GivenTheRespondPage_WhenItRenders_ThenTheReplyShowsARequiredIndicator() {
        AdminComplaintRespondView view = respondView();

        assertRequired(field(view, TextArea.class, "Your response"), "Your response");
    }

    // ---- CompanyRegistrationView ----

    @Test
    void GivenNoDescription_WhenTheMemberRegistersACompany_ThenTheDescriptionIsInvalidAndFocused() {
        CompanyRegistrationView view = attached(new CompanyRegistrationView(mock(CompanyRegistrationPresenter.class)));
        field(view, TextField.class, "Company name").setValue("BlueWave Productions");

        click(button(view, "Register Company"));

        assertRefusedAt(field(view, TextArea.class, "Description"), "Description");
    }

    @Test
    void GivenTheRegistrationForm_WhenItRenders_ThenTheDescriptionShowsARequiredIndicator() {
        CompanyRegistrationView view = new CompanyRegistrationView(mock(CompanyRegistrationPresenter.class));

        assertRequired(field(view, TextArea.class, "Description"), "Description");
    }

    // ---- ManagerInvitationView and OwnerAppointmentView ----

    @Test
    void GivenNoInvitee_WhenTheOwnerInvitesAManager_ThenTheInviteeFieldIsInvalidAndFocused() {
        ViewHarness.signInMember();
        ManagerInvitationView view = attached(new ManagerInvitationView(mock(ManagerInvitationPresenter.class)));

        click(button(view, "Send Invitation"));

        assertRefusedAt(field(view, TextField.class, "Username or email"), "Username or email");
    }

    @Test
    void GivenNoInvitee_WhenTheOwnerAppointsACoOwner_ThenTheInviteeFieldIsInvalidAndFocused() {
        OwnerAppointmentView view = attached(new OwnerAppointmentView(mock(MyCompaniesPresenter.class)));

        click(button(view, "Send Invitation"));

        assertRefusedAt(field(view, TextField.class, "Invitee username or email"), "Invitee username or email");
    }

    // ---- EventManagementView, create mode ----

    @Test
    void GivenNoCategory_WhenTheOwnerCreatesAnEvent_ThenTheCategoryIsInvalidAndFocused() {
        EventManagementView view = createEventPage();
        field(view, TextField.class, "Title").setValue("Othello");

        click(button(view, "Create Event"));

        assertRefusedAt(field(view, Select.class, "Category"), "Category");
    }

    @Test
    void GivenNoCountryOrCity_WhenTheOwnerCreatesAnEvent_ThenBothAreInvalidAndTheCountryIsFocused() {
        EventManagementView view = createEventPage();
        fillTitleAndCategory(view);

        click(button(view, "Create Event"));

        assertInvalidWithMessage(field(view, TextField.class, "City"), "City");
        assertRefusedAt(field(view, TextField.class, "Country"), "Country");
    }

    @Test
    void GivenNoStartOrEnd_WhenTheOwnerCreatesAnEvent_ThenBothAreInvalidAndTheStartIsFocused() {
        EventManagementView view = createEventPage();
        fillTitleAndCategory(view);
        fillPlace(view);

        click(button(view, "Create Event"));

        assertInvalidWithMessage(field(view, DateTimePicker.class, "Ends"), "Ends");
        assertRefusedAt(field(view, DateTimePicker.class, "Starts"), "Starts");
    }

    @Test
    void GivenNoArtist_WhenTheOwnerCreatesAnEvent_ThenTheArtistRowIsInvalidAndFocused() {
        EventManagementView view = createEventPage();
        fillTitleAndCategory(view);
        fillPlace(view);
        fillSchedule(view);

        click(button(view, "Create Event"));

        TextField firstArtist = find(find(view, LkTextRows.class).get(0), TextField.class).get(0);
        assertRefusedAt(firstArtist, "the first Artists row");
    }

    @Test
    void GivenTheCreatePage_WhenItRenders_ThenEveryFieldCreateEnforcesShowsARequiredIndicator() {
        EventManagementView view = createEventPage();

        assertRequired(field(view, Select.class, "Category"), "Category");
        assertRequired(field(view, TextField.class, "Country"), "Country");
        assertRequired(field(view, TextField.class, "City"), "City");
        assertRequired(field(view, DateTimePicker.class, "Starts"), "Starts");
        assertRequired(field(view, DateTimePicker.class, "Ends"), "Ends");
    }

    // ---- VenueMapEditorView ----

    @Test
    void GivenAnEmptyZoneName_WhenTheOwnerAppliesTheZone_ThenTheZoneNameIsInvalidAndFocused() {
        VenueMapEditorView view = venueEditor(new VenueMapPresenter.ZoneOutcome.InvalidName());

        click(button(view, "Apply Zone"));

        assertRefusedAt(field(view, TextField.class, "Zone name"), "Zone name");
    }

    @Test
    void GivenAnInvalidPrice_WhenTheOwnerAppliesTheZone_ThenThePriceIsInvalidAndFocused() {
        VenueMapEditorView view = venueEditor(new VenueMapPresenter.ZoneOutcome.InvalidPrice());

        click(button(view, "Apply Zone"));

        assertRefusedAt(field(view, TextField.class, "Price"), "Price");
    }

    // ---- assertions ------------------------------------------------------------------------------

    private void assertRefusedAt(Component field, String name) {
        assertInvalidWithMessage(field, name);
        List<Element> focused = harness.focusTargets();
        assertFalse(focused.isEmpty(),
            "focus must move to \"" + name + "\", the first field refused; nothing was focused");
        Element last = focused.get(focused.size() - 1);
        assertTrue(A11y.within(last, field.getElement()),
            "focus must move to \"" + name + "\", the first field refused; it moved to " + A11y.describe(last));
    }

    private static void assertInvalidWithMessage(Component field, String name) {
        Element e = field.getElement();
        boolean invalid = e.getProperty("invalid", false) || "true".equals(e.getAttribute("aria-invalid"));
        assertTrue(invalid, "\"" + name + "\" was refused, so it must be marked invalid (setInvalid), not only "
            + "named in a toast: " + A11y.describe(e));
        String message = e.getProperty("errorMessage", "");
        assertFalse(message == null || message.isBlank(),
            "\"" + name + "\" must say what is wrong in its error message; it has none: " + A11y.describe(e));
    }

    private static void assertRequired(Component field, String name) {
        assertTrue(A11y.showsRequired(field), "\"" + name + "\" is enforced on submit, so it must show a required indicator: "
            + A11y.describe(field.getElement()));
    }

    // ---- arrangement -----------------------------------------------------------------------------

    private <T extends Component> T attached(T view) {
        harness.attach(view);
        return view;
    }

    private RegisterView registerView() {
        UsernameAvailabilityPresenter availability = mock(UsernameAvailabilityPresenter.class);
        when(availability.check(any())).thenReturn(new UsernameAvailabilityPresenter.Outcome.Available());
        return attached(new RegisterView(mock(RegisterPresenter.class), availability, mock(SessionIdentity.class)));
    }

    private CheckoutView memberCheckout() {
        ViewHarness.signInMember();
        return checkout(new CheckoutPresenter.Identity(true, MEMBER_TOKEN, null, MEMBER_ID));
    }

    private CheckoutView guestCheckout() {
        return checkout(new CheckoutPresenter.Identity(false, null, "guest-session", 0));
    }

    private CheckoutView checkout(CheckoutPresenter.Identity identity) {
        CheckoutPresenter presenter = mock(CheckoutPresenter.class);
        when(presenter.resolveIdentity()).thenReturn(identity);
        when(presenter.loadOrder()).thenReturn(
            new CheckoutPresenter.LoadOutcome.Loaded(order(), new CheckoutPresenter.Pricing(TOTAL_CENTS, TOTAL_CENTS)));
        CheckoutView view = attached(new CheckoutView(presenter, CLOCK));
        view.beforeEnter(routeTo());
        view.afterNavigation(mock(AfterNavigationEvent.class));
        return view;
    }

    private static void fillCard(CheckoutView view) {
        field(view, TextField.class, "Cardholder name").setValue("Guest Example");
        field(view, TextField.class, "Card number").setValue("4111 1111 1111 1111");
        field(view, TextField.class, "Expiry").setValue("12/30");
        field(view, TextField.class, "CVC").setValue("123");
    }

    private static NativeButton payButton(CheckoutView view) {
        return find(view, NativeButton.class).stream()
            .filter(b -> b.getElement().getTextRecursively().trim().startsWith(PAY_LABEL_PREFIX))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no Pay button on the checkout"));
    }

    private static ActiveOrderDTO order() {
        LocalDateTime added = LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC);
        return new ActiveOrderDTO(MEMBER_ID, null, added, 300, 10.0,
            List.of(new ActiveOrderDTO.CartLineDTO(5, "Coldplay", 8, "A1", 10.0, added)));
    }

    private static SubmitComplaintPresenter complaintPresenter() {
        SubmitComplaintPresenter presenter = mock(SubmitComplaintPresenter.class);
        when(presenter.maxDetailsLength(any())).thenReturn(1000);
        return presenter;
    }

    private static AdminSendMessagesView sendMessagesView() {
        ViewHarness.signInAdmin();
        AdminSendMessagesPresenter presenter = mock(AdminSendMessagesPresenter.class);
        when(presenter.load(any())).thenReturn(new AdminSendMessagesPresenter.Outcome.Success(List.of()));
        return new AdminSendMessagesView(presenter, displayTime());
    }

    private static AdminComplaintRespondView respondView() {
        ViewHarness.signInAdmin();
        AdminComplaintQueuePresenter presenter = mock(AdminComplaintQueuePresenter.class);
        when(presenter.loadOne(any(), anyString())).thenReturn(
            new AdminComplaintQueuePresenter.SingleOutcome.Success(thread("c1", "COMPLAINT", "ADMIN_GROUP")));
        AdminComplaintRespondView view = new AdminComplaintRespondView(presenter, displayTime());
        view.beforeEnter(routeTo("id", "c1"));
        return view;
    }

    private EventManagementView createEventPage() {
        ViewHarness.signInMember();
        EventManagementPresenter presenter = mock(EventManagementPresenter.class);
        when(presenter.defaultTimeZone()).thenReturn(ZoneOffset.UTC);
        EventManagementView view = attached(new EventManagementView(presenter));
        view.beforeEnter(routeTo("eventId", "new"));
        return view;
    }

    @SuppressWarnings("unchecked")
    private static void fillTitleAndCategory(EventManagementView view) {
        field(view, TextField.class, "Title").setValue("Othello");
        field(view, Select.class, "Category").setValue("THEATER");
    }

    private static void fillPlace(EventManagementView view) {
        field(view, TextField.class, "Country").setValue("Israel");
        field(view, TextField.class, "City").setValue("Tel Aviv");
    }

    private static void fillSchedule(EventManagementView view) {
        LocalDateTime start = LocalDateTime.of(2027, 9, 1, 20, 0);
        field(view, DateTimePicker.class, "Starts").setValue(start);
        field(view, DateTimePicker.class, "Ends").setValue(start.plusHours(3));
    }

    private VenueMapEditorView venueEditor(VenueMapPresenter.ZoneOutcome outcome) {
        VenueMapPresenter presenter = mock(VenueMapPresenter.class);
        when(presenter.validateZone(any(), any(), anyBoolean(), any(), any(), any(), any())).thenReturn(outcome);
        return attached(new VenueMapEditorView(presenter, new VenueLimitsProperties(12, 49_500, 1_000_000, 100_000)));
    }
}
