package com.ticketing.system.catalog.adapter.out.persistence;
import com.ticketing.system.catalog.application.port.out.EventRepository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventStatus;

/**
 * Spring Data JPA repository for {@link Event} — the auto-implemented SQL backing
 * {@link JpaEventRepository}. The application layer never sees this type; it depends only on the
 * {@code EventRepository} domain port. The owned VenueMap / zone hierarchy / seats / element
 * collections persist by cascade with the event. {@link JpaSpecificationExecutor} backs the
 * 18-filter catalogue search (pushed down as a Criteria {@code Specification}).
 *
 * <p>The company-scoped queries reference {@code e.comapnyid} (the field name, a domain typo) via
 * explicit JPQL rather than a derived name.
 */
public interface SpringDataEventRepository extends JpaRepository<Event, Integer>, JpaSpecificationExecutor<Event> {

    @Query("select e from Event e where e.comapnyid = :companyId")
    List<Event> findByCompanyId(@Param("companyId") int companyId);

    @Query("select e.id from Event e where e.comapnyid = :companyId")
    List<Integer> findIdsByCompany(@Param("companyId") int companyId);

    @Query("select e from Event e where e.comapnyid = :companyId "
            + "and e.status = com.ticketing.system.catalog.domain.EventStatus.ON_SALE")
    List<Event> findActiveByCompany(@Param("companyId") int companyId);

    List<Event> findByStatus(EventStatus status);

    @Query("select coalesce(max(e.id), 0) from Event e")
    int findMaxEventId();

    @Query("select coalesce(max(v.id), 0) from VenueMap v")
    int findMaxVenueMapId();
}
