package com.ticketing.system.unit.infrastructure.persistence.OrderReceiptPersistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import com.ticketing.system.sales.adapter.out.persistence.JpaOrderReceiptRepository;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.ReceiptLine;

/**
 * Loading many receipts costs a few statements, not a few per receipt (#186, SLR.4). A receipt's lines
 * and transactions are eager collections, each loaded by its own select. Without batch fetching, a load
 * of N receipts ran 1 + 2N statements, and the history and analytics reads load every receipt.
 * {@code hibernate.default_batch_fetch_size} in {@code application.yml} loads the collections of up to
 * that many receipts per select. Bounding how many receipts those reads load is #222.
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import(JpaOrderReceiptRepository.class)
class ReceiptBatchFetchJpaTest {

    private static final int RECEIPTS = 10;
    // One select for the receipts, then one batched select for each of their two collections.
    private static final long BATCHED_STATEMENTS = 3;
    private static final LocalDateTime PURCHASED = LocalDateTime.of(2026, 10, 30, 12, 0);

    @Autowired private JpaOrderReceiptRepository repository;
    @Autowired private TestEntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @Test
    void GivenTenReceipts_WhenAllAreLoaded_ThenTheirCollectionsComeInBatchesNotOneSelectPerReceipt() {
        for (int id = 1; id <= RECEIPTS; id++) {
            repository.save(OrderReceipt.forMember(id, 7, 50.0,
                    List.of(new ReceiptLine(id * 10, 50.0, 5, 1, "A" + id, PURCHASED)), PURCHASED));
        }
        entityManager.flush();
        entityManager.clear();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        List<OrderReceipt> loaded = repository.findGlobal(null);

        assertEquals(RECEIPTS, loaded.size());
        assertTrue(loaded.stream().allMatch(receipt -> receipt.getReceiptLines().size() == 1),
                "arrangement: every receipt's lines are loaded");
        assertTrue(statistics.getPrepareStatementCount() <= BATCHED_STATEMENTS,
                "loading " + RECEIPTS + " receipts took " + statistics.getPrepareStatementCount() + " statements");
    }
}
