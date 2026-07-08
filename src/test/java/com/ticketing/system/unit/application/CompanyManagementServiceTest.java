package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import com.ticketing.system.Core.Application.dto.AppointmentInfoDTO;
import com.ticketing.system.Core.Application.dto.InvitationDTO;
import com.ticketing.system.Core.Application.dto.MyCompanyDTO;
import com.ticketing.system.Core.Application.dto.OrganizationalTreeNodeDTO;
import com.ticketing.system.Core.Application.dto.PermissionEditDTO;
import com.ticketing.system.Core.Application.dto.ProductionCompanyDTO;
import com.ticketing.system.Core.Application.dto.PurchaseHistoryDTO;
import com.ticketing.system.Core.Application.interfaces.INotificationService;
import com.ticketing.system.identity.application.port.out.SessionManager;

import com.ticketing.system.Core.Application.services.CompanyManagementService;
import com.ticketing.system.Core.Application.dto.AppointmentResponseDTO;
import com.ticketing.system.Core.Application.dto.AppointmentRevokeDTO;
import com.ticketing.system.Core.Application.dto.CompanyRegistrationDTO;
import com.ticketing.system.Core.Application.dto.ManagerAppointmentRequestDTO;
import com.ticketing.system.Core.Domain.Tickets.ITicketRepository;
import com.ticketing.system.Core.Domain.Tickets.Ticket;
import com.ticketing.system.Core.Domain.company.CompanyStatus;
import com.ticketing.system.Core.Domain.company.IProductionCompanyRepository;
import com.ticketing.system.Core.Domain.company.ProductionCompany;
import com.ticketing.system.Core.Domain.events.Event;
import com.ticketing.system.Core.Domain.events.IEventRepository;
import com.ticketing.system.Core.Domain.orders.IOrderReceiptRepository;
import com.ticketing.system.Core.Domain.orders.OrderReceipt;
import com.ticketing.system.Core.Domain.orders.ReceiptLine;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.Core.Domain.users.Permission;
import com.ticketing.system.identity.domain.User;

public class CompanyManagementServiceTest {

        private IProductionCompanyRepository mockCompanyRepo;
        private UserRepository mockUserRepo;
        private IOrderReceiptRepository mockOrderReceiptRepo;
        private SessionManager sessionManager;
        private CompanyManagementService companyService;
        private ITicketRepository ticketRepository;
        private IEventRepository eventRepository;
        private INotificationService notificationService;

        private final String OWNER_TOKEN = "owner-token";
        private final String TARGET_TOKEN = "target-token";
        private final String INVALID_TOKEN = "invalid-token";

        private final int COMPANY_ID = 100;
        private final int OWNER_ID = 1;
        private final int ORDER_RECEIPT_ID = 11;
        private final String COMPANY_1_NAME = "Company1";
        private final String COMPANY_1_DESCRIPTION = "A test production company1";

        private final int TARGET_USER_ID = 2;
        private List<Permission> defaultPermissions;

        @BeforeEach
        public void setUp() {
                mockCompanyRepo = mock(IProductionCompanyRepository.class);
                mockUserRepo = mock(UserRepository.class);
                mockOrderReceiptRepo = mock(IOrderReceiptRepository.class);
                sessionManager = mock(SessionManager.class);
                ticketRepository = mock(ITicketRepository.class);
                eventRepository = mock(IEventRepository.class);
                notificationService = mock(INotificationService.class);

                companyService = new CompanyManagementService(
                                mockCompanyRepo,
                                mockUserRepo,
                                mockOrderReceiptRepo,
                                sessionManager,
                                ticketRepository,
                                eventRepository,
                                notificationService);

                defaultPermissions = new ArrayList<>();
                defaultPermissions.add(Permission.CONFIGURE_VENUE);
                defaultPermissions.add(Permission.MANAGE_INVENTORY);

        }

