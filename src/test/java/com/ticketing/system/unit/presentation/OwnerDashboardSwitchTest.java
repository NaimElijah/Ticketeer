package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_TOKEN;
import static com.ticketing.system.unit.presentation.ViewHarness.buttons;
import static com.ticketing.system.unit.presentation.ViewHarness.find;
import static com.ticketing.system.unit.presentation.ViewHarness.founderOf;
import static com.ticketing.system.unit.presentation.ViewHarness.managerOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.organization.application.dto.UserCompanyDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.reporting.application.service.CompanyAnalyticsService;
import com.ticketing.system.shared.dto.CompanyDashboardDTO;
import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.ticketing.system.ui.components.kit.LkSelect;
import com.ticketing.system.ui.components.kit.LkTile;
import com.ticketing.system.ui.presenters.company.OwnerDashboardPresenter;
import com.ticketing.system.ui.session.CurrentCompanies;
import com.ticketing.system.ui.views.company.OwnerDashboardView;
import com.vaadin.flow.component.html.Span;

/**
 * The workspace dashboard follows the company it is switched to: its tiles are the ones that company's
 * role allows, and "New Event" shows only to a member who may create events (#187, UI-A17). The
 * dashboard runs on the real {@link OwnerDashboardPresenter}, whose company switch sets the current
 * company the capability gate reads.
 */
class OwnerDashboardSwitchTest {

    // ---- UI-A17: the owner dashboard goes stale (#187) ----

    private static final int FOUNDED_ID = 1;
    private static final int MANAGED_ID = 2;
    private static final String MANAGERS_TILE = "Managers";

    private ViewHarness harness;
    private CompanyAnalyticsService analytics;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
        ViewHarness.signInMember();
        analytics = mock(CompanyAnalyticsService.class);
        when(analytics.dashboard(eq(MEMBER_TOKEN), anyInt())).thenReturn(new CompanyDashboardDTO(0, null, null, null, 4.5));
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void GivenAFounderWhoAlsoManagesACompany_WhenSwitchingToTheManagedCompany_ThenTheFoundersTilesAreGone() {
        OwnerDashboardView view = dashboardFor(
            founderOf(FOUNDED_ID, "Acme"), managerOf(MANAGED_ID, "Beta"));
        assertTrue(tileTitles(view).contains(MANAGERS_TILE), "a founder sees the Managers tile");

        find(view, LkSelect.class).stream()
            .filter(select -> "Acme".equals(select.getValue()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the dashboard has no company selector"))
            .setValue("Beta");

        assertFalse(tileTitles(view).contains(MANAGERS_TILE),
            "a manager without permissions sees no Managers tile, but the dashboard shows " + tileTitles(view));
    }

    // The other half: the switch shows what the manager may do, not an empty dashboard (#189).
    @Test
    void GivenAFounderWhoAlsoManagesACompanyWithSalesAccess_WhenSwitchingToIt_ThenItsSalesTileShows() {
        OwnerDashboardView view = dashboardFor(
            founderOf(FOUNDED_ID, "Acme"), managerOf(MANAGED_ID, "Beta", Permission.VIEW_SALES));
        assertFalse(tileTitles(view).isEmpty(), "precondition: the founder's dashboard shows tiles");

        find(view, LkSelect.class).stream()
            .filter(select -> "Acme".equals(select.getValue()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the dashboard has no company selector"))
            .setValue("Beta");

        List<String> titles = tileTitles(view);
        assertTrue(titles.contains("Sales History"), "a manager who may view sales sees its tile: " + titles);
        assertFalse(titles.contains(MANAGERS_TILE), "but not the founder's Managers tile: " + titles);
    }

    @Test
    void GivenAManagerWhoMayNotEditEvents_WhenTheDashboardOpens_ThenNoNewEventActionShows() {
        OwnerDashboardView view = dashboardFor(managerOf(MANAGED_ID, "Beta"));

        assertTrue(buttons(view, "New Event").isEmpty(), "New Event shows to a member who cannot create events");
    }

    // Kept: a founder may create events.
    @Test
    void GivenAFounder_WhenTheDashboardOpens_ThenTheNewEventActionShows() {
        OwnerDashboardView view = dashboardFor(founderOf(FOUNDED_ID, "Acme"));

        assertFalse(buttons(view, "New Event").isEmpty(), "a founder must see New Event");
    }

    // ---- arrangement ----

    private OwnerDashboardView dashboardFor(UserCompanyDTO... memberships) {
        CompanyManagementService companies = harness.memberships(memberships);
        List<MyCompanyDTO> mine = Arrays.stream(memberships)
            .map(m -> new MyCompanyDTO(m.companyId(), m.name(), m.role().name()))
            .toList();
        when(companies.findMyCompanies(MEMBER_TOKEN)).thenReturn(mine);
        CurrentCompanies.setCurrentCompany(memberships[0].companyId());
        return new OwnerDashboardView(new OwnerDashboardPresenter(companies, analytics));
    }

    private static List<String> tileTitles(OwnerDashboardView view) {
        return find(view, LkTile.class).stream()
            .flatMap(tile -> find(tile, Span.class).stream())
            .filter(span -> span.hasClassName("lk-tile-title"))
            .map(Span::getText)
            .toList();
    }
}
