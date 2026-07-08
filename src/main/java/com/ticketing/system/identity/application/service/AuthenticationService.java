package com.ticketing.system.identity.application.service;
import com.ticketing.system.notifications.application.service.NotificationDispatchService; // transitional: cross-context call, to be rewired via an inbound port later

import java.util.List;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.LoginDTO;
import com.ticketing.system.shared.dto.GuestSessionDTO;
import com.ticketing.system.shared.dto.LoginRequestDTO;
import com.ticketing.system.shared.dto.LogoutRequestDTO;
import com.ticketing.system.shared.dto.NotificationDTO;
import com.ticketing.system.shared.dto.RefreshTokenRequestDTO;
import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.identity.application.port.out.CartRestorationPort;
import com.ticketing.system.identity.application.port.out.PasswordHasher;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.shared.metrics.ISystemMetrics;
import com.ticketing.system.shared.metrics.MetricType;
import com.ticketing.system.shared.exception.AuthenticationFailedException;
import com.ticketing.system.shared.exception.AccountLockedException;
import com.ticketing.system.identity.domain.Admin;
import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.shared.exception.DuplicateEmailException;
import com.ticketing.system.shared.exception.DuplicateUsernameException;
import com.ticketing.system.shared.exception.GuestSessionRequiredException;
import com.ticketing.system.shared.exception.InvalidEmailFormatException;
import com.ticketing.system.shared.exception.WeakPasswordException;
import com.ticketing.system.identity.application.port.out.SessionRepository;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.Session;
import com.ticketing.system.identity.domain.User;


/**
 * Application service for the auth slice — registration (UC-11), login
 * (UC-12), and logout (UC-14), plus guest-session lifecycle.
 *
 * <p>
 * Per the spec, a visitor is always a Guest first: they call
 * {@link #startGuestSession()} to mint a Session row, then optionally
 * {@link #register(RegisterRequestDTO)} (which keeps them Guest) and
 * {@link #login(LoginRequestDTO)} (which promotes their existing Session
 * to a Member session in place).
 */
