package com.ticketing.system.identity.adapter.out.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.SecretKey;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.identity.config.JwtProperties;
import com.ticketing.system.identity.config.SessionProperties;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.SessionExpiredException;
import com.ticketing.system.identity.application.port.out.SessionRepository;
import com.ticketing.system.identity.domain.Session;
import com.ticketing.system.identity.domain.SessionKind;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * jjwt-backed implementation of {@link SessionManager}. UC-12 / UC-14.
 *
 * <p>Tokens are HMAC-signed with {@code jwt.secret} ({@link JwtProperties}). The constructor refuses a
 * secret shorter than {@value JwtProperties#MIN_SECRET_LENGTH} characters (256 bits) with an
 * {@link IllegalStateException} that names the key but never the secret, so the boot stops on it.
 * jjwt picks HS256, HS384 or HS512 from the secret's length (the
 * committed 70-byte dev default gives HS512). Tokens expire after {@code session.member-ttl-minutes},
 * checked against the injected clock (the JWT's exp claim and the Session row's expiresAt are
 * always kept in sync by construction).
 * Revocation (UC-14) is recorded by deleting the corresponding {@link Session}
 * row through {@link SessionRepository}; revoked tokens fail every reader
 * because the Session existence check lives inside the central
 * {@link #parseBound} helper.
 *
 * <p>Each issued JWT carries a {@code sid} claim that identifies the matching
 * Session row, a {@code subject} that is the member or admin id, and a {@code role} claim.
 * Member ids and admin ids share one integer space, so a token is accepted only when its
 * subject is the row's user id and its role matches the row's {@link SessionKind} (ADR-012). Each
 * reader then accepts one kind only, as {@link SessionManager} sets out.
 */
@Component
@Slf4j
public class JwtSessionManager implements SessionManager {

    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_SID = "sid";
    private static final String CLAIM_ROLE = "role";
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_MEMBER = "MEMBER";
    // One reason for a missing row and a row of the wrong kind, so a member reader rejects an
    // admin token exactly as it rejects a revoked one.
    private static final String SESSION_NOT_FOUND = "session not found";

    private final SecretKey signingKey;
    private final long expirationMillis;
    private final SessionRepository sessions;
    private final Clock clock;
    // jjwt checks exp against the system clock unless told otherwise; the token's exp is stamped
    // from the injected clock, so both sides must read the same one (ADR-008).
    private final io.jsonwebtoken.Clock jwtClock;

    /**
     * @param jwtProperties     the signing secret
     * @param sessionProperties the member session lifetime, which every token's expiry follows
     * @param sessions          the session rows tokens are bound to
     * @param clock             the source of "now" for issuing and checking expiry
     * @throws IllegalStateException the secret is shorter than {@value JwtProperties#MIN_SECRET_LENGTH}
     *                               characters; the message names {@code jwt.secret}, never its value
     */
    public JwtSessionManager(
            JwtProperties jwtProperties,
            SessionProperties sessionProperties,
            SessionRepository sessions,
            Clock clock) {
        this.signingKey = Keys.hmacShaKeyFor(signingSecretOf(jwtProperties).getBytes(StandardCharsets.UTF_8));
        this.expirationMillis = Duration.ofMinutes(sessionProperties.memberTtlMinutes()).toMillis();
        this.sessions = sessions;
        this.clock = clock;
        this.jwtClock = () -> Date.from(clock.instant());
    }

    // Checked here rather than by a binding constraint on JwtProperties, whose failure report prints
    // the rejected value; so the message names the key and the minimum, never the secret or any part
    // of it. Counting characters is enough: each one is at least one byte in UTF-8, so jjwt's own
    // WeakKeyException, which would not name the key, cannot follow.
    private static String signingSecretOf(JwtProperties jwtProperties) {
        String secret = jwtProperties.secret();
        if (secret == null || secret.length() < JwtProperties.MIN_SECRET_LENGTH) {
            throw new IllegalStateException("jwt.secret (env JWT_SECRET) must be at least "
                    + JwtProperties.MIN_SECRET_LENGTH + " characters, the 256 bits HS256 signing needs");
        }
        return secret;
    }


    // UC-12: issue a new JWT for a user session. A Session row is created to track the session and enable revocation.
    @Override
    public String generateToken(int userId, String username) {
        Instant now = clock.instant();
        Instant expiry = now.plusMillis(expirationMillis);
        String sid = UUID.randomUUID().toString();
        sessions.save(new Session(sid, userId, now, expiry));
        return buildJwt(userId, username, sid, ROLE_MEMBER, now, expiry);
    }

    // Admin sign-in (#290): the row is an Admin session and the JWT carries the ADMIN role, so the
    // token passes only the admin readers (admin ids and member ids share an id space).
    @Override
    public String generateAdminToken(int adminId, String username) {
        Instant now = clock.instant();
        Instant expiry = now.plusMillis(expirationMillis);
        String sid = UUID.randomUUID().toString();
        sessions.save(Session.admin(sid, adminId, now, expiry));
        return buildJwt(adminId, username, sid, ROLE_ADMIN, now, expiry);
    }

    @Override
    public boolean isAdminToken(String token) {
        try {
            parseClaimsOfKind(token, SessionKind.ADMIN);
            return true;
        } catch (InvalidTokenException | SessionExpiredException e) {
            return false;
        }
    }

    // UC-12: issue a new JWT for an existing session (e.g. after password login to a guest session).
    // The Session row is reused and the JWT is reissued with the same expiry.
    @Override
    public String generateTokenForSession(Session session, String username) {
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        if (session.getKind() != SessionKind.MEMBER) {
            throw new IllegalStateException("a member JWT is issued for a member session only");
        }
        Instant now = clock.instant();
        return buildJwt(session.getUserId(), username, session.getSessionId(), ROLE_MEMBER, now, session.getExpiresAt());
    }

    @Override
    public boolean validateToken(String token) {
        if (token == null || token.isBlank())
            return false;
        parseClaimsOfKind(token, SessionKind.MEMBER);
        return true;
    }

    @Override
    public boolean validateAdminToken(String token) {
        if (token == null || token.isBlank())
            return false;
        parseClaimsOfKind(token, SessionKind.ADMIN);
        return true;
    }

    // UC-12: validate a buyer credential, either a member JWT or a guest's raw sessionId.
    // The token path routes through the member validation (signature, expiry, revocation, kind); the
    // raw path accepts only a live Guest row, since a member's or admin's credential is its JWT.
    @Override
    public boolean validateCredential(String credential) {
        if (credential == null || credential.isBlank())
            return false;
        if (looksLikeJwt(credential)) {
            try {
                return validateToken(credential);
            } catch (InvalidTokenException | SessionExpiredException e) {
                return false;
            }
        }
        return isLiveGuestRow(credential);
    }

    private boolean isLiveGuestRow(String sessionId) {
        return sessions.findById(sessionId)
                .filter(Session::isGuest)
                .filter(s -> !s.isExpiredAt(clock.instant()))
                .isPresent();
    }

    // A token is never a guest row's id (row ids are UUIDs), so the lookup alone refuses one.
    @Override
    public boolean validateGuestSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank())
            return false;
        return isLiveGuestRow(sessionId);
    }

    /** Cheap structural check: JWTs are {@code header.payload.signature} (two dots). */
    private static boolean looksLikeJwt(String s) {
        int firstDot = s.indexOf('.');
        if (firstDot <= 0) return false;
        int secondDot = s.indexOf('.', firstDot + 1);
        return secondDot > firstDot;
    }

    @Override
    public int extractUserId(String token) {
        return Integer.parseInt(parseClaimsOfKind(token, SessionKind.MEMBER).getSubject());
    }

    @Override
    public int extractAdminId(String token) {
        return Integer.parseInt(parseClaimsOfKind(token, SessionKind.ADMIN).getSubject());
    }

    @Override
    public String extractUsername(String token) {
        return parseClaimsOfKind(token, SessionKind.MEMBER).get(CLAIM_USERNAME, String.class);
    }

    @Override
    public String extractSessionId(String token) {
        return parseBoundClaims(token).get(CLAIM_SID, String.class);
    }

    @Override
    public long extractExpiration(String token) {
        return parseBoundClaims(token).getExpiration().getTime();
    }

    @Override
    public boolean isExpired(String token) {
        try {
            parseBoundClaims(token);
            return false;
        } catch (SessionExpiredException e) {
            return true;
        } catch (InvalidTokenException e) {
            // Malformed / revoked tokens cannot be checked for expiry; treat as unusable.
            return true;
        }
    }

    @Override
    public void invalidate(String token) {
        if (token == null || token.isBlank()) return;
        Claims claims;
        try {
            // Expiry-tolerant parsing: an expired token still revokes its session (idempotent
            // revocation). A token whose signature fails names no session we can trust, so it
            // revokes nothing.
            claims = parseJwtUnchecked(token);
        } catch (JwtException | IllegalArgumentException e) {
            return;
        }
        String sid = claims.get(CLAIM_SID, String.class);
        if (sid != null && !sid.isBlank()) {
            sessions.delete(sid);
            log.debug("session deleted");
        }
    }

    @Override
    public boolean isOnline(int userId) {
        return sessions.existsByUserId(userId);
    }

    @Override
    public Optional<Integer> tryExtractUserId(String token) {
        try {
            return Optional.of(extractUserId(token));
        } catch (InvalidTokenException | SessionExpiredException e) {
            return Optional.empty();
        }
    }

    /**
     * {@link #parseBoundClaims}, and the row must also be of {@code kind}. A row of the other
     * kind fails as a missing row does, so neither reader family can tell the other's tokens
     * from revoked ones.
     */
    private Claims parseClaimsOfKind(String token, SessionKind kind) {
        BoundToken bound = parseBound(token);
        if (bound.session().getKind() != kind) {
            log.debug("rejected token of another session kind, wanted={} found={}", kind, bound.session().getKind());
            throw new InvalidTokenException(SESSION_NOT_FOUND);
        }
        return bound.claims();
    }

    /** The claims of a token that {@link #parseBound} accepts, whatever its kind. */
    private Claims parseBoundClaims(String token) {
        return parseBound(token).claims();
    }

    /**
     * Decodes + verifies a JWT and binds it to the Session row it names: the row must exist, must
     * not be a Guest row (a guest's credential is its raw id), must belong to the token's subject,
     * must be of the kind the role claim states, and must be unexpired on the injected clock.
     * Every reader routes through here, so revocation is uniformly respected.
     *
     * @throws InvalidTokenException malformed, bad signature, revoked, or not bound to its row
     * @throws SessionExpiredException past the {@code exp} claim or the row's expiry
     */
    private BoundToken parseBound(String token) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .clock(jwtClock)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new SessionExpiredException();
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("token validation failed");
        }

        String sid = claims.get(CLAIM_SID, String.class);
        if (sid == null || sid.isBlank()) {
            log.debug("rejected token without session id");
            throw new InvalidTokenException("token missing session id");
        }
        Session session = sessions.findById(sid).orElseThrow(() -> {
            log.debug("rejected revoked token");
            return new InvalidTokenException(SESSION_NOT_FOUND);
        });
        if (session.isGuest()) {
            log.debug("rejected token naming a guest session");
            throw new InvalidTokenException("token names a guest session");
        }
        if (!String.valueOf(session.getUserId()).equals(claims.getSubject())) {
            log.debug("rejected token whose subject is not its session's user");
            throw new InvalidTokenException("token subject does not match its session");
        }
        if (kindOfRole(claims.get(CLAIM_ROLE, String.class)) != session.getKind()) {
            log.debug("rejected token whose role does not match its session's kind");
            throw new InvalidTokenException("token role does not match its session");
        }
        if (session.isExpiredAt(clock.instant())) {
            throw new SessionExpiredException();
        }
        return new BoundToken(claims, session);
    }

    /** The session kind a role claim stands for, or {@code null} for an unknown or missing role. */
    private static SessionKind kindOfRole(String role) {
        if (ROLE_MEMBER.equals(role)) {
            return SessionKind.MEMBER;
        }
        if (ROLE_ADMIN.equals(role)) {
            return SessionKind.ADMIN;
        }
        return null;
    }

    /** A verified token and the Session row it is bound to. */
    private record BoundToken(Claims claims, Session session) { }

    /**
     * Decodes a JWT and verifies its signature, but tolerates an expired exp claim — only
     * used by {@link #invalidate(String)} to recover the session id from tokens that may
     * already be expired but should still be revoked idempotently.
     *
     * @throws JwtException if the token is malformed or its signature does not verify
     */
    private Claims parseJwtUnchecked(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .clock(jwtClock)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            return e.getClaims();
        }
    }

    private String buildJwt(int userId, String username, String sid, String role,
            Instant issuedAt, Instant expiresAt) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_SID, sid)
                .claim(CLAIM_ROLE, role)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
    }
}
