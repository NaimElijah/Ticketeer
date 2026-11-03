package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.InboxViewHarness.displayTime;
import static com.ticketing.system.unit.presentation.InboxViewHarness.thread;
import static com.ticketing.system.unit.presentation.ViewHarness.buttons;
import static com.ticketing.system.unit.presentation.ViewHarness.click;
import static com.ticketing.system.unit.presentation.ViewHarness.field;
import static com.ticketing.system.unit.presentation.ViewHarness.find;
import static com.ticketing.system.unit.presentation.ViewHarness.founderOf;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.ZoneType;
import com.ticketing.system.sales.config.ReservationHoldProperties;
import com.ticketing.system.shared.dto.EventSummaryDTO;
import com.ticketing.system.shared.dto.InventoryZoneDTO;
import com.ticketing.system.shared.dto.MemberSearchResultDTO;
import com.ticketing.system.shared.dto.SeatDTO;
import com.ticketing.system.ui.components.venue.VkQuantitySelector;
import com.ticketing.system.ui.components.venue.VkSeat;
import com.ticketing.system.ui.presenters.ListOutcome;
import com.ticketing.system.ui.presenters.auth.RegisterPresenter;
import com.ticketing.system.ui.presenters.auth.UsernameAvailabilityPresenter;
import com.ticketing.system.ui.presenters.catalog.BrowseEventsPresenter;
import com.ticketing.system.ui.presenters.catalog.EventDetailsPresenter;
import com.ticketing.system.ui.presenters.catalog.SeatPickerPresenter;
import com.ticketing.system.ui.presenters.company.CompanyEventListPresenter;
import com.ticketing.system.ui.presenters.messaging.AdminComplaintQueuePresenter;
import com.ticketing.system.ui.presenters.messaging.AdminSendMessagesPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.admin.AdminComplaintQueueView;
import com.ticketing.system.ui.views.admin.AdminSendMessagesView;
import com.ticketing.system.ui.views.auth.RegisterView;
import com.ticketing.system.ui.views.catalog.BrowseEventsView;
import com.ticketing.system.ui.views.catalog.EventDetailsView;
import com.ticketing.system.ui.views.catalog.SeatPickerView;
import com.ticketing.system.ui.views.company.CompanyEventListView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.dom.Element;

/**
 * What changes on a page without a page load is announced, without moving focus (#188, U-A15, U-A22,
 * P-M10, A-m8, U-B5; WCAG 4.1.3 Status Messages): a filter's new result count, the quantity and price of
 * a standing ticket, the seats in the selection rail, the zone picked on the map, the recipients an admin
 * finds and adds, and a username's "Available", none of which a screen reader user would otherwise hear.
 * Each sits in a polite live region ({@code aria-live="polite"} or
 * {@code role=status}) that is on the page before the change and stays there across changes, since a
 * region added together with its text is not announced.
 */
class LiveAnnouncementsTest {

    // ---- F17: live announcements (#188) ----

    private static final Pattern ONE_EVENT = Pattern.compile("(?i)\\b1 event\\b");
    private static final Pattern TWO_EVENTS = Pattern.compile("(?i)\\b2 events\\b");

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    // ---- result counts ----

    @Test
    void GivenBrowse_WhenAFilterChangesTheResults_ThenTheSameLiveRegionAnnouncesTheNewCount() {
        BrowseEventsPresenter presenter = mock(BrowseEventsPresenter.class);
        when(presenter.search(any(), any())).thenReturn(
            new ListOutcome.Loaded<>(List.of(summary(1, "Hamlet"))),
            new ListOutcome.Loaded<>(List.of(summary(1, "Hamlet"), summary(2, "Othello"))));
        when(presenter.countries()).thenReturn(new ListOutcome.Loaded<>(List.of()));
        BrowseEventsView view = new BrowseEventsView(presenter, mock(SessionIdentity.class), displayTime());
        Element region = liveRegionSaying(view, ONE_EVENT, "the result count");

        find(view, TextField.class).get(0).setValue("e");

        assertStillSays(view, region, TWO_EVENTS, "the result count");
    }