@Service
@Slf4j
public class AuthenticationService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final SessionManager sessionManager;
    private final CartRestorationPort cartRestorationPort; // for UC-13 cart restore/merge (sales-owned adapter)
    private final NotificationDispatchService notificationDispatchService; // for UC-37 notification flush
    private final SessionRepository sessionRepository;
    private final ISystemMetrics systemMetrics;
    private final Clock clock;
    private final long guestIdleMinutes;
    private final long memberTtlMinutes;

    // Admin sign-in (#290).
    private final AdminRepository adminRepository;
    // Brute-force lockout (SLR.2 / #148) — shared by member AND admin sign-in. In-memory for
    // V1/V2 (a restart clears it). Keys are namespaced by pool ("m:"/"a:") so the two forms are
    // rate-limited independently.
    private final int lockoutMaxAttempts;    // 0 disables the lockout entirely
    private final long lockoutLockMinutes;
    private final ConcurrentMap<String, LoginAttempts> loginAttempts = new ConcurrentHashMap<>();

    public AuthenticationService(
            UserRepository userRepository,
            PasswordHasher passwordHasher,
            SessionManager sessionManager,
            CartRestorationPort cartRestorationPort,
            NotificationDispatchService notificationDispatchService,
            SessionRepository sessionRepository,
            ISystemMetrics systemMetrics,
            Clock clock,
            AdminRepository adminRepository,
            @Value("${session.guest-idle-timeout-minutes}") long guestIdleMinutes,
            @Value("${session.member-ttl-minutes}") long memberTtlMinutes,
            @Value("${auth.lockout.max-attempts:5}") int lockoutMaxAttempts,
            @Value("${auth.lockout.lock-minutes:15}") long lockoutLockMinutes) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.sessionManager = sessionManager;
        this.cartRestorationPort = cartRestorationPort;
        this.notificationDispatchService = notificationDispatchService;
        this.sessionRepository = sessionRepository;
        this.systemMetrics = systemMetrics;
        this.clock = clock;
        this.adminRepository = adminRepository;
        this.guestIdleMinutes = guestIdleMinutes;
        this.memberTtlMinutes = memberTtlMinutes;
        this.lockoutMaxAttempts = lockoutMaxAttempts;
        this.lockoutLockMinutes = lockoutLockMinutes;
    }

    /** Delegates to {@link SessionManager#extractUserId}. */
    @Transactional(readOnly = true)
    public int extractUserId(String token) {
        return sessionManager.extractUserId(token);
    }

    /** Delegates to {@link SessionManager#validateToken}. */
    @Transactional(readOnly = true)
    public boolean validateToken(String token) {
        return sessionManager.validateToken(token);
    }

    // ------------------------------------------------------------------
    // Guest session lifecycle
    // ------------------------------------------------------------------

    /**
     * Mints a new Guest session. Entry point for any visitor before
     * register/login.
     */
    @Transactional
    public GuestSessionDTO startGuestSession() {
        Instant now = clock.instant();
        Instant expiry = now.plus(guestIdleMinutes, ChronoUnit.MINUTES);
        String sid = UUID.randomUUID().toString();
        sessionRepository.save(new Session(sid, null, now, expiry));
        systemMetrics.record(MetricType.VISITOR_ENTRY);
        log.info("guest session started sid={}", sid);
        return new GuestSessionDTO(sid, now);
    }

    /**
     * Explicitly ends a Guest session. Idempotent — unknown / null / blank
     * input is a no-op. Attached cart cleanup is the sweeper's
     * responsibility (Phase 5).
     */
    @Transactional
    public void endGuestSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank())
            return;
        sessionRepository.delete(sessionId);
        systemMetrics.record(MetricType.VISITOR_EXIT);
        log.info("guest session ended sid={}", sessionId);
    }

    // ------------------------------------------------------------------
    // Register (UC-11)
    // ------------------------------------------------------------------

    /**
     * Registers a new Member. UC-11.
     *
     * <p>
     * Per II.1.4 / D10a: requires an active Guest session (via
     * {@link RegisterRequestDTO#guestSessionId()}). The session intentionally remains
     * Guest after register — {@link #login(LoginRequestDTO)} is the explicit promotion
     * step to Member-Visitor.
     *
     * @throws InvalidEmailFormatException email fails format check
     * @throws WeakPasswordException       password fails strength rules
     * @throws DuplicateUsernameException  username already taken
     * @throws DuplicateEmailException     email already registered
     */
    @Transactional
    public void register(RegisterRequestDTO request) {
        // 1. Cheap format validation first — bots / garbage fail before any IO.
        validateEmail(request.email());
        validatePassword(request.rawPassword());

        // 2. Guest session must exist and still be a Guest.
        Session session = requireActiveGuestSession(request.guestSessionId());

        // 3. Uniqueness.
        if (userRepository.existsByUsername(request.username())) {
            throw new DuplicateUsernameException(request.username());
        }
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw new DuplicateEmailException(request.email());
        }

        // 4. Create user.
        String hashed = passwordHasher.hash(request.rawPassword());
        User user = new User(userRepository.nextId(), request.username(), request.email(), hashed, request.age());
        // throws IllegalArgumentException("Age cannot be negative") if age < 0.
        userRepository.save(user);

        // 5. Session stays Guest — just touch the activity timestamp.
        session.touch(clock.instant());
        sessionRepository.save(session);
        systemMetrics.record(MetricType.REGISTRATION);

        log.info("member registered: username={} id={}", user.getUsername(), user.getUserId());
    }

    private static void validateEmail(String email) {
        if (email == null || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new InvalidEmailFormatException(email);
        }
    }

    private static void validatePassword(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < 8) {
            throw new WeakPasswordException("must be at least 8 characters");
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < rawPassword.length(); i++) {
            char c = rawPassword.charAt(i);
            if (Character.isLetter(c))
                hasLetter = true;
            else if (Character.isDigit(c))
                hasDigit = true;
        }
        if (!hasLetter || !hasDigit) {
            throw new WeakPasswordException("must contain at least one letter and one digit");
        }
    }

    // ------------------------------------------------------------------
    // Login (UC-12) — Guest session promoted in place
    // ------------------------------------------------------------------

    /**
     * Authenticates a Member and promotes their Guest session to a Member
     * session in place. UC-12.
     *
     * <p>
     * "Unknown username" and "wrong password" raise the same exception to avoid
     * username enumeration via the response.
     *
     * @throws GuestSessionRequiredException no / unknown / expired / non-guest
     *                                       sessionId
     * @throws AuthenticationFailedException invalid credentials
     */
    @Transactional
    public LoginDTO login(LoginRequestDTO request) {
        // 1. Guest session must exist.
        Session session = requireActiveGuestSession(request.guestSessionId());

        // 1b. Brute-force lockout (SLR.2 #148) — same policy as admin sign-in.
        String lockKey = lockoutKey("m", request.username());
        if (isLockedOut(lockKey)) {
            log.warn("MEMBER AUTH BLOCKED · username={} · reason=locked-out · at={}",
                    request.username(), clock.instant());
            throw new AccountLockedException(
                    "Too many failed attempts. Try again in about " + lockoutLockMinutes + " minutes.");
        }

        // 2. Authenticate (same exception for both unknown-user and wrong-password).
        User user;
        try {
            user = userRepository.findByUsername(request.username())
                    .orElseThrow(AuthenticationFailedException::new);
            if (!passwordHasher.matches(request.rawPassword(), user.getPasswordHash())) {
                throw new AuthenticationFailedException();
            }
        } catch (AuthenticationFailedException e) {
            int failures = recordFailure(lockKey);
            log.warn("MEMBER AUTH FAILED · username={} · failures={}", request.username(), failures);
            throw e;
        }
        clearFailures(lockKey);

        // 3. Promote — same sessionId, userId set, expiry bumped to Member TTL.
        Instant memberExpiry = clock.instant().plus(memberTtlMinutes, ChronoUnit.MINUTES);
        session.promoteTo(user.getUserId(), memberExpiry);
        sessionRepository.save(session);

        // 4. D9a — cart handling on promotion, delegated to the sales-owned adapter
        //    (runs inside this @Transactional login, so it stays atomic with the promotion).
        cartRestorationPort.mergeGuestCartOnPromotion(session.getSessionId(), user.getUserId());

        // 5. Issue a JWT bound to the existing (now-Member) session.
        String token = sessionManager.generateTokenForSession(session, user.getUsername());
        long expiresAt = sessionManager.extractExpiration(token);

        log.info("member logged in: username={} id={} sid={}",
                user.getUsername(), user.getUserId(), session.getSessionId());

        AuthTokenDTO authTokenDTO = new AuthTokenDTO(token, expiresAt, user.getUserId(), user.getUsername());
        ActiveOrderDTO activeOrderDTO = cartRestorationPort.restoreActiveOrder(user.getUserId());
        List<NotificationDTO> notifications = notificationDispatchService.deliverPending(user.getUserId());
        return new LoginDTO(authTokenDTO, activeOrderDTO, notifications);
    }

    /**
     * Admin sign-in (#290). Authenticates against the persisted admin pool (never the member pool
     * — disjoint-pool rule), applies the shared lock-after-N policy, audit-logs every failure, and
     * issues an ADMIN-role JWT so the backend admin gate can authorize it.
     */
    // Read-WRITE: generateAdminToken persists a brand-new Session row. A read-only transaction
    // would leave Hibernate in MANUAL flush mode, so the assigned-id INSERT would never flush
    // under the jpa profile and every admin token would fail validation as "session not found"
    // (member login is unaffected — it promotes an already-persisted guest session).
    @Transactional
    public AuthTokenDTO signInAsAdmin(String username, String rawPassword) {
        String lockKey = lockoutKey("a", username);

        if (isLockedOut(lockKey)) {
            log.warn("ADMIN AUTH BLOCKED · username={} · reason=locked-out · at={}", username, clock.instant());
            throw new AccountLockedException(
                    "Too many failed attempts. Try again in about " + lockoutLockMinutes + " minutes.");
        }

        Admin admin = adminRepository.findByUsername(username == null ? "" : username.trim());
        if (admin == null || !passwordHasher.matches(rawPassword, admin.getPasswordHash())) {
            int failures = recordFailure(lockKey);
            log.warn("ADMIN AUTH FAILED · username={} · reason={} · failures={} · at={}",
                    username, admin == null ? "unknown-admin" : "bad-password", failures, clock.instant());
            throw new AuthenticationFailedException();
        }

        clearFailures(lockKey);
        String token = sessionManager.generateAdminToken(admin.getId(), admin.getUsername());
        long expiresAt = sessionManager.extractExpiration(token);
        log.info("ADMIN AUTH OK · username={} · id={}", admin.getUsername(), admin.getId());
        return new AuthTokenDTO(token, expiresAt, admin.getId(), admin.getUsername(), true);
    }

    // ---- shared brute-force lockout (SLR.2 #148) — member + admin sign-in ----

    private static String lockoutKey(String pool, String username) {
        return pool + ":" + (username == null ? "" : username.trim().toLowerCase());
    }

    /** True while the key is inside an active lock window; clears an expired lock for a fresh start. */
    private boolean isLockedOut(String key) {
        if (lockoutMaxAttempts <= 0) {
            return false;
        }
        LoginAttempts a = loginAttempts.get(key);
        if (a == null || a.lockedUntil() == null) {
            return false;
        }
        if (clock.instant().isBefore(a.lockedUntil())) {
            return true;
        }
        loginAttempts.remove(key); // lock expired — fresh window
        return false;
    }

    /** Records a failed attempt; locks the key once it reaches the configured threshold. */
    private int recordFailure(String key) {
        if (lockoutMaxAttempts <= 0) {
            return 0;
        }
        LoginAttempts updated = loginAttempts.compute(key, (k, prev) -> {
            int failures = (prev == null ? 0 : prev.failures()) + 1;
            Instant lockedUntil = failures >= lockoutMaxAttempts
                    ? clock.instant().plus(lockoutLockMinutes, ChronoUnit.MINUTES)
                    : null;
            return new LoginAttempts(failures, lockedUntil);
        });
        return updated.failures();
    }

    private void clearFailures(String key) {
        loginAttempts.remove(key);
    }

    private record LoginAttempts(int failures, Instant lockedUntil) { }

    // ------------------------------------------------------------------
    // Logout (UC-14)
    // ------------------------------------------------------------------

    /**
     * Terminates the authenticated session by deleting the underlying
     * Session row (via {@link SessionManager#invalidate}). UC-14 / D8 (L1).
     *
     * <p>
     * Per II.3.1 the session state downgrades back to Guest-Visitor — to act again the
     * visitor must {@link #startGuestSession()} for a fresh sessionId (the old one is dead).
     * The Member cart is not touched here: it persists by userId (II.3.0.1) and is restored
     * on the next login (UC-13 / D9a, via
     * {@link CartRestorationPort#mergeGuestCartOnPromotion}).
     *
     * <p>
     * Idempotent: logout with a {@code null} / blank / already-revoked token is a no-op.
     */
    @Transactional
    public void logout(LogoutRequestDTO request) {
        Optional<Integer> userIdOpt = sessionManager.tryExtractUserId(request.token());
        sessionManager.invalidate(request.token());
        userIdOpt.ifPresent(userId -> log.info("member logged out: id={}", userId));
    }

    // Deferred from V1. Not in the UC-12 spec, no acceptance test gates it, and a
    // proper refresh-token flow needs a separate token store + revocation (overlaps
    // with UC-14 logout). Revisit if a later UC requires it.
    public AuthTokenDTO refreshToken(RefreshTokenRequestDTO request) {
        throw new UnsupportedOperationException("refresh-token flow deferred from V1");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Returns the active Guest Session for {@code sessionId}, or throws
     * {@link GuestSessionRequiredException} with a specific reason.
     */
    private Session requireActiveGuestSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new GuestSessionRequiredException(
                    "operation requires an active guest session — call startGuestSession() first");
        }
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new GuestSessionRequiredException("guest session not found"));
        if (session.isMember()) {
            throw new GuestSessionRequiredException("session is not a guest session");
        }
        if (session.isExpiredAt(clock.instant())) {
            throw new GuestSessionRequiredException("guest session expired");
        }
        return session;
    }
}
