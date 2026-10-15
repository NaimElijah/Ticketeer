package com.ticketing.system.sales.application.dtoMappers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.sales.domain.Ticket;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.domain.ProductionCompany;
// Catalog inbound display port + its sales-safe projection: the mapper resolves event/zone/venue/
// company display fields through this port, so it imports no catalog.domain type.
import com.ticketing.system.catalog.application.port.in.CatalogEventDisplayPort;
import com.ticketing.system.catalog.application.port.in.EventDisplayInfoDTO;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.ReceiptLine;
import com.ticketing.system.sales.domain.TransactionRecord;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.User;

/**
 * Maps {@link OrderReceipt} to {@link PurchaseHistoryDTO} read models.
 *
 * <p>The receipt is the immutable purchase snapshot; tickets are loaded to
 * enrich with current status. The optional {@code eventDisplay} /
 * {@code companyRepository} / {@code userRepository} let the mapper resolve
 * human-readable names (event, zone, company, buyer) — pass {@code null} for
 * any a caller doesn't need; the corresponding DTO field is then {@code null}.
 * Event/zone/venue display data comes from the catalog {@link CatalogEventDisplayPort}
 * so the mapper never touches a {@code catalog.domain} type.
 */
public class OrderReceiptMapper {

    /** Full receipt → record (member history / unfiltered global history). */
    public PurchaseHistoryDTO.PurchaseRecordDTO toPurchaseRecordDTO(OrderReceipt receipt,
            TicketRepository ticketRepository,
            CatalogEventDisplayPort eventDisplay,
            ProductionCompanyRepository companyRepository,
            UserRepository userRepository) {
        List<Ticket> tickets = ticketRepository.findByOrderReceiptId(receipt.getId());
        return map(receipt, safeList(tickets), eventDisplay, companyRepository, userRepository);
    }

    /**
     * UC-31 admin global history filtered by event: only the lines for {@code selectedEventIds},
     * with {@code totalPaid} as their sum, and every transaction of the receipt. Admin-only, since
     * a receipt's transactions also cover lines outside the filter.
     *
     * <p>{@code receipt}, {@code selectedEventIds} and {@code ticketRepository} must be non-null;
     * the other ports may be null, as described on the class.
     */
    public PurchaseHistoryDTO.PurchaseRecordDTO toAdminFilteredPurchaseRecordDTO(OrderReceipt receipt,
            Set<Integer> selectedEventIds,
            TicketRepository ticketRepository,
            CatalogEventDisplayPort eventDisplay,
            ProductionCompanyRepository companyRepository,
            UserRepository userRepository) {
        return mapSelectedEvents(receipt, selectedEventIds, toTransactionRecordDTOs(receipt),
                ticketRepository, eventDisplay, companyRepository, userRepository);
    }

    /**
     * UC-22 company sales history: only the lines for the company's events, with {@code totalPaid}
     * as their sum, and no transactions. A transaction covers the whole order and names no line or
     * company, so on an order shared with another company it would expose that company's payment
     * data (#121).
     *
     * <p>{@code receipt}, {@code companyEventIds} and {@code ticketRepository} must be non-null;
     * the other ports may be null, as described on the class.
     */
    public PurchaseHistoryDTO.PurchaseRecordDTO toCompanySalesRecordDTO(OrderReceipt receipt,
            Set<Integer> companyEventIds,
            TicketRepository ticketRepository,
            CatalogEventDisplayPort eventDisplay,
            ProductionCompanyRepository companyRepository,
            UserRepository userRepository) {
        return mapSelectedEvents(receipt, companyEventIds, List.of(),
                ticketRepository, eventDisplay, companyRepository, userRepository);
    }

    private PurchaseHistoryDTO.PurchaseRecordDTO mapSelectedEvents(OrderReceipt receipt,
            Set<Integer> selectedEventIds,
            List<PurchaseHistoryDTO.TransactionRecordDTO> transactionDtos,
            TicketRepository ticketRepository,
            CatalogEventDisplayPort eventDisplay,
            ProductionCompanyRepository companyRepository,
            UserRepository userRepository) {
        List<Ticket> tickets = ticketRepository.findByOrderReceiptId(receipt.getId());
        Map<Integer, Ticket> ticketsById = byId(safeList(tickets));

        List<ReceiptLine> linesToMap = receipt.getReceiptLines().stream()
                .filter(line -> selectedEventIds.contains(line.getEventId()))
                .toList();

        double totalPaid = linesToMap.stream().mapToDouble(ReceiptLine::getPriceAtReservation).sum();

        List<PurchaseHistoryDTO.TicketRecordDTO> ticketRecords = linesToMap.stream()
                .map(line -> toTicketRecordDTO(receipt, line, ticketsById, eventDisplay, companyRepository))
                .toList();

        return buildRecord(receipt, totalPaid, transactionDtos, ticketRecords, userRepository);
    }

