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
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.domain.InventoryZone;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.ReceiptLine;
import com.ticketing.system.sales.domain.TransactionRecord;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.User;

/**
 * Maps {@link OrderReceipt} to {@link PurchaseHistoryDTO} read models.
 *
 * <p>The receipt is the immutable purchase snapshot; tickets are loaded to
 * enrich with current status. The optional {@code eventRepository} /
 * {@code companyRepository} / {@code userRepository} let the mapper resolve
 * human-readable names (event, zone, company, buyer) — pass {@code null} for
 * any a caller doesn't need; the corresponding DTO field is then {@code null}.
 */
public class OrderReceiptMapper {

    /** Full receipt → record (member history / unfiltered global history). */
    public PurchaseHistoryDTO.PurchaseRecordDTO toPurchaseRecordDTO(OrderReceipt receipt,
            TicketRepository ticketRepository,
            EventRepository eventRepository,
            ProductionCompanyRepository companyRepository,
            UserRepository userRepository) {
        List<Ticket> tickets = ticketRepository.findByOrderReceiptId(receipt.getId());
        return map(receipt, safeList(tickets), false, true, eventRepository, companyRepository, userRepository);
    }

    /**
     * Maps only the selected tickets of a receipt (company sales history).
     * Transactions are omitted so a company can't see payment data for tickets
     * from another company that shared the same order.
     */
    public PurchaseHistoryDTO.PurchaseRecordDTO toPurchaseRecordDTO(OrderReceipt receipt,
            List<Ticket> selectedTickets,
            EventRepository eventRepository,
            ProductionCompanyRepository companyRepository,
            UserRepository userRepository) {
        return map(receipt, safeList(selectedTickets), true, false, eventRepository, companyRepository, userRepository);
    }

    /** UC-31 global history: include only tickets matching the event filter; all transactions. */
    public PurchaseHistoryDTO.PurchaseRecordDTO toFilteredPurchaseRecordDTO(OrderReceipt receipt,
            Set<Integer> selectedEventIds,
            TicketRepository ticketRepository,
            EventRepository eventRepository,
            ProductionCompanyRepository companyRepository,
            UserRepository userRepository) {
        List<Ticket> tickets = ticketRepository.findByOrderReceiptId(receipt.getId());
        Map<Integer, Ticket> ticketsById = byId(safeList(tickets));

        List<ReceiptLine> linesToMap = receipt.getReceiptLines().stream()
                .filter(line -> selectedEventIds.contains(line.getEventId()))
                .toList();

        double totalPaid = linesToMap.stream().mapToDouble(ReceiptLine::getPriceAtReservation).sum();

        List<PurchaseHistoryDTO.TicketRecordDTO> ticketRecords = linesToMap.stream()
                .map(line -> toTicketRecordDTO(receipt, line, ticketsById, eventRepository, companyRepository))
                .toList();

        List<PurchaseHistoryDTO.TransactionRecordDTO> transactionDtos = receipt.getTransactionRecords().stream()
                .map(this::toTransactionRecordDTO)
                .toList();

        return buildRecord(receipt, totalPaid, transactionDtos, ticketRecords, userRepository);
    }

    // Actual mapping work, with options to include only selected tickets and to include/exclude transactions.
    private PurchaseHistoryDTO.PurchaseRecordDTO map(OrderReceipt receipt, List<Ticket> tickets,
            boolean selectedTicketsOnly, boolean includeTransactions,
            EventRepository eventRepository, ProductionCompanyRepository companyRepository,
            UserRepository userRepository) {
        Map<Integer, Ticket> ticketsById = byId(tickets);

        List<ReceiptLine> linesToMap = receipt.getReceiptLines();
        if (selectedTicketsOnly) {
            Set<Integer> selectedTicketIds = ticketsById.keySet();
            linesToMap = linesToMap.stream()
                    .filter(line -> selectedTicketIds.contains(line.getTicketId()))
                    .toList();
        }

        double totalPaid = selectedTicketsOnly
                ? linesToMap.stream().mapToDouble(ReceiptLine::getPriceAtReservation).sum()
                : receipt.getTotalAmount();

        List<PurchaseHistoryDTO.TicketRecordDTO> ticketRecords = linesToMap.stream()
                .map(line -> toTicketRecordDTO(receipt, line, ticketsById, eventRepository, companyRepository))
                .toList();

        List<PurchaseHistoryDTO.TransactionRecordDTO> transactionDtos = includeTransactions
                ? receipt.getTransactionRecords().stream().map(this::toTransactionRecordDTO).toList()
                : List.of();

        return buildRecord(receipt, totalPaid, transactionDtos, ticketRecords, userRepository);
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
                receipt.wasRefunded(),
                transactionDtos,
                ticketRecords,
                resolveBuyerName(receipt.getHolderUserId(), userRepository));
    }

    private PurchaseHistoryDTO.TicketRecordDTO toTicketRecordDTO(OrderReceipt receipt, ReceiptLine line,
            Map<Integer, Ticket> ticketsById, EventRepository eventRepository,
            ProductionCompanyRepository companyRepository) {
        Ticket ticket = ticketsById.get(line.getTicketId());
        TicketStatus currentStatus = ticket != null ? ticket.getStatus() : fallbackStatus(receipt);

        Event event = findEvent(eventRepository, line.getEventId());
        String eventName = event == null ? null : event.getName();
        String zoneName = resolveZoneName(event, line.getZoneId());
        String companyName = resolveCompanyName(event, companyRepository);
        String category = (event == null || event.getCategory() == null) ? null : event.getCategory().toString();
        LocalDateTime eventStartsAt = resolveEventStart(event);
        String venue = (event == null || event.getVenueMap() == null || event.getVenueMap().getLocation() == null)
                ? null : event.getVenueMap().getLocation().toString();
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
                barcode);
    }

    private static LocalDateTime resolveEventStart(Event event) {
        if (event == null || event.getShowDates() == null || event.getShowDates().isEmpty()) return null;
        return event.getShowDates().get(0).getStartTime();
    }

    // ---- name resolution (all null-safe; a null repo yields a null name) ----

    private static Event findEvent(EventRepository eventRepository, int eventId) {
        if (eventRepository == null) return null;
        try {
            return eventRepository.findById(eventId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String resolveZoneName(Event event, int zoneId) {
        if (event == null || event.getVenueMap() == null) return null;
        for (InventoryZone zone : event.getVenueMap().getInventoryZones()) {
            if (zone.getId() == zoneId) return zone.getName();
        }
        return null;
    }

    private static String resolveCompanyName(Event event, ProductionCompanyRepository companyRepository) {
        if (event == null || companyRepository == null) return null;
        try {
            ProductionCompany company = companyRepository.getCompanyById(event.getCompanyId());
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

    private TicketStatus fallbackStatus(OrderReceipt receipt) {
        return receipt.wasRefunded() ? TicketStatus.REFUNDED : TicketStatus.PAID;
    }

    private static Map<Integer, Ticket> byId(List<Ticket> tickets) {
        return tickets.stream().collect(Collectors.toMap(Ticket::getId, Function.identity(), (a, b) -> a));
    }

    private List<Ticket> safeList(List<Ticket> tickets) {
        return tickets == null ? List.of() : tickets;
    }
}
