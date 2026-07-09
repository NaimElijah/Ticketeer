package com.ticketing.system.reporting.application.service;
// CompanyRatings lives in catalog (rating is derived from catalog Events); CompanyManagementService in
// organization; reporting reads both downward, which is allowed.
import com.ticketing.system.catalog.application.service.CompanyRatings;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.organization.application.service.CompanyMembershipService;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import com.ticketing.system.shared.dto.CompanyDashboardDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.sales.application.dtoMappers.OrderReceiptMapper;
import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.messaging.domain.ConversationType;
import com.ticketing.system.messaging.application.port.out.ConversationRepository;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.ReceiptLine;
import com.ticketing.system.sales.domain.Ticket;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.shared.exception.CompanyNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.shared.exception.UserNotFoundException;

/**
 * Read-side aggregator for the owner-workspace dashboard counters (V2-WIRE-OWNER-DASH).
 *
 * <p>Pure query service: it takes an already-authorized {@code companyId} (the caller — the
 * presenter — resolves the signed-in member's companies via
 * {@code CompanyManagementService.findMyCompanies} first) and assembles the four dashboard
 * figures from the event, order-receipt, and conversation repositories. Sales figures are the
 * company's own share of each receipt (a receipt can mix events from several companies), over
 * the trailing 30 days, excluding refunded receipts.
 */
@Service
@Slf4j
@Transactional(readOnly = true)
public class CompanyAnalyticsService {

    private static final int WINDOW_DAYS = 30;

    private final EventRepository eventRepository;
    private final OrderReceiptRepository orderReceiptRepository;
    private final ConversationRepository conversationRepository;
    private final TicketRepository ticketRepository;
    private final ProductionCompanyRepository companyRepository;
    private final UserRepository userRepository;
    // Company-membership/authorization checks moved off the User aggregate (task #20); reporting
    // already depends on organization.
    private final CompanyMembershipService companyMembershipService;
    // Session/token port — needed by the token-authenticated viewSalesHistory read (UC-22).
    private final SessionManager sessionManager;

    public CompanyAnalyticsService(
            EventRepository eventRepository,
            OrderReceiptRepository orderReceiptRepository,
            ConversationRepository conversationRepository,
            TicketRepository ticketRepository,
            ProductionCompanyRepository companyRepository,
            UserRepository userRepository,
            CompanyMembershipService companyMembershipService,
            SessionManager sessionManager) {
        this.eventRepository = eventRepository;
        this.orderReceiptRepository = orderReceiptRepository;
        this.conversationRepository = conversationRepository;
        this.ticketRepository = ticketRepository;
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.companyMembershipService = companyMembershipService;
        this.sessionManager = sessionManager;
    }

    /** Live dashboard counters for one company. Returns zeros for a fresh company with no events. */
    public CompanyDashboardDTO dashboard(int companyId) {
        int activeEvents = eventRepository.findActiveByCompany(companyId).size();

        int ticketsSold = 0;
        double revenue = 0;
        List<Integer> eventIds = eventRepository.findIdsByCompany(companyId);
        if (!eventIds.isEmpty()) {
            Set<Integer> companyEventIds = new HashSet<>(eventIds);
            LocalDateTime cutoff = LocalDateTime.now().minusDays(WINDOW_DAYS);
            for (OrderReceipt receipt : orderReceiptRepository.findByEventIds(eventIds)) {
                if (receipt.wasRefunded()
                        || receipt.getPurchaseTime() == null
                        || receipt.getPurchaseTime().isBefore(cutoff)) {
                    continue;
                }
                // Count only the lines that belong to this company's events — a receipt may
                // span multiple companies' events.
                for (ReceiptLine line : receipt.getReceiptLines()) {
                    if (companyEventIds.contains(line.getEventId())) {
                        ticketsSold++;
                        revenue += line.getPriceAtReservation();
                    }
                }
            }
        }

        int openInquiries = (int) conversationRepository.findByCompanyAsCounterparty(companyId).stream()
                .filter(c -> c.getType() == ConversationType.INQUIRY && !c.isClosed())
                .count();

        // Company rating is derived: the mean of this company's events' ratings (null if none rated).
        Double rating = CompanyRatings.fromEvents(eventRepository.findByCompanyId(companyId));

        log.debug("Dashboard for company {}: {} active events, {} tickets/30d, {} revenue/30d, {} open inquiries, rating {}",
                companyId, activeEvents, ticketsSold, revenue, openInquiries, rating);
        return new CompanyDashboardDTO(activeEvents, ticketsSold, revenue, openInquiries, rating);
    }

