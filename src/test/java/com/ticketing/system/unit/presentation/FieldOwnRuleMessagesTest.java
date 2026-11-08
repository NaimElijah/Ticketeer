package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.button;
import static com.ticketing.system.unit.presentation.ViewHarness.click;
import static com.ticketing.system.unit.presentation.ViewHarness.field;
import static com.ticketing.system.unit.presentation.ViewHarness.find;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.ticketing.system.catalog.config.VenueLimitsProperties;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.ui.presenters.ListOutcome;
import com.ticketing.system.ui.presenters.auth.AdminLoginPresenter;
import com.ticketing.system.ui.presenters.auth.LoginPresenter;
import com.ticketing.system.ui.presenters.auth.RegisterPresenter;
import com.ticketing.system.ui.presenters.auth.UsernameAvailabilityPresenter;
import com.ticketing.system.ui.presenters.catalog.BrowseEventsPresenter;
import com.ticketing.system.ui.presenters.company.CompanyEventListPresenter;
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
import com.ticketing.system.ui.views.catalog.BrowseEventsView;
import com.ticketing.system.ui.views.catalog.CatalogFilterSupport;
import com.ticketing.system.ui.views.company.CompanyEventListView;
import com.ticketing.system.ui.views.company.CompanyRegistrationView;
import com.ticketing.system.ui.views.company.EventManagementView;
import com.ticketing.system.ui.views.company.ManagerInvitationView;
import com.ticketing.system.ui.views.company.OwnerAppointmentView;
import com.ticketing.system.ui.views.company.VenueMapEditorView;
import com.ticketing.system.ui.views.messaging.NewInquiryView;
import com.ticketing.system.ui.views.messaging.SubmitComplaintView;
import com.ticketing.system.ui.views.order.CheckoutView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.shared.HasValidationProperties;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.AfterNavigationEvent;

/**
 * A field that its own rule (required, a range, a length, a format) marks invalid says what is wrong (#189,
 * WCAG 3.3.1). A Vaadin field validates itself on every change, and without i18n text for the rule it breaks
 * it turns red with a blank message. Each test attaches the page, gives one field a value its own rule refuses,
 * and checks that the field is invalid and says what that rule says, word for word, so a message wired to the
 * wrong rule fails too.
 */
class FieldOwnRuleMessagesTest {

    private static final String TYPED = "x";
    // Well formed, and one character over the 254 registration accepts.
    private static final String EMAIL_OF_255_CHARACTERS = "a".repeat(64) + "@" + "b".repeat(186) + ".com";
    private static final String MALFORMED_EMAIL = "bob@";
    private static final String SHORT_CARD_NUMBER = "4111";
    private static final String RESERVED_USERNAME = "admin";
    private static final String PASSWORD = "Secret-pw-123";
    private static final String UNMATCHED_SEARCH = "zzz";
    private static final String CHOOSE_COMPANY = "Please choose a production company.";
    private static final String ENTER_EMAIL = "Enter your email address.";
    private static final String ENTER_AGE = "Enter your age.";
    private static final String REGISTER_AGE_RANGE =
        "Enter an age from " + RegisterPresenter.MIN_AGE + " to " + RegisterPresenter.MAX_AGE + ".";
    private static final String GUEST_AGE_RANGE = "Enter an age from 1 to 120.";
    private static final String GUEST_EMAIL_FORMAT = "Enter an email address like you@example.com.";
    private static final String RATING_RANGE = "Enter a rating from 0 to 5.";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-07-15T17:00:00Z"), ZoneOffset.UTC);
    private static final String GUEST_SESSION = "guest-1";
    // What the complaint presenter allows in the details, well above anything these tests type.
    private static final int COMPLAINT_DETAILS_LIMIT = 1000;
    private static final String RATING = "Rating (0–5)";
    // Inside the price and rating filters' bounds, but off their steps of 10 and 0.5.
    private static final int PRICE_OFF_STEP = 15;
    private static final double RATING_OFF_STEP = 2.3;
    private static final VenueLimitsProperties VENUE_LIMITS = new VenueLimitsProperties(12, 49_500, 1_000_000, 100_000);
    private static final LocalDate START_DATE = LocalDate.of(2027, 8, 10);

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
    @CsvSource(delimiter = '|', value = {
        "Password         | Enter a password.",
        "Confirm password | Enter your password again."})
    void GivenTheRegisterPage_WhenARequiredTextFieldIsEmptied_ThenItSaysWhatToEnter(String label, String message) {
        Component field = field(registerPage(), Component.class, label);

        typeThenEmpty(field);

        assertSays(field, message, label);
    }