    @Test
    void GivenMyEvents_WhenAFilterChangesTheResults_ThenTheSameLiveRegionAnnouncesTheNewCount() {
        ViewHarness.signInMember();
        harness.memberships(founderOf(3, "Acme"));
        CompanyEventListPresenter presenter = mock(CompanyEventListPresenter.class);
        when(presenter.load(any(), any(), any())).thenReturn(
            new CompanyEventListPresenter.Outcome.Success(List.of(companyEvent("9", "Othello"))),
            new CompanyEventListPresenter.Outcome.Success(List.of(companyEvent("9", "Othello"),
                companyEvent("10", "Hamlet"))));
        when(presenter.countries(any(), any())).thenReturn(new ListOutcome.Loaded<>(List.of()));
        CompanyEventListView view = new CompanyEventListView(presenter, displayTime());
        Element region = liveRegionSaying(view, ONE_EVENT, "the result count");

        find(view, TextField.class).get(0).setValue("e");

        assertStillSays(view, region, TWO_EVENTS, "the result count");
    }

    @Test
    void GivenTheComplaintQueue_WhenTheStatusFilterChangesTheResults_ThenTheSameLiveRegionAnnouncesTheNewCount() {
        ViewHarness.signInAdmin();
        AdminComplaintQueuePresenter presenter = mock(AdminComplaintQueuePresenter.class);
        when(presenter.load(any(), eq("All"))).thenReturn(new AdminComplaintQueuePresenter.Outcome.Success(
            List.of(thread("c1", "COMPLAINT", "ADMIN_GROUP"), thread("c2", "COMPLAINT", "ADMIN_GROUP", "RESOLVED"))));
        when(presenter.load(any(), eq("Open"))).thenReturn(new AdminComplaintQueuePresenter.Outcome.Success(
            List.of(thread("c1", "COMPLAINT", "ADMIN_GROUP"))));
        when(presenter.badge(any(), any())).thenReturn(Optional.empty());
        AdminComplaintQueueView view = new AdminComplaintQueueView(presenter, displayTime());
        Element region = liveRegionSaying(view, Pattern.compile("(?i)\\b2 complaints\\b"), "the complaint count");

        choose(view, "Open");

        assertStillSays(view, region, Pattern.compile("(?i)\\b1 complaint\\b"), "the complaint count");
    }

    // ---- VkQuantitySelector ----

    @Test
    void GivenAQuantitySelector_WhenItRenders_ThenItsStepButtonsAreNamedDecreaseAndIncrease() {
        VkQuantitySelector selector = new VkQuantitySelector(3, 1000, null);

        assertEquals(List.of("Decrease quantity", "Increase quantity"),
            stepButtons(selector).stream().map(b -> A11y.name(b.getElement())).toList(),
            "the − and + buttons must be named for what they do, not read as \"minus\" and \"plus\"");
    }

    @Test
    void GivenTheQuantityAtItsMinimum_WhenTheSelectorRenders_ThenDecreaseIsAriaDisabledButStillFocusable() {
        VkQuantitySelector selector = new VkQuantitySelector(3, 1000, null);

        assertAriaDisabledNotDisabled(stepButtons(selector).get(0).getElement(), "Decrease at the minimum");
    }

    @Test
    void GivenTheQuantityAtItsMaximum_WhenTheSelectorRenders_ThenIncreaseIsAriaDisabledButStillFocusable() {
        VkQuantitySelector selector = new VkQuantitySelector(1, 1000, null);

        assertAriaDisabledNotDisabled(stepButtons(selector).get(1).getElement(), "Increase at the maximum");
    }

