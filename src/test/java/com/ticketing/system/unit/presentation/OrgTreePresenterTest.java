package com.ticketing.system.unit.presentation;

import com.ticketing.system.organization.application.dto.OrganizationalTreeNodeDTO;
import com.ticketing.system.organization.application.dto.ProductionCompanyDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.SessionExpiredException;
import com.ticketing.system.ui.presenters.admin.OrgTreePresenter;
import com.ticketing.system.ui.support.ServiceErrors;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OrgTreePresenter} (UC-25 / V2-VIEW-03).
 * Service is mocked; asserts the sealed-outcome mapping the view switches on.
 * Tests cover both the owner path (isAdmin=false) and the admin path (isAdmin=true).
 */
class OrgTreePresenterTest {

    private static final String TOKEN       = "owner-token";
    private static final String ADMIN_TOKEN = "admin-token";

    private CompanyManagementService service;
    private OrgTreePresenter presenter;

    @BeforeEach
    void setUp() {
        service = mock(CompanyManagementService.class);
        presenter = new OrgTreePresenter(service);
    }

    // ── null / unauthenticated ─────────────────────────────────────────────────

    @Test
    void GivenANullToken_WhenLoad_ThenReturnsNotAuthenticatedWithoutCallingService() {
        OrgTreePresenter.Outcome outcome = presenter.load(null, null, false);

        assertInstanceOf(OrgTreePresenter.Outcome.NotAuthenticated.class, outcome);
        verify(service, never()).findOwnedCompanies(anyString());
        verify(service, never()).adminListAllCompanies(anyString());
    }

    @Test
    void GivenAnInvalidToken_WhenLoad_ThenReturnsNotAuthenticated() {
        when(service.findOwnedCompanies(TOKEN)).thenThrow(new InvalidTokenException("bad token"));

        assertInstanceOf(OrgTreePresenter.Outcome.NotAuthenticated.class, presenter.load(TOKEN, null, false));
    }

    @Test
    void GivenAnAdminWithAnInvalidToken_WhenLoad_ThenReturnsNotAuthenticated() {
        when(service.adminListAllCompanies(ADMIN_TOKEN)).thenThrow(new InvalidTokenException("revoked"));

        assertInstanceOf(OrgTreePresenter.Outcome.NotAuthenticated.class,
                presenter.load(ADMIN_TOKEN, null, true));
    }

    @Test
    void GivenAnAdminWhoseSessionExpired_WhenLoad_ThenReturnsNotAuthenticated() {
        when(service.adminListAllCompanies(ADMIN_TOKEN)).thenThrow(new SessionExpiredException());

        assertInstanceOf(OrgTreePresenter.Outcome.NotAuthenticated.class,
                presenter.load(ADMIN_TOKEN, null, true));
    }

    // ── no company ────────────────────────────────────────────────────────────

    @Test
    void GivenNoOwnedCompanies_WhenLoad_ThenReturnsNoCompanyWithoutCallingTree() {
        when(service.findOwnedCompanies(TOKEN)).thenReturn(List.of());

        OrgTreePresenter.Outcome outcome = presenter.load(TOKEN, null, false);

        assertInstanceOf(OrgTreePresenter.Outcome.NoCompany.class, outcome);
        verify(service, never()).viewOrganizationalTree(anyString(), anyInt());
    }

    // ── owner success ──────────────────────────────────────────────────────────

    @Test
    void GivenOneCompanyAndANullCompanyId_WhenLoad_ThenSelectsFirstAndReturnsTree() {
        ProductionCompanyDTO company = company(7, "Acme");
        OrganizationalTreeNodeDTO tree = founderNode(1, "Alice");
        when(service.findOwnedCompanies(TOKEN)).thenReturn(List.of(company));
        when(service.viewOrganizationalTree(TOKEN, 7)).thenReturn(tree);

        OrgTreePresenter.Outcome outcome = presenter.load(TOKEN, null, false);

        OrgTreePresenter.Outcome.Success ok =
                assertInstanceOf(OrgTreePresenter.Outcome.Success.class, outcome);
        assertEquals(1, ok.companies().size());
        assertEquals(company, ok.selected());
        assertEquals(tree, ok.tree());
    }

    @Test
    void GivenMultipleCompaniesAndAMatchingCompanyId_WhenLoad_ThenSelectsCorrectOne() {
        ProductionCompanyDTO acme  = company(7, "Acme");
        ProductionCompanyDTO bravo = company(8, "Bravo");
        OrganizationalTreeNodeDTO tree = founderNode(2, "Bob");
        when(service.findOwnedCompanies(TOKEN)).thenReturn(List.of(acme, bravo));
        when(service.viewOrganizationalTree(TOKEN, 8)).thenReturn(tree);

        OrgTreePresenter.Outcome.Success ok =
                assertInstanceOf(OrgTreePresenter.Outcome.Success.class, presenter.load(TOKEN, 8, false));

        assertEquals(bravo, ok.selected());
        assertEquals(tree, ok.tree());
    }

    @Test
    void GivenMultipleCompaniesAndAnUnknownCompanyId_WhenLoad_ThenFallsBackToFirst() {
        ProductionCompanyDTO acme  = company(7, "Acme");
        ProductionCompanyDTO bravo = company(8, "Bravo");
        OrganizationalTreeNodeDTO tree = founderNode(1, "Alice");
        when(service.findOwnedCompanies(TOKEN)).thenReturn(List.of(acme, bravo));
        when(service.viewOrganizationalTree(TOKEN, 7)).thenReturn(tree);

        OrgTreePresenter.Outcome.Success ok =
                assertInstanceOf(OrgTreePresenter.Outcome.Success.class, presenter.load(TOKEN, 99, false));

        assertEquals(acme, ok.selected());
    }

