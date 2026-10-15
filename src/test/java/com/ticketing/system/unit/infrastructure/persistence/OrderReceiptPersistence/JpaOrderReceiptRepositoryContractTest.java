package com.ticketing.system.unit.infrastructure.persistence.OrderReceiptPersistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.adapter.out.persistence.JpaOrderReceiptRepository;
import com.ticketing.system.sales.adapter.out.persistence.SpringDataOrderReceiptRepository;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.ReceiptLine;
import com.ticketing.system.sales.domain.TransactionRecord;

/**
 * Runs the {@link IOrderReceiptRepositoryContractTest} suite against the JPA adapter on an embedded
 * H2 schema. {@code @ActiveProfiles("jpa")} activates {@link JpaOrderReceiptRepository};
 * {@code @DataJpaTest} provides H2 + real {@code receipts}, {@code receipt_lines} and
 * {@code receipt_transactions} tables. Each test starts from an empty schema ({@link #cleanTable()})
 * so the suite is order-independent. CI never touches a real database.
 */
@DataJpaTest
@ActiveProfiles("jpa")
@Import(JpaOrderReceiptRepository.class)
class JpaOrderReceiptRepositoryContractTest extends IOrderReceiptRepositoryContractTest {

    @Autowired
    private JpaOrderReceiptRepository repository;

    @Autowired
    private SpringDataOrderReceiptRepository data;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTable() {
        data.deleteAll();
    }

    @Override
    protected OrderReceiptRepository newRepository() {
        return repository;
    }

    // Review finding 4 on #129: a row written before refund state moved onto the lines (#20) records a
    // refund only in the order-level flag. Only a database that predates the column can hold one, so
    // this case is JPA-only. It must fail closed: nothing on it may be refunded again. It runs outside
    // the test transaction so the raw SQL commits and the adapter's own read sees it.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void GivenReceiptRefundedBeforeLineStateExisted_WhenLoaded_ThenItCountsAsFullyRefunded() {
        repository.save(OrderReceipt.forMember(1, 7, 100.0, List.of(
                new ReceiptLine(10, 50.0, 5, 1, "A1", LocalDateTime.now()),
                new ReceiptLine(11, 50.0, 6, 1, "A2", LocalDateTime.now()))));
        jdbc.update("update receipts set is_refunded = true where receipt_id = 1");
        jdbc.update("update receipt_lines set refund_state = null where receipt_id = 1");

        OrderReceipt legacy = repository.findByOrderReceiptId(1).orElseThrow();

        assertTrue(legacy.getReceiptLines().stream().allMatch(ReceiptLine::isRefunded));
        assertTrue(legacy.isFullyRefunded());
        assertEquals(0.0, legacy.netAmount(), "it earns nothing in the revenue figures");
        assertDoesNotThrow(legacy::checkInvariants, "the invariant accepts a pre-#129 row");
        assertTrue(legacy.hasRefundedOrPendingLines(), "a whole-order member refund must be refused");
        assertTrue(legacy.claimRefundForEvent(5).isEmpty(), "an event cancellation must claim nothing");
    }

    // Review finding 1 on #132: while #129's code ran, refunding one event of an order also set the
    // order-level flag. Such a row must read as partly refunded, and its other event must still be
    // claimable when that event is cancelled.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void GivenPartlyRefundedRowWithTheOrderFlagSet_WhenLoaded_ThenItIsPartlyRefundedAndItsOtherEventCanBeClaimed() {
        LocalDateTime at = LocalDateTime.now();
        OrderReceipt partly = OrderReceipt.forMember(2, 7, 100.0, List.of(
                new ReceiptLine(20, 50.0, 5, 1, "B1", at),
                new ReceiptLine(21, 50.0, 6, 1, "B2", at)));
        partly.claimRefundForEvent(5);
        partly.completeEventRefund(5, TransactionRecord.refund("refund-5", "WSEP", 50.0, "ILS", at));
        repository.save(partly);
        jdbc.update("update receipts set is_refunded = true where receipt_id = 2");

        OrderReceipt loaded = repository.findByOrderReceiptId(2).orElseThrow();

        assertFalse(loaded.isFullyRefunded());
        assertEquals(50.0, loaded.netAmount());
        assertEquals(50.0, loaded.claimRefundForEvent(6).orElseThrow());
    }
}
