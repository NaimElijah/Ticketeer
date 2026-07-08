package com.ticketing.system.sales.adapter.out.persistence;
import com.ticketing.system.sales.application.port.out.TicketRepository;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ticketing.system.sales.domain.Ticket;

/**
 * Spring Data JPA repository for {@link Ticket} — the auto-implemented SQL backing
 * {@link JpaTicketRepository}. The application layer never sees this type; it depends
 * only on the {@code TicketRepository} domain port.
 *
 * <p>The three single-key lookups are derived queries. The two zone lookups use an
 * explicit JPQL {@code @Query}: the field is named {@code zoneid} while its getter is
 * {@code getZoneId()}, so a derived {@code ...Zoneid...} name would be ambiguous —
 * JPQL referencing {@code t.zoneid} is unambiguous. {@code findAvailableInZone} caps the
 * result with a {@link Limit} (the requested quantity); both filter to
 * {@code status = AVAILABLE}.
 */
public interface SpringDataTicketRepository extends JpaRepository<Ticket, Integer> {

    List<Ticket> findByEventId(int eventId);

    List<Ticket> findByOrderReceiptId(int orderReceiptId);

    List<Ticket> findByHolderUserId(int holderUserId);

    @Query("select t from Ticket t where t.eventId = :eventId and t.zoneid = :zoneId "
            + "and t.status = com.ticketing.system.sales.domain.TicketStatus.AVAILABLE")
    List<Ticket> findAvailableInZone(@Param("eventId") int eventId, @Param("zoneId") int zoneId, Limit limit);

    @Query("select count(t) from Ticket t where t.eventId = :eventId and t.zoneid = :zoneId "
            + "and t.status = com.ticketing.system.sales.domain.TicketStatus.AVAILABLE")
    long countAvailableInZone(@Param("eventId") int eventId, @Param("zoneId") int zoneId);
}