    @Test
    void GivenTheRegisterPage_WhenTheEmailIsEmptied_ThenItSaysWhatToEnter() {
        EmailField email = field(registerPage(), EmailField.class, "Email");

        typeThenEmpty(email);

        assertSays(email, ENTER_EMAIL, "Email");
    }

    @Test
    void GivenTheRegisterPage_WhenTheEmailIsLongerThanItsMaximum_ThenItSaysWhatIsWrong() {
        EmailField email = field(registerPage(), EmailField.class, "Email");

        email.setValue(EMAIL_OF_255_CHARACTERS);

        assertSays(email, "Enter at most " + RegisterPresenter.MAX_EMAIL_LENGTH + " characters.",
            "an email of 255 characters");
    }

    @Test
    void GivenTheRegisterPage_WhenTheAgeIsBelowItsMinimum_ThenItSaysWhatIsWrong() {
        IntegerField age = field(registerPage(), IntegerField.class, "Age");

        age.setValue(RegisterPresenter.MIN_AGE - 1);

        assertSays(age, REGISTER_AGE_RANGE, "an age below the minimum");
    }

    @Test
    void GivenTheRegisterPage_WhenTheAgeIsEmptied_ThenItSaysWhatToEnter() {
        IntegerField age = field(registerPage(), IntegerField.class, "Age");
        age.setValue(RegisterPresenter.MIN_AGE);

        age.clear();

        assertSays(age, ENTER_AGE, "an emptied age");
    }

    // The refusal's message is one the view set, so the next edit hands the field back to its required rule.
    @Test
    void GivenAReservedUsernameWasRefused_WhenTheUsernameIsEmptied_ThenItSaysWhatToEnter() {
        UsernameAvailabilityPresenter availability = availabilityWithNoVerdict();
        when(availability.isReserved(RESERVED_USERNAME)).thenReturn(true);
        RegisterView view = registerPage(availability);
        TextField username = field(view, TextField.class, "Username");
        fillRegistration(view, RESERVED_USERNAME);
        click(button(view, "Create account"));
        assertEquals("That username is reserved — please choose another.", username.getErrorMessage(),
            "the reserved name must be refused before it is emptied");

        username.clear();

        assertSays(username, "Enter a username.", "an emptied username after a refusal");
    }

    // The live availability check lifts the required rule's mark while the user types: an emptied username
    // that no submit refused is not red.
    @Test
    void GivenNoRefusal_WhenTheUsernameIsEmptied_ThenItIsNotMarkedInvalid() {
        TextField username = field(registerPage(), TextField.class, "Username");

        typeThenEmpty(username);

        assertFalse(username.isInvalid(), "an emptied username no submit refused must not be red");
    }

    private RegisterView registerPage() {
        return registerPage(availabilityWithNoVerdict());
    }

    private RegisterView registerPage(UsernameAvailabilityPresenter availability) {
        RegisterView view = new RegisterView(mock(RegisterPresenter.class), availability, mock(SessionIdentity.class));
        harness.attach(view);
        return view;
    }

    // The live check answering Empty for every name: it calls no name taken, and lifts the mark on each change.
    private static UsernameAvailabilityPresenter availabilityWithNoVerdict() {
        UsernameAvailabilityPresenter availability = mock(UsernameAvailabilityPresenter.class);
        when(availability.check(any())).thenReturn(new UsernameAvailabilityPresenter.Outcome.Empty());
        return availability;
    }

