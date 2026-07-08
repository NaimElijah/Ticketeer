package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.Core.Application.interfaces.INotificationService;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.organization.domain.CompanyStatus;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.Core.Domain.events.IEventRepository;
import com.ticketing.system.Core.Domain.orders.IOrderReceiptRepository;
import com.ticketing.system.Core.Domain.Tickets.ITicketRepository;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.Core.Domain.users.Permission;
import com.ticketing.system.identity.domain.User;

class CompanyMembershipServiceTest {

    private UserRepository userRepository;
    private ProductionCompanyRepository companyRepository;
    private IEventRepository eventRepository;
    private CompanyManagementService service;

    private static final int USER_ID = 7;
    private static final int COMPANY_ID = 100;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        companyRepository = mock(ProductionCompanyRepository.class);
        eventRepository = mock(IEventRepository.class);
        service = new CompanyManagementService(
                companyRepository,
                userRepository,
                mock(IOrderReceiptRepository.class),
                mock(SessionManager.class),
                mock(ITicketRepository.class),
                eventRepository,
                mock(INotificationService.class));
    }

    @Test
    void givenFounderAppointment_whenListForUser_thenReturnsFounderMembership() {
        User user = new User(USER_ID, "founder", "founder@test.com", "hash", 30);
        user.addFounderAppointment(COMPANY_ID);

        ProductionCompany company = new ProductionCompany(
                COMPANY_ID, USER_ID, "Acme Events", CompanyStatus.ACTIVE, "Desc", 4.5);

        when(userRepository.getUserById(USER_ID)).thenReturn(user);
        when(companyRepository.getCompanyById(COMPANY_ID)).thenReturn(company);
        when(eventRepository.findByCompanyId(COMPANY_ID)).thenReturn(List.of());

        var memberships = service.listForUser(USER_ID);

        assertEquals(1, memberships.size());
        assertEquals("Founder", memberships.get(0).role());
        assertEquals("Acme Events", memberships.get(0).name());
        assertEquals(1, memberships.get(0).members());
    }

    @Test
    void givenManagerAppointment_whenListForUser_thenReturnsManagerPermissions() {
        User user = new User(USER_ID, "manager", "manager@test.com", "hash", 30);
        user.receiveManagerAppointment(COMPANY_ID, 1, List.of(Permission.VIEW_SALES));
        user.acceptInvitation(COMPANY_ID);

        ProductionCompany company = new ProductionCompany(
                COMPANY_ID, 1, "Acme Events", CompanyStatus.ACTIVE, "Desc", 4.5);
        company.addManager(USER_ID);

        when(userRepository.getUserById(USER_ID)).thenReturn(user);
        when(companyRepository.getCompanyById(COMPANY_ID)).thenReturn(company);
        when(eventRepository.findByCompanyId(COMPANY_ID)).thenReturn(List.of());

        var memberships = service.listForUser(USER_ID);

        assertEquals(1, memberships.size());
        assertEquals("Manager", memberships.get(0).role());
        assertEquals(0, memberships.get(0).activeEvents());
        assertEquals(List.of(Permission.VIEW_SALES), memberships.get(0).managerPermissions());
    }

    @Test
    void givenOwnerAppointment_whenIsOwnerOf_thenTrueForCoOwner() {
        User user = new User(USER_ID, "owner", "owner@test.com", "hash", 30);
        user.receiveOwnerAppointment(COMPANY_ID, 1);
        user.acceptInvitation(COMPANY_ID);

        when(userRepository.getUserById(USER_ID)).thenReturn(user);

        assertTrue(service.isOwnerOf(USER_ID, COMPANY_ID));
    }

    @Test
    void givenManagerAppointment_whenIsOwnerOf_thenFalse() {
        User user = new User(USER_ID, "manager", "manager@test.com", "hash", 30);
        user.receiveManagerAppointment(COMPANY_ID, 1, List.of(Permission.VIEW_SALES));
        user.acceptInvitation(COMPANY_ID);

        when(userRepository.getUserById(USER_ID)).thenReturn(user);

        assertFalse(service.isOwnerOf(USER_ID, COMPANY_ID));
    }
}
