package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.field;
import static com.ticketing.system.unit.presentation.ViewHarness.find;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.ZoneType;
import com.ticketing.system.organization.application.dto.OrganizationalTreeNodeDTO;
import com.ticketing.system.organization.application.dto.ProductionCompanyDTO;
import com.ticketing.system.sales.config.ReservationHoldProperties;
import com.ticketing.system.catalog.config.VenueLimitsProperties;
import com.ticketing.system.shared.dto.EventSummaryDTO;
import com.ticketing.system.shared.dto.InventoryZoneDTO;
import com.ticketing.system.shared.dto.MemberSearchResultDTO;
import com.ticketing.system.shared.dto.SeatDTO;
import com.ticketing.system.ui.components.kit.LkTextRows;
import com.ticketing.system.ui.components.venue.VkSeat;
import com.ticketing.system.ui.presenters.admin.AdminDashboardPresenter;
import com.ticketing.system.ui.presenters.admin.OrgTreePresenter;
import com.ticketing.system.ui.presenters.catalog.SeatPickerPresenter;
import com.ticketing.system.ui.presenters.company.EventManagementPresenter;
import com.ticketing.system.ui.presenters.company.VenueMapPresenter;
import com.ticketing.system.ui.presenters.landing.LandingPresenter;
import com.ticketing.system.ui.presenters.messaging.AdminSendMessagesPresenter;
import com.ticketing.system.ui.support.EventCategoryLabels;
import com.ticketing.system.ui.views.admin.AdminDashboardView;
import com.ticketing.system.ui.views.admin.AdminSendMessagesView;
import com.ticketing.system.ui.views.admin.OrganizationalTreeView;
import com.ticketing.system.ui.views.catalog.SeatPickerView;
import com.ticketing.system.ui.views.company.EventManagementView;
import com.ticketing.system.ui.views.company.VenueMapEditorView;
import com.ticketing.system.ui.views.landing.LandingView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.dom.Element;

/**
 * Every control a mouse user can click is a native button or link a keyboard user can reach and a screen
 * reader can name (#188, P-B3, P-M1, A-M2, A-M3, U-A12, A-m7, P-m5; WCAG 2.1.1, 4.1.2, 2.4.4). Today these
 * are divs and spans with click listeners: Tab skips them, so the admin cannot pick a company in the
 * organizational tree, a buyer cannot open a featured event, and nobody can remove a recipient, an artist,
 * a zone or a held seat without a mouse. Each ✕ remover is named for what it removes.
 */
class ClickOnlyControlsTest {

    // ---- F11: click-only controls (#188) ----

    private static final Pattern REMOVE_SOMETHING = Pattern.compile("(?i)^remove\\b.*\\S.*");

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    // ---- OrganizationalTreeView company picker ----

    @Test
    void GivenTwoCompanies_WhenTheAdminOpensTheOrgTree_ThenEachCompanyIsAButtonOrLinkAndTheShownOneIsCurrent() {
        ViewHarness.signInAdmin();
        OrganizationalTreeView view = new OrganizationalTreeView(orgTree());

        Element acme = control(view, "Acme");
        Element beta = control(view, "Beta Productions");

        assertCurrent(acme);
        assertNotCurrent(beta);
    }

    @Test
    void GivenTheOrgTreeOnAcme_WhenTheAdminActivatesBeta_ThenBetaBecomesTheCurrentCompany() {
        ViewHarness.signInAdmin();
        OrganizationalTreeView view = new OrganizationalTreeView(orgTree());

        activate(control(view, "Beta Productions"));

        assertCurrent(control(view, "Beta Productions"));
        assertNotCurrent(control(view, "Acme"));
    }

    // ---- LandingView: posters and category cards ----

    @Test
    void GivenAFeaturedEvent_WhenTheLandingRenders_ThenItsPosterIsAButtonOrLinkNamedByTheEvent() {
        LandingPresenter presenter = mock(LandingPresenter.class);
        when(presenter.load()).thenReturn(new LandingPresenter.Outcome.Success(List.of(event("Hamlet")), List.of()));

        control(new LandingView(presenter), "Hamlet");
    }