    // Every field filled and the passwords matching, so a submit reaches the reserved-name check.
    private static void fillRegistration(RegisterView view, String username) {
        field(view, TextField.class, "Username").setValue(username);
        field(view, EmailField.class, "Email").setValue("alice@example.com");
        field(view, IntegerField.class, "Age").setValue(RegisterPresenter.MIN_AGE);
        field(view, PasswordField.class, "Password").setValue(PASSWORD);
        field(view, PasswordField.class, "Confirm password").setValue(PASSWORD);
    }

    // ---- CheckoutView, as a guest ----

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Cardholder name | Enter the name on the card.",
        "Card number     | Enter the card number.",
        "Expiry          | Enter the expiry date, as MM / YY.",
        "CVC             | Enter the security code."})
    void GivenTheGuestCheckout_WhenARequiredCardFieldIsEmptied_ThenItSaysWhatToEnter(String label, String message) {
        Component field = field(guestCheckout(), Component.class, label);

        typeThenEmpty(field);

        assertSays(field, message, label);
    }

    @Test
    void GivenTheGuestCheckout_WhenTheEmailIsEmptied_ThenItSaysWhatToEnter() {
        EmailField email = field(guestCheckout(), EmailField.class, "Your email");

        typeThenEmpty(email);

        assertSays(email, ENTER_EMAIL, "an emptied guest email");
    }

    @Test
    void GivenTheGuestCheckout_WhenTheEmailIsMalformed_ThenItSaysWhatIsWrong() {
        EmailField email = field(guestCheckout(), EmailField.class, "Your email");

        email.setValue(MALFORMED_EMAIL);

        assertSays(email, GUEST_EMAIL_FORMAT, "\"" + MALFORMED_EMAIL + "\"");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 121})
    void GivenTheGuestCheckout_WhenTheAgeIsOutsideItsRange_ThenItSaysWhatIsWrong(int age) {
        IntegerField field = field(guestCheckout(), IntegerField.class, "Your age");

        field.setValue(age);

        assertSays(field, GUEST_AGE_RANGE, "an age of " + age);
    }

    @Test
    void GivenTheGuestCheckout_WhenTheAgeIsEmptied_ThenItSaysWhatToEnter() {
        IntegerField field = field(guestCheckout(), IntegerField.class, "Your age");
        field.setValue(RegisterPresenter.MIN_AGE);

        field.clear();

        assertSays(field, ENTER_AGE, "an emptied guest age");
    }

    // The second Pay hands the refused email back to its rule before any edit has validated it, then stops at
    // the card number: the user's next edit must still decide what the email says.
    @Test
    void GivenTheEmailWasHandedBackByAPayThatStoppedEarlier_WhenAMalformedEmailIsTyped_ThenItSaysWhatIsWrong() {
        CheckoutView view = guestCheckout();
        fillCard(view);
        field(view, IntegerField.class, "Your age").setValue(RegisterPresenter.MIN_AGE);
        EmailField email = field(view, EmailField.class, "Your email");
        click(payButton(view));
        assertEquals("Please enter a valid email address.", email.getErrorMessage(),
            "the first Pay must refuse the empty email");
        TextField cardNumber = field(view, TextField.class, "Card number");
        cardNumber.setValue(SHORT_CARD_NUMBER);
        click(payButton(view));
        assertEquals("Card number must be 16 digits.", cardNumber.getErrorMessage(),
            "the second Pay must stop at the card number, before it checks the email");

        email.setValue(MALFORMED_EMAIL);

        assertSays(email, GUEST_EMAIL_FORMAT, "\"" + MALFORMED_EMAIL + "\"");
    }

    private static void fillCard(CheckoutView view) {
        field(view, TextField.class, "Cardholder name").setValue("Guest Example");
        field(view, TextField.class, "Card number").setValue("4111 1111 1111 1111");
        field(view, TextField.class, "Expiry").setValue("12/30");
        field(view, TextField.class, "CVC").setValue("123");
    }

    private static NativeButton payButton(CheckoutView view) {
        return find(view, NativeButton.class).stream()
            .filter(button -> button.getElement().getTextRecursively().trim().startsWith("Pay "))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no Pay button on the checkout"));
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
    @CsvSource(delimiter = '|', value = {
        "Username | Enter your username.",
        "Password | Enter your password."})
    void GivenTheSignInPage_WhenARequiredFieldIsEmptied_ThenItSaysWhatToEnter(String label, String message) {
        LoginView view = new LoginView(mock(LoginPresenter.class), mock(SessionIdentity.class));
        harness.attach(view);
        Component field = field(view, Component.class, label);

        typeThenEmpty(field);

        assertSays(field, message, label);
    }

    // ---- AdminLoginView ----

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Admin username | Enter your admin username.",
        "Password       | Enter your password."})
    void GivenTheAdminSignInPage_WhenARequiredFieldIsEmptied_ThenItSaysWhatToEnter(String label, String message) {
        AdminLoginView view = new AdminLoginView(mock(AdminLoginPresenter.class));
        harness.attach(view);
        Component field = field(view, Component.class, label);

        typeThenEmpty(field);

        assertSays(field, message, label);
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

        assertSays(reply, "Please write a response.", "an emptied response");
    }

    // ---- AdminSendMessagesView ----

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Subject | Enter a subject.",
        "Body    | Write the message."})
    void GivenTheSendMessagesPage_WhenARequiredFieldIsEmptied_ThenItSaysWhatToEnter(String label, String message) {
        ViewHarness.signInAdmin();
        AdminSendMessagesPresenter presenter = mock(AdminSendMessagesPresenter.class);
        when(presenter.load(any())).thenReturn(new AdminSendMessagesPresenter.Outcome.Success(List.of()));
        AdminSendMessagesView view = new AdminSendMessagesView(presenter, InboxViewHarness.displayTime());
        harness.attach(view);
        Component field = field(view, Component.class, label);

        typeThenEmpty(field);

        assertSays(field, message, label);
    }

    // ---- CompanyRegistrationView ----

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Company name | Please fill in a company name.",
        "Description  | Please provide a company description."})
    void GivenTheCompanyRegistrationPage_WhenARequiredFieldIsEmptied_ThenItSaysWhatToEnter(String label,
                                                                                          String message) {
        CompanyRegistrationView view = new CompanyRegistrationView(mock(CompanyRegistrationPresenter.class));
        harness.attach(view);
        Component field = field(view, Component.class, label);

        typeThenEmpty(field);

        assertSays(field, message, label);
    }

    // ---- ManagerInvitationView ----

    @Test
    void GivenTheManagerInvitationPage_WhenTheInviteeIsEmptied_ThenItSaysWhatToEnter() {
        ManagerInvitationView view = new ManagerInvitationView(mock(ManagerInvitationPresenter.class));
        harness.attach(view);
        Component invitee = field(view, Component.class, "Username or email");

        typeThenEmpty(invitee);

        assertSays(invitee, "Enter a username or email to invite.", "an emptied invitee");
    }

    // ---- OwnerAppointmentView ----

    @Test
    void GivenTheOwnerAppointmentPage_WhenTheInviteeIsEmptied_ThenItSaysWhatToEnter() {
        OwnerAppointmentView view = new OwnerAppointmentView(mock(MyCompaniesPresenter.class));
        harness.attach(view);
        Component invitee = field(view, Component.class, "Invitee username or email");

        typeThenEmpty(invitee);

        assertSays(invitee, "Enter a username or email to appoint.", "an emptied invitee");
    }

    // ---- NewInquiryView ----

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Title       | Enter a title.",
        "Description | Enter a description."})
    void GivenTheNewInquiryPage_WhenARequiredFieldIsEmptied_ThenItSaysWhatToEnter(String label, String message) {
        NewInquiryView view = new NewInquiryView(mock(NewInquiryPresenter.class));
        harness.attach(view);
        Component field = field(view, Component.class, label);

        typeThenEmpty(field);

        assertSays(field, message, label);
    }

    // The search box validates nothing itself, but a refusal hands it back to its required rule on its next edit.
    @Test
    void GivenASearchWithNoCompanyPickedWasRefused_WhenTheSearchIsCleared_ThenItSaysToChooseACompany() {
        ViewHarness.signInMember();
        NewInquiryPresenter presenter = mock(NewInquiryPresenter.class);
        when(presenter.searchCompanies(anyString())).thenReturn(new NewInquiryPresenter.SearchOutcome.Success(List.of()));
        NewInquiryView view = new NewInquiryView(presenter);
        harness.attach(view);
        TextField search = field(view, TextField.class, "Production company");
        search.setValue(UNMATCHED_SEARCH);
        click(button(view, "Submit Inquiry"));
        assertEquals(CHOOSE_COMPANY, search.getErrorMessage(), "the search with no company picked must be refused first");

        search.clear();

        assertSays(search, CHOOSE_COMPANY, "a cleared search with no company picked");
    }

    // ---- SubmitComplaintView ----

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Subject | Enter a subject.",
        "Details | Describe what happened."})
    void GivenTheComplaintPage_WhenARequiredFieldIsEmptied_ThenItSaysWhatToEnter(String label, String message) {
        SubmitComplaintPresenter presenter = mock(SubmitComplaintPresenter.class);
        when(presenter.maxDetailsLength(any())).thenReturn(COMPLAINT_DETAILS_LIMIT);
        SubmitComplaintView view = new SubmitComplaintView(presenter);
        harness.attach(view);
        Component field = field(view, Component.class, label);

        typeThenEmpty(field);

        assertSays(field, message, label);
    }

    // ---- EventManagementView, create mode ----

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Title   | Please enter a title.",
        "Country | Enter the country.",
        "City    | Enter the city."})
    void GivenTheCreateEventPage_WhenARequiredTextFieldIsEmptied_ThenItSaysWhatToEnter(String label, String message) {
        Component field = field(createEventPage(), Component.class, label);

        typeThenEmpty(field);

        assertSays(field, message, label);
    }

    @Test
    @SuppressWarnings("unchecked") // the category select holds the category's name
    void GivenTheCreateEventPage_WhenTheCategoryIsCleared_ThenItSaysWhatToChoose() {
        Select<String> category = field(createEventPage(), Select.class, "Category");
        category.setValue(EventCategory.THEATER.name());

        category.clear();

        assertSays(category, "Please choose a category.", "a cleared category");
    }

    @Test
    @SuppressWarnings("unchecked") // the time zone box holds zone ids
    void GivenTheCreateEventPage_WhenTheTimeZoneIsCleared_ThenItSaysWhatToChoose() {
        ComboBox<ZoneId> timeZone = field(createEventPage(), ComboBox.class, "Time zone");

        timeZone.clear();

        assertSays(timeZone, "Please choose the venue's time zone.", "a cleared time zone");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Starts | Set the start time.",
        "Ends   | Set the end time."})
    void GivenTheCreateEventPage_WhenAShowTimeIsCleared_ThenItSaysWhatToSet(String label, String message) {
        DateTimePicker picker = field(createEventPage(), DateTimePicker.class, label);
        picker.setValue(LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC).plusDays(30));

        picker.clear();

        assertSays(picker, message, label);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.5, 5.5})
    void GivenTheCreateEventPage_WhenTheRatingIsOutsideItsRange_ThenItSaysWhatIsWrong(double value) {
        NumberField rating = field(createEventPage(), NumberField.class, RATING);

        rating.setValue(value);

        assertSays(rating, RATING_RANGE, "a rating of " + value);
    }

    @Test
    void GivenTheCreateEventPage_WhenTheRatingIsEmptied_ThenItSaysWhatToEnter() {
        NumberField rating = field(createEventPage(), NumberField.class, RATING);
        rating.setValue(1.0);

        rating.clear();

        assertSays(rating, "Please set a rating (0–5).", "an emptied rating");
    }

    private EventManagementView createEventPage() {
        ViewHarness.signInMember();
        EventManagementPresenter presenter = mock(EventManagementPresenter.class);
        when(presenter.defaultTimeZone()).thenReturn(ZoneOffset.UTC);
        EventManagementView view = new EventManagementView(presenter);
        harness.attach(view);
        view.beforeEnter(routeTo("eventId", "new"));
        return view;
    }

    // ---- VenueMapEditorView ----

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
        "Zone name | Zone name is required.",
        "Price     | Enter the zone's price."})
    void GivenTheVenueEditor_WhenARequiredZoneFieldIsEmptied_ThenItSaysWhatToEnter(String label, String message) {
        TextField field = field(venueEditor(), TextField.class, label);

        field.clear();

        assertSays(field, message, label);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Rows", "Seats / row", "Capacity", "Grid row", "Grid col", "Row span", "Col span",
        "Grid rows", "Grid cols"})
    void GivenTheVenueEditor_WhenANumberIsBelowItsMinimum_ThenItSaysWhatIsWrong(String label) {
        IntegerField field = field(venueEditor(), IntegerField.class, label);

        field.setValue(field.getMin() - 1);

        assertSays(field, placementRange(field), label + " below its minimum");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Rows", "Seats / row", "Capacity", "Grid row", "Grid col", "Row span", "Col span",
        "Grid rows", "Grid cols"})
    void GivenTheVenueEditor_WhenANumberIsAboveItsMaximum_ThenItSaysWhatIsWrong(String label) {
        IntegerField field = field(venueEditor(), IntegerField.class, label);

        field.setValue(field.getMax() + 1);

        assertSays(field, placementRange(field), label + " above its maximum");
    }

    // A placement field's maximum follows the canvas, so its message must name the new one.
    @Test
    void GivenTheVenueEditor_WhenTheCanvasGrowsAndAPlacementIsAboveTheNewMaximum_ThenItNamesTheNewMaximum() {
        VenueMapEditorView view = venueEditor();
        IntegerField gridRows = field(view, IntegerField.class, "Grid rows");
        IntegerField gridRow = field(view, IntegerField.class, "Grid row");
        int grown = gridRows.getValue() + 1;
        gridRows.setValue(grown);

        gridRow.setValue(grown + 1);

        assertSays(gridRow, "Enter a number from 1 to " + grown + ".", "a grid row past the grown canvas");
    }

    // What a venue editor number says outside its bounds, which the field holds.
    private static String placementRange(IntegerField field) {
        return "Enter a number from " + field.getMin() + " to " + field.getMax() + ".";
    }

    private VenueMapEditorView venueEditor() {
        VenueMapEditorView view = new VenueMapEditorView(mock(VenueMapPresenter.class), VENUE_LIMITS);
        harness.attach(view);
        return view;
    }

    // ---- BrowseEventsView: the filters ----

    // Below the minimum and above the maximum, each on the step of 10.
    @ParameterizedTest
    @ValueSource(ints = {-10, 1010})
    void GivenTheBrowsePage_WhenAPriceBoundIsOutsideItsRange_ThenItSaysTheRange(int price) {
        for (IntegerField bound : bounds(browsePage(), IntegerField.class, 2)) {
            bound.setValue(price);

            assertSays(bound, "Enter a price from " + bound.getMin() + " to " + bound.getMax() + ".",
                "a price bound of " + price);
        }
    }

    @Test
    void GivenTheBrowsePage_WhenAPriceBoundIsOffItsStep_ThenItSaysTheStep() {
        for (IntegerField bound : bounds(browsePage(), IntegerField.class, 2)) {
            bound.setValue(PRICE_OFF_STEP);

            assertSays(bound, CatalogFilterSupport.PRICE_OFF_STEP, "a price bound of " + PRICE_OFF_STEP);
        }
    }

    // Below the minimum and above the maximum, each on the step of 0.5.
    @ParameterizedTest
    @ValueSource(doubles = {-0.5, 5.5})
    void GivenTheBrowsePage_WhenARatingBoundIsOutsideItsRange_ThenItSaysTheRange(double rating) {
        for (NumberField bound : bounds(browsePage(), NumberField.class, 4)) {
            bound.setValue(rating);

            assertSays(bound, CatalogFilterSupport.RATING_OUT_OF_RANGE, "a rating bound of " + rating);
        }
    }

    @Test
    void GivenTheBrowsePage_WhenARatingBoundIsOffItsStep_ThenItSaysTheStep() {
        for (NumberField bound : bounds(browsePage(), NumberField.class, 4)) {
            bound.setValue(RATING_OFF_STEP);

            assertSays(bound, CatalogFilterSupport.RATING_OFF_STEP, "a rating bound of " + RATING_OFF_STEP);
        }
    }

    @Test
    void GivenTheBrowsePage_WhenTheEndDateIsBeforeTheStartDate_ThenTheEndDateSaysWhatIsWrong() {
        BrowseEventsView view = browsePage();
        customPicker(view, CatalogFilterSupport.CUSTOM_FROM).setValue(START_DATE);
        DatePicker end = customPicker(view, CatalogFilterSupport.CUSTOM_TO);

        end.setValue(START_DATE.minusDays(1));

        assertSays(end, CatalogFilterSupport.END_BEFORE_START, "an end date before the start date");
    }

    @Test
    void GivenTheBrowsePage_WhenTheStartDateIsAfterTheEndDate_ThenTheStartDateSaysWhatIsWrong() {
        BrowseEventsView view = browsePage();
        customPicker(view, CatalogFilterSupport.CUSTOM_TO).setValue(START_DATE);
        DatePicker start = customPicker(view, CatalogFilterSupport.CUSTOM_FROM);

        start.setValue(START_DATE.plusDays(1));

        assertSays(start, CatalogFilterSupport.START_AFTER_END, "a start date after the end date");
    }

    private BrowseEventsView browsePage() {
        BrowseEventsPresenter presenter = mock(BrowseEventsPresenter.class);
        when(presenter.search(any(), any())).thenReturn(new ListOutcome.Loaded<>(List.of()));
        when(presenter.countries()).thenReturn(new ListOutcome.Loaded<>(List.of()));
        BrowseEventsView view = new BrowseEventsView(presenter, mock(SessionIdentity.class),
            InboxViewHarness.displayTime());
        harness.attach(view);
        return view;
    }

    // The custom range's pickers carry their name as an accessible name, not a label.
    private static DatePicker customPicker(Component view, String name) {
        return find(view, DatePicker.class).stream()
            .filter(picker -> picker.getAriaLabel().filter(name::equals).isPresent())
            .findFirst()
            .orElseThrow(() -> new AssertionError("no date picker named \"" + name + "\""));
    }

    // Each bound of a range filter, checked to be there, so a loop over them cannot pass on none.
    private static <T extends Component> List<T> bounds(Component view, Class<T> type, int expected) {
        List<T> bounds = find(view, type);
        assertEquals(expected, bounds.size(), "the filters' " + type.getSimpleName() + " bounds");
        return bounds;
    }

    // ---- CompanyEventListView: the filters ----

    // One step of 10 below the minimum; the list's price bounds have no maximum.
    @Test
    void GivenTheCompanyEventList_WhenAPriceBoundIsBelowItsMinimum_ThenItSaysTheMinimum() {
        for (IntegerField bound : bounds(companyEventList(), IntegerField.class, 2)) {
            bound.setValue(bound.getMin() - CatalogFilterSupport.PRICE_STEP);

            assertSays(bound, "Enter a price of " + bound.getMin() + " or more.", "a price bound below its minimum");
        }
    }

    @Test
    void GivenTheCompanyEventList_WhenAPriceBoundIsOffItsStep_ThenItSaysTheStep() {
        for (IntegerField bound : bounds(companyEventList(), IntegerField.class, 2)) {
            bound.setValue(PRICE_OFF_STEP);

            assertSays(bound, CatalogFilterSupport.PRICE_OFF_STEP, "a price bound of " + PRICE_OFF_STEP);
        }
    }

    // Below the minimum and above the maximum, each on the step of 0.5.
    @ParameterizedTest
    @ValueSource(doubles = {-0.5, 5.5})
    void GivenTheCompanyEventList_WhenARatingBoundIsOutsideItsRange_ThenItSaysTheRange(double rating) {
        for (NumberField bound : bounds(companyEventList(), NumberField.class, 2)) {
            bound.setValue(rating);

            assertSays(bound, CatalogFilterSupport.RATING_OUT_OF_RANGE, "a rating bound of " + rating);
        }
    }

    @Test
    void GivenTheCompanyEventList_WhenARatingBoundIsOffItsStep_ThenItSaysTheStep() {
        for (NumberField bound : bounds(companyEventList(), NumberField.class, 2)) {
            bound.setValue(RATING_OFF_STEP);

            assertSays(bound, CatalogFilterSupport.RATING_OFF_STEP, "a rating bound of " + RATING_OFF_STEP);
        }
    }

    @Test
    void GivenTheCompanyEventList_WhenTheEndDateIsBeforeTheStartDate_ThenTheEndDateSaysWhatIsWrong() {
        CompanyEventListView view = companyEventList();
        customPicker(view, CatalogFilterSupport.CUSTOM_FROM).setValue(START_DATE);
        DatePicker end = customPicker(view, CatalogFilterSupport.CUSTOM_TO);

        end.setValue(START_DATE.minusDays(1));

        assertSays(end, CatalogFilterSupport.END_BEFORE_START, "an end date before the start date");
    }

    @Test
    void GivenTheCompanyEventList_WhenTheStartDateIsAfterTheEndDate_ThenTheStartDateSaysWhatIsWrong() {
        CompanyEventListView view = companyEventList();
        customPicker(view, CatalogFilterSupport.CUSTOM_TO).setValue(START_DATE);
        DatePicker start = customPicker(view, CatalogFilterSupport.CUSTOM_FROM);

        start.setValue(START_DATE.plusDays(1));

        assertSays(start, CatalogFilterSupport.START_AFTER_END, "a start date after the end date");
    }

    private CompanyEventListView companyEventList() {
        ViewHarness.signInMember();
        CompanyEventListPresenter presenter = mock(CompanyEventListPresenter.class);
        when(presenter.load(any(), any(), any())).thenReturn(new CompanyEventListPresenter.Outcome.Success(List.of()));
        when(presenter.countries(any(), any())).thenReturn(new ListOutcome.Loaded<>(List.of()));
        CompanyEventListView view = new CompanyEventListView(presenter, InboxViewHarness.displayTime());
        harness.attach(view);
        return view;
    }

    // ---- shared ----

    @SuppressWarnings("unchecked") // every field these tests type into holds text
    private static void typeThenEmpty(Component field) {
        HasValue<?, String> text = (HasValue<?, String>) field;
        text.setValue(TYPED);
        text.clear();
    }

    private static void assertSays(Component component, String message, String what) {
        HasValidationProperties field = (HasValidationProperties) component;
        assertTrue(field.isInvalid(), what + " must be invalid by the field's own rule");
        assertEquals(message, field.getErrorMessage(), what + " must say what its rule says");
    }
}
