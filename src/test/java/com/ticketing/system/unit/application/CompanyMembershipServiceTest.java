package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.organization.adapter.out.persistence.MemoryCompanyAppointmentRepository;
import com.ticketing.system.organization.application.dto.MembershipRole;
import com.ticketing.system.organization.application.dto.UserCompanyDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.organization.application.service.CompanyMembershipService;
import com.ticketing.system.organization.domain.CompanyStatus;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.application.port.out.CompanyEventStatsPort;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;

/**
 * Exercises {@link CompanyManagementService}'s membership read paths ({@code listForUser},
 * {@code isOwnerOf}), each of which answers for its token's own member and refuses any other token.
 * Since appointments were promoted off {@code User} (task #20), the appointment state is seeded
 * through a real {@link CompanyMembershipService} (backed by an in-memory appointment repository)
 * rather than on the {@code User} object, and the service under test resolves membership through
 * that same instance.
 */
class CompanyMembershipServiceTest {

    private UserRepository userRepository;
    private ProductionCompanyRepository companyRepository;
    // Outbound port to catalog's active-event count (replaces the former direct EventRepository read).
    private CompanyEventStatsPort companyEventStatsPort;
    // Real membership service over an in-memory appointment repo — seeds and answers appointment queries.
    private CompanyMembershipService membershipService;
    private CompanyManagementService service;
    private SessionManager sessionManager;

    private static final int USER_ID = 7;
    private static final int OTHER_USER_ID = 8;
    private static final int COMPANY_ID = 100;
    private static final int OTHER_COMPANY_ID = 101;
    private static final String MEMBER_TOKEN = "member-token";
    private static final String INVALID_TOKEN = "invalid-token";
    // An admin token naming an admin whose id is also a member's: admin and member ids share one id
    // space (ADR-012), so a reader that took the admin's id would answer for that member.
    private static final String ADMIN_TOKEN = "admin-token";

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        companyRepository = mock(ProductionCompanyRepository.class);
        companyEventStatsPort = mock(CompanyEventStatsPort.class);
        membershipService = new CompanyMembershipService(new MemoryCompanyAppointmentRepository(), Clock.systemUTC());
        sessionManager = mock(SessionManager.class);
        when(sessionManager.validateToken(MEMBER_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(MEMBER_TOKEN)).thenReturn(USER_ID);
        when(sessionManager.validateAdminToken(ADMIN_TOKEN)).thenReturn(true);
        when(sessionManager.extractAdminId(ADMIN_TOKEN)).thenReturn(OTHER_USER_ID);
        service = new CompanyManagementService(
                companyRepository,
                userRepository,
                sessionManager,
                companyEventStatsPort,
                membershipService,
                mock(ApplicationEventPublisher.class));
    }

    // Appointments are stamped from the injected clock, as UTC wall time (ADR-008).
    @Test
    void GivenFixedClock_WhenOwnerAppointmentReceived_ThenItIsCreatedAtTheClock() {
        Instant t0 = Instant.parse("2027-07-15T17:00:00Z");
        CompanyMembershipService membership =
                new CompanyMembershipService(new MemoryCompanyAppointmentRepository(), Clock.fixed(t0, ZoneOffset.UTC));

        membership.receiveOwnerAppointment(7, 3, 1);

        assertEquals(LocalDateTime.ofInstant(t0, ZoneOffset.UTC),
                membership.getPendingCompanyAppointment(7, 3).getCreatedAt());
    }

    @Test
    void givenFounderAppointment_whenListForUser_thenReturnsFounderMembership() {
        User user = new User(USER_ID, "founder", "founder@test.com", "hash", 30);
        membershipService.addFounderAppointment(USER_ID, COMPANY_ID);

        ProductionCompany company = new ProductionCompany(
                COMPANY_ID, USER_ID, "Acme Events", CompanyStatus.ACTIVE, "Desc", 4.5);

        when(userRepository.getUserById(USER_ID)).thenReturn(user);
        when(companyRepository.getCompanyById(COMPANY_ID)).thenReturn(company);
        // Company has no active events — the outbound port answers 0 (was: eventRepository stub).
        when(companyEventStatsPort.countActiveEvents(COMPANY_ID)).thenReturn(0);

        var memberships = service.listForUser(MEMBER_TOKEN);

        assertEquals(1, memberships.size());
        assertEquals(MembershipRole.FOUNDER, memberships.get(0).role());
        assertEquals("Acme Events", memberships.get(0).name());
        assertEquals(1, memberships.get(0).members());
    }

