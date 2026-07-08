package com.ticketing.system.Infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.SecretKey;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ticketing.system.Core.Application.interfaces.ISessionManager;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.SessionExpiredException;
import com.ticketing.system.Core.Domain.users.ISessionRepository;
import com.ticketing.system.Core.Domain.users.Session;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * jjwt-backed implementation of {@link ISessionManager}. UC-12 / UC-14.
 *
 * <p>Tokens are HS256-signed using {@code jwt.secret} from configuration and
 * expire after {@code session.member-ttl-minutes} (the JWT's exp claim and
 * the Session row's expiresAt are always kept in sync by construction).
 * Revocation (UC-14) is recorded by deleting the corresponding {@link Session}
 * row through {@link ISessionRepository}; revoked tokens fail every reader
 * because the Session existence check lives inside the central
 * {@link #parseClaims} helper.
 *
 * <p>Each issued JWT carries a {@code sid} claim that identifies the matching
 * Session row. {@code subject} is still the userId so callers that only need
 * identity (not validity) can decode the JWT cheaply without touching the
 * repository.
 */
@Component
@Slf4j
public class JwtSessionManager implements ISessionManager {

    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_SID = "sid";
    private static final String CLAIM_ROLE = "role";
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_MEMBER = "MEMBER";

    private final SecretKey signingKey;
    private final long expirationMillis;
    private final ISessionRepository sessions;
    private final Clock clock;

    public JwtSessionManager(
            @Value("${jwt.secret}") String secret,
            @Value("${session.member-ttl-minutes}") long expirationMinutes,
            ISessionRepository sessions,
            Clock clock) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMillis = expirationMinutes * 60 * 1000;
        this.sessions = sessions;
        this.clock = clock;
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

    // Admin sign-in (#290): issue a JWT carrying the ADMIN role so the admin-authorization gate
    // can never be satisfied by a member token (admin ids and member ids share an id space).
    @Override
    public String generateAdminToken(int adminId, String username) {
        Instant now = clock.instant();
        Instant expiry = now.plusMillis(expirationMillis);
        String sid = UUID.randomUUID().toString();
        sessions.save(new Session(sid, adminId, now, expiry));
        return buildJwt(adminId, username, sid, ROLE_ADMIN, now, expiry);
    }

    @Override
    public boolean isAdminToken(String token) {
        try {
            return ROLE_ADMIN.equals(parseClaims(token).get(CLAIM_ROLE, String.class));
        } catch (RuntimeException e) {
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
        if (!session.isMember()) {
            throw new IllegalStateException("cannot issue JWT for a guest session");
        }
        Instant now = clock.instant();
        return buildJwt(session.getUserId(), username, session.getSessionId(), ROLE_MEMBER, now, session.getExpiresAt());
    }

    // UC-12: validate a JWT's signature, expiry, and revocation status. Revocation is checked by verifying that the Session row still exists.
    @Override
    public boolean validateToken(String token) {
        if (token == null || token.isBlank())
            return false;
        parseClaims(token);
        return true;
    }

    // UC-12: validate either a raw sessionId or a JWT. This is the main entry point for authentication checks on incoming requests, so it supports both "raw" (sessionId) and "token" (JWT) credentials for flexibility.
    // The raw path is validated by checking for a non-expired Session row with the given id; the token path routes through the full JWT validation logic (signature, expiry, revocation).
    @Override
    public boolean validateCredential(String credential) {
        if (credential == null || credential.isBlank())
            return false;
        // Token path — if it looks like a JWT, try to validate it as one. If validation fails for any reason, return false (invalid token).
        if (looksLikeJwt(credential)) {
            try {
                return validateToken(credential);
            } catch (Exception e) {
                return false;
            }
        }
        // Raw sessionId path — direct repository lookup. returns true if a non-expired Session with the given id exists, false otherwise.
        return sessions.findById(credential)
                .filter(s -> !s.isExpiredAt(clock.instant()))
                .isPresent();
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
        return Integer.parseInt(parseClaims(token).getSubject());
    }

    @Override
    public String extractUsername(String token) {
        return parseClaims(token).get(CLAIM_USERNAME, String.class);
    }

    @Override
    public String extractSessionId(String token) {
        return parseClaims(token).get(CLAIM_SID, String.class);
    }

    @Override
    public long extractExpiration(String token) {
        return parseClaims(token).getExpiration().getTime();
    }

    @Override
    public boolean isExpired(String token) {
        try {
            parseClaims(token);
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
        try {
            // Use raw parsing — we want to delete the session even if the JWT is
            // signature-invalid or already-expired (idempotent revocation).
            Claims claims = parseJwtUnchecked(token);
            String sid = claims.get(CLAIM_SID, String.class);
            if (sid != null && !sid.isBlank()) {
                sessions.delete(sid);
                log.debug("session deleted sid={}", sid);
            }
        } catch (Exception e) {
            // Malformed input — nothing to invalidate. Idempotent.
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
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Decodes + verifies a JWT and also enforces that the corresponding Session
     * row still exists. Every reader (validate, extract*, isExpired) routes
     * through here so revocation is uniformly respected.
     */
    private Claims parseClaims(String token) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(signingKey)
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
        if (sessions.findById(sid).isEmpty()) {
            log.debug("rejected revoked token sid={}", sid);
            throw new InvalidTokenException("session not found");
        }
        return claims;
    }

    /**
     * Decodes a JWT without enforcing signature or expiry semantics — only
     * used by {@link #invalidate(String)} to recover the session id from
     * tokens that may already be expired but should still be revoked
     * idempotently.
     */
    private Claims parseJwtUnchecked(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
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
