package com.ticketing.system.organization.application.service;
import com.ticketing.system.identity.domain.Admin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

import com.ticketing.system.organization.application.dto.UserCompanyDTO;
import com.ticketing.system.shared.dto.AppointmentInfoDTO;
import com.ticketing.system.shared.dto.InvitationDTO;
import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.ticketing.system.organization.application.dto.OrganizationalTreeNodeDTO;
import com.ticketing.system.shared.dto.OwnerAppointmentRequestDTO;
import com.ticketing.system.organization.application.dto.PermissionEditDTO;
import com.ticketing.system.shared.dto.AppointmentResponseDTO;
import com.ticketing.system.shared.dto.CompanyPolicyConfigDTO;
import com.ticketing.system.shared.dto.AppointmentRevokeDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.organization.application.dtoMappers.AppointmentInfoMapper;
import com.ticketing.system.sales.application.dtoMappers.OrderReceiptMapper;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.organization.domain.CompanyStatus;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.shared.exception.CompanyNotFoundException;
import com.ticketing.system.shared.exception.DomainException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.shared.exception.UserNotFoundException;
import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.sales.domain.Ticket;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.organization.domain.AppointmentStatus;
import com.ticketing.system.organization.domain.CompanyAppointment;
import com.ticketing.system.organization.domain.CompanyRole;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.organization.application.dto.ProductionCompanyDTO;
import org.springframework.context.ApplicationEventPublisher;
import com.ticketing.system.shared.event.ManagerRevokedNotice;
import com.ticketing.system.shared.event.OwnerAppointmentPendingNotice;
import com.ticketing.system.shared.event.RoleChangedNotice;

import com.ticketing.system.shared.dto.CompanyRegistrationDTO;
import com.ticketing.system.organization.application.dto.ManagerAppointmentRequestDTO;