    @Test
    void givenManagerAppointment_whenListForUser_thenReturnsManagerPermissions() {
        User user = new User(USER_ID, "manager", "manager@test.com", "hash", 30);
        membershipService.receiveManagerAppointment(USER_ID, COMPANY_ID, 1, List.of(Permission.VIEW_SALES));
        membershipService.acceptInvitation(USER_ID, COMPANY_ID);

        ProductionCompany company = new ProductionCompany(
                COMPANY_ID, 1, "Acme Events", CompanyStatus.ACTIVE, "Desc", 4.5);
        company.addManager(USER_ID);

        when(userRepository.getUserById(USER_ID)).thenReturn(user);
        when(companyRepository.getCompanyById(COMPANY_ID)).thenReturn(company);
        // Company has no active events — the outbound port answers 0 (was: eventRepository stub).
        when(companyEventStatsPort.countActiveEvents(COMPANY_ID)).thenReturn(0);

        var memberships = service.listForUser(MEMBER_TOKEN);

        assertEquals(1, memberships.size());
        assertEquals(MembershipRole.MANAGER, memberships.get(0).role());
        assertEquals(0, memberships.get(0).activeEvents());
        assertEquals(List.of(Permission.VIEW_SALES), memberships.get(0).managerPermissions());
    }

    @Test
    void GivenAcceptedOwnerAppointmentInAnotherFoundersCompany_WhenListForUser_ThenRoleIsCoOwner() {
        User user = new User(USER_ID, "coowner", "coowner@test.com", "hash", 30);
        membershipService.receiveOwnerAppointment(USER_ID, COMPANY_ID, 1);
        membershipService.acceptInvitation(USER_ID, COMPANY_ID);
        when(userRepository.getUserById(USER_ID)).thenReturn(user);
        when(companyRepository.getCompanyById(COMPANY_ID)).thenReturn(new ProductionCompany(
                COMPANY_ID, 1, "Acme Events", CompanyStatus.ACTIVE, "Desc", 4.5));

        var memberships = service.listForUser(MEMBER_TOKEN);

        assertEquals(List.of(MembershipRole.CO_OWNER), memberships.stream().map(UserCompanyDTO::role).toList());
    }

    @Test
    void givenOwnerAppointment_whenIsOwnerOf_thenTrueForCoOwner() {
        User user = new User(USER_ID, "owner", "owner@test.com", "hash", 30);
        membershipService.receiveOwnerAppointment(USER_ID, COMPANY_ID, 1);
        membershipService.acceptInvitation(USER_ID, COMPANY_ID);

        when(userRepository.getUserById(USER_ID)).thenReturn(user);

        assertTrue(service.isOwnerOf(MEMBER_TOKEN, COMPANY_ID));
    }

    @Test
    void givenManagerAppointment_whenIsOwnerOf_thenFalse() {
        User user = new User(USER_ID, "manager", "manager@test.com", "hash", 30);
        membershipService.receiveManagerAppointment(USER_ID, COMPANY_ID, 1, List.of(Permission.VIEW_SALES));
        membershipService.acceptInvitation(USER_ID, COMPANY_ID);

        when(userRepository.getUserById(USER_ID)).thenReturn(user);

        assertFalse(service.isOwnerOf(MEMBER_TOKEN, COMPANY_ID));
    }

