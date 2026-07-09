package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.context.ApplicationEventPublisher;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.organization.adapter.out.persistence.MemoryCompanyAppointmentRepository;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.organization.application.service.CompanyMembershipService;
import com.ticketing.system.organization.domain.CompanyStatus;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.application.port.out.CompanyEventStatsPort;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.identity.domain.User;

/**
 * Exercises {@link CompanyManagementService}'s membership read paths ({@code listForUser},
 * {@code isOwnerOf}). Since appointments were promoted off {@code User} (task #20), the appointment
 * state is seeded through a real {@link CompanyMembershipService} (backed by an in-memory appointment
 * repository) rather than on the {@code User} object, and the service under test resolves membership
 * through that same instance.
 */
class CompanyMembershipServiceTest {

    private UserRepository userRepository;
    private ProductionCompanyRepository companyRepository;
    // Outbound port to catalog's active-event count (replaces the former direct EventRepository read).
    private CompanyEventStatsPort companyEventStatsPort;
    // Real membership service over an in-memory appointment repo — seeds and answers appointment queries.
    private CompanyMembershipService membershipService;
    private CompanyManagementService service;

    private static final int USER_ID = 7;
    private static final int COMPANY_ID = 100;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        companyRepository = mock(ProductionCompanyRepository.class);
        companyEventStatsPort = mock(CompanyEventStatsPort.class);
        membershipService = new CompanyMembershipService(new MemoryCompanyAppointmentRepository());
        service = new CompanyManagementService(
                companyRepository,
                userRepository,
                mock(SessionManager.class),
                companyEventStatsPort,
                membershipService,
                mock(ApplicationEventPublisher.class));
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

        var memberships = service.listForUser(USER_ID);

        assertEquals(1, memberships.size());
        assertEquals("Founder", memberships.get(0).role());
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

        var memberships = service.listForUser(USER_ID);

        assertEquals(1, memberships.size());
        assertEquals("Manager", memberships.get(0).role());
        assertEquals(0, memberships.get(0).activeEvents());
        assertEquals(List.of(Permission.VIEW_SALES), memberships.get(0).managerPermissions());
    }

    @Test
    void givenOwnerAppointment_whenIsOwnerOf_thenTrueForCoOwner() {
        User user = new User(USER_ID, "owner", "owner@test.com", "hash", 30);
        membershipService.receiveOwnerAppointment(USER_ID, COMPANY_ID, 1);
        membershipService.acceptInvitation(USER_ID, COMPANY_ID);

        when(userRepository.getUserById(USER_ID)).thenReturn(user);

        assertTrue(service.isOwnerOf(USER_ID, COMPANY_ID));
    }

    @Test
    void givenManagerAppointment_whenIsOwnerOf_thenFalse() {
        User user = new User(USER_ID, "manager", "manager@test.com", "hash", 30);
        membershipService.receiveManagerAppointment(USER_ID, COMPANY_ID, 1, List.of(Permission.VIEW_SALES));
        membershipService.acceptInvitation(USER_ID, COMPANY_ID);

        when(userRepository.getUserById(USER_ID)).thenReturn(user);

        assertFalse(service.isOwnerOf(USER_ID, COMPANY_ID));
    }
}
