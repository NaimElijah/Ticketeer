package com.ticketing.system.unit.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.SessionExpiredException;
import com.ticketing.system.identity.application.port.out.SessionRepository;
import com.ticketing.system.identity.domain.Session;
import com.ticketing.system.identity.adapter.out.persistence.MemorySessionRepository;
import com.ticketing.system.identity.adapter.out.security.JwtSessionManager;

class JwtSessionManagerTest {

    private static final String SECRET =
        "unit-test-secret-must-be-long-enough-for-hmac-sha-256-please-ignore-me";

    private Clock clock;
    private SessionRepository sessions;
    private JwtSessionManager manager;

    @BeforeEach
    void setUp() {
        clock = Clock.systemUTC();
        sessions = new MemorySessionRepository(clock);
        manager = new JwtSessionManager(SECRET, 60, sessions, clock);
    }

    @Test
    void generateToken_producesDottedJwtString() {
        String token = manager.generateToken(42, "alice");
        assertNotNull(token);
        assertEquals(3, token.split("\\.").length, "JWT should have 3 dot-separated parts");
    }

    @Test
    void validateToken_returnsTrueForFreshToken() {
        String token = manager.generateToken(42, "alice");
        assertTrue(manager.validateToken(token));
    }

    @Test
    void validateToken_returnsFalseForNull() {
        assertFalse(manager.validateToken(null));
    }

    @Test
    void validateToken_returnsFalseForBlank() {
        assertFalse(manager.validateToken("   "));
    }

    @Test
    void validateToken_throwsInvalidTokenForGarbage() {
        assertThrows(InvalidTokenException.class, () -> manager.validateToken("not.a.jwt"));
    }

    @Test
    void validateToken_throwsInvalidTokenForTamperedSignature() {
        String token = manager.generateToken(42, "alice");
        // Append extra bytes to the signature segment so HMAC verification fails.
        String tampered = token + "AAAA";
        assertThrows(InvalidTokenException.class, () -> manager.validateToken(tampered));
    }

    @Test
    void validateToken_throwsSessionExpiredForExpiredToken() {
        // Negative expiration → token is issued already past its exp claim.
        Clock expiredClock = Clock.systemUTC();
        SessionRepository expiredSessions = new MemorySessionRepository(expiredClock);
        JwtSessionManager expiredManager = new JwtSessionManager(SECRET, -1, expiredSessions, expiredClock);
        String token = expiredManager.generateToken(42, "alice");
        assertThrows(SessionExpiredException.class, () -> expiredManager.validateToken(token));
    }

    @Test
    void extractUserId_returnsEncodedUserId() {
        String token = manager.generateToken(42, "alice");
        assertEquals(42, manager.extractUserId(token));
    }

    @Test
    void extractUsername_returnsEncodedUsername() {
        String token = manager.generateToken(42, "alice");
        assertEquals("alice", manager.extractUsername(token));
    }

    @Test
    void extractExpiration_returnsFutureEpochMillis() {
        long before = System.currentTimeMillis();
        String token = manager.generateToken(42, "alice");
        long expiry = manager.extractExpiration(token);
        assertTrue(expiry > before, "expiry should be after issuance time");
    }

    @Test
    void isExpired_returnsFalseForFreshToken() {
        String token = manager.generateToken(42, "alice");
        assertFalse(manager.isExpired(token));
    }

    @Test
    void isExpired_returnsTrueForGarbage() {
        assertTrue(manager.isExpired("not.a.jwt"));
    }

    @Test
    void tryExtractUserId_returnsPresentForValidToken() {
        String token = manager.generateToken(42, "alice");
        Optional<Integer> id = manager.tryExtractUserId(token);
        assertTrue(id.isPresent());
        assertEquals(42, id.get());
    }

    @Test
    void tryExtractUserId_returnsEmptyForGarbage() {
        assertTrue(manager.tryExtractUserId("not.a.jwt").isEmpty());
    }

    @Test
    void invalidate_thenValidate_throwsInvalidToken() {
        String token = manager.generateToken(42, "alice");
        manager.invalidate(token);
        assertThrows(InvalidTokenException.class, () -> manager.validateToken(token));
    }

    @Test
    void invalidate_thenExtractUserId_throwsInvalidToken() {
        // Revocation flows through parseClaims, so every reader respects it.
        String token = manager.generateToken(42, "alice");
        manager.invalidate(token);
        assertThrows(InvalidTokenException.class, () -> manager.extractUserId(token));
    }

    @Test
    void invalidate_thenTryExtractUserId_returnsEmpty() {
        String token = manager.generateToken(42, "alice");
        manager.invalidate(token);
        assertTrue(manager.tryExtractUserId(token).isEmpty());
    }

    @Test
    void invalidate_thenIsExpired_returnsTrue() {
        String token = manager.generateToken(42, "alice");
        manager.invalidate(token);
        assertTrue(manager.isExpired(token));
    }

    @Test
    void invalidate_twice_doesNotThrow() {
        String token = manager.generateToken(42, "alice");
        manager.invalidate(token);
        manager.invalidate(token);
    }

    @Test
    void invalidate_null_doesNotThrow() {
        manager.invalidate(null);
    }

    @Test
    void invalidate_blank_doesNotThrow() {
        manager.invalidate("   ");
    }

    @Test
    void invalidate_doesNotAffectOtherTokens() {
        String tokenA = manager.generateToken(1, "alice");
        String tokenB = manager.generateToken(2, "bob");
        manager.invalidate(tokenA);

        // tokenB is untouched.
        assertTrue(manager.validateToken(tokenB));
        assertEquals(2, manager.extractUserId(tokenB));
    }

