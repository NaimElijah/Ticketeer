package com.ticketing.system.sales.adapter.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ticketing.system.sales.domain.OrderReceipt;

import jakarta.persistence.LockModeType;

/**
 * Spring Data JPA repository for {@link OrderReceipt} — the auto-implemented SQL backing
 * {@link JpaOrderReceiptRepository}. The application layer never sees this type; it depends only on
 * the {@code OrderReceiptRepository} domain port. Owned receipt-line and transaction value lists
 * persist as {@code @ElementCollection} side-tables by cascade with the receipt.
 *
 * <p>{@link JpaSpecificationExecutor} backs the admin global-history query, whose optional filters
 * are assembled dynamically. The event-id lookups join into the receipt-line rows.
 */
public interface SpringDataOrderReceiptRepository
        extends JpaRepository<OrderReceipt, Integer>, JpaSpecificationExecutor<OrderReceipt> {

    /** Member receipts for a buyer (guest receipts have a null userid, so they are excluded). */
    List<OrderReceipt> findByUserid(int userid);

    /**
     * Pessimistic-write fetch (SELECT … FOR UPDATE) of a receipt by id — backs the refund critical
     * section's row lock (#410). The lock is held until the surrounding transaction commits, so
     * concurrent refunds of the same receipt serialise and only one reaches {@code paymentGateway.refund()}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from OrderReceipt r where r.receiptId = :id")
    Optional<OrderReceipt> findByIdForUpdate(@Param("id") int id);

    @Query("select distinct r from OrderReceipt r join r.receiptLines l where l.eventid = :eventId")
    List<OrderReceipt> findByEventId(@Param("eventId") int eventId);

    @Query("select distinct r from OrderReceipt r join r.receiptLines l where l.eventid in :eventIds")
    List<OrderReceipt> findByEventIds(@Param("eventIds") List<Integer> eventIds);

    /** Highest existing receiptId (0 when empty) — seeds the assigned-id sequence across restarts. */
    @Query("select coalesce(max(r.receiptId), 0) from OrderReceipt r")
    int findMaxReceiptId();
}
