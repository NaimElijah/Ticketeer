package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import com.ticketing.system.shared.dto.AppointmentInfoDTO;
import com.ticketing.system.shared.dto.InvitationDTO;
import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.ticketing.system.organization.application.dto.OrganizationalTreeNodeDTO;
import com.ticketing.system.organization.application.dto.PermissionEditDTO;
import com.ticketing.system.organization.application.dto.ProductionCompanyDTO;
import org.springframework.context.ApplicationEventPublisher;
import com.ticketing.system.identity.application.port.out.SessionManager;

import com.ticketing.system.organization.adapter.out.persistence.MemoryCompanyAppointmentRepository;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.organization.application.service.CompanyMembershipService;
import com.ticketing.system.shared.dto.AppointmentResponseDTO;
import com.ticketing.system.shared.dto.AppointmentRevokeDTO;
import com.ticketing.system.shared.dto.CompanyRegistrationDTO;
import com.ticketing.system.shared.dto.OwnerAppointmentRequestDTO;
import com.ticketing.system.organization.application.dto.ManagerAppointmentRequestDTO;
import com.ticketing.system.organization.domain.CompanyStatus;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.application.port.out.CompanyEventStatsPort;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.organization.domain.CompanyRole;
import com.ticketing.system.shared.domain.policy.AgePurchasePolicy;
import com.ticketing.system.shared.domain.policy.AndPurchasePolicy;
import com.ticketing.system.shared.domain.policy.MaxTicketsPurchasePolicy;
import com.ticketing.system.shared.domain.policy.MinTicketsPurchasePolicy;
import com.ticketing.system.shared.domain.policy.NoPurchasePolicy;
import com.ticketing.system.shared.domain.policy.OrPurchasePolicy;
import com.ticketing.system.shared.domain.policy.PurchaseContext;
import com.ticketing.system.shared.domain.policy.PurchasePolicy;
import com.ticketing.system.shared.dto.CompanyPolicyConfigDTO;
import com.ticketing.system.shared.dto.PurchasePolicyDTO;
import com.ticketing.system.shared.event.RoleChangedNotice;
import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;

public class CompanyManagementServiceTest {

        private ProductionCompanyRepository mockCompanyRepo;
        private UserRepository mockUserRepo;
        private SessionManager sessionManager;
        private CompanyManagementService companyService;
        // Real membership service over an in-memory appointment repo — appointments were promoted off the
        // User aggregate (task #20), so seeding and appointment queries go through this instead of User.
        private CompanyMembershipService membershipService;
        // Outbound port to catalog's active-event count (replaces the former direct EventRepository read).
        private CompanyEventStatsPort companyEventStatsPort;
        private ApplicationEventPublisher eventPublisher;

        private final String OWNER_TOKEN = "owner-token";
        private final String TARGET_TOKEN = "target-token";
        private final String INVALID_TOKEN = "invalid-token";

        private final int COMPANY_ID = 100;
        private final int OWNER_ID = 1;
        private final String COMPANY_1_NAME = "Company1";
        private final String COMPANY_1_DESCRIPTION = "A test production company1";

        private final int TARGET_USER_ID = 2;
        private List<Permission> defaultPermissions;

        @BeforeEach
        public void setUp() {
                mockCompanyRepo = mock(ProductionCompanyRepository.class);
                mockUserRepo = mock(UserRepository.class);
                sessionManager = mock(SessionManager.class);
                companyEventStatsPort = mock(CompanyEventStatsPort.class);
                eventPublisher = mock(ApplicationEventPublisher.class);
                membershipService = new CompanyMembershipService(new MemoryCompanyAppointmentRepository(), Clock.systemUTC());

                companyService = new CompanyManagementService(
                                mockCompanyRepo,
                                mockUserRepo,
                                sessionManager,
                                companyEventStatsPort,
                                membershipService,
                                eventPublisher);

                defaultPermissions = new ArrayList<>();
                defaultPermissions.add(Permission.CONFIGURE_VENUE);
                defaultPermissions.add(Permission.MANAGE_INVENTORY);

        }

