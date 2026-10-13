package com.ticketing.system.identity.adapter.out.persistence;
import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ticketing.system.identity.domain.Session;

/**
 * Spring Data JPA repository for {@link Session} — the auto-implemented SQL backing
 * {@link JpaSessionRepository}. The application layer never sees this type; it depends
 * only on the {@code SessionRepository} domain port.
 *
 * <p>Two derived queries push the Session-specific predicates into SQL:
 * <ul>
 *   <li>{@code existsByUserIdAndExpiresAtAfter} — at least one non-expired member
 *       session for a user ({@code expiresAt > now}, mirroring the exclusive
 *       {@code !Session.isExpiredAt(now)} boundary). Guest rows ({@code userId == null})
 *       never match a non-null user id, so they are excluded as required.</li>
 *   <li>{@code findByExpiresAtLessThanEqual} — the UC-2 sweep ({@code expiresAt <= cutoff},
 *       mirroring the inclusive {@code Session.isExpiredAt} boundary).</li>
 * </ul>
 */
public interface SpringDataSessionRepository extends JpaRepository<Session, String> {

    boolean existsByUserIdAndExpiresAtAfter(Integer userId, Instant now);

    List<Session> findByExpiresAtLessThanEqual(Instant cutoff);
}