    @Test
    void GivenTheQuantityIncreasedToItsMaximum_WhenTheSelectorRefreshes_ThenIncreaseIsAriaDisabledButStillFocusable() {
        VkQuantitySelector selector = new VkQuantitySelector(2, 1000, null);
        NativeButton increase = stepButtons(selector).get(1);

        click(increase);

        assertAriaDisabledNotDisabled(increase.getElement(), "Increase once the maximum is reached");
    }

    // Kept: a button left focusable at the limit must still not step past it.
    @Test
    void GivenTheQuantityAtItsMinimum_WhenDecreaseIsActivated_ThenTheQuantityStaysAtOne() {
        VkQuantitySelector selector = new VkQuantitySelector(3, 1000, null);

        click(stepButtons(selector).get(0));

        assertEquals(1, selector.getQuantity(), "decrease at the minimum must leave the quantity at 1");
    }

    @Test
    void GivenAQuantitySelector_WhenTheQuantityIncreases_ThenTheSameLiveRegionAnnouncesTheCountAndPrice() {
        VkQuantitySelector selector = new VkQuantitySelector(3, 1000, null);
        Element region = liveRegionSaying(selector, Pattern.compile("(?i)\\b1 ticket\\b.*\\$10\\.00"),
            "the quantity and price");

        click(stepButtons(selector).get(1));

        assertStillSays(selector, region, Pattern.compile("(?i)\\b2 tickets\\b.*\\$20\\.00"), "the quantity and price");
    }

    // ---- SeatPickerView selection rail ----

    @Test
    void GivenASeatedZone_WhenTheBuyerPicksASecondSeat_ThenTheSameLiveRegionAnnouncesTheSelection() {
        SeatPickerView view = seatPicker();
        click(seat(view, "A1"));
        Element region = liveRegionSaying(view, Pattern.compile("(?i)\\b1 seat\\b"), "the selection summary");

        click(seat(view, "A2"));

        assertStillSays(view, region, Pattern.compile("(?i)\\b2 seats\\b"), "the selection summary");
    }

    // ---- EventDetailsView selected zone ----

    @Test
    void GivenAnEventWithTwoZones_WhenTheBuyerPicksTheOtherZone_ThenTheSameLiveRegionAnnouncesIt() {
        EventDetailsView view = eventPage();
        Element region = liveRegionSaying(view, Pattern.compile("Selected:\\s*Floor"), "the selected zone");

        click(zoneButton(view, "Balcony"));

        assertStillSays(view, region, Pattern.compile("Selected:\\s*Balcony"), "the selected zone");
    }

    // ---- AdminSendMessagesView ----

    @Test
    void GivenTheComposer_WhenTheAdminSearchesForRecipients_ThenALiveRegionOnThePageAnnouncesTheResults() {
        AdminSendMessagesView view = sendMessagesView();
        Set<Element> regionsBefore = Set.copyOf(politeRegions(view));

        field(view, TextField.class, "Search members").setValue("al");

        assertAnnouncedByAnExistingRegion(view, regionsBefore, Pattern.compile("\\b2\\b"),
            "the number of members found");
    }

    @Test
    void GivenSearchResults_WhenTheAdminAddsARecipient_ThenALiveRegionOnThePageAnnouncesTheRecipient() {
        AdminSendMessagesView view = sendMessagesView();
        field(view, TextField.class, "Search members").setValue("al");
        Set<Element> regionsBefore = Set.copyOf(politeRegions(view));

        click(buttons(view, "alice").get(0));

        assertAnnouncedByAnExistingRegion(view, regionsBefore, Pattern.compile("(?i)alice"), "the recipient added");
    }