import com.ticketing.system.shared.dto.PurchasePolicyDTO;
import com.ticketing.system.shared.domain.policy.PurchasePolicy;
import com.ticketing.system.shared.domain.policy.NoPurchasePolicy;
import com.ticketing.system.shared.domain.policy.AgePurchasePolicy;
import com.ticketing.system.shared.domain.policy.AndPurchasePolicy;
import com.ticketing.system.shared.domain.policy.OrPurchasePolicy;
import com.ticketing.system.shared.domain.policy.MinTicketsPurchasePolicy;
import com.ticketing.system.shared.domain.policy.MaxTicketsPurchasePolicy;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class CompanyManagementService {
    private final ProductionCompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final OrderReceiptRepository orderReceiptRepository;
    private final SessionManager sessionManager;
    private final TicketRepository ticketRepository;
    private final EventRepository eventRepository;
    // Publisher for cross-context integration events (role-change / appointment notices); the
    // notifications context listens for these instead of organization calling it directly.
    private final ApplicationEventPublisher eventPublisher;

    public CompanyManagementService(ProductionCompanyRepository companyRepository, UserRepository userRepository,
            OrderReceiptRepository orderReceiptRepository, SessionManager sessionManager,
            TicketRepository ticketRepository, EventRepository eventRepository,
            ApplicationEventPublisher eventPublisher) {
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.orderReceiptRepository = orderReceiptRepository;
        this.sessionManager = sessionManager;
        this.ticketRepository = ticketRepository;
        this.eventRepository = eventRepository;
        this.eventPublisher = eventPublisher;
    }

    // UC-23 — Owner appoints another Member as co-Owner (PENDING).
    @Transactional
    public void appointOwner(String token, OwnerAppointmentRequestDTO request) {
        if (request.companyId() <= 0 || request.targetUserId() <= 0) {
            log.warn("Invalid appointment request: companyId and targetUserId must be positive integers");
            throw new IllegalArgumentException("companyId and targetUserId must be positive integers");
        }
        int appointerId = authenticate(token);

        User appointer = null;
        User targetUser = null;
        try {
            appointer = userRepository.getUserById(appointerId);
            targetUser = userRepository.getUserById(request.targetUserId());
        } catch (UserNotFoundException e) {
            log.warn("User appointer/appointee not found during appointment: {}", e.getMessage());
            throw e;
        }

        appointer.requireOwnerInCompany(request.companyId()); // check if appointer has permissions
        targetUser.receiveOwnerAppointment(request.companyId(), appointerId); // target user receives pending owner
                                                                              // appointment, here we'll do logic
                                                                              // checks.

        userRepository.updateUser(targetUser); // update target user with new appointment

        // Notify target user
        try {
            ProductionCompany company = companyRepository.getCompanyById(request.companyId());
            // Publish a cross-context integration event; the notifications listener delivers it in-line.
            eventPublisher.publishEvent(new OwnerAppointmentPendingNotice(request.targetUserId(), request.companyId(),
                    company.getName()));
        } catch (Exception e) {
            log.warn("Owner appointment created but notification failed for userId={}", request.targetUserId(), e);
        }

        log.info("Owner appointment created successfully: appointerId={}, targetUserId={}, companyId={}",
                appointerId, request.targetUserId(), request.companyId());
    }

    // UC-24 — Owner appoints a Manager with explicit granular permissions.
    @Transactional
    public void appointManager(String token, ManagerAppointmentRequestDTO request) {
        if (request.companyId() <= 0 || request.targetUserId() <= 0) {
            log.warn("Invalid manager appointment request: companyId and targetUserId must be positive integers");
            throw new IllegalArgumentException("companyId and targetUserId must be positive integers");
        }
        int ownerId = authenticate(token);
        User owner = userRepository.getUserById(ownerId);
        User targetUser = userRepository.getUserById(request.targetUserId());
        owner.requireOwnerInCompany(request.companyId());

        targetUser.receiveManagerAppointment(request.companyId(), ownerId, request.permissions());
        userRepository.updateUser(targetUser);

        // Notify target user
        try {
            ProductionCompany company = companyRepository.getCompanyById(request.companyId());
            // Publish a cross-context integration event; the notifications listener delivers it in-line.
            eventPublisher.publishEvent(new RoleChangedNotice(request.targetUserId(), request.companyId(),
                    company.getName(), "MANAGER"));
        } catch (Exception e) {
            log.warn("Manager appointment created but notification failed for userId={}", request.targetUserId(), e);
        }

        log.info("Manager appointment created successfully: ownerId={}, targetUserId={}, companyId={}, permissions={}",
                ownerId, request.targetUserId(), request.companyId(), request.permissions());
    }

    // UC-23 / UC-24 — target accepts or rejects a pending owner/manager
    // appointment.
    @Transactional
    public void respondToAppointment(String token, AppointmentResponseDTO response) {
        if (response.companyId() <= 0) {
            log.warn("Invalid appointment response: companyId must be a positive integer");
            throw new IllegalArgumentException("companyId must be a positive integer");
        }

        int userId = authenticate(token);
        User user = userRepository.getUserById(userId);
        ProductionCompany company = companyRepository.getCompanyById(response.companyId());

        CompanyAppointment appointment;

        if (response.accept()) {
            appointment = user.acceptInvitation(response.companyId()); // transitions the pending appointment to
                                                                       // accepted state
            if (appointment.getRole() == CompanyRole.Owner) {
                company.addOwner(appointment.getInviterId(), userId);
            } else if (appointment.getRole() == CompanyRole.Manager) {
                company.addManager(userId);
            }
            log.info("Appointment accepted: userId={}, companyId={}", userId, response.companyId());

            // Notify of role change to final role
            try {
                // Publish a cross-context integration event; the notifications listener delivers it in-line.
                eventPublisher.publishEvent(new RoleChangedNotice(userId, response.companyId(), company.getName(),
                        appointment.getRole().name()));
            } catch (Exception e) {
                log.warn("Appointment accepted but notification failed for userId={}", userId, e);
            }
        } else {
            user.rejectInvitation(response.companyId()); // transitions the pending appointment to rejected state,
                                                         // status-based lookups will no longer return it.
            log.info("Appointment rejected: userId={}, companyId={}", userId, response.companyId());
        }
        userRepository.updateUser(user);
        companyRepository.updateCompany(company);
    }

    // UC-24 — edit a Manager's permission set (only by the original appointer).
    @Transactional
    public void editManagerPermissions(String token, PermissionEditDTO edit) {
        int ownerId = authenticate(token);

        User manager = null;
        try {
            manager = userRepository.getUserById(edit.targetUserId());
        } catch (UserNotFoundException e) {
            log.warn("Manager not found during permission edit: {}", e.getMessage());
            throw e;
        }

        if (edit.newPermissions() == null || edit.newPermissions().isEmpty()) {
            log.warn("Invalid permission edit: newPermissions list cannot be null or empty");
            throw new IllegalArgumentException("Manager role must have at least one permission");
        }

        manager.ModifyManagerPermissions(edit.companyId(), ownerId, edit.newPermissions()); // checks done in here.

        userRepository.updateUser(manager);

        // Notify manager of permission change
        try {
            ProductionCompany company = companyRepository.getCompanyById(edit.companyId());
            // Publish a cross-context integration event; the notifications listener delivers it in-line.
            eventPublisher.publishEvent(new RoleChangedNotice(edit.targetUserId(), edit.companyId(),
                    company.getName(), "MANAGER (permissions updated)"));
        } catch (Exception e) {
            log.warn("Manager permissions updated but notification failed for userId={}", edit.targetUserId(), e);
        }

        log.info("Manager permissions updated successfully for user {} in company {}", edit.targetUserId(),
                edit.companyId());
    }

    @Transactional
    public void RevokeAppointment(String token, AppointmentRevokeDTO revokeRequest) {
        int ownerId = authenticate(token);
        ProductionCompany company = companyRepository.getCompanyById(revokeRequest.companyId());
        User targetUser = userRepository.getUserById(revokeRequest.targetUserId());

        if (company.getFounderId() == revokeRequest.targetUserId()) {
            log.warn("Cannot revoke appointment: target user {} is the founder of company {}",
                    revokeRequest.targetUserId(), company.getCompanyId());
            throw new UnauthorizedActionException("revoke the appointment of a company founder");
        }

        targetUser.revokeAppointment(revokeRequest.companyId(), ownerId); // checks done in here.
        company.RevokeAppointment(revokeRequest.targetUserId());

        userRepository.updateUser(targetUser);
        companyRepository.updateCompany(company);

        // Notify user of revocation
        try {
            // Publish a cross-context integration event; the notifications listener delivers it in-line.
            eventPublisher.publishEvent(new ManagerRevokedNotice(revokeRequest.targetUserId(),
                    revokeRequest.companyId(), company.getName()));
        } catch (Exception e) {
            log.warn("Role revoked but notification failed for userId={}", revokeRequest.targetUserId(), e);
        }

        log.info("Manager revoked successfully");
    }

    // Resolves a username-or-email string to a userId — used by the invite flow.
    @Transactional(readOnly = true)
    public int resolveUserId(String identifier) {
        if (identifier == null || identifier.isBlank())
            throw new IllegalArgumentException("Identifier must not be blank");

        Optional<User> byName = userRepository.findByUsername(identifier.trim());
        if (byName.isPresent())
            return byName.get().getUserId();

        Optional<User> byEmail = userRepository.findByEmail(identifier.trim());
        if (byEmail.isPresent())
            return byEmail.get().getUserId();

        throw new UserNotFoundException();
    }

    // ---------------------------------------------------------------------------
    // Read-side roster queries (#264 — wire ManagerListView).
    // ---------------------------------------------------------------------------

    // II.4.7.1 — active managers of a company (owner-only view).
    @Transactional(readOnly = true)
    public List<AppointmentInfoDTO> listManagers(String token, int companyId) {
        int requesterId = authenticate(token);
        ProductionCompany company = companyRepository.getCompanyById(companyId);
        if (company == null) {
            throw new CompanyNotFoundException();
        }
        User requester = userRepository.getUserById(requesterId);
        if (requester == null) {
            throw new UserNotFoundException();
        }
        requester.requireOwnerInCompany(companyId);

        AppointmentInfoMapper mapper = new AppointmentInfoMapper();
        List<AppointmentInfoDTO> managers = new ArrayList<>();
        for (Integer managerId : company.getManagers()) {
            User manager = userRepository.getUserById(managerId);
            CompanyAppointment appt = manager.getActiveCompanyAppointment(companyId);
            if (appt != null) {
                managers.add(mapper.toDTO(appt, manager.getUsername(), company.getName()));
            }
        }
        log.info("Listed {} active managers for company {}", managers.size(), companyId);
        return managers;
    }

    // II.4.7.1 — pending invitations (manager + owner offers) awaiting acceptance.
    @Transactional(readOnly = true)
    public List<AppointmentInfoDTO> listPendingInvitations(String token, int companyId) {
        int requesterId = authenticate(token);
        ProductionCompany company = companyRepository.getCompanyById(companyId);
        if (company == null) {
            throw new CompanyNotFoundException();
        }
        User requester = userRepository.getUserById(requesterId);
        if (requester == null) {
            throw new UserNotFoundException();
        }
        requester.requireOwnerInCompany(companyId);

        AppointmentInfoMapper mapper = new AppointmentInfoMapper();
        List<AppointmentInfoDTO> pending = new ArrayList<>();
        for (User invitee : userRepository.findUsersWithPendingAppointmentForCompany(companyId)) {
            CompanyAppointment appt = invitee.getPendingCompanyAppointment(companyId);
            if (appt != null) {
                pending.add(mapper.toDTO(appt, invitee.getUsername(), company.getName()));
            }
        }
        log.info("Listed {} pending invitations for company {}", pending.size(), companyId);
        return pending;
    }

    // Companies where the authenticated user holds an ACTIVE Owner appointment.
    // Bridges token -> companyId for the owner workspace until a real
    // current-company
    // selector lands (V2-CADMIN-05).
    @Transactional(readOnly = true)
    public List<ProductionCompanyDTO> findOwnedCompanies(String token) {
        int userId = authenticate(token);
        User user = userRepository.getUserById(userId);
        if (user == null) {
            throw new UserNotFoundException();
        }

        List<ProductionCompanyDTO> owned = new ArrayList<>();
        for (CompanyAppointment appt : user.getAllCompanyAppointments()) {
            if (appt.getRole() == CompanyRole.Owner && appt.getStatus() == AppointmentStatus.ACTIVE) {
                ProductionCompany company = companyRepository.getCompanyById(appt.getCompanyId());
                if (company != null) {
                    owned.add(new ProductionCompanyDTO(
                            company.getCompanyId(),
                            company.getName(),
                            company.getDescription(),
                            company.getStatus().name(),
                            company.getFounderId()));
                }
            }
        }
        log.info("User {} owns {} active company appointment(s)", userId, owned.size());
        return owned;
    }

    // Every company the authenticated member belongs to via an ACTIVE appointment
    // (Owner OR
    // Manager), with the viewer's display role resolved
    // ("Founder"/"Co-owner"/"Manager").
    // Feeds the owner-workspace company selector + name/role subtitle
    // (V2-WIRE-OWNER-DASH);
    // unlike findOwnedCompanies it keeps managers, since /owner is reachable by
    // them too.
    @Transactional(readOnly = true)
    public List<MyCompanyDTO> findMyCompanies(String token) {
        int userId = authenticate(token);
        User user = userRepository.getUserById(userId);
        if (user == null) {
            throw new UserNotFoundException();
        }

        List<MyCompanyDTO> companies = new ArrayList<>();
        for (CompanyAppointment appt : user.getAllCompanyAppointments()) {
            if (appt.getStatus() != AppointmentStatus.ACTIVE) {
                continue;
            }
            ProductionCompany company = companyRepository.getCompanyById(appt.getCompanyId());
            if (company == null) {
                continue;
            }
            String role;
            if (appt.getRole() == CompanyRole.Owner) {
                role = company.getFounderId() == userId ? "Founder" : "Co-owner";
            } else {
                role = "Manager";
            }
            companies.add(new MyCompanyDTO(company.getCompanyId(), company.getName(), role));
        }
        log.info("User {} belongs to {} active company appointment(s)", userId, companies.size());
        return companies;
    }

    // II.4.7.3 / II.4.8.2 — the signed-in member's own invitation records (every
    // status),
    // keyed on the inviter. The presenter splits PENDING (the pending list) from
    // the
    // resolved ACTIVE/REJECTED/REVOKED rows (history). Names are resolved per row,
    // mirroring
    // listPendingInvitations.
    @Transactional(readOnly = true)
    public List<InvitationDTO> listMyInvitations(String token) {
        int userId = authenticate(token);
        User user = userRepository.getUserById(userId);
        if (user == null) {
            throw new UserNotFoundException();
        }

        List<InvitationDTO> invitations = new ArrayList<>();
        for (CompanyAppointment appt : user.getAllCompanyAppointments()) {
            ProductionCompany company = companyRepository.getCompanyById(appt.getCompanyId());
            String companyName = company != null ? company.getName() : "(unknown company)";
            // getUserById throws (never returns null) when the inviter no longer
            // exists, so fall back to a placeholder rather than failing the whole list.
            String fromUsername;
            try {
                fromUsername = userRepository.getUserById(appt.getInviterId()).getUsername();
            } catch (UserNotFoundException e) {
                fromUsername = "(unknown)";
            }

            invitations.add(new InvitationDTO(
                    String.valueOf(appt.getAppointmentId()),
                    appt.getCompanyId(),
                    companyName,
                    appt.getRole().name(),
                    fromUsername,
                    appt.getPermissions().stream().map(Permission::name).toList(),
                    appt.getStatus().name(),
                    appt.getCreatedAt()));
        }
        log.info("Listed {} invitation record(s) for user {}", invitations.size(), userId);
        return invitations;
    }

    // ---------------------------------------------------------------------------
    // DTO-typed methods added in skeleton round (parallel to the existing
    // token-arg / List<Permission>-arg methods above; team to consolidate later).
    // ---------------------------------------------------------------------------

    // UC-18 — register a new Production Company; appoints Founder/Owner in same
    // transaction.
    @Transactional
    public ProductionCompanyDTO registerCompany(String token, CompanyRegistrationDTO request) {
        int userId = authenticate(token);
        User user = userRepository.getUserById(userId);

     

        // CompanyRegistrationDTO is a class with get* accessors, not a record.
        if (request.getName() == null || request.getName().trim().isEmpty() ||
                request.getDescription() == null || request.getDescription().trim().isEmpty()) {
            log.warn("Company registration failed: Missing required fields by user {}", userId);
            throw new IllegalArgumentException("All company fields (name, description) must be provided");
        }

        // Call on the injected instance, not the interface.
        if (companyRepository.existsByName(request.getName().trim())) {
            log.warn("Company registration failed: Company name '{}' already exists", request.getName());
            throw new IllegalStateException("A company with this name already exists");
        }

        try {
            int companyId = companyRepository.nextId();
            ProductionCompany newProductionCompany = new ProductionCompany(
                    companyId,
                    userId,
                    request.getName().trim(),
                    CompanyStatus.ACTIVE,
                    request.getDescription().trim(),
                    0.0);

            // ProductionCompanyRepository.save returns void; the new instance IS the saved
            // one.
            companyRepository.save(newProductionCompany);
            user.addFounderAppointment(companyId);
            userRepository.updateUser(user);
            log.info("Successfully registered new company: '{}' by userId: {}", newProductionCompany.getName(), userId);

            return new ProductionCompanyDTO(
                    newProductionCompany.getCompanyId(),
                    newProductionCompany.getName(),
                    newProductionCompany.getDescription(),
                    newProductionCompany.getStatus().name(), // DTO field is String
                    newProductionCompany.getFounderId() // DTO field is founderId
            );

        } catch (DomainException e) {
            throw e; // a known business failure (e.g. duplicate) keeps its type/message; don't mask it
        } catch (Exception e) {
            log.error("Error occurred while saving company '{}': {}", request.getName(), e.getMessage());
            throw new RuntimeException("Failed to register company due to a server error", e);
        }
    }

    @Transactional
    public void setCompanyPolicies(String token, CompanyPolicyConfigDTO config) {
        if (config == null) {
            throw new IllegalArgumentException("Company policy config cannot be null");
        }
        int userId = authenticate(token);
        ProductionCompany company = companyRepository.getCompanyById(config.companyId());
        if (company == null) {
            throw new CompanyNotFoundException();
        }
        User user = userRepository.getUserById(userId);
        if (user == null) {
            throw new UserNotFoundException();
        }
        user.requirePermissionInCompany(config.companyId(), Permission.EDIT_POLICIES);
        PurchasePolicy policy = buildPurchasePolicyFromDTO(config.defaultPurchasePolicy());
        company.setPurchasePolicy(policy);
        companyRepository.save(company);
        log.info("Purchase policy updated for company {} by user {}", config.companyId(), userId);
    }

    // UC-22 — Owner-side flat list of company sales.
    // this function returns a list of PurchaseHistoryDTO, each containing a single
    // PurchaseRecordDTO, which represents a single OrderReceipt that has
    // at least one ticket for an event of this company. The PurchaseRecordDTO
    // contains a list of TicketRecordDTOs, but only those that are
    // for events of this company (the rest are filtered out). This way we return
    // the full receipt details for each relevant purchase, but only
    // include the tickets that are relevant to this company's sales history.
    @Transactional(readOnly = true)
    public List<PurchaseHistoryDTO> viewSalesHistory(String token, int companyId) {
        log.info("Attempting to view sales history for company {}", companyId);

        int requesterId = authenticate(token);
        ProductionCompany company = companyRepository.getCompanyById(companyId);
        if (company == null) {
            log.warn("Company {} not found", companyId);
            throw new CompanyNotFoundException();
        }

        User currUser = userRepository.getUserById(requesterId);
        if (currUser == null) {
            log.warn("User {} not found", requesterId);
            throw new UserNotFoundException();
        }
        if (!currUser.hasPermissionInCompany(companyId, Permission.VIEW_SALES)) {
            log.warn("User {} does not have permission to view sales history for company {}", requesterId, companyId);
            throw new UnauthorizedActionException("view this company's data");
        }

        List<Integer> companyEventIds = eventRepository.findIdsByCompany(companyId);

        if (companyEventIds == null || companyEventIds.isEmpty()) {
            return List.of();
        }

        Set<Integer> companyEventIdSet = Set.copyOf(companyEventIds);
        OrderReceiptMapper mapper = new OrderReceiptMapper();

        // The repository method finds all receipts that have at least one ticket for
        // the company's events.
        List<PurchaseHistoryDTO> salesHistory = this.orderReceiptRepository.findByEventIds(companyEventIds).stream()
                .map(receipt -> {
                    List<Ticket> companyTickets = ticketRepository.findByOrderReceiptId(receipt.getId()).stream()
                            .filter(ticket -> companyEventIdSet.contains(ticket.getEventId()))
                            .toList();

                    return new PurchaseHistoryDTO(List.of(mapper.toPurchaseRecordDTO(
                            receipt, companyTickets, eventRepository, companyRepository, userRepository)));
                    // use overloaded mapper to pass the filtered list of tickets for richer DTO
                    // construction without bloating service logic
                }).toList();

        // This is the correct responsibility split.
        // The repository finds receipts related to company events.
        // The service filters the tickets to only this company’s events.
        // The mapper maps the receipt and the selected tickets into DTOs.

        log.info("Successfully retrieved sales history for company {}", companyId);
        return salesHistory;
    }

    // UC-25 — recursive organizational tree (Owners only per II.4.15).
    @Transactional(readOnly = true)
    public OrganizationalTreeNodeDTO viewOrganizationalTree(String token, int companyId) {
        log.info("Attempting to view organizational tree for company {}", companyId);

        int requesterId = authenticate(token);
        ProductionCompany company = companyRepository.getCompanyById(companyId);
        if (company == null) {
            log.warn("Company {} not found", companyId);
            throw new CompanyNotFoundException();
        }

        User currUser = userRepository.getUserById(requesterId);
        if (currUser == null) {
            log.warn("User {} not found", requesterId);
            throw new UserNotFoundException();
        }
        if (!currUser.isOwnerInCompany(companyId)) {
            log.warn("User {} does not have permission to view organizational tree for company {}, he's not an owner",
                    requesterId,
                    companyId);
            throw new UnauthorizedActionException("view this company's data");
        }

        log.info("Successfully retrieved organizational tree for company {}", companyId);
        // using the helper method to build the tree starting from the founder (root of
        // the tree)
        return buildOrganizationalTree(companyId, company.getFounderId());
    }

    // Admin-only: list every company in the system regardless of ownership.
    public List<ProductionCompanyDTO> adminListAllCompanies(String token) {
        authenticate(token);
        if (!sessionManager.isAdminToken(token)) {
            throw new InvalidTokenException("Admin privileges required");
        }
        // Sort by companyId so the default selection (the presenter falls back to the first
        // entry) is deterministic — repository iteration order is not guaranteed.
        return companyRepository.findAll().stream()
                .sorted(Comparator.comparingInt(ProductionCompany::getCompanyId))
                .map(c -> new ProductionCompanyDTO(
                        c.getCompanyId(), c.getName(), c.getDescription(),
                        c.getStatus().name(), c.getFounderId()))
                .toList();
    }

    // Admin-only: build org tree for any company, bypassing the ownership check.
    public OrganizationalTreeNodeDTO adminViewOrgTree(String token, int companyId) {
        authenticate(token);
        if (!sessionManager.isAdminToken(token)) {
            throw new InvalidTokenException("Admin privileges required");
        }
        ProductionCompany company = companyRepository.getCompanyById(companyId);
        if (company == null) {
            log.warn("Company {} not found", companyId);
            throw new CompanyNotFoundException();
        }
        log.info("Admin viewing organizational tree for company {}", companyId);
        return buildOrganizationalTree(companyId, company.getFounderId());
    }

    // *HELPER METHOD* — BFS build of the organizational tree for UC-25
    // (viewOrganizationalTree).
    private OrganizationalTreeNodeDTO buildOrganizationalTree(int companyId, int founderId) {
        ProductionCompany company = companyRepository.getCompanyById(companyId);

        // gather all members (owners and managers) of the company in a single list for
        // easy processing
        List<Integer> members = new ArrayList<>();
        members.addAll(company.getManagers());
        members.addAll(company.getOwnersIds());

        Map<Integer, OrganizationalTreeNodeDTO> userIdToNodeMap = new HashMap<>();

        // First pass: create a node for each member (including founder) without setting
        // children yet.
        for (Integer memberId : members) {
            User memberUser = userRepository.getUserById(memberId);
            CompanyAppointment appt = memberUser.getActiveCompanyAppointment(companyId);

            OrganizationalTreeNodeDTO node = new OrganizationalTreeNodeDTO(
                    memberId,
                    memberUser.getUsername(),
                    appt.getRole().name(),
                    memberId == founderId,
                    appt.getPermissions().stream().toList(),
                    new ArrayList<>());

            userIdToNodeMap.put(memberId, node);
        }

        // Second pass: set the appointedByThisUser list for each node based on
        // inviterId.
        for (Integer memberId : members) {
            if (memberId == founderId)
                continue; // skip founder, they have no appointer
            User memberUser = userRepository.getUserById(memberId);
            CompanyAppointment appt = memberUser.getActiveCompanyAppointment(companyId);
            OrganizationalTreeNodeDTO node = userIdToNodeMap.get(memberId);
            OrganizationalTreeNodeDTO inviterNode = userIdToNodeMap.get(appt.getInviterId());
            inviterNode.appointedByThisUser().add(node);
        }

        // return the founder's node, which is the root of the organizational tree
        return userIdToNodeMap.get(founderId);
    }

    @Transactional(readOnly = true)
    public List<UserCompanyDTO> listForUser(int userId) {
        User user = userRepository.getUserById(userId);
        if (user == null) {
            throw new UserNotFoundException();
        }
        List<UserCompanyDTO> memberships = new ArrayList<>();
        for (CompanyAppointment appointment : user.getAllCompanyAppointments()) {
            if (appointment.getStatus() != AppointmentStatus.ACTIVE) {
                continue;
            }
            ProductionCompany company;
            try {
                company = companyRepository.getCompanyById(appointment.getCompanyId());
            } catch (RuntimeException e) {
                log.warn("Skipping membership for missing companyId={}", appointment.getCompanyId());
                continue;
            }
            memberships.add(toMembershipDto(userId, appointment, company));
        }
        return memberships;
    }

    @Transactional(readOnly = true)
    public boolean isOwnerOf(int userId, int companyId) {
        User user = userRepository.getUserById(userId);
        if (user == null) {
            throw new UserNotFoundException();
        }
        CompanyAppointment appointment = user.getActiveCompanyAppointment(companyId);
        return appointment != null && appointment.getRole() == CompanyRole.Owner;
    }

    private UserCompanyDTO toMembershipDto(int userId, CompanyAppointment appointment, ProductionCompany company) {
        List<Permission> managerPermissions = appointment.getRole() == CompanyRole.Manager
                ? List.copyOf(appointment.getPermissions())
                : List.of();
        return new UserCompanyDTO(
                company.getCompanyId(),
                company.getName(),
                company.getDescription(),
                "",
                displayRole(userId, appointment, company),
                company.getStatus().name(),
                company.getOwnersIds().size() + company.getManagers().size(),
                countActiveEvents(company.getCompanyId()),
                managerPermissions);
    }

    private static String displayRole(int userId, CompanyAppointment appointment, ProductionCompany company) {
        if (company.getFounderId() == userId)
            return "Founder";
        if (appointment.getRole() == CompanyRole.Owner)
            return "Co-owner";
        if (appointment.getRole() == CompanyRole.Manager)
            return "Manager";
        return appointment.getRole().name();
    }

    private int countActiveEvents(int companyId) {
        int count = 0;
        for (Event event : eventRepository.findByCompanyId(companyId)) {
            EventStatus status = event.getStatus();
            if (status == EventStatus.ON_SALE || status == EventStatus.SCHEDULED || status == EventStatus.SOLD_OUT) {
                count++;
            }
        }
        return count;
    }

    private int authenticate(String token) {
        if (!sessionManager.validateToken(token)) {
            throw new InvalidTokenException("Invalid token");
        }
        return sessionManager.extractUserId(token);
    }

    private PurchasePolicy buildPurchasePolicyFromDTO(PurchasePolicyDTO dto) {
        if (dto == null)
            return new NoPurchasePolicy();
        if (dto.type() == null || dto.type().isBlank())
            throw new IllegalArgumentException("Purchase policy type is required");
        switch (dto.type().trim().toUpperCase()) {
            case "AGE":
                if (dto.minimumAge() == null)
                    throw new IllegalArgumentException("minimumAge is required");
                return new AgePurchasePolicy(dto.minimumAge());
            case "MIN_TICKETS":
                if (dto.minimumTickets() == null)
                    throw new IllegalArgumentException("minimumTickets is required");
                return new MinTicketsPurchasePolicy(dto.minimumTickets());
            case "MAX_TICKETS":
                if (dto.maximumTickets() == null)
                    throw new IllegalArgumentException("maximumTickets is required");
                return new MaxTicketsPurchasePolicy(dto.maximumTickets());
            case "AND":
                if (dto.children() == null || dto.children().size() < 2)
                    throw new IllegalArgumentException("AND policy must have at least two children");
                PurchasePolicy andResult = buildPurchasePolicyFromDTO(dto.children().get(0));
                for (int i = 1; i < dto.children().size(); i++)
                    andResult = new AndPurchasePolicy(andResult, buildPurchasePolicyFromDTO(dto.children().get(i)));
                return andResult;
            case "OR":
                if (dto.children() == null || dto.children().size() < 2)
                    throw new IllegalArgumentException("OR policy must have at least two children");
                PurchasePolicy orResult = buildPurchasePolicyFromDTO(dto.children().get(0));
                for (int i = 1; i < dto.children().size(); i++)
                    orResult = new OrPurchasePolicy(orResult, buildPurchasePolicyFromDTO(dto.children().get(i)));
                return orResult;
            case "NONE":
                return new NoPurchasePolicy();
            default:
                throw new IllegalArgumentException("Unknown purchase policy type: " + dto.type());
        }
    }

    @Transactional(readOnly = true)
    public PurchasePolicyDTO getCompanyPurchasePolicy(String token, int companyId) {
        int userId = authenticate(token);
        ProductionCompany company = companyRepository.getCompanyById(companyId);
        if (company == null)
            throw new CompanyNotFoundException();
        User user = userRepository.getUserById(userId);
        if (user == null)
            throw new UserNotFoundException();
        user.requirePermissionInCompany(companyId, Permission.EDIT_POLICIES);
        return policyToDTO(company.getPurchasePolicy());
    }

    private PurchasePolicyDTO policyToDTO(PurchasePolicy policy) {
        if (policy == null || policy instanceof NoPurchasePolicy)
            return new PurchasePolicyDTO("NONE", null, null, null, null);
        if (policy instanceof AgePurchasePolicy a)
            return new PurchasePolicyDTO("AGE", a.getMinimumAge(), null, null, null);
        if (policy instanceof MinTicketsPurchasePolicy m)
            return new PurchasePolicyDTO("MIN_TICKETS", null, m.getMinimumTickets(), null, null);
        if (policy instanceof MaxTicketsPurchasePolicy m)
            return new PurchasePolicyDTO("MAX_TICKETS", null, null, m.getMaximumTickets(), null);
        if (policy instanceof AndPurchasePolicy a)
            return new PurchasePolicyDTO("AND", null, null, null,
                    List.of(policyToDTO(a.getLeftPolicy()), policyToDTO(a.getRightPolicy())));
        if (policy instanceof OrPurchasePolicy o)
            return new PurchasePolicyDTO("OR", null, null, null,
                    List.of(policyToDTO(o.getLeftPolicy()), policyToDTO(o.getRightPolicy())));
        return new PurchasePolicyDTO("NONE", null, null, null, null);
    }
}