    @Test
    void GivenTheLanding_WhenItRenders_ThenEachCategoryCardIsAButtonOrLinkNamedByItsCategory() {
        LandingPresenter presenter = mock(LandingPresenter.class);
        when(presenter.load()).thenReturn(new LandingPresenter.Outcome.Success(List.of(), List.of()));
        LandingView view = new LandingView(presenter);

        for (EventCategory category : List.of(EventCategory.CONCERT, EventCategory.SPORTS, EventCategory.THEATER,
                EventCategory.COMEDY)) {
            control(view, EventCategoryLabels.label(category));
        }
    }

    // ---- LkTile (admin dashboard) ----

    @Test
    void GivenTheAdminDashboard_WhenItRenders_ThenEachToolTileIsALinkNamedWithoutItsOpenArrow() {
        ViewHarness.signInAdmin();
        AdminDashboardPresenter presenter = mock(AdminDashboardPresenter.class);
        when(presenter.load()).thenReturn(new AdminDashboardPresenter.Outcome.Failure("overview-down"));
        AdminDashboardView view = new AdminDashboardView(presenter);

        for (String title : List.of("Global purchase history", "Organizational tree", "Send Messages",
                "Complaint queue")) {
            Element tile = control(view, title);
            assertTrue(A11y.isLink(tile), "a tile that opens a page is a link (a href): " + A11y.describe(tile));
            assertFalse(A11y.name(tile).contains("Open"),
                "the decorative \"Open →\" must be aria-hidden, out of the tile's name: " + A11y.describe(tile));
        }
    }

    // ---- EventManagementView linked editors ----

    @Test
    void GivenTheEventPage_WhenItRenders_ThenEachLinkedEditorRowIsAButtonOrLink() {
        ViewHarness.signInMember();
        EventManagementView view = new EventManagementView(mock(EventManagementPresenter.class));

        for (String label : List.of("Venue map + zones", "Purchase policies", "Sales for this event")) {
            control(view, label);
        }
    }

    // ---- ✕ removers ----

    @Test
    void GivenARecipientChip_WhenTheAdminAddsBob_ThenItsRemoverIsAButtonNamedRemoveBob() {
        ViewHarness.signInAdmin();
        AdminSendMessagesPresenter presenter = mock(AdminSendMessagesPresenter.class);
        when(presenter.load(any())).thenReturn(new AdminSendMessagesPresenter.Outcome.Success(List.of()));
        when(presenter.searchUsers(any(), anyString())).thenReturn(new AdminSendMessagesPresenter.SearchOutcome.Success(
            List.of(new MemberSearchResultDTO(7, "bob"))));
        AdminSendMessagesView view = new AdminSendMessagesView(presenter, InboxViewHarness.displayTime());

        field(view, TextField.class, "Search members").setValue("bo");
        activate(control(view, "bob"));

        assertRemover(view, "bob");
    }

    @Test
    void GivenTwoArtistRows_WhenTheyRender_ThenEachRemoverIsAButtonNamedForItsRow() {
        LkTextRows rows = new LkTextRows("Artists", "+ Add artist");
        rows.setValues(List.of("Queen", "Muse"));

        List<Element> removers = A11y.elements(rows, e -> A11y.isButton(e) && REMOVE_SOMETHING.matcher(A11y.name(e)).matches());
        List<String> names = removers.stream().map(A11y::name).toList();
        assertEquals(2, removers.size(), "each row needs a remove button named \"Remove …\"; found: " + names
            + "; the ✕ controls render as: " + crosses(rows));
        assertEquals(2, names.stream().distinct().count(), "each remove button must say which row it removes: " + names);
    }

    @Test
    void GivenAZone_WhenTheVenueEditorRenders_ThenItsRemoverIsAButtonNamedRemoveTheZone() {
        ViewHarness.signInMember();
        VenueMapEditorView view = new VenueMapEditorView(mock(VenueMapPresenter.class),
            new VenueLimitsProperties(12, 49_500, 1_000_000, 100_000));

        assertRemover(view, "Zone 1");
    }