    // A count left from the last search would tell a screen reader user of results the page no longer shows.
    @Test
    void GivenSearchResults_WhenTheAdminClearsTheQuery_ThenNoLiveRegionStillSaysMembersWereFound() {
        AdminSendMessagesView view = sendMessagesView();
        TextField search = field(view, TextField.class, "Search members");
        search.setValue("al");

        search.setValue("");

        List<String> stale = politeRegions(view).stream()
            .map(A11y::text)
            .filter(text -> Pattern.compile("(?i)\\bfound\\b").matcher(text).find())
            .toList();
        assertTrue(stale.isEmpty(), "with the query cleared no results show, so no live region may still say how "
            + "many members were found: " + stale);
    }

    // ---- RegisterView username availability ----

    @Test
    void GivenTheRegistrationForm_WhenTheUsernameIsAvailable_ThenALiveRegionOnThePageAnnouncesIt() {
        UsernameAvailabilityPresenter availability = mock(UsernameAvailabilityPresenter.class);
        when(availability.check(any())).thenReturn(new UsernameAvailabilityPresenter.Outcome.Available());
        RegisterView view = new RegisterView(mock(RegisterPresenter.class), availability, mock(SessionIdentity.class));
        Set<Element> regionsBefore = Set.copyOf(politeRegions(view));

        field(view, TextField.class, "Username").setValue("alice");

        assertAnnouncedByAnExistingRegion(view, regionsBefore, Pattern.compile("(?i)\\bavailable\\b"),
            "that the username is available");
    }

    // ---- assertions ------------------------------------------------------------------------------

    // The innermost element showing text that matches, which must sit in a polite live region.
    private static Element liveRegionSaying(Component root, Pattern text, String what) {
        List<Element> showing = A11y.elements(root, e -> text.matcher(A11y.text(e)).find());
        assertFalse(showing.isEmpty(), "the page shows no " + what + " matching /" + text + "/ in: "
            + A11y.text(root.getElement()));
        Element innermost = showing.get(showing.size() - 1);
        return A11y.politeLiveRegion(innermost).orElseThrow(() -> new AssertionError(
            what + " changes without a page load, so it must sit in a polite live region (aria-live=polite or "
                + "role=status); it renders as " + A11y.describe(innermost)));
    }

    private static void assertStillSays(Component root, Element region, Pattern text, String what) {
        assertTrue(A11y.within(region, root.getElement()),
            "the live region for " + what + " must stay on the page across updates, not be replaced (a new region "
                + "is not announced): " + A11y.describe(region));
        assertTrue(text.matcher(A11y.text(region)).find(),
            "the live region for " + what + " must now say /" + text + "/; it says \"" + A11y.text(region) + "\"");
    }

    private static void assertAnnouncedByAnExistingRegion(Component root, Set<Element> regionsBefore, Pattern text,
                                                          String what) {
        List<Element> saying = politeRegions(root).stream().filter(r -> text.matcher(A11y.text(r)).find()).toList();
        assertFalse(saying.isEmpty(), "a polite live region (aria-live=polite or role=status) must announce " + what
            + "; the page's live regions say: " + politeRegions(root).stream().map(A11y::text).toList());
        assertTrue(saying.stream().anyMatch(regionsBefore::contains),
            "the live region announcing " + what + " must be on the page before the change, not added with it "
                + "(a new region is not announced): " + saying.stream().map(A11y::describe).toList());
    }

    private static void assertAriaDisabledNotDisabled(Element button, String what) {
        assertEquals("true", button.getAttribute("aria-disabled"),
            what + " must say it is unavailable (aria-disabled=true): " + A11y.describe(button));
        assertFalse(A11y.disabled(button),
            what + " must stay focusable, not be disabled, so focus is not lost from it: " + A11y.describe(button));
    }

    private static List<Element> politeRegions(Component root) {
        return A11y.elements(root, e -> A11y.politeLiveRegion(e).filter(e::equals).isPresent());
    }

    // ---- arrangement -----------------------------------------------------------------------------

    private static void choose(Component root, String option) {
        Element choice = A11y.elements(root, e -> option.equals(A11y.name(e))
                && Set.of("radio", "option", "menuitemradio").contains(A11y.role(e)))
            .stream()
            .findFirst()
            .orElseThrow(() -> new AssertionError("no option \"" + option + "\" to choose"));
        A11y.activate(choice);
    }