        @Test
        public void GivenOwnerAndTargetUser_WhenInviteManager_ThenTargetHasOneInvitation() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User ownerUser = new User(OWNER_ID, "ownerUser", "user@test.com", "password", 22);
                ownerUser.addFounderAppointment(COMPANY_ID);
                User targetUser = new User(TARGET_USER_ID, "targetUser", "user@test.com", "password", 19);

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);

                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(targetUser);

                companyService.appointManager(OWNER_TOKEN, new ManagerAppointmentRequestDTO(
                                COMPANY_ID, TARGET_USER_ID, defaultPermissions));

                assertNotEquals(null, targetUser.getPendingCompanyAppointment(COMPANY_ID));
        }

        @Test
        public void GivenPendingManagerInvitation_WhenTargetListsMyInvitations_ThenInvitationIsReturned() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User ownerUser = new User(OWNER_ID, "ownerUser", "user@test.com", "password", 22);
                ownerUser.addFounderAppointment(COMPANY_ID);
                User targetUser = new User(TARGET_USER_ID, "targetUser", "user@test.com", "password", 19);

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(targetUser);

                companyService.appointManager(OWNER_TOKEN, new ManagerAppointmentRequestDTO(
                                COMPANY_ID, TARGET_USER_ID, defaultPermissions));

                when(sessionManager.validateToken(TARGET_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(TARGET_TOKEN)).thenReturn(TARGET_USER_ID);

                List<InvitationDTO> invitations = companyService.listMyInvitations(TARGET_TOKEN);

                assertEquals(1, invitations.size());
                InvitationDTO inv = invitations.get(0);
                assertEquals(COMPANY_ID, inv.companyId());
                assertEquals(COMPANY_1_NAME, inv.companyName());
                assertEquals("Manager", inv.role());
                assertEquals("ownerUser", inv.fromUsername());
                assertEquals("PENDING", inv.status());
        }

        @Test
        public void GivenPendingManagerInvitation_WhenTargetAccepts_ThenTargetIsCompanyManager() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User ownerUser = new User(OWNER_ID, "ownerUser", "user@test.com", "password", 22);
                ownerUser.addFounderAppointment(COMPANY_ID);
                User targetUser = new User(TARGET_USER_ID, "targetUser", "user@test.com", "password", 20);

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);

                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(targetUser);

                companyService.appointManager(OWNER_TOKEN, new ManagerAppointmentRequestDTO(
                                COMPANY_ID, TARGET_USER_ID, defaultPermissions));

                when(sessionManager.validateToken(TARGET_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(TARGET_TOKEN)).thenReturn(TARGET_USER_ID);

                companyService.respondToAppointment(TARGET_TOKEN, new AppointmentResponseDTO(COMPANY_ID, true));

                assertTrue(company.getManagers().contains(TARGET_USER_ID));
        }

        @Test
        public void GivenPendingManagerInvitation_WhenTargetRejects_ThenTargetHasNoInvitations() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User ownerUser = new User(OWNER_ID, "ownerUser", "user@test.com", "password", 22);
                ownerUser.addFounderAppointment(COMPANY_ID);
                User targetUser = new User(TARGET_USER_ID, "targetUser", "user@test.com", "password", 19);

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);

                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(targetUser);

                companyService.appointManager(
                                OWNER_TOKEN,
                                new ManagerAppointmentRequestDTO(
                                                COMPANY_ID,
                                                TARGET_USER_ID,
                                                defaultPermissions));

                when(sessionManager.validateToken(TARGET_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(TARGET_TOKEN)).thenReturn(TARGET_USER_ID);

                companyService.respondToAppointment(TARGET_TOKEN, new AppointmentResponseDTO(COMPANY_ID, false));

                assertTrue(targetUser.getPendingCompanyAppointment(COMPANY_ID) == null);
        }

        @Test
        public void GivenAcceptedManager_WhenOwnerModifiesPermissions_ThenCompanyPermissionsAreUpdated() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User ownerUser = new User(OWNER_ID, "ownerUser", "user@test.com", "password", 22);
                ownerUser.addFounderAppointment(COMPANY_ID);
                User targetUser = new User(TARGET_USER_ID, "targetUser", "user@test.com", "password", 20);

                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(targetUser);

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);

                companyService.appointManager(OWNER_TOKEN, new ManagerAppointmentRequestDTO(
                                COMPANY_ID,
                                TARGET_USER_ID,
                                defaultPermissions));

                when(sessionManager.validateToken(TARGET_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(TARGET_TOKEN)).thenReturn(TARGET_USER_ID);

                companyService.respondToAppointment(TARGET_TOKEN, new AppointmentResponseDTO(COMPANY_ID, true));

                List<Permission> newPermissions = new ArrayList<>();
                newPermissions.add(Permission.EDIT_POLICIES);

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);

                companyService.editManagerPermissions(OWNER_TOKEN,
                                new PermissionEditDTO(COMPANY_ID, TARGET_USER_ID, newPermissions));

                for (Permission perm : newPermissions) {
                        assertTrue(targetUser.hasPermissionInCompany(COMPANY_ID, perm));
                }
        }

        @Test
        public void GivenAcceptedManager_WhenOwnerRevokesManager_ThenCompanyDoesNotContainManager() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User ownerUser = new User(OWNER_ID, "ownerUser", "user@test.com", "password", 22);
                ownerUser.addFounderAppointment(COMPANY_ID);
                User targetUser = new User(TARGET_USER_ID, "targetUser", "user@test.com", "password", 20);

                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(targetUser);

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);

                companyService.appointManager(OWNER_TOKEN, new ManagerAppointmentRequestDTO(
                                COMPANY_ID,
                                TARGET_USER_ID,
                                defaultPermissions));

                when(sessionManager.validateToken(TARGET_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(TARGET_TOKEN)).thenReturn(TARGET_USER_ID);

                companyService.respondToAppointment(TARGET_TOKEN, new AppointmentResponseDTO(COMPANY_ID, true));

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);

                companyService.RevokeAppointment(OWNER_TOKEN, new AppointmentRevokeDTO(COMPANY_ID, TARGET_USER_ID));

                assertFalse(company.getManagers().contains(TARGET_USER_ID));
        }

        @Test
        public void GivenInvalidToken_WhenInviteManager_ThenThrowException() {
                when(sessionManager.validateToken("invalid-token")).thenReturn(false);

                assertThrows(RuntimeException.class, () -> companyService.appointManager(
                                "invalid-token",
                                new ManagerAppointmentRequestDTO(
                                                COMPANY_ID,
                                                TARGET_USER_ID,
                                                defaultPermissions)));
        }

        @Test
        public void GivenCompanyDoesNotExist_WhenInviteManager_ThenThrowException() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(null);

                assertThrows(RuntimeException.class, () -> companyService.appointManager(
                                OWNER_TOKEN,
                                new ManagerAppointmentRequestDTO(
                                                COMPANY_ID,
                                                TARGET_USER_ID,
                                                defaultPermissions)));
        }

        @Test
        public void GivenUserIsNotOwner_WhenInviteManager_ThenThrowException() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User nonOwnerUser = new User(3, "nonOwner", "user@test.com", "password", 25);

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(3);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(3)).thenReturn(nonOwnerUser);

                assertThrows(RuntimeException.class, () -> companyService.appointManager(
                                OWNER_TOKEN,
                                new ManagerAppointmentRequestDTO(
                                                COMPANY_ID,
                                                TARGET_USER_ID,
                                                defaultPermissions)));
        }

        @Test
        public void GivenTargetUserDoesNotExist_WhenInviteManager_ThenThrowException() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User ownerUser = new User(OWNER_ID, "ownerUser", "user@test.com", "password", 22);
                ownerUser.addFounderAppointment(COMPANY_ID);

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(null);

                assertThrows(RuntimeException.class, () -> companyService.appointManager(
                                OWNER_TOKEN,
                                new ManagerAppointmentRequestDTO(
                                                COMPANY_ID,
                                                TARGET_USER_ID,
                                                defaultPermissions)));
        }

        @Test
        public void GivenEmptyPermissions_WhenInviteManager_ThenThrowException() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User ownerUser = new User(OWNER_ID, "ownerUser", "user@test.com", "password", 22);
                ownerUser.addFounderAppointment(COMPANY_ID);
                User targetUser = new User(TARGET_USER_ID, "targetUser", "user@test.com", "password", 20);

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(targetUser);

                assertThrows(RuntimeException.class, () -> companyService.appointManager(
                                OWNER_TOKEN,
                                new ManagerAppointmentRequestDTO(
                                                COMPANY_ID,
                                                TARGET_USER_ID,
                                                new ArrayList<>())));
        }

        @Test
        public void GivenInvalidToken_WhenAcceptManagerInvitation_ThenThrowException() {
                when(sessionManager.validateToken("invalid-token")).thenReturn(false);

                assertThrows(RuntimeException.class, () -> companyService.respondToAppointment(
                                "invalid-token",
                                new AppointmentResponseDTO(COMPANY_ID, true)));
        }

        @Test
        public void GivenTargetUserDoesNotExist_WhenAcceptManagerInvitation_ThenThrowException() {
                when(sessionManager.validateToken(TARGET_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(TARGET_TOKEN)).thenReturn(TARGET_USER_ID);
                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(null);

                assertThrows(RuntimeException.class, () -> companyService.respondToAppointment(
                                TARGET_TOKEN,
                                new AppointmentResponseDTO(COMPANY_ID, true)));
        }

        @Test
        public void GivenCompanyDoesNotExist_WhenAcceptManagerInvitation_ThenThrowException() {
                User targetUser = new User(TARGET_USER_ID, "targetUser", "user@test.com", "password", 20);

                when(sessionManager.validateToken(TARGET_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(TARGET_TOKEN)).thenReturn(TARGET_USER_ID);
                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(targetUser);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(null);

                assertThrows(RuntimeException.class, () -> companyService.respondToAppointment(
                                TARGET_TOKEN,
                                new AppointmentResponseDTO(COMPANY_ID, true)));
        }

        @Test
        public void GivenNoPendingInvitation_WhenAcceptManagerInvitation_ThenThrowException() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User targetUser = new User(TARGET_USER_ID, "targetUser", "user@test.com", "password", 20);

                when(sessionManager.validateToken(TARGET_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(TARGET_TOKEN)).thenReturn(TARGET_USER_ID);
                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(targetUser);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);

                assertThrows(RuntimeException.class, () -> companyService.respondToAppointment(
                                TARGET_TOKEN,
                                new AppointmentResponseDTO(COMPANY_ID, true)));
        }

        @Test
        public void GivenInvalidToken_WhenRejectManagerInvitation_ThenThrowException() {
                when(sessionManager.validateToken("invalid-token")).thenReturn(false);

                assertThrows(RuntimeException.class, () -> companyService.respondToAppointment(
                                "invalid-token",
                                new AppointmentResponseDTO(COMPANY_ID, false)));
        }

        @Test
        public void GivenNoPendingInvitation_WhenRejectManagerInvitation_ThenThrowException() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User targetUser = new User(TARGET_USER_ID, "targetUser", "user@test.com", "password", 20);

                when(sessionManager.validateToken(TARGET_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(TARGET_TOKEN)).thenReturn(TARGET_USER_ID);
                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(targetUser);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);

                assertThrows(RuntimeException.class, () -> companyService.respondToAppointment(
                                TARGET_TOKEN,
                                new AppointmentResponseDTO(COMPANY_ID, false)));
        }

        @Test
        public void GivenInvalidToken_WhenRevokeManager_ThenThrowException() {
                when(sessionManager.validateToken("invalid-token")).thenReturn(false);

                assertThrows(RuntimeException.class, () -> companyService.RevokeAppointment(
                                "invalid-token",
                                new AppointmentRevokeDTO(COMPANY_ID, TARGET_USER_ID)));
        }

        @Test
        public void GivenUserIsNotOwner_WhenRevokeManager_ThenThrowException() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User nonOwnerUser = new User(3, "nonOwner", "user@test.com", "password", 22);
                User targetUser = new User(TARGET_USER_ID, "targetUser", "user@test.com", "password", 22);

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(3);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(3)).thenReturn(nonOwnerUser);
                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(targetUser);

                assertThrows(RuntimeException.class, () -> companyService.RevokeAppointment(
                                OWNER_TOKEN,
                                new AppointmentRevokeDTO(COMPANY_ID, TARGET_USER_ID)));
        }

        @Test
        public void GivenTargetIsNotManager_WhenRevokeManager_ThenThrowException() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User ownerUser = new User(OWNER_ID, "ownerUser", "user@test.com", "password", 22);
                ownerUser.addFounderAppointment(COMPANY_ID);
                User targetUser = new User(TARGET_USER_ID, "targetUser", "user@test.com", "password", 20);

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(targetUser);

                assertThrows(RuntimeException.class, () -> companyService.RevokeAppointment(
                                OWNER_TOKEN,
                                new AppointmentRevokeDTO(COMPANY_ID, TARGET_USER_ID)));
        }

        @Test
        public void GivenInvalidToken_WhenModifyManagerPermissions_ThenThrowException() {
                when(sessionManager.validateToken("invalid-token")).thenReturn(false);

                assertThrows(RuntimeException.class, () -> companyService.editManagerPermissions(
                                "invalid-token",
                                new PermissionEditDTO(COMPANY_ID, TARGET_USER_ID, defaultPermissions)));
        }

        @Test
        public void GivenUserIsNotOwner_WhenModifyManagerPermissions_ThenThrowException() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User targetUser = new User(TARGET_USER_ID, "targetUser", "user@test.com", "password", 25);

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(3);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(targetUser);

                assertThrows(RuntimeException.class, () -> companyService.editManagerPermissions(
                                OWNER_TOKEN,
                                new PermissionEditDTO(COMPANY_ID, TARGET_USER_ID, defaultPermissions)));
        }

        @Test
        public void GivenTargetIsNotManager_WhenModifyManagerPermissions_ThenThrowException() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User targetUser = new User(TARGET_USER_ID, "targetUser", "user@test.com", "password", 20);

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(targetUser);

                // editManagerPermissions only fetches the target user (not the owner), so no
                // owner mock needed
                assertThrows(RuntimeException.class, () -> companyService.editManagerPermissions(
                                OWNER_TOKEN,
                                new PermissionEditDTO(COMPANY_ID, TARGET_USER_ID, defaultPermissions)));
        }

        @Test
        public void GivenEmptyPermissions_WhenModifyManagerPermissions_ThenThrowException() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User ownerUser = new User(OWNER_ID, "ownerUser", "user@test.com", "password", 22);
                ownerUser.addFounderAppointment(COMPANY_ID);
                User targetUser = new User(TARGET_USER_ID, "targetUser", "user@test.com", "password", 20);

                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(targetUser);

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);

                companyService.appointManager(OWNER_TOKEN,
                                new ManagerAppointmentRequestDTO(COMPANY_ID, TARGET_USER_ID, defaultPermissions));

                when(sessionManager.validateToken(TARGET_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(TARGET_TOKEN)).thenReturn(TARGET_USER_ID);

                companyService.respondToAppointment(
                                TARGET_TOKEN, new AppointmentResponseDTO(COMPANY_ID, true));

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);

                assertThrows(RuntimeException.class, () -> companyService.editManagerPermissions(
                                OWNER_TOKEN,
                                new PermissionEditDTO(COMPANY_ID, TARGET_USER_ID, new ArrayList<>())));
        }

        @Test
        public void GivenAlreadyPendingInvitation_WhenInviteManagerAgain_ThenThrowException() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User ownerUser = new User(OWNER_ID, "ownerUser", "user@test.com", "password", 22);
                ownerUser.addFounderAppointment(COMPANY_ID);
                User targetUser = new User(TARGET_USER_ID, "targetUser", "user@test.com", "password", 20);

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(targetUser);

                companyService.appointManager(OWNER_TOKEN,
                                new ManagerAppointmentRequestDTO(COMPANY_ID, TARGET_USER_ID, defaultPermissions));

                assertThrows(RuntimeException.class, () -> companyService.appointManager(OWNER_TOKEN,
                                new ManagerAppointmentRequestDTO(COMPANY_ID, TARGET_USER_ID, defaultPermissions)));
        }

        @Test
        public void GivenValidData_WhenRegisterCompany_ThenCompanySavedAndDTOReturned() {
                String token = "valid-token";
                int userId = 10;
                int expectedCompanyId = 100;
                CompanyRegistrationDTO request = new CompanyRegistrationDTO("Epic Productions", "Great movies");

                when(sessionManager.validateToken(token)).thenReturn(true);
                when(sessionManager.extractUserId(token)).thenReturn(userId);

                when(mockCompanyRepo.existsByName("Epic Productions")).thenReturn(false);
                when(mockCompanyRepo.nextId()).thenReturn(expectedCompanyId);

                when(mockUserRepo.getUserById(userId))
                                .thenReturn(new User(userId, "founder", "user@test.com", "password", 30));

                // save() is void per IProductionCompanyRepository — no return-value mock
                // needed.
                // The service builds the DTO from the locally-constructed ProductionCompany,
                // so the test verifies via the captured argument below + the returned DTO.

                ProductionCompanyDTO result = companyService.registerCompany(token, request);

                assertNotNull(result);
                assertEquals(expectedCompanyId, result.companyId());
                assertEquals("Epic Productions", result.name());

                ArgumentCaptor<ProductionCompany> companyCaptor = ArgumentCaptor.forClass(ProductionCompany.class);
                verify(mockCompanyRepo, times(1)).save(companyCaptor.capture());

                ProductionCompany capturedCompany = companyCaptor.getValue();
                assertEquals("Epic Productions", capturedCompany.getName());
                assertEquals(userId, capturedCompany.getOwnerId());
        }

        @Test
        public void GivenInvalidToken_WhenRegisterCompany_ThenThrowException() {
                String token = "invalid-token";
                CompanyRegistrationDTO request = new CompanyRegistrationDTO("Name", "Desc");

                when(sessionManager.validateToken(token)).thenReturn(false);

                RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                        companyService.registerCompany(token, request);
                });

                assertEquals("Invalid authentication token: Invalid token", exception.getMessage());
                verify(mockCompanyRepo, never()).save(any());
        }

        @Test
        public void GivenEmptyCompanyName_WhenRegisterCompany_ThenThrowException() {
                String token = "valid-token";
                CompanyRegistrationDTO request = new CompanyRegistrationDTO("   ", "Valid Description");

                when(sessionManager.validateToken(token)).thenReturn(true);
                when(sessionManager.extractUserId(token)).thenReturn(1);

                IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
                        companyService.registerCompany(token, request);
                });

                assertTrue(exception.getMessage().contains("All company fields"));
                verify(mockCompanyRepo, never()).save(any());
        }

        @Test
        public void GivenExistingCompanyName_WhenRegisterCompany_ThenThrowException() {
                String token = "valid-token";
                CompanyRegistrationDTO request = new CompanyRegistrationDTO("Existing Name", "Desc");

                when(sessionManager.validateToken(token)).thenReturn(true);
                when(sessionManager.extractUserId(token)).thenReturn(1);

                when(mockCompanyRepo.existsByName("Existing Name")).thenReturn(true);

                IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
                        companyService.registerCompany(token, request);
                });

                assertEquals("A company with this name already exists", exception.getMessage());
                verify(mockCompanyRepo, never()).save(any());
        }

        @Test
        public void GivenDatabaseError_WhenRegisterCompany_ThenThrowException() {
                String token = "valid-token";
                CompanyRegistrationDTO request = new CompanyRegistrationDTO("New Co", "Desc");

                when(sessionManager.validateToken(token)).thenReturn(true);
                when(sessionManager.extractUserId(token)).thenReturn(1);
                when(mockCompanyRepo.existsByName(anyString())).thenReturn(false);
                when(mockCompanyRepo.nextId()).thenReturn(100);

                // save() returns void; use Mockito's doThrow().when() pattern for void methods.
                doThrow(new RuntimeException("Database connection lost"))
                                .when(mockCompanyRepo).save(any(ProductionCompany.class));

                RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                        companyService.registerCompany(token, request);
                });

                assertEquals("Failed to register company due to a server error", exception.getMessage());
                assertEquals("Database connection lost", exception.getCause().getMessage());
        }

        @Test
        public void GivenInvalidToken_WhenViewSalesHistory_ThenThrowException() {
                when(sessionManager.validateToken(INVALID_TOKEN)).thenReturn(false);

                assertThrows(RuntimeException.class, () -> companyService.viewSalesHistory(INVALID_TOKEN, COMPANY_ID));
        }

        @Test
        public void GivenCompanyNotFound_WhenViewSalesHistory_ThenThrowException() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(null);

                assertThrows(RuntimeException.class, () -> companyService.viewSalesHistory(OWNER_TOKEN, COMPANY_ID));
        }

        @Test
        public void GivenUserNotFound_WhenViewSalesHistory_ThenThrowException() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(OWNER_ID)).thenReturn(null);

                assertThrows(RuntimeException.class, () -> companyService.viewSalesHistory(OWNER_TOKEN, COMPANY_ID));
        }

        @Test
        public void GivenUserWithNoPermission_WhenViewSalesHistory_ThenThrowException() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User requester = mock(User.class);

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(OWNER_ID)).thenReturn(requester);
                when(requester.isOwnerInCompany(COMPANY_ID)).thenReturn(false);
                when(requester.hasPermissionInCompany(COMPANY_ID, Permission.VIEW_SALES)).thenReturn(false);

                assertThrows(RuntimeException.class, () -> companyService.viewSalesHistory(OWNER_TOKEN, COMPANY_ID));
        }

        @Test
        public void GivenOwnerWithNoSales_WhenViewSalesHistory_ThenReturnEmptyList() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User ownerUser = mock(User.class);

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(eventRepository.findIdsByCompany(COMPANY_ID)).thenReturn(new ArrayList<>());
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(ownerUser.hasPermissionInCompany(COMPANY_ID, Permission.VIEW_SALES)).thenReturn(true);
                // when(mockOrderReceiptRepo.findByCompanyId(COMPANY_ID)).thenReturn(new
                // ArrayList<>());

                List<PurchaseHistoryDTO> result = companyService.viewSalesHistory(OWNER_TOKEN, COMPANY_ID);

                assertNotNull(result);
                assertTrue(result.isEmpty());
        }

        @Test
        public void GivenManagerWithViewSalesPermission_WhenViewSalesHistory_ThenReturnSalesHistory() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User managerUser = mock(User.class);
                OrderReceipt mockReceipt = mock(OrderReceipt.class);
                Ticket ticket = new Ticket(1, 1, ORDER_RECEIPT_ID, null, 50.0, 10, "BARCODE-001");
                Event mockEvent = mock(Event.class);

                when(sessionManager.validateToken(TARGET_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(TARGET_TOKEN)).thenReturn(TARGET_USER_ID);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(managerUser);
                when(managerUser.isOwnerInCompany(COMPANY_ID)).thenReturn(false);
                when(managerUser.hasPermissionInCompany(COMPANY_ID, Permission.VIEW_SALES)).thenReturn(true);

                when(mockReceipt.getId()).thenReturn(42);
                when(mockReceipt.getPurchaseTime()).thenReturn(LocalDateTime.now());
                ReceiptLine mockLine = mock(ReceiptLine.class);
                when(mockLine.getTicketId()).thenReturn(10);
                when(mockLine.getPriceAtReservation()).thenReturn(50.0);
                when(mockReceipt.getReceiptLines()).thenReturn(List.of(mockLine));
                when(ticketRepository.findByOrderReceiptId(42)).thenReturn(List.of(ticket));
                when(eventRepository.findById(1)).thenReturn(mockEvent);
                when(mockEvent.getName()).thenReturn("Rock Concert");
                when(eventRepository.findIdsByCompany(COMPANY_ID)).thenReturn(List.of(1));
                when(mockOrderReceiptRepo.findByEventIds(List.of(1))).thenReturn(List.of(mockReceipt));

                List<PurchaseHistoryDTO> result = companyService.viewSalesHistory(TARGET_TOKEN, COMPANY_ID);

                assertNotNull(result);
                assertEquals(1, result.size());
                PurchaseHistoryDTO.PurchaseRecordDTO record = result.get(0).records().get(0);
                assertEquals(42, record.orderReceiptId());
                // assertEquals(1, record.eventId());
                // assertEquals("Rock Concert", record.eventName());
                assertEquals(50.0, record.totalPaid());
                assertEquals(1, record.tickets().size());
        }

        @Test
        public void GivenOwnerWithMultipleSales_WhenViewSalesHistory_ThenReturnAllRecords() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User ownerUser = mock(User.class);
                OrderReceipt mockReceipt1 = mock(OrderReceipt.class);
                OrderReceipt mockReceipt2 = mock(OrderReceipt.class);
                Event mockEvent = mock(Event.class);

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(ownerUser.hasPermissionInCompany(COMPANY_ID, Permission.VIEW_SALES)).thenReturn(true);

                when(mockEvent.getName()).thenReturn("Summer Festival");

                when(mockReceipt1.getId()).thenReturn(1);
                when(mockReceipt1.getPurchaseTime()).thenReturn(LocalDateTime.now());
                when(ticketRepository.findByOrderReceiptId(1)).thenReturn(new ArrayList<>());
                when(eventRepository.findById(10)).thenReturn(mockEvent);

                when(mockReceipt2.getId()).thenReturn(2);
                when(mockReceipt2.getPurchaseTime()).thenReturn(LocalDateTime.now());
                when(ticketRepository.findByOrderReceiptId(2)).thenReturn(new ArrayList<>());
                when(eventRepository.findIdsByCompany(COMPANY_ID)).thenReturn(List.of(10));
                when(mockOrderReceiptRepo.findByEventIds(List.of(10))).thenReturn(List.of(mockReceipt1, mockReceipt2));

                List<PurchaseHistoryDTO> result = companyService.viewSalesHistory(OWNER_TOKEN, COMPANY_ID);

                assertNotNull(result);
                assertEquals(2, result.size());
        }

        @Test
        public void GivenInvalidToken_WhenViewOrganizationalTree_ThenThrowException() {
                when(sessionManager.validateToken(INVALID_TOKEN)).thenReturn(false);

                assertThrows(RuntimeException.class,
                                () -> companyService.viewOrganizationalTree(INVALID_TOKEN, COMPANY_ID));
        }

        @Test
        public void GivenCompanyNotFound_WhenViewOrganizationalTree_ThenThrowException() {
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(null);

                assertThrows(RuntimeException.class,
                                () -> companyService.viewOrganizationalTree(OWNER_TOKEN, COMPANY_ID));
        }

        @Test
        public void GivenUserNotFound_WhenViewOrganizationalTree_ThenThrowException() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(OWNER_ID)).thenReturn(null);

                assertThrows(RuntimeException.class,
                                () -> companyService.viewOrganizationalTree(OWNER_TOKEN, COMPANY_ID));
        }

        @Test
        public void GivenUserIsNotOwner_WhenViewOrganizationalTree_ThenThrowException() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User nonOwnerUser = mock(User.class);

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(OWNER_ID)).thenReturn(nonOwnerUser);
                when(nonOwnerUser.isOwnerInCompany(COMPANY_ID)).thenReturn(false);

                assertThrows(RuntimeException.class,
                                () -> companyService.viewOrganizationalTree(OWNER_TOKEN, COMPANY_ID));
        }

        @Test
        public void GivenOwnerWithNoManagers_WhenViewOrganizationalTree_ThenReturnRootOnlyNode() {
                ProductionCompany company = mock(ProductionCompany.class);
                when(company.getManagers()).thenReturn(new ArrayList<>());
                when(company.getFounderId()).thenReturn(OWNER_ID);
                when(company.getOwnersIds()).thenReturn(List.of(OWNER_ID));

                User ownerUser = new User(OWNER_ID, "ownerUser", "user@test.com", "password", 30);
                ownerUser.addFounderAppointment(COMPANY_ID);

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(OWNER_ID)).thenReturn(ownerUser);

                OrganizationalTreeNodeDTO result = companyService.viewOrganizationalTree(OWNER_TOKEN, COMPANY_ID);

                assertNotNull(result);
                assertEquals(OWNER_ID, result.userId());
                assertEquals("ownerUser", result.username());
                assertEquals("Owner", result.role());
                assertTrue(result.isFounder());
                assertTrue(result.grantedPermissions().isEmpty());
                assertTrue(result.appointedByThisUser().isEmpty());
        }

        @Test
        public void GivenOwnerWithOneDirectManager_WhenViewOrganizationalTree_ThenReturnTreeWithOneChild() {
                List<Integer> managersMap = new ArrayList<>();
                managersMap.add(TARGET_USER_ID);

                ProductionCompany company = mock(ProductionCompany.class);
                when(company.getManagers()).thenReturn(managersMap);
                when(company.getFounderId()).thenReturn(OWNER_ID);
                when(company.getOwnersIds()).thenReturn(List.of(OWNER_ID));

                User ownerUser = new User(OWNER_ID, "ownerUser", "user@test.com", "password", 30);
                ownerUser.addFounderAppointment(COMPANY_ID);

                User managerUser = new User(TARGET_USER_ID, "managerUser", "user@test.com", "password", 85);
                managerUser.receiveManagerAppointment(COMPANY_ID, OWNER_ID, defaultPermissions);
                managerUser.acceptInvitation(COMPANY_ID);

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(managerUser);

                OrganizationalTreeNodeDTO result = companyService.viewOrganizationalTree(OWNER_TOKEN, COMPANY_ID);

                assertNotNull(result);
                assertEquals(OWNER_ID, result.userId());
                assertEquals(1, result.appointedByThisUser().size());

                OrganizationalTreeNodeDTO managerNode = result.appointedByThisUser().get(0);
                assertEquals(TARGET_USER_ID, managerNode.userId());
                assertEquals("managerUser", managerNode.username());
                assertEquals("Manager", managerNode.role());
                assertFalse(managerNode.isFounder());
                assertEquals(defaultPermissions.size(), managerNode.grantedPermissions().size());
                assertTrue(managerNode.appointedByThisUser().isEmpty());
        }

        @Test
        public void GivenOwnerWithTwoDirectManagers_WhenViewOrganizationalTree_ThenReturnTreeWithTwoChildren() {
                int MANAGER1_ID = TARGET_USER_ID;
                int MANAGER2_ID = 3;

                List<Integer> managersMap = new ArrayList<>();
                managersMap.add(MANAGER1_ID);
                managersMap.add(MANAGER2_ID);

                ProductionCompany company = mock(ProductionCompany.class);
                when(company.getManagers()).thenReturn(managersMap);
                when(company.getFounderId()).thenReturn(OWNER_ID);
                when(company.getOwnersIds()).thenReturn(List.of(OWNER_ID));

                User ownerUser = new User(OWNER_ID, "ownerUser", "user@test.com", "password", 30);
                ownerUser.addFounderAppointment(COMPANY_ID);

                User manager1 = new User(MANAGER1_ID, "manager1", "user@test.com", "password", 101);
                manager1.receiveManagerAppointment(COMPANY_ID, OWNER_ID, defaultPermissions);
                manager1.acceptInvitation(COMPANY_ID);

                User manager2 = new User(MANAGER2_ID, "manager2", "user@test.com", "password", 102);
                manager2.receiveManagerAppointment(COMPANY_ID, OWNER_ID, List.of(Permission.VIEW_SALES));
                manager2.acceptInvitation(COMPANY_ID);

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockUserRepo.getUserById(MANAGER1_ID)).thenReturn(manager1);
                when(mockUserRepo.getUserById(MANAGER2_ID)).thenReturn(manager2);

                OrganizationalTreeNodeDTO result = companyService.viewOrganizationalTree(OWNER_TOKEN, COMPANY_ID);

                assertNotNull(result);
                assertEquals(OWNER_ID, result.userId());
                assertEquals(2, result.appointedByThisUser().size());

                List<Integer> childIds = result.appointedByThisUser().stream()
                                .map(OrganizationalTreeNodeDTO::userId)
                                .toList();
                assertTrue(childIds.contains(MANAGER1_ID));
                assertTrue(childIds.contains(MANAGER2_ID));
                result.appointedByThisUser().forEach(child -> {
                        assertEquals("Manager", child.role());
                        assertFalse(child.isFounder());
                        assertTrue(child.appointedByThisUser().isEmpty());
                });
        }

        @Test
        public void GivenManagerWhoAppointedSubManager_WhenViewOrganizationalTree_ThenReturnMultiLevelTree() {
                int MANAGER1_ID = TARGET_USER_ID;
                int MANAGER2_ID = 3;

                List<Integer> managersMap = new ArrayList<>();
                managersMap.add(MANAGER1_ID);
                managersMap.add(MANAGER2_ID);

                ProductionCompany company = mock(ProductionCompany.class);
                when(company.getManagers()).thenReturn(managersMap);
                when(company.getFounderId()).thenReturn(OWNER_ID);
                when(company.getOwnersIds()).thenReturn(List.of(OWNER_ID));

                User ownerUser = new User(OWNER_ID, "ownerUser", "user@test.com", "password", 30);
                ownerUser.addFounderAppointment(COMPANY_ID);

                // manager1 was appointed by the owner
                User manager1 = new User(MANAGER1_ID, "manager1", "user@test.com", "password", 101);
                manager1.receiveManagerAppointment(COMPANY_ID, OWNER_ID, defaultPermissions);
                manager1.acceptInvitation(COMPANY_ID);

                // manager2 was appointed by manager1, not by the owner
                User manager2 = new User(MANAGER2_ID, "manager2", "user@test.com", "password", 152);
                manager2.receiveManagerAppointment(COMPANY_ID, MANAGER1_ID, List.of(Permission.MANAGE_INVENTORY));
                manager2.acceptInvitation(COMPANY_ID);

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockUserRepo.getUserById(MANAGER1_ID)).thenReturn(manager1);
                when(mockUserRepo.getUserById(MANAGER2_ID)).thenReturn(manager2);

                OrganizationalTreeNodeDTO result = companyService.viewOrganizationalTree(OWNER_TOKEN, COMPANY_ID);

                assertNotNull(result);
                assertEquals(OWNER_ID, result.userId());
                assertEquals(1, result.appointedByThisUser().size());

                OrganizationalTreeNodeDTO manager1Node = result.appointedByThisUser().get(0);
                assertEquals(MANAGER1_ID, manager1Node.userId());
                assertEquals("manager1", manager1Node.username());
                assertEquals("Manager", manager1Node.role());
                assertFalse(manager1Node.isFounder());
                assertEquals(1, manager1Node.appointedByThisUser().size());

                OrganizationalTreeNodeDTO manager2Node = manager1Node.appointedByThisUser().get(0);
                assertEquals(MANAGER2_ID, manager2Node.userId());
                assertEquals("manager2", manager2Node.username());
                assertEquals("Manager", manager2Node.role());
                assertFalse(manager2Node.isFounder());
                assertTrue(manager2Node.appointedByThisUser().isEmpty());
                assertEquals(List.of(Permission.MANAGE_INVENTORY), manager2Node.grantedPermissions());
        }

        // -- #264: roster read queries ---------------------------------------

        @Test
        public void GivenActiveManager_WhenListManagers_ThenManagerAppearsInRoster() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User ownerUser = new User(OWNER_ID, "ownerUser", "owner@test.com", "password", 22);
                ownerUser.addFounderAppointment(COMPANY_ID);
                User targetUser = new User(TARGET_USER_ID, "targetUser", "target@test.com", "password", 20);

                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(targetUser);
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);

                companyService.appointManager(OWNER_TOKEN,
                                new ManagerAppointmentRequestDTO(COMPANY_ID, TARGET_USER_ID, defaultPermissions));
                when(sessionManager.validateToken(TARGET_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(TARGET_TOKEN)).thenReturn(TARGET_USER_ID);
                companyService.respondToAppointment(TARGET_TOKEN, new AppointmentResponseDTO(COMPANY_ID, true));

                List<AppointmentInfoDTO> managers = companyService.listManagers(OWNER_TOKEN, COMPANY_ID);

                assertEquals(1, managers.size());
                assertEquals(TARGET_USER_ID, managers.get(0).targetUserId());
                assertEquals("targetUser", managers.get(0).targetUsername());
                assertEquals("Manager", managers.get(0).role());
                assertEquals("ACTIVE", managers.get(0).status());
                assertEquals(COMPANY_1_NAME, managers.get(0).companyName());
        }

        @Test
        public void GivenNonOwner_WhenListManagers_ThenThrows() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User nonOwner = new User(TARGET_USER_ID, "nonOwner", "no@test.com", "password", 30);

                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(nonOwner);
                when(sessionManager.validateToken(TARGET_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(TARGET_TOKEN)).thenReturn(TARGET_USER_ID);

                assertThrows(RuntimeException.class, () -> companyService.listManagers(TARGET_TOKEN, COMPANY_ID));
        }

        @Test
        public void GivenPendingInvitation_WhenListPendingInvitations_ThenInvitationAppears() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User ownerUser = new User(OWNER_ID, "ownerUser", "owner@test.com", "password", 22);
                ownerUser.addFounderAppointment(COMPANY_ID);
                User invitee = new User(TARGET_USER_ID, "invitee", "invitee@test.com", "password", 25);
                invitee.receiveManagerAppointment(COMPANY_ID, OWNER_ID, defaultPermissions);

                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockUserRepo.findUsersWithPendingAppointmentForCompany(COMPANY_ID))
                                .thenReturn(List.of(invitee));
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);

                List<AppointmentInfoDTO> pending = companyService.listPendingInvitations(OWNER_TOKEN, COMPANY_ID);

                assertEquals(1, pending.size());
                assertEquals("invitee", pending.get(0).targetUsername());
                assertEquals("Manager", pending.get(0).role());
                assertEquals("PENDING", pending.get(0).status());
        }

        @Test
        public void GivenUserOwnsCompany_WhenFindOwnedCompanies_ThenCompanyReturned() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User ownerUser = new User(OWNER_ID, "ownerUser", "owner@test.com", "password", 22);
                ownerUser.addFounderAppointment(COMPANY_ID);

                when(mockUserRepo.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);

                List<ProductionCompanyDTO> owned = companyService.findOwnedCompanies(OWNER_TOKEN);

                assertEquals(1, owned.size());
                assertEquals(COMPANY_ID, owned.get(0).companyId());
                assertEquals(COMPANY_1_NAME, owned.get(0).name());
        }

        // -- findMyCompanies (V2-WIRE-OWNER-DASH) ---------------------------------

        @Test
        public void GivenFounderAppointment_WhenFindMyCompanies_ThenRoleIsFounder() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User ownerUser = new User(OWNER_ID, "ownerUser", "owner@test.com", "password", 22);
                ownerUser.addFounderAppointment(COMPANY_ID);

                when(mockUserRepo.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);

                List<MyCompanyDTO> mine = companyService.findMyCompanies(OWNER_TOKEN);

                assertEquals(1, mine.size());
                assertEquals(COMPANY_ID, mine.get(0).companyId());
                assertEquals(COMPANY_1_NAME, mine.get(0).name());
                assertEquals("Founder", mine.get(0).role());
        }

        @Test
        public void GivenActiveCoOwnerAppointment_WhenFindMyCompanies_ThenRoleIsCoOwner() {
                // Company founded by OWNER_ID; TARGET_USER_ID is appointed co-owner and
                // accepts.
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User coOwner = new User(TARGET_USER_ID, "coOwner", "co@test.com", "password", 30);
                coOwner.receiveOwnerAppointment(COMPANY_ID, OWNER_ID);
                coOwner.acceptInvitation(COMPANY_ID);

                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(coOwner);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(sessionManager.validateToken(TARGET_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(TARGET_TOKEN)).thenReturn(TARGET_USER_ID);

                List<MyCompanyDTO> mine = companyService.findMyCompanies(TARGET_TOKEN);

                assertEquals(1, mine.size());
                assertEquals("Co-owner", mine.get(0).role());
        }

        @Test
        public void GivenActiveManagerAppointment_WhenFindMyCompanies_ThenRoleIsManager() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User manager = new User(TARGET_USER_ID, "managerUser", "mgr@test.com", "password", 28);
                manager.receiveManagerAppointment(COMPANY_ID, OWNER_ID, defaultPermissions);
                manager.acceptInvitation(COMPANY_ID);

                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(manager);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(sessionManager.validateToken(TARGET_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(TARGET_TOKEN)).thenReturn(TARGET_USER_ID);

                List<MyCompanyDTO> mine = companyService.findMyCompanies(TARGET_TOKEN);

                assertEquals(1, mine.size());
                assertEquals("Manager", mine.get(0).role());
        }

        @Test
        public void GivenOnlyPendingAppointment_WhenFindMyCompanies_ThenExcluded() {
                User invitee = new User(TARGET_USER_ID, "pendingUser", "p@test.com", "password", 28);
                invitee.receiveManagerAppointment(COMPANY_ID, OWNER_ID, defaultPermissions); // PENDING, not accepted

                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(invitee);
                when(sessionManager.validateToken(TARGET_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(TARGET_TOKEN)).thenReturn(TARGET_USER_ID);

                List<MyCompanyDTO> mine = companyService.findMyCompanies(TARGET_TOKEN);

                assertTrue(mine.isEmpty());
        }

}
