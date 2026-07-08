package com.ticketing.system.identity.adapter.out.persistence;

import com.ticketing.system.shared.persistence.RepositoryLocks;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import com.ticketing.system.identity.application.port.out.SessionRepository;
import com.ticketing.system.identity.domain.Session;

/**
 * In-memory {@link SessionRepository}.
 *
 * <p>Storage is a thread-safe {@code ConcurrentHashMap} keyed by sessionId.
 * Mirrors {@code MemoryUserRepository}'s shape. {@code @Profile("!jpa")}: the
 * {@code jpa} run/dev profile swaps in {@link JpaSessionRepository} instead.
 *
 * <p>{@link Clock} is injected so tests can supply a fixed clock for
 * deterministic expiry behavior.
 */
@Repository
@Profile("!jpa")
public class MemorySessionRepository implements SessionRepository {

    private final Map<String, Session> sessionsById = new ConcurrentHashMap<>();
    private final RepositoryLocks<String> locks = new RepositoryLocks<>();
    private final Clock clock;

    @Override
    public void lockForUpdate(String id) { locks.lock(id); }

    @Override
    public void unlock(String id) { locks.unlock(id); }

    public MemorySessionRepository(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void save(Session session) {
        sessionsById.put(session.getSessionId(), session);
    }

    @Override
    public Optional<Session> findById(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessionsById.get(sessionId));
    }

    @Override
    public boolean existsByUserId(int userId) {
        Instant now = clock.instant();
        Integer target = userId;
        for (Session s : sessionsById.values()) {
            if (target.equals(s.getUserId()) && !s.isExpiredAt(now)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void delete(String sessionId) {
        if (sessionId == null) {
            return;
        }
        sessionsById.remove(sessionId);
    }

    @Override
    public List<Session> findExpiredBefore(Instant cutoff) {
        List<Session> result = new ArrayList<>();
        for (Session s : sessionsById.values()) {
            if (s.isExpiredAt(cutoff)) {
                result.add(s);
            }
        }
        return result;
    }
}
