package com.ticketing.system.unit.infrastructure.persistence.OrderReceiptPersistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.GlobalHistoryFiltersDTO;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.domain.LineRefundState;
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

    private static final LocalDateTime AT = LocalDateTime.of(2027, 7, 15, 17, 0);

    protected abstract OrderReceiptRepository newRepository();

    private OrderReceiptRepository repo;

    @BeforeEach
    void setUp() {
        repo = newRepository();
    }

    private ReceiptLine line(int ticketId, int eventId) {
        return new ReceiptLine(ticketId, 50.0, eventId, 1, "A1", AT);
    }

    private OrderReceipt member(int receiptId, int userId, int eventId) {
        return OrderReceipt.forMember(receiptId, userId, 50.0, List.of(line(receiptId * 10, eventId)), AT);
    }

    private OrderReceipt memberAt(int receiptId, LocalDateTime purchasedAt) {
        return OrderReceipt.forMember(receiptId, 7, 50.0,
                List.of(new ReceiptLine(receiptId * 10, 50.0, 5, 1, "A1", purchasedAt)), purchasedAt);
    }

    private Set<Integer> ids(List<OrderReceipt> rs) {
        return rs.stream().map(OrderReceipt::getId).collect(Collectors.toSet());
    }

    @Test
    void GivenRepository_WhenNextIdCalledTwice_ThenProducesDistinctIncreasingValues() {
        int a = repo.nextId();
        int b = repo.nextId();
        assertNotEquals(a, b);
        assertTrue(b > a);
    }

    @Test
    void GivenASavedMemberReceipt_WhenFindByOrderReceiptId_ThenReturnsTheMemberReceipt() {
        repo.save(member(1, 7, 5));

        OrderReceipt found = repo.findByOrderReceiptId(1).orElseThrow();
        assertEquals(7, found.getUserid());
        assertTrue(found.isMemberReceipt());
    }

    @Test
    void GivenASavedGuestReceipt_WhenFindByOrderReceiptId_ThenReturnsTheGuestReceipt() {
        repo.save(OrderReceipt.forGuest("g@example.com", "sess-1", 2, 50.0, List.of(line(20, 5)), AT));

        OrderReceipt found = repo.findByOrderReceiptId(2).orElseThrow();
        assertTrue(found.isGuestReceipt());
        assertEquals("g@example.com", found.getGuestEmail());
    }

    @Test
    void GivenNoSuchReceipt_WhenFindByOrderReceiptId_ThenIsEmpty() {
        assertFalse(repo.findByOrderReceiptId(9999).isPresent());
    }

    @Test
    void GivenAReceiptWithLinesAndATransaction_WhenSavedAndReloaded_ThenPersistsReceiptLinesAndTransactions() {
        OrderReceipt r = OrderReceipt.forMember(1, 7, 100.0, List.of(line(10, 5), line(11, 5)), AT);
        r.addTransaction(TransactionRecord.paymentCharge(999, "WSEP", 100.0, "ILS", AT));
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

    // The Jpa table holds the transaction type under a check constraint that a migration must widen for
    // each new constant (ADR-007).
    @Test
    void GivenAReceiptWithATransactionOfEachType_WhenSavedAndReloaded_ThenEachTypeComesBack() {
        OrderReceipt r = member(1, 7, 5);
        r.addTransaction(TransactionRecord.paymentCharge(999, "WSEP", 50.0, "ILS", AT));
        r.addTransaction(TransactionRecord.ticketIssuance("issue-1", "WSEP", AT));
        r.addTransaction(TransactionRecord.refund("refund-1", "WSEP", 50.0, "ILS", AT));
        repo.save(r);

        assertEquals(Set.of(TransactionType.values()), repo.findByOrderReceiptId(1).orElseThrow()
                .getTransactionRecords().stream().map(TransactionRecord::getType).collect(Collectors.toSet()));
    }

    @Test
    void GivenLinesInEveryRefundState_WhenSavedAndReloaded_ThenEachLineKeepsItsState() {
        OrderReceipt r = OrderReceipt.forMember(1, 7, 150.0, List.of(line(10, 5), line(11, 6), line(12, 8)), AT);
        r.claimRefundForEvent(5);
        r.completeEventRefund(5, TransactionRecord.refund("refund-1", "WSEP", 50.0, "ILS", AT));
        r.claimRefundForEvent(6);
        repo.save(r);

        OrderReceipt found = repo.findByOrderReceiptId(1).orElseThrow();
        Map<Integer, LineRefundState> stateByTicket = found.getReceiptLines().stream()
                .collect(Collectors.toMap(ReceiptLine::getTicketId, ReceiptLine::getRefundState));
        assertEquals(Map.of(10, LineRefundState.REFUNDED, 11, LineRefundState.PENDING, 12, LineRefundState.NONE),
                stateByTicket);
        assertFalse(found.isFullyRefunded());
    }

    // An event cancellation loads the receipt, refunds that event's lines and saves it again. Inherited, so
    // the Jpa subclass loads a detached copy and the change reaches the tables only through this save.
    @Test
    void GivenASavedReceipt_WhenLoadedRefundedForOneEventAndSavedAgain_ThenTheStoredReceiptHasTheRefund() {
        repo.save(OrderReceipt.forMember(1, 7, 100.0, List.of(line(10, 5), line(11, 6)), AT));

        OrderReceipt loaded = repo.findByOrderReceiptId(1).orElseThrow();
        loaded.claimRefundForEvent(5);
        loaded.completeEventRefund(5, TransactionRecord.refund("refund-5", "WSEP", 50.0, "ILS", AT));
        repo.save(loaded);

        OrderReceipt reloaded = repo.findByOrderReceiptId(1).orElseThrow();
        assertEquals(Map.of(10, LineRefundState.REFUNDED, 11, LineRefundState.NONE), reloaded.getReceiptLines().stream()
                .collect(Collectors.toMap(ReceiptLine::getTicketId, ReceiptLine::getRefundState)));
        assertEquals(List.of("refund-5"), reloaded.getTransactionRecords().stream()
                .map(TransactionRecord::getExternalTransactionId).toList());
        assertEquals(Set.of(1), ids(repo.findGlobal(null)), "the receipt is updated, not stored a second time");
    }

    @Test
    void GivenMemberAndGuestReceipts_WhenFindByHolderUserId_ThenReturnsMemberReceiptsForThatBuyer() {
        repo.save(member(1, 7, 5));
        repo.save(member(2, 7, 6));
        repo.save(member(3, 9, 5));
        repo.save(OrderReceipt.forGuest("g@example.com", "sess-1", 4, 50.0, List.of(line(40, 5)), AT));

        assertEquals(Set.of(1, 2), ids(repo.findByHolderUserId(7)));
        assertTrue(repo.findByHolderUserId(123).isEmpty());
    }

    @Test
    void GivenReceiptsForTwoEvents_WhenFindByEventId_ThenReturnsReceiptsContainingThatEvent() {
        repo.save(member(1, 7, 5));
        repo.save(member(2, 7, 6));

        assertEquals(Set.of(1), ids(repo.findByEventId(5)));
        assertTrue(repo.findByEventId(999).isEmpty());
    }

    @Test
    void GivenReceiptsForThreeEvents_WhenFindByEventIds_ThenReturnsReceiptsContainingAnyOfThoseEvents() {
        repo.save(member(1, 7, 5));
        repo.save(member(2, 7, 6));
        repo.save(member(3, 7, 8));

        assertEquals(Set.of(1, 2), ids(repo.findByEventIds(List.of(5, 6))));
    }

    @Test
    void GivenSavedReceipts_WhenFindByEventIdsWithAnEmptyList_ThenNothingMatches() {
        repo.save(member(1, 7, 5));

        assertTrue(repo.findByEventIds(List.of()).isEmpty());
    }

    // A list, not a set: a receipt with lines in two of the events is still one receipt, listed once.
    @Test
    void GivenAReceiptWithLinesInTwoOfTheEvents_WhenFindByEventIds_ThenItIsListedOnce() {
        repo.save(OrderReceipt.forMember(1, 7, 100.0, List.of(line(10, 5), line(11, 6)), AT));

        assertEquals(List.of(1), repo.findByEventIds(List.of(5, 6)).stream().map(OrderReceipt::getId).toList());
    }

    // A company with no events is filtered by an empty event list: it has no sales, so nothing matches.
    // Read as "no filter", it would load every receipt and leave the caller to discard them.
    @Test
    void GivenAnEmptyEventIdFilter_WhenFindGlobal_ThenNoReceiptMatches() {
        repo.save(member(1, 7, 5));

        assertTrue(repo.findGlobal(new GlobalHistoryFiltersDTO(null, null, List.of(), null, null)).isEmpty());
    }

    // A date filter covers whole days: the end date runs up to, not including, the next midnight.
    // Purchase times carry fractions of a second, so an end bound of 23:59:59 dropped the last second.
    @Test
    void GivenAPurchaseInTheLastSecondOfTheEndDate_WhenFindGlobalUpToThatDate_ThenItIsIncluded() {
        repo.save(memberAt(1, LocalDateTime.of(2026, 10, 30, 23, 59, 59, 500_000_000)));

        assertEquals(Set.of(1), ids(repo.findGlobal(new GlobalHistoryFiltersDTO(
                null, null, null, LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 30)))));
    }

    // The start date covers its whole day too, from its first instant.
    @Test
    void GivenAPurchaseAtTheMidnightThatStartsTheFromDate_WhenFindGlobalFromThatDate_ThenItIsIncluded() {
        repo.save(memberAt(1, LocalDateTime.of(2026, 10, 30, 0, 0)));

        assertEquals(Set.of(1), ids(repo.findGlobal(new GlobalHistoryFiltersDTO(
                null, null, null, LocalDate.of(2026, 10, 30), null))));
    }

    @Test
    void GivenAPurchaseInTheLastMicrosecondBeforeTheFromDate_WhenFindGlobalFromThatDate_ThenItIsExcluded() {
        repo.save(memberAt(1, LocalDateTime.of(2026, 10, 29, 23, 59, 59, 999_999_000)));

        assertTrue(repo.findGlobal(new GlobalHistoryFiltersDTO(
                null, null, null, LocalDate.of(2026, 10, 30), null)).isEmpty());
    }

    // Every filter given at once narrows by all of them: each other receipt fails exactly one.
    @Test
    void GivenReceiptsThatEachMissOneFilter_WhenFindGlobalWithBuyerEventAndDates_ThenOnlyTheReceiptMatchingAllIsReturned() {
        LocalDateTime inRange = LocalDateTime.of(2026, 10, 30, 12, 0);
        repo.save(receipt(1, 7, 5, inRange));
        repo.save(receipt(2, 9, 5, inRange));
        repo.save(receipt(3, 7, 6, inRange));
        repo.save(receipt(4, 7, 5, inRange.plusDays(2)));

        assertEquals(Set.of(1), ids(repo.findGlobal(new GlobalHistoryFiltersDTO(
                7, null, List.of(5), LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 31)))));
    }

    private OrderReceipt receipt(int receiptId, int userId, int eventId, LocalDateTime purchasedAt) {
        return OrderReceipt.forMember(receiptId, userId, 50.0,
                List.of(new ReceiptLine(receiptId * 10, 50.0, eventId, 1, "A1", purchasedAt)), purchasedAt);
    }

    @Test
    void GivenAPurchaseAtTheMidnightAfterTheEndDate_WhenFindGlobalUpToThatDate_ThenItIsExcluded() {
        repo.save(memberAt(1, LocalDateTime.of(2026, 10, 31, 0, 0)));

        assertTrue(repo.findGlobal(new GlobalHistoryFiltersDTO(
                null, null, null, LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 30))).isEmpty());
    }

    @Test
    void GivenReceiptsOfTwoBuyers_WhenFindGlobal_ThenNoFiltersReturnsAllAndFilterByBuyerOrEventNarrows() {
        repo.save(member(1, 7, 5));
        repo.save(member(2, 9, 6));

        assertEquals(Set.of(1, 2), ids(repo.findGlobal(null)));
        assertEquals(Set.of(1),
                ids(repo.findGlobal(new GlobalHistoryFiltersDTO(7, null, null, null, null))));
        assertEquals(Set.of(2),
                ids(repo.findGlobal(new GlobalHistoryFiltersDTO(null, null, List.of(6), null, null))));
    }
}
