package com.ticketing.system.identity.adapter.out.persistence;
import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ticketing.system.identity.domain.Session;
import com.ticketing.system.identity.domain.SessionKind;

/**
 * Spring Data JPA repository for {@link Session} — the auto-implemented SQL backing
 * {@link JpaSessionRepository}. The application layer never sees this type; it depends
 * only on the {@code SessionRepository} domain port.
 *
 * <p>Two derived queries push the Session-specific predicates into SQL:
 * <ul>
 *   <li>{@code existsByUserIdAndKindAndExpiresAtAfter} — at least one non-expired session
 *       of one kind for a user ({@code expiresAt > now}, mirroring the exclusive
 *       {@code !Session.isExpiredAt(now)} boundary). Asked for MEMBER rows, it excludes an
 *       admin's rows, whose id can equal a member's (ADR-012), and guest rows.</li>
 *   <li>{@code findByExpiresAtLessThanEqual} — the UC-2 sweep ({@code expiresAt <= cutoff},
 *       mirroring the inclusive {@code Session.isExpiredAt} boundary).</li>
 * </ul>
 */
public interface SpringDataSessionRepository extends JpaRepository<Session, String> {

    boolean existsByUserIdAndKindAndExpiresAtAfter(Integer userId, SessionKind kind, Instant now);

    List<Session> findByExpiresAtLessThanEqual(Instant cutoff);
}
