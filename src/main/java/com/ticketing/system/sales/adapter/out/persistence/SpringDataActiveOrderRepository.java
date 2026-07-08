package com.ticketing.system.sales.adapter.out.persistence;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ticketing.system.sales.domain.ActiveOrder;

/**
 * Spring Data JPA repository for {@link ActiveOrder} — the auto-implemented SQL backing
 * {@link JpaActiveOrderRepository}. The application layer never sees this type; it depends only on
 * the {@code ActiveOrderRepository} domain port. Owned cart items persist as an
 * {@code @ElementCollection} by cascade with the order.
 */
public interface SpringDataActiveOrderRepository extends JpaRepository<ActiveOrder, String> {

    /** Member carts for a user (after the save-time identity collapse there is at most one). */
    List<ActiveOrder> findByUserId(int userId);

    /** Guest carts (no userId) bound to a session — used by the guest-identity collapse. */
    List<ActiveOrder> findBySessionIdAndUserIdIsNull(String sessionId);

    /** Any cart (guest or active-session member) attached to the session. */
    Optional<ActiveOrder> findFirstBySessionId(String sessionId);

    /** Sweep query: carts holding any item whose hold window has elapsed ({@code addedAt <= cutoff}). */
    @Query("select distinct o from ActiveOrder o join o.items i where i.addedAt <= :cutoff")
    List<ActiveOrder> findWithItemAddedBefore(@Param("cutoff") LocalDateTime cutoff);
}
