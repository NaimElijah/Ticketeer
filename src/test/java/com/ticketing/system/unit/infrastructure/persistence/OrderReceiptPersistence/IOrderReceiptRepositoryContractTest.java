package com.ticketing.system.unit.infrastructure.persistence.OrderReceiptPersistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.Core.Application.dto.GlobalHistoryFiltersDTO;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.ReceiptLine;
import com.ticketing.system.sales.domain.TransactionRecord;
import com.ticketing.system.sales.domain.TransactionRecord.TransactionType;

/**
 * Contract every {@link OrderReceiptRepository} implementation must satisfy. The Memory and JPA
 * adapters each subclass this with their own {@link #newRepository()} factory; the tests are reused.
 * The lines/transactions test pins the acceptance: the owned value lists survive save/reload on both
 * adapters.
 */
abstract class IOrderReceiptRepositoryContractTest {

    protected abstract OrderReceiptRepository newRepository();

    private OrderReceiptRepository repo;

    @BeforeEach
    void setUp() {
        repo = newRepository();
    }

    private ReceiptLine line(int ticketId, int eventId) {
        return new ReceiptLine(ticketId, 50.0, eventId, 1, "A1", LocalDateTime.now());
    }

    private OrderReceipt member(int receiptId, int userId, int eventId) {
        return OrderReceipt.forMember(receiptId, userId, 50.0, List.of(line(receiptId * 10, eventId)));
    }

    private Set<Integer> ids(List<OrderReceipt> rs) {
        return rs.stream().map(OrderReceipt::getId).collect(Collectors.toSet());
    }

    @Test
    void nextId_producesDistinctIncreasingValues() {
        int a = repo.nextId();
        int b = repo.nextId();
        assertNotEquals(a, b);
        assertTrue(b > a);
    }

    @Test
    void save_thenFindById_returnsTheMemberReceipt() {
        repo.save(member(1, 7, 5));

        OrderReceipt found = repo.findByOrderReceiptId(1).orElseThrow();
        assertEquals(7, found.getUserid());
        assertTrue(found.isMemberReceipt());
    }

    @Test
    void save_thenFindById_returnsTheGuestReceipt() {
        repo.save(OrderReceipt.forGuest("g@example.com", "sess-1", 2, 50.0, List.of(line(20, 5))));

        OrderReceipt found = repo.findByOrderReceiptId(2).orElseThrow();
        assertTrue(found.isGuestReceipt());
        assertEquals("g@example.com", found.getGuestEmail());
    }

    @Test
    void findById_emptyWhenMissing() {
        assertFalse(repo.findByOrderReceiptId(9999).isPresent());
    }

    @Test
    void save_persistsReceiptLinesAndTransactions() {
        OrderReceipt r = OrderReceipt.forMember(1, 7, 100.0, List.of(line(10, 5), line(11, 5)));
        r.addTransaction(TransactionRecord.paymentCharge(999, "WSEP", 100.0, "ILS", LocalDateTime.now()));
        repo.save(r);

        OrderReceipt found = repo.findByOrderReceiptId(1).orElseThrow();
        assertEquals(2, found.getReceiptLines().size());
        assertTrue(found.getReceiptLines().stream()
                .anyMatch(l -> l.getTicketId() == 10 && l.getEventId() == 5 && "A1".equals(l.getSeatNumber())));
        assertEquals(1, found.getTransactionRecords().size());
        TransactionRecord tx = found.getTransactionRecords().get(0);
        assertEquals(TransactionType.PAYMENT_CHARGE, tx.getType());
        assertEquals("999", tx.getExternalTransactionId());
        assertEquals("ILS", tx.getCurrency());
    }

    @Test
    void findByHolderUserId_returnsMemberReceiptsForThatBuyer() {
        repo.save(member(1, 7, 5));
        repo.save(member(2, 7, 6));
        repo.save(member(3, 9, 5));
        repo.save(OrderReceipt.forGuest("g@example.com", "sess-1", 4, 50.0, List.of(line(40, 5))));

        assertEquals(Set.of(1, 2), ids(repo.findByHolderUserId(7)));
        assertTrue(repo.findByHolderUserId(123).isEmpty());
    }

    @Test
    void findByEventId_returnsReceiptsContainingThatEvent() {
        repo.save(member(1, 7, 5));
        repo.save(member(2, 7, 6));

        assertEquals(Set.of(1), ids(repo.findByEventId(5)));
        assertTrue(repo.findByEventId(999).isEmpty());
    }

    @Test
    void findByEventIds_returnsReceiptsContainingAnyOfThoseEvents() {
        repo.save(member(1, 7, 5));
        repo.save(member(2, 7, 6));
        repo.save(member(3, 7, 8));

        assertEquals(Set.of(1, 2), ids(repo.findByEventIds(List.of(5, 6))));
        assertTrue(repo.findByEventIds(List.of()).isEmpty());
    }

    @Test
    void findGlobal_noFiltersReturnsAll_filterByBuyerNarrows() {
        repo.save(member(1, 7, 5));
        repo.save(member(2, 9, 6));

        assertEquals(Set.of(1, 2), ids(repo.findGlobal(null)));
        assertEquals(Set.of(1),
                ids(repo.findGlobal(new GlobalHistoryFiltersDTO(7, null, null, null, null))));
        assertEquals(Set.of(2),
                ids(repo.findGlobal(new GlobalHistoryFiltersDTO(null, null, List.of(6), null, null))));
    }
}