    @Test
    void GivenAHeldSeatInTheRail_WhenTheSeatPickerShowsIt_ThenItsRemoverIsAButtonNamedRemoveTheSeat() {
        SeatPickerView view = seatPicker();
        VkSeat a1 = find(view, VkSeat.class).stream()
            .filter(seat -> A11y.text(seat.getElement()).contains("A1"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no seat A1 on the picker"));

        ViewHarness.click(a1);

        assertRemover(view, "A1");
    }

    @Test
    void GivenTheSeatPicker_WhenItRenders_ThenBackToAreasIsALinkOrButton() {
        control(seatPicker(), "Back to areas");
    }

    // ---- helpers ---------------------------------------------------------------------------------

    // The native button or link whose name holds {@code label}.
    private static Element control(Component root, String label) {
        return A11y.elements(root, e -> A11y.isButtonOrLink(e) && A11y.name(e).contains(label)).stream()
            .findFirst()
            .orElseThrow(() -> new AssertionError("\"" + label + "\" must be a native button or link; it renders as: "
                + A11y.renderedAs(root, label)));
    }

    private static void activate(Element control) {
        A11y.activate(control);
    }

    private static void assertCurrent(Element control) {
        String current = control.getAttribute("aria-current");
        assertTrue(current != null && !"false".equals(current),
            "the company shown must say so (aria-current): " + A11y.describe(control));
    }

    private static void assertNotCurrent(Element control) {
        String current = control.getAttribute("aria-current");
        assertTrue(current == null || "false".equals(current), "only the company shown is current: "
            + A11y.describe(control));
    }

    private static void assertRemover(Component root, String thing) {
        List<Element> removers = A11y.elements(root, e -> A11y.isButton(e)
            && REMOVE_SOMETHING.matcher(A11y.name(e)).matches() && A11y.name(e).contains(thing));
        assertFalse(removers.isEmpty(), "the ✕ that removes \"" + thing + "\" must be a button named \"Remove "
            + thing + "\"; the ✕ controls render as: " + crosses(root));
    }

    private static List<String> crosses(Component root) {
        List<String> found = new ArrayList<>();
        for (Element e : A11y.elements(root)) {
            boolean cross = e.getChildren().anyMatch(c -> c.isTextNode() && c.getText().contains("✕"))
                || e.getAttributeNames().anyMatch(n -> String.valueOf(e.getAttribute(n)).toLowerCase(Locale.ROOT)
                    .contains("remove"))
                || e.getClassList().stream().anyMatch(c -> c.endsWith("-x"));
            if (cross) {
                found.add(A11y.describe(e));
            }
        }
        return found;
    }

    private static OrgTreePresenter orgTree() {
        ProductionCompanyDTO acme = new ProductionCompanyDTO(1, "Acme", "desc", "ACTIVE", 10);
        ProductionCompanyDTO beta = new ProductionCompanyDTO(2, "Beta Productions", "desc", "ACTIVE", 11);
        OrganizationalTreeNodeDTO tree = new OrganizationalTreeNodeDTO(10, "founder", "Owner", true, List.of(),
            new ArrayList<>());
        OrgTreePresenter presenter = mock(OrgTreePresenter.class);
        when(presenter.load(any(), isNull(), anyBoolean()))
            .thenReturn(new OrgTreePresenter.Outcome.Success(List.of(acme, beta), acme, tree));
        when(presenter.load(any(), eq(1), anyBoolean()))
            .thenReturn(new OrgTreePresenter.Outcome.Success(List.of(acme, beta), acme, tree));
        when(presenter.load(any(), eq(2), anyBoolean()))
            .thenReturn(new OrgTreePresenter.Outcome.Success(List.of(acme, beta), beta, tree));
        return presenter;
    }

    private static EventSummaryDTO event(String name) {
        return new EventSummaryDTO(1, name, "ON_SALE", null, "Acme", EventCategory.THEATER.name(), "Tel Aviv",
            List.of(), ZoneOffset.UTC, 50.0, 50.0, false, List.of());
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
}