        @Test
        public void GivenOwnerAndTargetUser_WhenInviteManager_ThenTargetHasOneInvitation() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User ownerUser = new User(OWNER_ID, "ownerUser", "user@test.com", "password", 22);
                membershipService.addFounderAppointment(OWNER_ID, COMPANY_ID);
                User targetUser = new User(TARGET_USER_ID, "targetUser", "user@test.com", "password", 19);

                when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);

                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(OWNER_ID)).thenReturn(ownerUser);
                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(targetUser);

                companyService.appointManager(OWNER_TOKEN, new ManagerAppointmentRequestDTO(
                                COMPANY_ID, TARGET_USER_ID, defaultPermissions));

                assertNotEquals(null, membershipService.getPendingCompanyAppointment(TARGET_USER_ID, COMPANY_ID));
        }

        @Test
        public void GivenPendingManagerInvitation_WhenTargetListsMyInvitations_ThenInvitationIsReturned() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User ownerUser = new User(OWNER_ID, "ownerUser", "user@test.com", "password", 22);
                membershipService.addFounderAppointment(OWNER_ID, COMPANY_ID);
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
                membershipService.addFounderAppointment(OWNER_ID, COMPANY_ID);
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
                membershipService.addFounderAppointment(OWNER_ID, COMPANY_ID);
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

                assertTrue(membershipService.getPendingCompanyAppointment(TARGET_USER_ID, COMPANY_ID) == null);
        }

        @Test
        public void GivenAcceptedManager_WhenOwnerModifiesPermissions_ThenCompanyPermissionsAreUpdated() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User ownerUser = new User(OWNER_ID, "ownerUser", "user@test.com", "password", 22);
                membershipService.addFounderAppointment(OWNER_ID, COMPANY_ID);
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
                        assertTrue(membershipService.hasPermissionInCompany(TARGET_USER_ID, COMPANY_ID, perm));
                }
        }

        @Test
        public void GivenAcceptedManager_WhenOwnerRevokesManager_ThenCompanyDoesNotContainManager() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                User ownerUser = new User(OWNER_ID, "ownerUser", "user@test.com", "password", 22);
                membershipService.addFounderAppointment(OWNER_ID, COMPANY_ID);
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
                membershipService.addFounderAppointment(OWNER_ID, COMPANY_ID);

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
                membershipService.addFounderAppointment(OWNER_ID, COMPANY_ID);
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
                membershipService.addFounderAppointment(OWNER_ID, COMPANY_ID);
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
                membershipService.addFounderAppointment(OWNER_ID, COMPANY_ID);
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
                membershipService.addFounderAppointment(OWNER_ID, COMPANY_ID);
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

                // save() is void per ProductionCompanyRepository — no return-value mock
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
                // No appointment is seeded for OWNER_ID, so the membership service reports "not an owner".

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
                membershipService.addFounderAppointment(OWNER_ID, COMPANY_ID);

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
                membershipService.addFounderAppointment(OWNER_ID, COMPANY_ID);

                User managerUser = new User(TARGET_USER_ID, "managerUser", "user@test.com", "password", 85);
                membershipService.receiveManagerAppointment(TARGET_USER_ID, COMPANY_ID, OWNER_ID, defaultPermissions);
                membershipService.acceptInvitation(TARGET_USER_ID, COMPANY_ID);

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
                membershipService.addFounderAppointment(OWNER_ID, COMPANY_ID);

                User manager1 = new User(MANAGER1_ID, "manager1", "user@test.com", "password", 101);
                membershipService.receiveManagerAppointment(MANAGER1_ID, COMPANY_ID, OWNER_ID, defaultPermissions);
                membershipService.acceptInvitation(MANAGER1_ID, COMPANY_ID);

                User manager2 = new User(MANAGER2_ID, "manager2", "user@test.com", "password", 102);
                membershipService.receiveManagerAppointment(MANAGER2_ID, COMPANY_ID, OWNER_ID, List.of(Permission.VIEW_SALES));
                membershipService.acceptInvitation(MANAGER2_ID, COMPANY_ID);

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
                membershipService.addFounderAppointment(OWNER_ID, COMPANY_ID);

                // manager1 was appointed by the owner
                User manager1 = new User(MANAGER1_ID, "manager1", "user@test.com", "password", 101);
                membershipService.receiveManagerAppointment(MANAGER1_ID, COMPANY_ID, OWNER_ID, defaultPermissions);
                membershipService.acceptInvitation(MANAGER1_ID, COMPANY_ID);

                // manager2 was appointed by manager1, not by the owner
                User manager2 = new User(MANAGER2_ID, "manager2", "user@test.com", "password", 152);
                membershipService.receiveManagerAppointment(MANAGER2_ID, COMPANY_ID, MANAGER1_ID, List.of(Permission.MANAGE_INVENTORY));
                membershipService.acceptInvitation(MANAGER2_ID, COMPANY_ID);

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
                membershipService.addFounderAppointment(OWNER_ID, COMPANY_ID);
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
                membershipService.addFounderAppointment(OWNER_ID, COMPANY_ID);
                User invitee = new User(TARGET_USER_ID, "invitee", "invitee@test.com", "password", 25);
                membershipService.receiveManagerAppointment(TARGET_USER_ID, COMPANY_ID, OWNER_ID, defaultPermissions);

                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                when(mockUserRepo.getUserById(OWNER_ID)).thenReturn(ownerUser);
                // listPendingInvitations now resolves the invitee's username by targetId via getUserById.
                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(invitee);
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
                membershipService.addFounderAppointment(OWNER_ID, COMPANY_ID);

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
                membershipService.addFounderAppointment(OWNER_ID, COMPANY_ID);

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
                membershipService.receiveOwnerAppointment(TARGET_USER_ID, COMPANY_ID, OWNER_ID);
                membershipService.acceptInvitation(TARGET_USER_ID, COMPANY_ID);

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
                membershipService.receiveManagerAppointment(TARGET_USER_ID, COMPANY_ID, OWNER_ID, defaultPermissions);
                membershipService.acceptInvitation(TARGET_USER_ID, COMPANY_ID);

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
                membershipService.receiveManagerAppointment(TARGET_USER_ID, COMPANY_ID, OWNER_ID, defaultPermissions); // PENDING, not accepted

                when(mockUserRepo.getUserById(TARGET_USER_ID)).thenReturn(invitee);
                when(sessionManager.validateToken(TARGET_TOKEN)).thenReturn(true);
                when(sessionManager.extractUserId(TARGET_TOKEN)).thenReturn(TARGET_USER_ID);

                List<MyCompanyDTO> mine = companyService.findMyCompanies(TARGET_TOKEN);

                assertTrue(mine.isEmpty());
        }

        // ---- a removed owner keeps no control over the managers they appointed -----------------
        // The founder appoints a co-owner, who appoints a manager. Once the founder revokes the
        // co-owner, the co-owner is no longer an owner, so they can neither edit nor revoke "their"
        // manager, and a manager invite they sent before the revocation can no longer be accepted.
        // A current co-owner keeps all three abilities (the control cases).

        private static final int CO_OWNER_ID = 3;
        private static final String CO_OWNER_TOKEN = "co-owner-token";

        private ProductionCompany companyWithCoOwnerWhoInvitedAManager(boolean managerAccepted) {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                membershipService.addFounderAppointment(OWNER_ID, COMPANY_ID);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                givenUserWithToken(OWNER_ID, "founder", OWNER_TOKEN);
                givenUserWithToken(CO_OWNER_ID, "coOwner", CO_OWNER_TOKEN);
                givenUserWithToken(TARGET_USER_ID, "manager", TARGET_TOKEN);

                companyService.appointOwner(OWNER_TOKEN, new OwnerAppointmentRequestDTO(COMPANY_ID, CO_OWNER_ID));
                companyService.respondToAppointment(CO_OWNER_TOKEN, new AppointmentResponseDTO(COMPANY_ID, true));
                companyService.appointManager(CO_OWNER_TOKEN,
                                new ManagerAppointmentRequestDTO(COMPANY_ID, TARGET_USER_ID, defaultPermissions));
                if (managerAccepted) {
                        companyService.respondToAppointment(TARGET_TOKEN, new AppointmentResponseDTO(COMPANY_ID, true));
                }
                return company;
        }

        private void givenUserWithToken(int userId, String username, String token) {
                when(mockUserRepo.getUserById(userId))
                                .thenReturn(new User(userId, username, username + "@test.com", "password", 30));
                when(sessionManager.validateToken(token)).thenReturn(true);
                when(sessionManager.extractUserId(token)).thenReturn(userId);
        }

        private void founderRevokesTheCoOwner() {
                companyService.RevokeAppointment(OWNER_TOKEN, new AppointmentRevokeDTO(COMPANY_ID, CO_OWNER_ID));
        }

        private static List<Permission> editedPermissions() {
                return List.of(Permission.EDIT_POLICIES);
        }

        @Test
        public void GivenRevokedOwner_WhenEditManagerPermissions_ThenRejected() {
                companyWithCoOwnerWhoInvitedAManager(true);
                founderRevokesTheCoOwner();
                Set<Permission> permissionsBefore = Set.copyOf(
                                membershipService.getActiveCompanyAppointment(TARGET_USER_ID, COMPANY_ID).getPermissions());

                assertThrows(UnauthorizedActionException.class, () -> companyService.editManagerPermissions(CO_OWNER_TOKEN,
                                new PermissionEditDTO(COMPANY_ID, TARGET_USER_ID, editedPermissions())),
                                "a revoked owner must not edit the permissions of a manager they appointed");
                assertEquals(permissionsBefore,
                                membershipService.getActiveCompanyAppointment(TARGET_USER_ID, COMPANY_ID).getPermissions(),
                                "the manager's permissions are unchanged");
        }

        @Test
        public void GivenRevokedOwner_WhenRevokeTheirManager_ThenRejected() {
                companyWithCoOwnerWhoInvitedAManager(true);
                founderRevokesTheCoOwner();

                assertThrows(UnauthorizedActionException.class, () -> companyService.RevokeAppointment(CO_OWNER_TOKEN,
                                new AppointmentRevokeDTO(COMPANY_ID, TARGET_USER_ID)),
                                "a revoked owner must not revoke a manager they appointed");
                assertTrue(membershipService.isMemberInCompany(TARGET_USER_ID, COMPANY_ID),
                                "the manager is still appointed");
        }

        @Test
        public void GivenPendingManagerInviteFromAnOwnerSinceRevoked_WhenAccepted_ThenRejected() {
                companyWithCoOwnerWhoInvitedAManager(false);
                founderRevokesTheCoOwner();

                assertThrows(BusinessRuleViolationException.class, () -> companyService.respondToAppointment(TARGET_TOKEN,
                                new AppointmentResponseDTO(COMPANY_ID, true)),
                                "a manager invite from an owner who has since been revoked must not be accepted");
                assertFalse(membershipService.isMemberInCompany(TARGET_USER_ID, COMPANY_ID),
                                "the invitee did not become a manager");
                assertNotNull(membershipService.getPendingCompanyAppointment(TARGET_USER_ID, COMPANY_ID),
                                "the invitation is still pending, unaccepted");
        }

        @Test
        public void GivenCurrentCoOwner_WhenEditTheirManagersPermissions_ThenTheManagerHoldsTheNewPermissions() {
                companyWithCoOwnerWhoInvitedAManager(true);

                companyService.editManagerPermissions(CO_OWNER_TOKEN,
                                new PermissionEditDTO(COMPANY_ID, TARGET_USER_ID, editedPermissions()));

                assertTrue(membershipService.hasPermissionInCompany(TARGET_USER_ID, COMPANY_ID, Permission.EDIT_POLICIES));
                assertFalse(membershipService.hasPermissionInCompany(TARGET_USER_ID, COMPANY_ID, Permission.CONFIGURE_VENUE));
        }

        @Test
        public void GivenCurrentCoOwner_WhenRevokeTheirManager_ThenTheManagerIsGone() {
                ProductionCompany company = companyWithCoOwnerWhoInvitedAManager(true);

                companyService.RevokeAppointment(CO_OWNER_TOKEN, new AppointmentRevokeDTO(COMPANY_ID, TARGET_USER_ID));

                assertFalse(company.getManagers().contains(TARGET_USER_ID));
                assertNull(membershipService.getActiveCompanyAppointment(TARGET_USER_ID, COMPANY_ID));
        }

        @Test
        public void GivenPendingManagerInviteFromACurrentCoOwner_WhenAccepted_ThenTheTargetIsAManager() {
                ProductionCompany company = companyWithCoOwnerWhoInvitedAManager(false);

                companyService.respondToAppointment(TARGET_TOKEN, new AppointmentResponseDTO(COMPANY_ID, true));

                assertTrue(company.getManagers().contains(TARGET_USER_ID));
        }

        @Test
        public void GivenCoOwner_WhenRevokingTheirOwnAppointment_ThenTheyAreNoLongerAnOwner() {
                ProductionCompany company = companyWithCoOwnerWhoInvitedAManager(true);

                companyService.RevokeAppointment(CO_OWNER_TOKEN, new AppointmentRevokeDTO(COMPANY_ID, CO_OWNER_ID));

                assertFalse(company.isOwner(CO_OWNER_ID));
                assertFalse(membershipService.isOwnerInCompany(CO_OWNER_ID, COMPANY_ID));
        }

        // ---- a manager invitation is a pending offer, not a role change (#187) ----------------
        // The invitee's role changes only when they accept, so the invitation must not tell them it
        // already has; the acceptance tells them once.

        private ProductionCompany founderInvitedTheTargetAsManager() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                membershipService.addFounderAppointment(OWNER_ID, COMPANY_ID);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                givenUserWithToken(OWNER_ID, "founder", OWNER_TOKEN);
                givenUserWithToken(TARGET_USER_ID, "invitee", TARGET_TOKEN);
                companyService.appointManager(OWNER_TOKEN,
                                new ManagerAppointmentRequestDTO(COMPANY_ID, TARGET_USER_ID, defaultPermissions));
                return company;
        }

        private List<Object> publishedEvents() {
                return mockingDetails(eventPublisher).getInvocations().stream()
                                .filter(invocation -> invocation.getMethod().getName().equals("publishEvent"))
                                .map(invocation -> invocation.getArguments()[0])
                                .toList();
        }

        private List<RoleChangedNotice> publishedRoleChangedNotices() {
                return publishedEvents().stream()
                                .filter(RoleChangedNotice.class::isInstance)
                                .map(RoleChangedNotice.class::cast)
                                .toList();
        }

        // True when the event is a notice whose userId and companyId components name the given pair:
        // the shape every shared/event notice addressed to a member has. Kept type-agnostic so it holds
        // for the pending-manager notice the fix introduces, which does not exist yet.
        private static boolean isNoticeFor(Object event, int userId, int companyId) {
                Integer noticeUserId = intRecordComponent(event, "userId");
                Integer noticeCompanyId = intRecordComponent(event, "companyId");
                return noticeUserId != null && noticeUserId == userId
                                && noticeCompanyId != null && noticeCompanyId == companyId;
        }

        private static Integer intRecordComponent(Object event, String name) {
                if (!event.getClass().isRecord()) {
                        return null;
                }
                for (RecordComponent component : event.getClass().getRecordComponents()) {
                        if (component.getName().equals(name) && component.getType() == int.class) {
                                try {
                                        return (Integer) component.getAccessor().invoke(event);
                                } catch (ReflectiveOperationException e) {
                                        throw new IllegalStateException("Cannot read " + name + " of " + event, e);
                                }
                        }
                }
                return null;
        }

        @Test
        public void GivenOwnerAndTargetUser_WhenInviteManager_ThenNoRoleChangedNoticeIsPublished() {
                founderInvitedTheTargetAsManager();

                List<RoleChangedNotice> roleChanges = publishedRoleChangedNotices();
                assertTrue(roleChanges.isEmpty(),
                                "an invitation is still pending, so the invitee's role has not changed; published: "
                                                + roleChanges);
        }

        @Test
        public void GivenOwnerAndTargetUser_WhenInviteManager_ThenANoticeAddressedToTheInviteeIsPublished() {
                founderInvitedTheTargetAsManager();

                List<Object> published = publishedEvents();
                assertTrue(published.stream().anyMatch(event -> isNoticeFor(event, TARGET_USER_ID, COMPANY_ID)),
                                "the invitee must be told about the invitation in company " + COMPANY_ID + "; published: "
                                                + published);
        }

        @Test
        public void GivenPendingManagerInvitation_WhenTargetAccepts_ThenExactlyOneRoleChangedNoticeNamesTheManagerRole() {
                founderInvitedTheTargetAsManager();
                clearInvocations(eventPublisher);

                companyService.respondToAppointment(TARGET_TOKEN, new AppointmentResponseDTO(COMPANY_ID, true));

                assertEquals(List.of(new RoleChangedNotice(TARGET_USER_ID, COMPANY_ID, COMPANY_1_NAME,
                                CompanyRole.Manager.name())), publishedRoleChangedNotices(),
                                "accepting the invitation is the one role change the invitee is told about");
        }

        // ---- an owner offer dies with its inviter's ownership, as a manager invitation does (#187) ----

        private ProductionCompany companyWithCoOwnerWhoOfferedOwnership() {
                ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
                membershipService.addFounderAppointment(OWNER_ID, COMPANY_ID);
                when(mockCompanyRepo.getCompanyById(COMPANY_ID)).thenReturn(company);
                givenUserWithToken(OWNER_ID, "founder", OWNER_TOKEN);
                givenUserWithToken(CO_OWNER_ID, "coOwner", CO_OWNER_TOKEN);
                givenUserWithToken(TARGET_USER_ID, "offeree", TARGET_TOKEN);

                companyService.appointOwner(OWNER_TOKEN, new OwnerAppointmentRequestDTO(COMPANY_ID, CO_OWNER_ID));
                companyService.respondToAppointment(CO_OWNER_TOKEN, new AppointmentResponseDTO(COMPANY_ID, true));
                companyService.appointOwner(CO_OWNER_TOKEN, new OwnerAppointmentRequestDTO(COMPANY_ID, TARGET_USER_ID));
                return company;
        }

        @Test
        public void GivenPendingOwnerOfferFromAnOwnerSinceRevoked_WhenAccepted_ThenRefusedAsNoLongerValid() {
                ProductionCompany company = companyWithCoOwnerWhoOfferedOwnership();
                founderRevokesTheCoOwner();

                BusinessRuleViolationException refused = assertThrows(BusinessRuleViolationException.class,
                                () -> companyService.respondToAppointment(TARGET_TOKEN,
                                                new AppointmentResponseDTO(COMPANY_ID, true)),
                                "an owner offer from an owner who has since been revoked must be refused as no longer valid");
                assertEquals("This invitation is no longer valid: the owner who sent it is no longer an owner of this company",
                                refused.getMessage());
                assertFalse(company.isOwner(TARGET_USER_ID), "the offeree did not become an owner of the company");
                assertFalse(membershipService.isOwnerInCompany(TARGET_USER_ID, COMPANY_ID),
                                "the offeree holds no active owner appointment");
        }

        @Test
        public void GivenPendingOwnerOfferFromACurrentCoOwner_WhenAccepted_ThenTheTargetIsAnOwner() {
                ProductionCompany company = companyWithCoOwnerWhoOfferedOwnership();

                companyService.respondToAppointment(TARGET_TOKEN, new AppointmentResponseDTO(COMPANY_ID, true));

                assertTrue(company.isOwner(TARGET_USER_ID));
                assertTrue(membershipService.isOwnerInCompany(TARGET_USER_ID, COMPANY_ID));
        }

}