    // ---- credentials: each read answers for the token's own member, and only a member's token counts ----

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {INVALID_TOKEN, ADMIN_TOKEN})
    void GivenNoLiveMemberToken_WhenListForUser_ThenInvalidToken(String token) {
        seedFounder(OTHER_USER_ID, COMPANY_ID, "Other's Company");

        assertThrows(InvalidTokenException.class, () -> service.listForUser(token));
    }

    @Test
    void GivenTwoMembersWithTheirOwnCompanies_WhenListForUserWithOneMembersToken_ThenOnlyThatMembersCompany() {
        seedFounder(USER_ID, COMPANY_ID, "Mine");
        seedFounder(OTHER_USER_ID, OTHER_COMPANY_ID, "Theirs");

        var memberships = service.listForUser(MEMBER_TOKEN);

        assertEquals(List.of(COMPANY_ID), memberships.stream().map(UserCompanyDTO::companyId).toList(),
                "the token's member must see their own companies and no one else's");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {INVALID_TOKEN, ADMIN_TOKEN})
    void GivenNoLiveMemberToken_WhenIsOwnerOf_ThenInvalidToken(String token) {
        seedFounder(OTHER_USER_ID, COMPANY_ID, "Other's Company");

        assertThrows(InvalidTokenException.class, () -> service.isOwnerOf(token, COMPANY_ID));
    }

    @Test
    void GivenAnotherMemberOwnsTheCompany_WhenIsOwnerOfWithTheMembersToken_ThenFalse() {
        when(userRepository.getUserById(USER_ID)).thenReturn(new User(USER_ID, "member", "member@test.com", "hash", 30));
        seedFounder(OTHER_USER_ID, COMPANY_ID, "Theirs");

        assertFalse(service.isOwnerOf(MEMBER_TOKEN, COMPANY_ID),
                "the answer must be about the token's member, who owns nothing here");
    }

    private void seedFounder(int userId, int companyId, String companyName) {
        when(userRepository.getUserById(userId)).thenReturn(
                new User(userId, "user" + userId, "user" + userId + "@test.com", "hash", 30));
        membershipService.addFounderAppointment(userId, companyId);
        when(companyRepository.getCompanyById(companyId)).thenReturn(
                new ProductionCompany(companyId, userId, companyName, CompanyStatus.ACTIVE, "Desc", 4.5));
    }

    // Only the owner who appointed a manager may change the manager's permissions; another owner is
    // refused as an unauthorised action, and the permissions stay as they were.
    @Test
    void GivenManagerAppointedByAnotherOwner_WhenModifyManagerPermissions_ThenUnauthorizedActionAndUnchanged() {
        int appointerId = 1;
        int otherOwnerId = 2;
        membershipService.receiveManagerAppointment(USER_ID, COMPANY_ID, appointerId, List.of(Permission.VIEW_SALES));
        membershipService.acceptInvitation(USER_ID, COMPANY_ID);

        UnauthorizedActionException refused = assertThrows(UnauthorizedActionException.class,
                () -> membershipService.modifyManagerPermissions(USER_ID, COMPANY_ID, otherOwnerId,
                        List.of(Permission.EDIT_POLICIES)));

        assertEquals("Not authorized to perform action: Only the original appointer can modify manager permissions",
                refused.getMessage());
        assertEquals(Set.of(Permission.VIEW_SALES),
                membershipService.getActiveCompanyAppointment(USER_ID, COMPANY_ID).getPermissions());
    }

    // A failing company store is an outage, not a missing company: listing must not hide it behind
    // an empty list (#187).
    @Test
    void GivenCompanyStoreOutage_WhenListForUser_ThenTheOutagePropagates() {
        User user = new User(USER_ID, "founder", "founder@test.com", "hash", 30);
        membershipService.addFounderAppointment(USER_ID, COMPANY_ID);
        when(userRepository.getUserById(USER_ID)).thenReturn(user);
        DataAccessResourceFailureException outage = new DataAccessResourceFailureException("company store unreachable");
        when(companyRepository.getCompanyById(COMPANY_ID)).thenThrow(outage);

        DataAccessResourceFailureException thrown = assertThrows(DataAccessResourceFailureException.class,
                () -> service.listForUser(MEMBER_TOKEN),
                "a company store outage must reach the caller, not be read as a missing company");

        assertSame(outage, thrown);
    }
}