    // ---------------------------------------------------------------------
    // Phase 2 — Session-row backed behavior
    // ---------------------------------------------------------------------

    @Test
    void generateToken_createsSessionRow() {
        manager.generateToken(42, "alice");
        // Exactly one row should now exist for userId 42.
        assertTrue(sessions.existsByUserId(42));
    }

    @Test
    void generateToken_eachCallProducesDistinctSessionId() {
        String a = manager.generateToken(42, "alice");
        String b = manager.generateToken(42, "alice");
        // Different JWTs → different sid claims → both rows exist (Q4 multi-device).
        assertEquals(2, countSessionsForUser(42));
        // Sanity: tokens differ.
        assertTrue(!a.equals(b));
    }

    @Test
    void invalidate_deletesOnlyThatTokensSessionRow() {
        String tokenA = manager.generateToken(42, "alice");
        String tokenB = manager.generateToken(42, "alice");   // second device per Q4

        manager.invalidate(tokenA);

        // The other session for the same user survives.
        assertTrue(sessions.existsByUserId(42));
        assertTrue(manager.validateToken(tokenB));
        assertThrows(InvalidTokenException.class, () -> manager.validateToken(tokenA));
    }

    @Test
    void isOnline_falseWhenNoSession() {
        assertFalse(manager.isOnline(99));
    }

    @Test
    void isOnline_trueAfterGenerateToken() {
        manager.generateToken(7, "carol");
        assertTrue(manager.isOnline(7));
    }

    @Test
    void isOnline_falseAfterInvalidate() {
        String token = manager.generateToken(7, "carol");
        manager.invalidate(token);
        assertFalse(manager.isOnline(7));
    }

    @Test
    void isOnline_trueAcrossMultipleDevices() {
        // Q4 — allow-multiple Member sessions per userId.
        manager.generateToken(7, "carol");
        manager.generateToken(7, "carol");
        assertTrue(manager.isOnline(7));
    }

    @Test
    void generateTokenForSession_reusesSessionId() {
        // Promote-on-login scenario: an existing Session row gains a JWT.
        Instant now = Instant.now();
        Session preexisting = new Session(
                "preserved-sid",
                9,
                now,
                now.plusSeconds(3600)
        );
        sessions.save(preexisting);

        String token = manager.generateTokenForSession(preexisting, "dave");

        // The JWT validates (signature ok, exp ok, Session row exists).
        assertTrue(manager.validateToken(token));
        assertEquals(9, manager.extractUserId(token));
        assertEquals("dave", manager.extractUsername(token));
    }

    @Test
    void generateTokenForSession_rejectsNull() {
        assertThrows(IllegalArgumentException.class,
                () -> manager.generateTokenForSession(null, "dave"));
    }

    @Test
    void generateTokenForSession_rejectsGuestSession() {
        Instant now = Instant.now();
        Session guest = new Session("guest-sid", null, now, now.plusSeconds(3600));
        sessions.save(guest);

        assertThrows(IllegalStateException.class,
                () -> manager.generateTokenForSession(guest, "guest"));
    }

    @Test
    void validateToken_throwsWhenSessionRowDeletedExternally() {
        String token = manager.generateToken(11, "ed");
        // Simulate sweeper deleting the only session for user 11 directly.
        sessionsForUser(11).forEach(s -> sessions.delete(s.getSessionId()));

        assertThrows(InvalidTokenException.class, () -> manager.validateToken(token));
    }

    // ---------------------------------------------------------------------
    // validateCredential — accepts either JWT or raw sessionId
    // ---------------------------------------------------------------------

    @Test
    void validateCredential_trueForFreshJwt() {
        String token = manager.generateToken(42, "alice");
        assertTrue(manager.validateCredential(token));
    }

    @Test
    void validateCredential_trueForRawGuestSessionId() {
        Instant now = Instant.now();
        Session guest = new Session("guest-sid-xyz", null, now, now.plusSeconds(3600));
        sessions.save(guest);

        assertTrue(manager.validateCredential("guest-sid-xyz"));
    }

    @Test
    void validateCredential_falseForUnknownSessionId() {
        assertFalse(manager.validateCredential("ghost-sid"));
    }

    @Test
    void validateCredential_falseForExpiredSession() {
        Instant past = Instant.now().minusSeconds(3600);
        Session expired = new Session("expired-sid", null, past, past.plusSeconds(60));
        sessions.save(expired);

        assertFalse(manager.validateCredential("expired-sid"));
    }

    @Test
    void validateCredential_falseForNullAndBlank() {
        assertFalse(manager.validateCredential(null));
        assertFalse(manager.validateCredential(""));
        assertFalse(manager.validateCredential("   "));
    }

    @Test
    void validateCredential_falseForRevokedJwt() {
        String token = manager.generateToken(42, "alice");
        manager.invalidate(token);
        assertFalse(manager.validateCredential(token));
    }

    @Test
    void validateCredential_falseForGarbageString() {
        // Not a JWT (no dots) and not a known sessionId → false, not throw.
        assertFalse(manager.validateCredential("just-some-random-string"));
    }

    // ---- helpers ----

    private int countSessionsForUser(int userId) {
        return sessionsForUser(userId).size();
    }

    private java.util.List<Session> sessionsForUser(int userId) {
        // findExpiredBefore(Instant.MAX) returns every row — enough for tests
        // that need a full repository scan without exposing extra repo methods.
        java.util.List<Session> result = new java.util.ArrayList<>();
        for (Session s : sessions.findExpiredBefore(Instant.MAX)) {
            if (s.getUserId() != null && s.getUserId() == userId) result.add(s);
        }
        return result;
    }
}
