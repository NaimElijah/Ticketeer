package com.ticketing.system.unit.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Optional;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.SessionExpiredException;
import com.ticketing.system.identity.application.port.out.SessionRepository;
import com.ticketing.system.identity.domain.Session;
import com.ticketing.system.identity.adapter.out.persistence.MemorySessionRepository;
import com.ticketing.system.identity.adapter.out.security.JwtSessionManager;
import com.ticketing.system.identity.config.JwtProperties;
import com.ticketing.system.identity.config.SessionProperties;
import com.ticketing.system.testutil.LogCapture;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

class JwtSessionManagerTest {

    // JwtSessionManager reads only the member TTL; the guest idle timeout is irrelevant here.
    private static SessionProperties sessionTtl(long memberTtlMinutes) {
        return new SessionProperties(memberTtlMinutes, 30);
    }

    private static final String SECRET =
        "unit-test-secret-must-be-long-enough-for-hmac-sha-256-please-ignore-me";

    private static final SecretKey TEST_KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    // Claim names and role values of the token format JwtSessionManager issues.
    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_SID = "sid";
    private static final String CLAIM_ROLE = "role";
    private static final String ROLE_MEMBER = "MEMBER";
    private static final String ROLE_ADMIN = "ADMIN";

    private static final Instant T0 = Instant.parse("2100-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    // The same integer on purpose: member and admin ids share one id space.
    private static final int MEMBER_ID = 1;
    private static final int ADMIN_ID = 1;
    private static final int OTHER_MEMBER_ID = 2;
    private static final String MEMBER_SID = "member-sid";
    private static final String GUEST_SID = "guest-sid";

    private Clock clock;
    private SessionRepository sessions;
    private JwtSessionManager manager;

    // A manager on a fixed clock, for the token-to-row binding tests.
    private SessionRepository boundSessions;
    private JwtSessionManager boundManager;

    @BeforeEach
    void setUp() {
        clock = Clock.systemUTC();
        sessions = new MemorySessionRepository(clock);
        manager = new JwtSessionManager(new JwtProperties(SECRET), sessionTtl(60), sessions, clock);

        Clock fixed = Clock.fixed(T0, ZoneOffset.UTC);
        boundSessions = new MemorySessionRepository(fixed);
        boundManager = new JwtSessionManager(new JwtProperties(SECRET), sessionTtl(60), boundSessions, fixed);
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
        JwtSessionManager expiredManager = new JwtSessionManager(new JwtProperties(SECRET), sessionTtl(-1), expiredSessions, expiredClock);
        String token = expiredManager.generateToken(42, "alice");
        assertThrows(SessionExpiredException.class, () -> expiredManager.validateToken(token));
    }

    @Test
    void GivenClockFixedYearsBeforeRealTime_WhenValidateFreshToken_ThenTrue() {
        Clock past = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC);
        JwtSessionManager pastManager =
                new JwtSessionManager(new JwtProperties(SECRET), sessionTtl(60), new MemorySessionRepository(past), past);
        String token = pastManager.generateToken(42, "alice");

        assertTrue(pastManager.validateToken(token));
    }

    @Test
    void GivenInjectedClockPastTokenExpiry_WhenValidate_ThenThrowsSessionExpired() {
        Instant issuedAt = Instant.parse("2100-01-01T00:00:00Z");
        SessionRepository shared = new MemorySessionRepository(Clock.fixed(issuedAt, ZoneOffset.UTC));
        String token = new JwtSessionManager(new JwtProperties(SECRET), sessionTtl(60), shared,
                Clock.fixed(issuedAt, ZoneOffset.UTC)).generateToken(42, "alice");
        JwtSessionManager twoHoursLater = new JwtSessionManager(new JwtProperties(SECRET), sessionTtl(60), shared,
                Clock.fixed(issuedAt.plus(Duration.ofHours(2)), ZoneOffset.UTC));

        assertThrows(SessionExpiredException.class, () -> twoHoursLater.validateToken(token));
    }