    private static List<NativeButton> stepButtons(VkQuantitySelector selector) {
        List<NativeButton> buttons = find(selector, NativeButton.class);
        assertEquals(2, buttons.size(), "the selector has a decrease and an increase button");
        return buttons;
    }

    private static EventSummaryDTO summary(int id, String name) {
        return new EventSummaryDTO(id, name, "ON_SALE", null, "Acme", EventCategory.THEATER.name(), "Tel Aviv",
            List.of(), ZoneOffset.UTC, 50.0, 50.0, false, List.of());
    }

    private static EventDetailDTO companyEvent(String id, String name) {
        return new EventDetailDTO(id, name, null, "", EventCategory.THEATER, null, "3", "Acme",
            EventStatus.ON_SALE, List.of(), ZoneOffset.UTC, List.of(), 50.0);
    }

    private static SeatPickerView seatPicker() {
        SeatPickerPresenter presenter = mock(SeatPickerPresenter.class);
        when(presenter.loadZone(anyInt(), anyInt())).thenReturn(new SeatPickerPresenter.LoadOutcome.Loaded(
            new InventoryZoneDTO(8, "Floor", "SEATED", 2, 2, 0, 0, 10.0,
                List.of(new SeatDTO("A1", 1, 0, "AVAILABLE"), new SeatDTO("A2", 2, 0, "AVAILABLE")), null),
            ZoneType.SEATED));
        SeatPickerView view = new SeatPickerView(presenter, new ReservationHoldProperties(10));
        view.beforeEnter(routeTo("eventId", "5", "zoneId", "8"));
        return view;
    }

    private static VkSeat seat(SeatPickerView view, String label) {
        return find(view, VkSeat.class).stream()
            .filter(s -> A11y.name(s.getElement()).startsWith("Seat " + label + ","))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no seat " + label + " on the picker"));
    }

    private static EventDetailsView eventPage() {
        EventDetailsPresenter presenter = mock(EventDetailsPresenter.class);
        when(presenter.load(any(), anyInt())).thenReturn(new EventDetailsPresenter.Outcome.Success(
            new EventDetailDTO("5", "Coldplay", null, "Live", EventCategory.CONCERT, null, "3", "Acme",
                EventStatus.ON_SALE, List.of(), ZoneOffset.UTC, List.of(), 10.0),
            List.of(new InventoryZoneDTO(8, "Floor", "STANDING", 100, 50, 0, 50, 10.0, List.of(), null),
                new InventoryZoneDTO(9, "Balcony", "STANDING", 100, 50, 0, 50, 20.0, List.of(), null)),
            3, 3, null));
        EventDetailsView view = new EventDetailsView(presenter, mock(SessionIdentity.class),
            new ReservationHoldProperties(10));
        view.beforeEnter(routeTo("eventId", "5"));
        return view;
    }

    private static NativeButton zoneButton(EventDetailsView view, String zone) {
        return find(view, NativeButton.class).stream()
            .filter(b -> b.hasClassName("vk-zone") && b.getElement().getTextRecursively().contains(zone))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no zone " + zone + " on the map"));
    }

    private static AdminSendMessagesView sendMessagesView() {
        ViewHarness.signInAdmin();
        AdminSendMessagesPresenter presenter = mock(AdminSendMessagesPresenter.class);
        when(presenter.load(any())).thenReturn(new AdminSendMessagesPresenter.Outcome.Success(List.of()));
        when(presenter.searchUsers(any(), any())).thenReturn(new AdminSendMessagesPresenter.SearchOutcome.Success(
            List.of(new MemberSearchResultDTO(7, "alice"), new MemberSearchResultDTO(8, "alan"))));
        return new AdminSendMessagesView(presenter, displayTime());
    }
}
