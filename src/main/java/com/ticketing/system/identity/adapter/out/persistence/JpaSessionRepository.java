package com.ticketing.system.identity.adapter.out.persistence;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.identity.application.port.out.SessionRepository;
import com.ticketing.system.identity.domain.Session;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * JPA-backed {@link SessionRepository} — active only in the {@code jpa} run/dev profile.
 * Adapts the domain port onto Spring Data ({@link SpringDataSessionRepository}); the
 * application layer depends only on {@code SessionRepository}, never on Spring Data.
 *
 * <p>{@code lockForUpdate}/{@code unlock} are no-ops: concurrent writes are guarded by
 * {@code Session}'s {@code @Version} optimistic lock within the surrounding transaction
 * (per the {@code IRepository} contract — JPA replaces the in-memory write-lock with
 * version checks).
 *
 * <p>{@link #save} is an upsert that preserves optimistic locking on the real flows:
 * {@code data.save} inserts a fresh session (new {@code sid}, version {@code null}) and
 * updates a loaded-then-mutated one under its {@code @Version} check (login / start-guest
 * insert; promote / extend / touch update). The only case {@code data.save} can't express
 * is a brand-new instance reusing an already-persisted id — with an assigned id + version
 * {@code null}, merge/persist treats it as transient and tries to INSERT (duplicate key).
 * That never happens in the application (sids are unique), only in the in-memory
 * overwrite contract; it's handled by copying the state onto the managed row via
 * {@link Session#overwriteFrom}. Each write carries its own {@code @Transactional} so the
 * adapter is self-sufficient before the service layer gains transactions (#359); the read
 * delegations inherit Spring Data's read-only tx.
 *
 * <p>{@code existsByUserId}'s "now" comes from the injected {@link Clock} (parity with
 * {@code MemorySessionRepository}), and the non-expired filter runs in SQL.
 */
@Repository
@Profile("jpa")
public class JpaSessionRepository implements SessionRepository {

    private final SpringDataSessionRepository data;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    public JpaSessionRepository(SpringDataSessionRepository data, Clock clock) {
        this.data = data;
        this.clock = clock;
    }

    @Override
    public void lockForUpdate(String id) { /* no-op — @Version optimistic locking */ }

    @Override
    public void unlock(String id) { /* no-op */ }

    @Override
    @Transactional
    public void save(Session session) {
        if (session.getVersion() == null && data.existsById(session.getSessionId())) {
            // Brand-new instance reusing an already-persisted id (the in-memory
            // overwrite contract — never the running app). persist/merge would try to
            // INSERT for a null version, so copy the state onto the managed row instead.
            Session managed = entityManager.find(Session.class, session.getSessionId());
            managed.overwriteFrom(session);
        } else {
            // Insert (version null, id absent) or update a loaded session under its
            // @Version optimistic check (version set).
            data.save(session);
        }
    }

    @Override
    public Optional<Session> findById(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return data.findById(sessionId);
    }

    @Override
    public boolean existsByUserId(int userId) {
        return data.existsByUserIdAndExpiresAtAfter(userId, clock.instant());
    }

    @Override
    @Transactional
    public void delete(String sessionId) {
        if (sessionId == null) {
            return;
        }
        // Idempotent: deleteById would throw EmptyResultDataAccessException for an
        // unknown id, but the contract requires a silent no-op (delete_unknownIsIdempotent).
        if (data.existsById(sessionId)) {
            data.deleteById(sessionId);
        }
    }

    @Override
    public List<Session> findExpiredBefore(Instant cutoff) {
        return data.findByExpiresAtLessThanEqual(cutoff);
    }
}