    // Maps every line of the receipt (enriched with current ticket status) and all its transactions.
    private PurchaseHistoryDTO.PurchaseRecordDTO map(OrderReceipt receipt, List<Ticket> tickets,
            CatalogEventDisplayPort eventDisplay, ProductionCompanyRepository companyRepository,
            UserRepository userRepository) {
        Map<Integer, Ticket> ticketsById = byId(tickets);

        List<PurchaseHistoryDTO.TicketRecordDTO> ticketRecords = receipt.getReceiptLines().stream()
                .map(line -> toTicketRecordDTO(receipt, line, ticketsById, eventDisplay, companyRepository))
                .toList();

        return buildRecord(receipt, receipt.getTotalAmount(), toTransactionRecordDTOs(receipt), ticketRecords,
                userRepository);
    }

    private List<PurchaseHistoryDTO.TransactionRecordDTO> toTransactionRecordDTOs(OrderReceipt receipt) {
        return receipt.getTransactionRecords().stream()
                .map(this::toTransactionRecordDTO)
                .toList();
    }

    private PurchaseHistoryDTO.PurchaseRecordDTO buildRecord(OrderReceipt receipt, double totalPaid,
            List<PurchaseHistoryDTO.TransactionRecordDTO> transactionDtos,
            List<PurchaseHistoryDTO.TicketRecordDTO> ticketRecords,
            UserRepository userRepository) {
        return new PurchaseHistoryDTO.PurchaseRecordDTO(
                receipt.getId(),
                receipt.getHolderUserId(),
                receipt.getGuestEmail(),
                receipt.getPurchaseTime(),
                totalPaid,
                PurchaseHistoryDTO.RefundStatus.of(ticketRecords),
                transactionDtos,
                ticketRecords,
                resolveBuyerName(receipt.getHolderUserId(), userRepository));
    }

    private PurchaseHistoryDTO.TicketRecordDTO toTicketRecordDTO(OrderReceipt receipt, ReceiptLine line,
            Map<Integer, Ticket> ticketsById, CatalogEventDisplayPort eventDisplay,
            ProductionCompanyRepository companyRepository) {
        Ticket ticket = ticketsById.get(line.getTicketId());
        TicketStatus currentStatus = ticket != null ? ticket.getStatus() : fallbackStatus(line);

        // One display projection per line (null when the event is unknown / a null port was passed).
        EventDisplayInfoDTO event = describeEvent(eventDisplay, line.getEventId());
        String eventName = event == null ? null : event.eventName();
        String zoneName = resolveZoneName(event, line.getZoneId());
        String companyName = resolveCompanyName(event, companyRepository);
        String category = event == null ? null : event.category();
        LocalDateTime eventStartsAt = event == null ? null : event.eventStartsAt();
        String venue = event == null ? null : event.venueLocation();
        String barcode = ticket == null ? null : ticket.getBarcode();

        return new PurchaseHistoryDTO.TicketRecordDTO(
                line.getTicketId(),
                line.getZoneId(),
                line.getEventId(),
                receipt.getId(),
                line.getSeatNumber(),
                line.getPriceAtReservation(),
                currentStatus,
                eventName,
                zoneName,
                companyName,
                category,
                eventStartsAt,
                venue,
                barcode,
                line.isRefunded());
    }

    // ---- name resolution (all null-safe; a null port/repo yields a null name) ----

    private static EventDisplayInfoDTO describeEvent(CatalogEventDisplayPort eventDisplay, int eventId) {
        if (eventDisplay == null) return null;
        try {
            return eventDisplay.describeEvent(eventId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String resolveZoneName(EventDisplayInfoDTO event, int zoneId) {
        if (event == null || event.zones() == null) return null;
        for (EventDisplayInfoDTO.ZoneNameDTO zone : event.zones()) {
            if (zone.zoneId() == zoneId) return zone.name();
        }
        return null;
    }

    private static String resolveCompanyName(EventDisplayInfoDTO event, ProductionCompanyRepository companyRepository) {
        if (event == null || companyRepository == null) return null;
        try {
            ProductionCompany company = companyRepository.getCompanyById(event.companyId());
            return company == null ? null : company.getName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String resolveBuyerName(Integer holderUserId, UserRepository userRepository) {
        if (holderUserId == null || userRepository == null) return null;
        try {
            User user = userRepository.getUserById(holderUserId);
            return user == null ? null : user.getUsername();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private PurchaseHistoryDTO.TransactionRecordDTO toTransactionRecordDTO(TransactionRecord record) {
        return new PurchaseHistoryDTO.TransactionRecordDTO(
                record.getType().name(),
                record.getProviderName(),
                record.getExternalTransactionId(),
                record.getAmount(),
                record.getCurrency(),
                record.getTimestamp());
    }

    // Per line, not per order: on a shared order another company's refund must not show here (#21).
    private TicketStatus fallbackStatus(ReceiptLine line) {
        return line.isRefunded() ? TicketStatus.REFUNDED : TicketStatus.PAID;
    }

    private static Map<Integer, Ticket> byId(List<Ticket> tickets) {
        return tickets.stream().collect(Collectors.toMap(Ticket::getId, Function.identity(), (a, b) -> a));
    }

    private List<Ticket> safeList(List<Ticket> tickets) {
        return tickets == null ? List.of() : tickets;
    }
}