    @Test
    void GivenInjectedClockOneSecondBeforeExp_WhenValidate_ThenTrue() {
        Instant issuedAt = Instant.parse("2100-01-01T00:00:00Z");
        SessionRepository shared = new MemorySessionRepository(Clock.fixed(issuedAt, ZoneOffset.UTC));
        String token = new JwtSessionManager(new JwtProperties(SECRET), sessionTtl(60), shared,
                Clock.fixed(issuedAt, ZoneOffset.UTC)).generateToken(42, "alice");
        JwtSessionManager justBeforeExp = new JwtSessionManager(new JwtProperties(SECRET), sessionTtl(60), shared,
                Clock.fixed(issuedAt.plus(Duration.ofMinutes(60)).minusSeconds(1), ZoneOffset.UTC));

        assertTrue(justBeforeExp.validateToken(token));
    }

    @Test
    void GivenInjectedClockOneSecondAfterExp_WhenValidate_ThenThrowsSessionExpired() {
        Instant issuedAt = Instant.parse("2100-01-01T00:00:00Z");
        SessionRepository shared = new MemorySessionRepository(Clock.fixed(issuedAt, ZoneOffset.UTC));
        String token = new JwtSessionManager(new JwtProperties(SECRET), sessionTtl(60), shared,
                Clock.fixed(issuedAt, ZoneOffset.UTC)).generateToken(42, "alice");
        JwtSessionManager justAfterExp = new JwtSessionManager(new JwtProperties(SECRET), sessionTtl(60), shared,
                Clock.fixed(issuedAt.plus(Duration.ofMinutes(60)).plusSeconds(1), ZoneOffset.UTC));

        assertThrows(SessionExpiredException.class, () -> justAfterExp.validateToken(token));
    }

    @Test
    void GivenTokenPastExpiryOnInjectedClock_WhenInvalidate_ThenSessionRowDeleted() {
        Instant issuedAt = Instant.parse("2100-01-01T00:00:00Z");
        // The repository's clock stays at issue time, so the row still counts as live to it and
        // only invalidate() can make it disappear.
        SessionRepository shared = new MemorySessionRepository(Clock.fixed(issuedAt, ZoneOffset.UTC));
        String token = new JwtSessionManager(new JwtProperties(SECRET), sessionTtl(60), shared,
                Clock.fixed(issuedAt, ZoneOffset.UTC)).generateToken(42, "alice");
        JwtSessionManager twoHoursLater = new JwtSessionManager(new JwtProperties(SECRET), sessionTtl(60), shared,
                Clock.fixed(issuedAt.plus(Duration.ofHours(2)), ZoneOffset.UTC));
        assertTrue(shared.existsByUserId(42));

        twoHoursLater.invalidate(token);

        assertFalse(shared.existsByUserId(42));
    }

    @Test
    void GivenTamperedSignatureToken_WhenInvalidate_ThenOriginalSessionStillValid() {
        String token = manager.generateToken(42, "alice");
        // Same header and payload, so the same sid, but a signature that no longer verifies.
        String tampered = token + "AAAA";

        manager.invalidate(tampered);

        assertTrue(manager.validateToken(token));
    }

    // #187: the length rule lives here, not in JwtProperties, because a bind-validation failure
    // report prints the rejected value; so the refusal names the key and never the secret.
    @Test
    void GivenSecretOf31Characters_WhenConstruct_ThenIllegalStateNamingTheKeyButNotTheSecret() {
        // 31 ASCII characters, so 31 bytes: one short of the 256 bits HS256 needs.
        String shortSecret = "only-31-bytes-of-secret-materia";
        assertEquals(31, shortSecret.length(), "test fixture: secret length");

        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> new JwtSessionManager(
                new JwtProperties(shortSecret), sessionTtl(60), new MemorySessionRepository(FIXED_CLOCK), FIXED_CLOCK));

