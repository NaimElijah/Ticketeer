package com.ticketing.system.reporting.application.service;
// CompanyRatings lives in catalog (rating is derived from catalog Events); CompanyManagementService in
// organization; reporting reads both downward, which is allowed.
import com.ticketing.system.catalog.application.service.CompanyRatings;
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
import com.ticketing.system.catalog.application.port.in.CatalogEventDisplayPort;
import com.ticketing.system.messaging.domain.ConversationType;
import com.ticketing.system.messaging.application.port.out.ConversationRepository;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.ReceiptLine;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.shared.exception.CompanyNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.shared.exception.UserNotFoundException;

/**
 * Read side for company analytics: the owner-workspace dashboard counters (V2-WIRE-OWNER-DASH)
 * and the company sales history (UC-22).
 *
 * <p>Every read is company-scoped and authorizes the caller itself (#22): it validates the
 * session token, requires an active appointment in the company, and checks the organization
 * context's permission for sales or inquiry data. Callers never pass a pre-authorized id. Sales
 * figures are the company's own share of each receipt (a receipt can mix events from several
 * companies), over the trailing 30 days, excluding refunded receipts.
 */
@Service
@Slf4j
@Transactional(readOnly = true)
public class CompanyAnalyticsService {

    private static final int WINDOW_DAYS = 30;

    private final EventRepository eventRepository;
    // Catalog display port for resolving event/zone names in sales-history records (via OrderReceiptMapper).
    private final CatalogEventDisplayPort eventDisplayPort;
    private final OrderReceiptRepository orderReceiptRepository;
    private final ConversationRepository conversationRepository;
    private final TicketRepository ticketRepository;
    private final ProductionCompanyRepository companyRepository;
    private final UserRepository userRepository;
    // Company-membership/authorization checks moved off the User aggregate (task #20); reporting
    // already depends on organization.
    private final CompanyMembershipService companyMembershipService;
    // Session/token port — every company-scoped read authenticates its caller (#22).
    private final SessionManager sessionManager;

    public CompanyAnalyticsService(
            EventRepository eventRepository,
            CatalogEventDisplayPort eventDisplayPort,
            OrderReceiptRepository orderReceiptRepository,
            ConversationRepository conversationRepository,
            TicketRepository ticketRepository,
            ProductionCompanyRepository companyRepository,
            UserRepository userRepository,
            CompanyMembershipService companyMembershipService,
            SessionManager sessionManager) {
        this.eventRepository = eventRepository;
        this.eventDisplayPort = eventDisplayPort;
        this.orderReceiptRepository = orderReceiptRepository;
        this.conversationRepository = conversationRepository;
        this.ticketRepository = ticketRepository;
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.companyMembershipService = companyMembershipService;
        this.sessionManager = sessionManager;
    }

    /**
     * Live dashboard counters for one company, as seen by the calling member. Returns zeros for a
     * fresh company with no events.
     *
     * <p>Figures the caller may not see are withheld as {@code null}: revenue
     * needs {@link Permission#VIEW_SALES} and open inquiries need
     * {@link Permission#RESPOND_TO_INQUIRIES}. Owners hold both implicitly.
     *
     * @param token     the caller's session token
     * @param companyId the company to summarise
     * @return the dashboard counters visible to the caller
     * @throws InvalidTokenException       if the token is not valid
     * @throws CompanyNotFoundException    if the company does not exist
     * @throws UserNotFoundException       if the token's user no longer exists
     * @throws UnauthorizedActionException if the caller holds no active appointment in the company
     */
    public CompanyDashboardDTO dashboard(String token, int companyId) {
        int requesterId = authorizeMember(token, companyId);
        boolean canViewSales = companyMembershipService
                .hasPermissionInCompany(requesterId, companyId, Permission.VIEW_SALES);
        boolean canViewInquiries = companyMembershipService
                .hasPermissionInCompany(requesterId, companyId, Permission.RESPOND_TO_INQUIRIES);

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

        Integer openInquiries = canViewInquiries ? countOpenInquiries(companyId) : null;

        // Company rating is derived: the mean of this company's events' ratings (null if none rated).
        Double rating = CompanyRatings.fromEvents(eventRepository.findByCompanyId(companyId));

        log.debug("Dashboard for company {}: {} active events, {} tickets/30d, {} revenue/30d, {} open inquiries, rating {}",
                companyId, activeEvents, ticketsSold, revenue, openInquiries, rating);
        return new CompanyDashboardDTO(activeEvents, ticketsSold, canViewSales ? revenue : null,
                openInquiries, rating);
    }

    private int countOpenInquiries(int companyId) {
        return (int) conversationRepository.findByCompanyAsCounterparty(companyId).stream()
                .filter(c -> c.getType() == ConversationType.INQUIRY && !c.isClosed())
                .count();
    }

    /**
     * UC-22 — the company's sales history: one record per receipt that holds at least one ticket
     * for the company's events, carrying only that company's lines and revenue.
     *
     * @param token     the caller's session token
     * @param companyId the company whose sales are requested
     * @return the company's sales records (empty when the company has no events)
     * @throws InvalidTokenException       if the token is not valid
     * @throws CompanyNotFoundException    if the company does not exist
     * @throws UserNotFoundException       if the token's user no longer exists
     * @throws UnauthorizedActionException if the caller is not a member holding {@link Permission#VIEW_SALES}
     */
    public PurchaseHistoryDTO salesHistory(String token, int companyId) {
        int requesterId = authorizeMember(token, companyId);
        requirePermission(requesterId, companyId, Permission.VIEW_SALES);

        List<Integer> eventIds = eventRepository.findIdsByCompany(companyId);
        if (eventIds.isEmpty()) return new PurchaseHistoryDTO(List.of());
        Set<Integer> companyEventIds = new HashSet<>(eventIds);
        OrderReceiptMapper mapper = new OrderReceiptMapper();
        List<PurchaseHistoryDTO.PurchaseRecordDTO> records = orderReceiptRepository
                .findByEventIds(eventIds)
                .stream()
                .map(r -> mapper.toFilteredPurchaseRecordDTO(
                        r, companyEventIds,
                        ticketRepository, eventDisplayPort, companyRepository, userRepository))
                .toList();
        return new PurchaseHistoryDTO(records);
    }

    /**
     * Authenticates the caller and requires an active appointment in the company.
     *
     * @return the caller's user id
     * @throws InvalidTokenException       if the token is not valid
     * @throws CompanyNotFoundException    if the company does not exist
     * @throws UserNotFoundException       if the token's user no longer exists
     * @throws UnauthorizedActionException if the caller is not an active member of the company
     */
    private int authorizeMember(String token, int companyId) {
        int requesterId = authenticate(token);
        if (companyRepository.getCompanyById(companyId) == null) {
            log.warn("Company {} not found", companyId);
            throw new CompanyNotFoundException();
        }
        if (userRepository.getUserById(requesterId) == null) {
            log.warn("User {} not found", requesterId);
            throw new UserNotFoundException();
        }
        if (!companyMembershipService.isMemberInCompany(requesterId, companyId)) {
            log.warn("User {} is not a member of company {}", requesterId, companyId);
            throw new UnauthorizedActionException("view this company's data");
        }
        return requesterId;
    }

    private void requirePermission(int requesterId, int companyId, Permission permission) {
        if (!companyMembershipService.hasPermissionInCompany(requesterId, companyId, permission)) {
            log.warn("User {} lacks {} in company {}", requesterId, permission, companyId);
            throw new UnauthorizedActionException("view this company's data");
        }
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
