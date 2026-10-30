package com.ticketing.system.unit.infrastructure.persistence.SessionPersistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.identity.application.port.out.SessionRepository;
import com.ticketing.system.identity.domain.Session;
import com.ticketing.system.identity.domain.SessionKind;

// Contract tests every SessionRepository implementation must satisfy. Future
// JPA-backed adapter will subclass this with its own newRepository() factory;
// tests are reused.
abstract class ISessionRepositoryContractTest {

    protected abstract SessionRepository newRepository();

    private SessionRepository repo;

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T_FAR_FUTURE = Instant.parse("2027-01-01T00:00:00Z");
    private static final Instant T_PAST = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant T_PAST_PLUS_ONE = Instant.parse("2025-01-01T00:00:01Z");

    @BeforeEach
    void setUp() {
        repo = newRepository();
    }

    @Test
    void save_thenFindById_returnsTheSavedSession() {
        Session s = new Session("sid-1", 5, T0, T_FAR_FUTURE);
        repo.save(s);

        Optional<Session> found = repo.findById("sid-1");
        assertTrue(found.isPresent());
        assertEquals(5, found.get().getUserId());
    }

    @Test
    void findById_returnsEmptyWhenMissing() {
        assertFalse(repo.findById("ghost").isPresent());
    }

    @Test
    void findById_nullReturnsEmpty() {
        assertFalse(repo.findById(null).isPresent());
    }

    @Test
    void delete_removesTheSession() {
        repo.save(new Session("sid-1", 5, T0, T_FAR_FUTURE));
        assertTrue(repo.findById("sid-1").isPresent());

        repo.delete("sid-1");

        assertFalse(repo.findById("sid-1").isPresent());
    }

    @Test
    void delete_unknownIsIdempotent() {
        assertDoesNotThrow(() -> repo.delete("ghost"));
        assertDoesNotThrow(() -> repo.delete(null));
    }

    @Test
    void existsByUserId_trueForLiveMemberSession() {
        repo.save(new Session("sid-1", 5, T0, T_FAR_FUTURE));
        assertTrue(repo.existsByUserId(5));
    }

    @Test
    void existsByUserId_falseWhenNoSessionForUser() {
        repo.save(new Session("sid-1", 7, T0, T_FAR_FUTURE));
        assertFalse(repo.existsByUserId(5));
    }

    @Test
    void existsByUserId_ignoresExpiredSessions() {
        repo.save(new Session("sid-1", 5, T_PAST, T_PAST_PLUS_ONE));
        assertFalse(repo.existsByUserId(5));
    }

    @Test
    void existsByUserId_ignoresGuestSessions() {
        repo.save(new Session("sid-guest", null, T0, T_FAR_FUTURE));
        // No userId equals null's "missing user," so any int query returns false.
        assertFalse(repo.existsByUserId(0));
    }

    @Test
    void existsByUserId_trueWithMultipleSessionsForSameUser() {
        // Q4 — allow-multiple Member sessions per userId (multi-device login).
        repo.save(new Session("sid-1", 5, T0, T_FAR_FUTURE));
        repo.save(new Session("sid-2", 5, T0, T_FAR_FUTURE));
        assertTrue(repo.existsByUserId(5));
    }

    @Test
    void findExpiredBefore_returnsOnlyExpired() {
        Session live = new Session("live", 5, T0, T_FAR_FUTURE);
        Session expired = new Session("expired", 7, T_PAST, T_PAST_PLUS_ONE);
        repo.save(live);
        repo.save(expired);

        List<Session> result = repo.findExpiredBefore(T0);

        assertEquals(1, result.size());
        assertEquals("expired", result.get(0).getSessionId());
    }

    @Test
    void findExpiredBefore_atCutoffBoundaryCountsAsExpired() {
        // expiresAt == cutoff → counts as expired (matches Session.isExpiredAt boundary).
        repo.save(new Session("boundary", 5, T_PAST, T0));
        assertEquals(1, repo.findExpiredBefore(T0).size());
    }

    @Test
    void findExpiredBefore_emptyWhenNothingExpired() {
        repo.save(new Session("live-1", 5, T0, T_FAR_FUTURE));
        repo.save(new Session("live-2", 7, T0, T_FAR_FUTURE));
        assertTrue(repo.findExpiredBefore(T0).isEmpty());
    }

    @Test
    void save_withSameSessionIdOverwrites() {
        repo.save(new Session("sid", 5, T0, T_FAR_FUTURE));
        repo.save(new Session("sid", 7, T0, T_FAR_FUTURE));

        Optional<Session> found = repo.findById("sid");
        assertTrue(found.isPresent());
        assertEquals(7, found.get().getUserId());
    }

    // ---- the session kind round-trips (#185) ----
    // These tests are inherited, so the Jpa subclass runs them without a test transaction: each
    // adapter call commits on its own, and every findById below reads the sessions table.

    @Test
    void GivenGuestSession_WhenSavedAndReloaded_ThenKindIsGuest() {
        repo.save(new Session("sid-guest", null, T0, T_FAR_FUTURE));

        assertEquals(SessionKind.GUEST, repo.findById("sid-guest").orElseThrow().getKind());
    }

    @Test
    void GivenMemberSession_WhenSavedAndReloaded_ThenKindIsMember() {
        repo.save(new Session("sid-member", 5, T0, T_FAR_FUTURE));

        assertEquals(SessionKind.MEMBER, repo.findById("sid-member").orElseThrow().getKind());
    }

    @Test
    void GivenAdminSession_WhenSavedAndReloaded_ThenKindIsAdminAndTheRowNamesTheAdmin() {
        repo.save(Session.admin("sid-admin", 5, T0, T_FAR_FUTURE));

        Session found = repo.findById("sid-admin").orElseThrow();
        assertEquals(SessionKind.ADMIN, found.getKind());
        assertEquals(5, found.getUserId());
    }

    @Test
    void GivenSavedGuestSession_WhenPromotedSavedAndReloaded_ThenKindIsMember() {
        repo.save(new Session("sid-promoted", null, T0, T_FAR_FUTURE));
        Session loaded = repo.findById("sid-promoted").orElseThrow();
        loaded.promoteTo(5, T_FAR_FUTURE);

        repo.save(loaded);

        assertEquals(SessionKind.MEMBER, repo.findById("sid-promoted").orElseThrow().getKind());
    }

    @Test
    void GivenMemberSession_WhenAnAdminSessionWithTheSameIdIsSaved_ThenKindIsAdmin() {
        repo.save(new Session("sid", 5, T0, T_FAR_FUTURE));

        repo.save(Session.admin("sid", 5, T0, T_FAR_FUTURE));

        assertEquals(SessionKind.ADMIN, repo.findById("sid").orElseThrow().getKind(),
                "overwriting a session must overwrite its kind, as it overwrites its user id");
    }
}