        assertThat(refusal.getMessage()).as("the refusal names the key to fix").contains("jwt.secret");
        assertThat(refusal.getMessage()).as("the refusal must never carry the secret").doesNotContain(shortSecret);
    }

    @Test
    void GivenSecretOfExactly32Characters_WhenConstruct_ThenItIssuesTokensThatValidate() {
        String secret = "exactly-32-bytes-of-secret-mater";
        assertEquals(32, secret.length(), "test fixture: secret length");
        JwtSessionManager onShortestKey = new JwtSessionManager(
                new JwtProperties(secret), sessionTtl(60), new MemorySessionRepository(FIXED_CLOCK), FIXED_CLOCK);

        String token = onShortestKey.generateToken(MEMBER_ID, "alice");

        assertTrue(onShortestKey.validateToken(token), "32 bytes is the shortest key HS256 accepts");
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

    // ---------------------------------------------------------------------
    // A token is bound to the session row it names (#185). Member and admin ids share one integer
    // space, so the row, not the token's own claims, decides whose session it is and of what kind.
    // ---------------------------------------------------------------------

    @Test
    void GivenTokenWhoseSubjectDiffersFromItsSessionRow_WhenValidate_ThenThrowsInvalidToken() {
        String memberToken = boundManager.generateToken(MEMBER_ID, "alice");
        String token = signedToken(OTHER_MEMBER_ID, sidOf(memberToken), ROLE_MEMBER);

        assertThrows(InvalidTokenException.class, () -> boundManager.validateToken(token),
                "a token naming another user's session row must not validate");
    }

    @Test
    void GivenTokenWhoseSubjectDiffersFromItsSessionRow_WhenExtractUserId_ThenThrowsInvalidToken() {
        String memberToken = boundManager.generateToken(MEMBER_ID, "alice");
        String token = signedToken(OTHER_MEMBER_ID, sidOf(memberToken), ROLE_MEMBER);

        assertThrows(InvalidTokenException.class, () -> boundManager.extractUserId(token),
                "the user id must come from a token whose subject matches its session row");
    }

    @Test
    void GivenAdminRoleOnAMemberSessionRow_WhenValidate_ThenThrowsInvalidToken() {
        String memberToken = boundManager.generateToken(MEMBER_ID, "alice");
        String token = signedToken(MEMBER_ID, sidOf(memberToken), ROLE_ADMIN);

        assertThrows(InvalidTokenException.class, () -> boundManager.validateToken(token),
                "a role claim that does not match the row's kind must not validate");
    }

    @Test
    void GivenAdminRoleOnAMemberSessionRow_WhenIsAdminToken_ThenFalse() {
        String memberToken = boundManager.generateToken(MEMBER_ID, "alice");
        String token = signedToken(MEMBER_ID, sidOf(memberToken), ROLE_ADMIN);

        assertFalse(boundManager.isAdminToken(token),
                "a member session row never makes a token an admin token, whatever its role claim says");
    }

    @Test
    void GivenMemberRoleOnAnAdminSessionRow_WhenValidate_ThenThrowsInvalidToken() {
        String adminToken = boundManager.generateAdminToken(ADMIN_ID, "admin");
        String token = signedToken(ADMIN_ID, sidOf(adminToken), ROLE_MEMBER);

        assertThrows(InvalidTokenException.class, () -> boundManager.validateToken(token),
                "an admin session row must not back a member token");
    }

    @Test
    void GivenMemberRoleOnAnAdminSessionRow_WhenExtractUserId_ThenThrowsInvalidToken() {
        String adminToken = boundManager.generateAdminToken(ADMIN_ID, "admin");
        String token = signedToken(ADMIN_ID, sidOf(adminToken), ROLE_MEMBER);

        assertThrows(InvalidTokenException.class, () -> boundManager.extractUserId(token),
                "an admin session row must not yield a member user id");
    }

    @Test
    void GivenJwtNamingAGuestSessionRow_WhenValidate_ThenThrowsInvalidToken() {
        boundSessions.save(new Session(GUEST_SID, null, T0, T0.plus(Duration.ofMinutes(30))));
        String token = signedToken(MEMBER_ID, GUEST_SID, ROLE_MEMBER);

        assertThrows(InvalidTokenException.class, () -> boundManager.validateToken(token),
                "a guest session's credential is its raw id, never a JWT");
    }

    @Test
    void GivenJwtWhoseSessionRowHasExpired_WhenValidate_ThenSessionExpired() {
        // The row expired before the token's own exp claim, so only the row can reject it, and it does
        // so as an expired exp claim would (ADR-012, decision 2).
        boundSessions.save(new Session(MEMBER_SID, MEMBER_ID, T0.minus(Duration.ofHours(2)),
                T0.minus(Duration.ofHours(1))));
        String token = signedToken(MEMBER_ID, MEMBER_SID, ROLE_MEMBER);

        assertThrows(SessionExpiredException.class, () -> boundManager.validateToken(token),
                "a token whose session row has expired must be rejected as expired");
    }

    @Test
    void GivenMatchingSubjectRoleAndLiveMemberRow_WhenValidate_ThenTrue() {
        // Control for the tests above: a correctly bound hand-built token is accepted, so the
        // rejections are caused by the mismatch and not by how the test builds its tokens.
        String memberToken = boundManager.generateToken(MEMBER_ID, "alice");
        String token = signedToken(MEMBER_ID, sidOf(memberToken), ROLE_MEMBER);

        assertTrue(boundManager.validateToken(token));
        assertEquals(MEMBER_ID, boundManager.extractUserId(token));
    }

    // ---- member readers accept member tokens only ----

    @Test
    void GivenAdminToken_WhenValidateToken_ThenThrowsInvalidToken() {
        String adminToken = boundManager.generateAdminToken(ADMIN_ID, "admin");

        assertThrows(InvalidTokenException.class, () -> boundManager.validateToken(adminToken),
                "member paths must reject an admin token as they reject a revoked one");
    }

    @Test
    void GivenAdminToken_WhenExtractUserId_ThenThrowsInvalidToken() {
        String adminToken = boundManager.generateAdminToken(ADMIN_ID, "admin");

        assertThrows(InvalidTokenException.class, () -> boundManager.extractUserId(adminToken),
                "an admin id must never be read as a member id");
    }

    @Test
    void GivenAdminToken_WhenTryExtractUserId_ThenEmpty() {
        String adminToken = boundManager.generateAdminToken(ADMIN_ID, "admin");

        assertTrue(boundManager.tryExtractUserId(adminToken).isEmpty(),
                "an admin token names no member");
    }

    @Test
    void GivenAdminToken_WhenValidateCredential_ThenFalse() {
        String adminToken = boundManager.generateAdminToken(ADMIN_ID, "admin");

        assertFalse(boundManager.validateCredential(adminToken),
                "an admin token is not a buyer credential");
    }

    @Test
    void GivenAdminToken_WhenIsAdminToken_ThenTrue() {
        String adminToken = boundManager.generateAdminToken(ADMIN_ID, "admin");

        assertTrue(boundManager.isAdminToken(adminToken));
    }

    @Test
    void GivenMemberToken_WhenIsAdminToken_ThenFalse() {
        String memberToken = boundManager.generateToken(MEMBER_ID, "alice");

        assertFalse(boundManager.isAdminToken(memberToken));
    }

    // ---- admin readers accept admin tokens only (new port methods) ----

    @Test
    void GivenAdminToken_WhenValidateAdminToken_ThenTrue() {
        String adminToken = boundManager.generateAdminToken(ADMIN_ID, "admin");

        assertTrue(boundManager.validateAdminToken(adminToken));
    }

    @Test
    void GivenAdminToken_WhenExtractAdminId_ThenTheAdminsId() {
        String adminToken = boundManager.generateAdminToken(ADMIN_ID, "admin");

        assertEquals(ADMIN_ID, boundManager.extractAdminId(adminToken));
    }

    @Test
    void GivenMemberToken_WhenValidateAdminToken_ThenThrowsInvalidToken() {
        String memberToken = boundManager.generateToken(ADMIN_ID, "alice");

        assertThrows(InvalidTokenException.class, () -> boundManager.validateAdminToken(memberToken),
                "a member token whose id equals an admin's must not pass as an admin token");
    }

    @Test
    void GivenMemberToken_WhenExtractAdminId_ThenThrowsInvalidToken() {
        String memberToken = boundManager.generateToken(ADMIN_ID, "alice");

        assertThrows(InvalidTokenException.class, () -> boundManager.extractAdminId(memberToken),
                "a member id must never be read as an admin id");
    }

    @Test
    void GivenNullOrBlank_WhenValidateAdminToken_ThenFalse() {
        assertFalse(boundManager.validateAdminToken(null));
        assertFalse(boundManager.validateAdminToken("   "));
    }

    @Test
    void GivenRevokedAdminToken_WhenValidateAdminToken_ThenThrowsInvalidToken() {
        String adminToken = boundManager.generateAdminToken(ADMIN_ID, "admin");
        boundManager.invalidate(adminToken);

        assertThrows(InvalidTokenException.class, () -> boundManager.validateAdminToken(adminToken));
    }

    // ---- validateCredential's raw path accepts only a live guest row ----

    @Test
    void GivenLiveMembersRawSessionId_WhenValidateCredential_ThenFalse() {
        String memberToken = boundManager.generateToken(MEMBER_ID, "alice");

        assertFalse(boundManager.validateCredential(sidOf(memberToken)),
                "a member session's raw id is not a guest credential");
    }

    @Test
    void GivenLiveAdminsRawSessionId_WhenValidateCredential_ThenFalse() {
        String adminToken = boundManager.generateAdminToken(ADMIN_ID, "admin");

        assertFalse(boundManager.validateCredential(sidOf(adminToken)),
                "an admin session's raw id is not a guest credential");
    }

    @Test
    void GivenLiveGuestRowOnInjectedClock_WhenValidateCredential_ThenTrue() {
        boundSessions.save(new Session(GUEST_SID, null, T0, T0.plus(Duration.ofMinutes(30))));

        assertTrue(boundManager.validateCredential(GUEST_SID));
    }

    @Test
    void GivenGuestRowExpiredOnInjectedClock_WhenValidateCredential_ThenFalse() {
        boundSessions.save(new Session(GUEST_SID, null, T0.minus(Duration.ofMinutes(30)), T0));

        assertFalse(boundManager.validateCredential(GUEST_SID),
                "a guest row is expired at its expiresAt instant");
    }

    // ---- validateGuestSession: the guest paths' credential is a live guest row's raw id only ----
    // (ADR-012, decision 4). Unlike validateCredential it accepts no token, a member's included.

    @Test
    void GivenLiveGuestRowOnInjectedClock_WhenValidateGuestSession_ThenTrue() {
        boundSessions.save(new Session(GUEST_SID, null, T0, T0.plus(Duration.ofMinutes(30))));

        assertTrue(boundManager.validateGuestSession(GUEST_SID));
    }

    @Test
    void GivenGuestRowExpiredOnInjectedClock_WhenValidateGuestSession_ThenFalse() {
        boundSessions.save(new Session(GUEST_SID, null, T0.minus(Duration.ofMinutes(30)), T0));

        assertFalse(boundManager.validateGuestSession(GUEST_SID),
                "a guest row is expired at its expiresAt instant");
    }

    @Test
    void GivenLiveMembersToken_WhenValidateGuestSession_ThenFalse() {
        String memberToken = boundManager.generateToken(MEMBER_ID, "alice");

        assertFalse(boundManager.validateGuestSession(memberToken), "a member token is not a guest session id");
    }

    @Test
    void GivenLiveMembersRawSessionId_WhenValidateGuestSession_ThenFalse() {
        String memberToken = boundManager.generateToken(MEMBER_ID, "alice");

        assertFalse(boundManager.validateGuestSession(sidOf(memberToken)),
                "a member session's raw id is not a guest session id");
    }

    @Test
    void GivenLiveAdminsRawSessionId_WhenValidateGuestSession_ThenFalse() {
        String adminToken = boundManager.generateAdminToken(ADMIN_ID, "admin");

        assertFalse(boundManager.validateGuestSession(sidOf(adminToken)),
                "an admin session's raw id is not a guest session id");
    }

    @Test
    void GivenLiveAdminsToken_WhenValidateGuestSession_ThenFalse() {
        String adminToken = boundManager.generateAdminToken(ADMIN_ID, "admin");

        assertFalse(boundManager.validateGuestSession(adminToken), "an admin token is not a guest session id");
    }

    @Test
    void GivenUnknownNullOrBlankId_WhenValidateGuestSession_ThenFalseWithoutThrowing() {
        assertFalse(boundManager.validateGuestSession("ghost-sid"));
        assertFalse(boundManager.validateGuestSession(null));
        assertFalse(boundManager.validateGuestSession(""));
        assertFalse(boundManager.validateGuestSession("   "));
    }

    // ---- session ids never reach the log (#185) ----
    // A member session keeps the id its guest session had, and a guest's raw id is its credential,
    // so neither revoking nor rejecting a token may log the id it names, at any level.

    private LogCapture logs;

    @AfterEach
    void detachLogCapture() {
        if (logs != null) {
            logs.close();
        }
    }

    @Test
    void GivenMemberToken_WhenInvalidate_ThenNoLogEventCarriesTheSessionId() {
        String token = boundManager.generateToken(MEMBER_ID, "alice");
        String sid = sidOf(token);
        logs = LogCapture.of(JwtSessionManager.class);

        boundManager.invalidate(token);

        assertTrue(logs.eventCount() > 0, "control: this path logs, so the capture sees the manager's logger");
        assertEquals(java.util.List.of(), logs.eventsContaining(sid), "revoking a session must not log its id");
    }

    @Test
    void GivenRevokedToken_WhenValidate_ThenNoLogEventCarriesTheSessionId() {
        String token = boundManager.generateToken(MEMBER_ID, "alice");
        String sid = sidOf(token);
        boundManager.invalidate(token);
        logs = LogCapture.of(JwtSessionManager.class);

        assertThrows(InvalidTokenException.class, () -> boundManager.validateToken(token));

        assertTrue(logs.eventCount() > 0, "control: this path logs, so the capture sees the manager's logger");
        assertEquals(java.util.List.of(), logs.eventsContaining(sid), "rejecting a revoked token must not log its id");
    }

    @Test
    void GivenAdminToken_WhenValidateAsMember_ThenNoLogEventCarriesTheSessionId() {
        String adminToken = boundManager.generateAdminToken(ADMIN_ID, "admin");
        String sid = sidOf(adminToken);
        logs = LogCapture.of(JwtSessionManager.class);

        assertThrows(InvalidTokenException.class, () -> boundManager.validateToken(adminToken));

        assertTrue(logs.eventCount() > 0, "control: this path logs, so the capture sees the manager's logger");
        assertEquals(java.util.List.of(), logs.eventsContaining(sid),
                "rejecting a token of the other session kind must not log its id");
    }

    @Test
    void GivenJwtNamingAGuestSessionRow_WhenValidate_ThenNoLogEventCarriesTheSessionId() {
        boundSessions.save(new Session(GUEST_SID, null, T0, T0.plus(Duration.ofMinutes(30))));
        String token = signedToken(MEMBER_ID, GUEST_SID, ROLE_MEMBER);
        logs = LogCapture.of(JwtSessionManager.class);

        assertThrows(InvalidTokenException.class, () -> boundManager.validateToken(token));

        assertTrue(logs.eventCount() > 0, "control: this path logs, so the capture sees the manager's logger");
        assertEquals(java.util.List.of(), logs.eventsContaining(GUEST_SID),
                "the guest session id is the live guest's credential and must not be logged");
    }

    @Test
    void GivenTokenWhoseSubjectDiffersFromItsSessionRow_WhenValidate_ThenNoLogEventCarriesTheSessionId() {
        String memberToken = boundManager.generateToken(MEMBER_ID, "alice");
        String sid = sidOf(memberToken);
        String token = signedToken(OTHER_MEMBER_ID, sid, ROLE_MEMBER);
        logs = LogCapture.of(JwtSessionManager.class);

        assertThrows(InvalidTokenException.class, () -> boundManager.validateToken(token));

        assertTrue(logs.eventCount() > 0, "control: this path logs, so the capture sees the manager's logger");
        assertEquals(java.util.List.of(), logs.eventsContaining(sid),
                "rejecting a token bound to another user's row must not log the row's id");
    }

    @Test
    void GivenAdminRoleOnAMemberSessionRow_WhenValidate_ThenNoLogEventCarriesTheSessionId() {
        String memberToken = boundManager.generateToken(MEMBER_ID, "alice");
        String sid = sidOf(memberToken);
        String token = signedToken(MEMBER_ID, sid, ROLE_ADMIN);
        logs = LogCapture.of(JwtSessionManager.class);

        assertThrows(InvalidTokenException.class, () -> boundManager.validateToken(token));

        assertTrue(logs.eventCount() > 0, "control: this path logs, so the capture sees the manager's logger");
        assertEquals(java.util.List.of(), logs.eventsContaining(sid),
                "rejecting a token whose role does not match its row must not log the row's id");
    }

    // ---- a session-store failure is not a bad token (#187) ----
    // A rejected token means "no" (false, a no-op, empty), but a store that cannot answer must
    // surface: otherwise a member is told their credential is invalid during an outage, and a logout
    // whose delete failed leaves the session, and so the JWT, live while the user sees a sign-out.

    @Test
    void GivenSessionStoreUnreachable_WhenValidateCredentialWithAMemberJwt_ThenTheStoreFailurePropagates() {
        OutageSessionRepository store = new OutageSessionRepository(new MemorySessionRepository(clock));
        JwtSessionManager onStore = new JwtSessionManager(new JwtProperties(SECRET), sessionTtl(60), store, clock);
        String token = onStore.generateToken(MEMBER_ID, "alice");
        store.goDown();

        assertThrows(DataAccessResourceFailureException.class, () -> onStore.validateCredential(token),
                "an unreachable session store must not read as an invalid credential");
    }

    @Test
    void GivenSessionStoreUnreachable_WhenInvalidate_ThenTheStoreFailurePropagates() {
        OutageSessionRepository store = new OutageSessionRepository(new MemorySessionRepository(clock));
        JwtSessionManager onStore = new JwtSessionManager(new JwtProperties(SECRET), sessionTtl(60), store, clock);
        String token = onStore.generateToken(MEMBER_ID, "alice");
        store.goDown();

        assertThrows(DataAccessResourceFailureException.class, () -> onStore.invalidate(token),
                "a logout whose delete failed leaves the session live, so it must not look like it succeeded");
    }

    @Test
    void GivenSessionStoreUnreachable_WhenTryExtractUserId_ThenTheStoreFailurePropagates() {
        OutageSessionRepository store = new OutageSessionRepository(new MemorySessionRepository(clock));
        JwtSessionManager onStore = new JwtSessionManager(new JwtProperties(SECRET), sessionTtl(60), store, clock);
        String token = onStore.generateToken(MEMBER_ID, "alice");
        store.goDown();

        assertThrows(DataAccessResourceFailureException.class, () -> onStore.tryExtractUserId(token),
                "an unreachable session store must not read as a token naming no user");
    }

    @Test
    void GivenJwtSignedWithAnotherKey_WhenValidateCredential_ThenFalse() {
        String token = manager.generateToken(MEMBER_ID, "alice");

        assertFalse(manager.validateCredential(signedWithAnotherKey(token)));
    }

    @Test
    void GivenExpiredMemberJwt_WhenValidateCredential_ThenFalse() {
        JwtSessionManager expiredManager = new JwtSessionManager(new JwtProperties(SECRET), sessionTtl(-1), sessions, clock);
        String token = expiredManager.generateToken(MEMBER_ID, "alice");

        assertFalse(expiredManager.validateCredential(token));
    }

    @Test
    void GivenMalformedToken_WhenInvalidate_ThenNoOpWithoutThrowing() {
        String liveToken = manager.generateToken(MEMBER_ID, "alice");

        assertDoesNotThrow(() -> manager.invalidate("not.a.jwt"));

        assertTrue(manager.validateToken(liveToken), "a malformed token revokes nothing");
    }

    @Test
    void GivenJwtSignedWithAnotherKey_WhenInvalidate_ThenNoOpWithoutThrowing() {
        String liveToken = manager.generateToken(MEMBER_ID, "alice");

        assertDoesNotThrow(() -> manager.invalidate(signedWithAnotherKey(liveToken)));

        assertTrue(manager.validateToken(liveToken), "a token whose signature fails names no session to revoke");
    }

    @Test
    void GivenJwtSignedWithAnotherKey_WhenTryExtractUserId_ThenEmpty() {
        String token = manager.generateToken(MEMBER_ID, "alice");

        assertEquals(Optional.empty(), manager.tryExtractUserId(signedWithAnotherKey(token)));
    }

    @Test
    void GivenExpiredMemberJwt_WhenTryExtractUserId_ThenEmpty() {
        JwtSessionManager expiredManager = new JwtSessionManager(new JwtProperties(SECRET), sessionTtl(-1), sessions, clock);
        String token = expiredManager.generateToken(MEMBER_ID, "alice");

        assertEquals(Optional.empty(), expiredManager.tryExtractUserId(token));
    }

    // ---- helpers ----

    private static final String OTHER_SECRET =
        "another-secret-that-is-also-long-enough-for-hmac-sha-256-but-not-ours";

    /** The same claims, so the same sid, re-signed with a key the manager does not hold. */
    private static String signedWithAnotherKey(String token) {
        io.jsonwebtoken.Claims claims = Jwts.parser().verifyWith(TEST_KEY).build()
                .parseSignedClaims(token).getPayload();
        return Jwts.builder().claims(claims)
                .signWith(Keys.hmacShaKeyFor(OTHER_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    /**
     * A session store that works until {@link #goDown()}, after which every read and delete fails as
     * an unreachable database does.
     */
    private static final class OutageSessionRepository implements SessionRepository {

        private final SessionRepository live;
        private boolean down;

        OutageSessionRepository(SessionRepository live) {
            this.live = live;
        }

        void goDown() {
            down = true;
        }

        private void failIfDown() {
            if (down) {
                throw new DataAccessResourceFailureException("session store unreachable");
            }
        }

        @Override
        public void save(Session session) {
            failIfDown();
            live.save(session);
        }

        @Override
        public Optional<Session> findById(String sessionId) {
            failIfDown();
            return live.findById(sessionId);
        }

        @Override
        public boolean existsByUserId(int userId) {
            failIfDown();
            return live.existsByUserId(userId);
        }

        @Override
        public void delete(String sessionId) {
            failIfDown();
            live.delete(sessionId);
        }

        @Override
        public java.util.List<Session> findExpiredBefore(Instant cutoff) {
            failIfDown();
            return live.findExpiredBefore(cutoff);
        }

        @Override
        public void lockForUpdate(String id) {
            live.lockForUpdate(id);
        }

        @Override
        public void unlock(String id) {
            live.unlock(id);
        }
    }

    /** The sid claim, read with the test's own key so it does not depend on the manager's readers. */
    private static String sidOf(String token) {
        return Jwts.parser().verifyWith(TEST_KEY).clock(() -> Date.from(T0)).build()
                .parseSignedClaims(token).getPayload().get(CLAIM_SID, String.class);
    }

    /** A token signed with the manager's own secret, so only its claims can make it invalid. */
    private static String signedToken(int subject, String sid, String role) {
        return Jwts.builder()
                .subject(String.valueOf(subject))
                .claim(CLAIM_USERNAME, "someone")
                .claim(CLAIM_SID, sid)
                .claim(CLAIM_ROLE, role)
                .issuedAt(Date.from(T0))
                .expiration(Date.from(T0.plus(Duration.ofHours(1))))
                .signWith(TEST_KEY)
                .compact();
    }

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