    @Test
    void GivenTwoOwnedCompanies_WhenLoad_ThenCompaniesListHasTwoEntries() {
        ProductionCompanyDTO acme  = company(7, "Acme");
        ProductionCompanyDTO bravo = company(8, "Bravo");
        when(service.findOwnedCompanies(TOKEN)).thenReturn(List.of(acme, bravo));
        when(service.viewOrganizationalTree(anyString(), anyInt())).thenReturn(founderNode(1, "Alice"));

        OrgTreePresenter.Outcome.Success ok =
                assertInstanceOf(OrgTreePresenter.Outcome.Success.class, presenter.load(TOKEN, null, false));

        assertEquals(2, ok.companies().size());
    }

    // ── admin success ──────────────────────────────────────────────────────────

    @Test
    void GivenAnAdmin_WhenLoad_ThenSeesAllCompaniesNotJustOwned() {
        ProductionCompanyDTO acme  = company(7, "Acme");
        ProductionCompanyDTO bravo = company(8, "Bravo");
        when(service.adminListAllCompanies(ADMIN_TOKEN)).thenReturn(List.of(acme, bravo));
        when(service.adminViewOrgTree(ADMIN_TOKEN, 7)).thenReturn(founderNode(1, "Alice"));

        OrgTreePresenter.Outcome.Success ok =
                assertInstanceOf(OrgTreePresenter.Outcome.Success.class,
                        presenter.load(ADMIN_TOKEN, null, true));

        assertEquals(2, ok.companies().size());
        assertEquals(acme, ok.selected());
        verify(service, never()).findOwnedCompanies(anyString());
        verify(service, never()).viewOrganizationalTree(anyString(), anyInt());
    }

    @Test
    void GivenAnAdminAndASpecificCompanyId_WhenLoad_ThenSelectsThatCompanyById() {
        ProductionCompanyDTO acme  = company(7, "Acme");
        ProductionCompanyDTO bravo = company(8, "Bravo");
        OrganizationalTreeNodeDTO tree = founderNode(2, "Bob");
        when(service.adminListAllCompanies(ADMIN_TOKEN)).thenReturn(List.of(acme, bravo));
        when(service.adminViewOrgTree(ADMIN_TOKEN, 8)).thenReturn(tree);

        OrgTreePresenter.Outcome.Success ok =
                assertInstanceOf(OrgTreePresenter.Outcome.Success.class,
                        presenter.load(ADMIN_TOKEN, 8, true));

        assertEquals(bravo, ok.selected());
        assertEquals(tree, ok.tree());
    }

    @Test
    void GivenAnAdminAndNoCompaniesInSystem_WhenLoad_ThenReturnsNoCompanyWithoutCallingTree() {
        when(service.adminListAllCompanies(ADMIN_TOKEN)).thenReturn(List.of());

        assertInstanceOf(OrgTreePresenter.Outcome.NoCompany.class,
                presenter.load(ADMIN_TOKEN, null, true));
        verify(service, never()).adminViewOrgTree(anyString(), anyInt());
    }

    // ── failure ───────────────────────────────────────────────────────────────

    @Test
    void GivenTheServiceFailsInternally_WhenLoad_ThenReturnsGenericFailure() {
        when(service.findOwnedCompanies(TOKEN)).thenThrow(new RuntimeException("db down"));

        OrgTreePresenter.Outcome.Failure fail =
                assertInstanceOf(OrgTreePresenter.Outcome.Failure.class, presenter.load(TOKEN, null, false));

        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    @Test
    void GivenTheTreeCallFailsInternally_WhenLoad_ThenReturnsGenericFailure() {
        when(service.findOwnedCompanies(TOKEN)).thenReturn(List.of(company(7, "Acme")));
        when(service.viewOrganizationalTree(TOKEN, 7)).thenThrow(new RuntimeException("tree error"));

        OrgTreePresenter.Outcome.Failure fail =
                assertInstanceOf(OrgTreePresenter.Outcome.Failure.class, presenter.load(TOKEN, null, false));
        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    @Test
    void GivenAnAdminAndTheServiceFailsInternally_WhenLoad_ThenReturnsGenericFailure() {
        when(service.adminListAllCompanies(ADMIN_TOKEN)).thenThrow(new RuntimeException("admin token required"));

        OrgTreePresenter.Outcome.Failure fail =
                assertInstanceOf(OrgTreePresenter.Outcome.Failure.class,
                        presenter.load(ADMIN_TOKEN, null, true));
        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ProductionCompanyDTO company(int id, String name) {
        return new ProductionCompanyDTO(id, name, "desc", "ACTIVE", 1);
    }

    private static OrganizationalTreeNodeDTO founderNode(int userId, String username) {
        return new OrganizationalTreeNodeDTO(userId, username, "Owner", true, List.of(), new ArrayList<>());
    }

    @Test
    void GivenTheTreeCallIsUnauthorized_WhenLoad_ThenReturnsFailureWithTheRulesMessage() {
        UnauthorizedActionException rule = new UnauthorizedActionException("view the organizational tree");
        when(service.findOwnedCompanies(TOKEN)).thenReturn(List.of(company(7, "Acme")));
        when(service.viewOrganizationalTree(TOKEN, 7)).thenThrow(rule);

        OrgTreePresenter.Outcome.Failure fail =
                assertInstanceOf(OrgTreePresenter.Outcome.Failure.class, presenter.load(TOKEN, null, false));
        assertEquals(rule.getMessage(), fail.reason());
    }
}