    public PurchaseHistoryDTO salesHistory(int companyId) {
        List<Integer> eventIds = eventRepository.findIdsByCompany(companyId);
        if (eventIds.isEmpty()) return new PurchaseHistoryDTO(List.of());
        Set<Integer> companyEventIds = new HashSet<>(eventIds);
        OrderReceiptMapper mapper = new OrderReceiptMapper();
        List<PurchaseHistoryDTO.PurchaseRecordDTO> records = orderReceiptRepository
                .findByEventIds(eventIds)
                .stream()
                .map(r -> mapper.toFilteredPurchaseRecordDTO(
                        r, companyEventIds,
                        ticketRepository, eventRepository, companyRepository, userRepository))
                .toList();
        return new PurchaseHistoryDTO(records);
    }

    /**
     * UC-22 — token-authenticated, owner/manager-side flat list of a company's sales history
     * (relocated here from {@code organization.CompanyManagementService.viewSalesHistory}: it is a
     * cross-context reporting read, so it belongs in reporting, not organization). Each returned
     * {@link PurchaseHistoryDTO} wraps a single receipt that has at least one ticket for one of the
     * company's events, carrying only that company's tickets (other companies' tickets on a shared
     * receipt are filtered out). Validates the session token and requires the
     * {@link Permission#VIEW_SALES} permission for the company. Behaviour preserved verbatim.
     *
     * @param token     the caller's session token
     * @param companyId the company whose sales history is requested
     * @return one purchase-history entry per relevant receipt (empty list when the company has no events)
     */
    public List<PurchaseHistoryDTO> viewSalesHistory(String token, int companyId) {
        log.info("Attempting to view sales history for company {}", companyId);

        int requesterId = authenticate(token);                        // validate token → caller's userId
        ProductionCompany company = companyRepository.getCompanyById(companyId); // resolve the company
        if (company == null) {                                        // unknown company → not-found failure
            log.warn("Company {} not found", companyId);
            throw new CompanyNotFoundException();
        }

        User currUser = userRepository.getUserById(requesterId);      // resolve the calling user
        if (currUser == null) {                                       // caller no longer exists → not-found failure
            log.warn("User {} not found", requesterId);
            throw new UserNotFoundException();
        }
        if (!companyMembershipService.hasPermissionInCompany(requesterId, companyId, Permission.VIEW_SALES)) { // authorization gate
            log.warn("User {} does not have permission to view sales history for company {}", requesterId, companyId);
            throw new UnauthorizedActionException("view this company's data");
        }

        List<Integer> companyEventIds = eventRepository.findIdsByCompany(companyId); // this company's event ids
        if (companyEventIds == null || companyEventIds.isEmpty()) {   // no events → nothing to report
            return List.of();
        }

        Set<Integer> companyEventIdSet = Set.copyOf(companyEventIds); // fast membership test for ticket filtering
        OrderReceiptMapper mapper = new OrderReceiptMapper();         // stateless mapper for receipt → DTO

        // Each receipt that touches a company event becomes one PurchaseHistoryDTO carrying only the
        // tickets for this company's events (the rest are filtered out).
        List<PurchaseHistoryDTO> salesHistory = orderReceiptRepository.findByEventIds(companyEventIds).stream()
                .map(receipt -> {
                    List<Ticket> companyTickets = ticketRepository.findByOrderReceiptId(receipt.getId()).stream()
                            .filter(ticket -> companyEventIdSet.contains(ticket.getEventId())) // keep only this company's
                            .toList();
                    return new PurchaseHistoryDTO(List.of(mapper.toPurchaseRecordDTO(
                            receipt, companyTickets, eventRepository, companyRepository, userRepository)));
                }).toList();

        log.info("Successfully retrieved sales history for company {}", companyId);
        return salesHistory;
    }

    /**
     * Validates a session token and returns the authenticated user id.
     *
     * @param token the session token to validate
     * @return the user id encoded in the token
     * @throws InvalidTokenException if the token is not valid
     */
    private int authenticate(String token) {
        if (!sessionManager.validateToken(token)) {  // reject unknown/expired tokens
            throw new InvalidTokenException("Invalid token");
        }
        return sessionManager.extractUserId(token);  // token valid → extract the caller's id
    }
}
