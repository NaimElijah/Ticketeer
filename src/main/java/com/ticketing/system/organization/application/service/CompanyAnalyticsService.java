package com.ticketing.system.organization.application.service;
import com.ticketing.system.organization.application.service.CompanyRatings;
import com.ticketing.system.organization.application.service.CompanyManagementService;

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
import com.ticketing.system.identity.application.port.out.UserRepository;

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

    public CompanyAnalyticsService(
            EventRepository eventRepository,
            OrderReceiptRepository orderReceiptRepository,
            ConversationRepository conversationRepository,
            TicketRepository ticketRepository,
            ProductionCompanyRepository companyRepository,
            UserRepository userRepository) {
        this.eventRepository = eventRepository;
        this.orderReceiptRepository = orderReceiptRepository;
        this.conversationRepository = conversationRepository;
        this.ticketRepository = ticketRepository;
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
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
}
