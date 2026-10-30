package com.ticketing.system.identity.application.service;
import com.ticketing.system.notifications.application.service.NotificationDispatchService; // transitional: cross-context call, to be rewired via an inbound port later

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;
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
import com.ticketing.system.identity.application.service.LoginAttemptTracker.Admission;
import com.ticketing.system.identity.application.service.LoginAttemptTracker.Pool;
import com.ticketing.system.identity.config.IdentityProperties;
import com.ticketing.system.identity.config.LockoutProperties;
import com.ticketing.system.identity.config.SessionProperties;
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
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.ReservedUsernameException;
import com.ticketing.system.shared.exception.SessionExpiredException;
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

    /**
     * The longest email accepted: RFC 5321 caps a forward path at 254 characters. Checked before
     * {@link #EMAIL_PATTERN}, whose backtracking grows with the input. Public so the registration
     * form can cap its field at the same length.
     */
    public static final int MAX_EMAIL_LENGTH = 254;

    /** The most of a password BCrypt reads; {@code PasswordHasher.hash} throws for a longer one. */
    static final int MAX_PASSWORD_UTF8_BYTES = 72;

    private static final int MIN_PASSWORD_LENGTH = 8;

    /**
     * Verified against when the username names no account, so an unknown username costs the same
     * BCrypt work as a known one and the response time does not tell which usernames exist. A real
     * hash at the cost {@code BcryptPasswordHasher} uses (10), of a random secret that was
     * discarded, so no password matches it. Regenerate it if that cost changes.
     */
    private static final String DUMMY_HASH = "$2a$10$m9cx4gEdeGJXWUzDeiQIB.l5tExjDwjuLGriou9K2t7N6Eg2ya6bG";

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
    // Brute-force lockout (SLR.2 / #148), shared by member and admin sign-in.
    private final LoginAttemptTracker loginAttempts;
    // Names no member may register (#76): identity.reserved-usernames plus the default admin's
    // username, trimmed and lower-cased.
    private final Set<String> reservedUsernames;

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
            SessionProperties sessionProperties,
            LockoutProperties lockoutProperties,
            IdentityProperties identityProperties) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.sessionManager = sessionManager;
        this.cartRestorationPort = cartRestorationPort;
        this.notificationDispatchService = notificationDispatchService;
        this.sessionRepository = sessionRepository;
        this.systemMetrics = systemMetrics;
        this.clock = clock;
        this.adminRepository = adminRepository;
        this.guestIdleMinutes = sessionProperties.guestIdleTimeoutMinutes();
        this.memberTtlMinutes = sessionProperties.memberTtlMinutes();
        this.loginAttempts = new LoginAttemptTracker(lockoutProperties, clock);
        this.reservedUsernames = reservedUsernamesOf(identityProperties);
    }

    private static Set<String> reservedUsernamesOf(IdentityProperties identity) {
        Stream<String> configured = identity.reservedUsernames().stream();
        return Stream.concat(configured, Stream.ofNullable(identity.platformAdminUsername()))
                .filter(name -> name != null && !name.isBlank())
                .map(AuthenticationService::normalizedUsername)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalizedUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether {@code username} is one no member may register (#76): a name from
     * {@code identity.reserved-usernames} or the default admin's username, ignoring case and
     * surrounding whitespace. {@link #register} refuses such a name; the UI asks this to say so
     * before the visitor submits.
     *
     * @param username the requested username, as typed
     * @return {@code true} if it is reserved; {@code false} for any other name, {@code null} included
     */
    public boolean isUsernameReserved(String username) {
        return username != null && reservedUsernames.contains(normalizedUsername(username));
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

    /**
     * Answers "is this stored token's session still live, and whose is it?" without throwing for the
     * expected no. It checks what {@link #validateToken} checks (signature, expiry, and that the token
     * names a live member session row), so an admin token names no member.
     *
     * <p>{@link #validateToken} throws for an expired or revoked token. For a caller that holds a token
     * and only needs to know whether it is still live (the UI, on every request), that is an ordinary
     * answer rather than a failure; letting it throw would also have {@code LoggingAspect} log a WARN
     * for every such check.
     *
     * @param token a signed-in visitor's JWT
     * @return the token's user id while it validates; empty when it has expired, is malformed, carries
     *     a bad signature, has been revoked, is not a member token, or is {@code null}/blank
     * @throws RuntimeException any other validation failure (e.g. the session store is unreachable),
     *     propagated unchanged
     */
    @Transactional(readOnly = true)
    public OptionalInt liveMemberId(String token) {
        try {
            return sessionManager.validateToken(token)
                    ? OptionalInt.of(sessionManager.extractUserId(token))
                    : OptionalInt.empty();
        } catch (SessionExpiredException | InvalidTokenException e) {
            return OptionalInt.empty();
        }
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
        log.info("guest session started");
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
        log.info("guest session ended");
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
     * @throws InvalidEmailFormatException email is longer than {@value #MAX_EMAIL_LENGTH} characters
     *                                     or fails the format check
     * @throws WeakPasswordException       password fails strength rules, or is longer than
     *                                     {@value #MAX_PASSWORD_UTF8_BYTES} bytes in UTF-8
     * @throws ReservedUsernameException   username is reserved ({@link #isUsernameReserved})
     * @throws DuplicateUsernameException  username already taken, including by a registration that
     *                                     committed after this one's check
     * @throws DuplicateEmailException     email already registered
     */
    @Transactional
    public void register(RegisterRequestDTO request) {
        // 1. Cheap format validation first — bots / garbage fail before any IO.
        validateEmail(request.email());
        validatePassword(request.rawPassword());

        // 2. Guest session must exist and still be a Guest.
        Session session = requireActiveGuestSession(request.guestSessionId());

        // 3. Uniqueness. The check gives the usual answer early; two registrations that pass it at
        //    once are settled by save, which refuses the second with DuplicateUsernameException.
        if (isUsernameReserved(request.username())) {
            throw new ReservedUsernameException(request.username());
        }
        if (userRepository.existsByUsername(request.username())) {
            throw new DuplicateUsernameException(request.username());
        }
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw new DuplicateEmailException();
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
        if (email == null || email.length() > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new InvalidEmailFormatException();
        }
    }

    private static void validatePassword(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new WeakPasswordException("must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_UTF8_BYTES) {
            throw new WeakPasswordException("must be at most " + MAX_PASSWORD_UTF8_BYTES
                    + " bytes in UTF-8, the most BCrypt reads; a character outside ASCII takes two or more");
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
     * "Unknown username" and "wrong password" raise the same exception, after the same
     * password-hash work, to avoid username enumeration via the response or its timing.
     *
     * @throws GuestSessionRequiredException no / unknown / expired / non-guest
     *                                       sessionId
     * @throws AccountLockedException        the username's attempt window already holds
     *                                       {@code auth.lockout.max-attempts} attempts; the
     *                                       password is not checked
     * @throws AuthenticationFailedException invalid credentials
     */
    @Transactional
    public LoginDTO login(LoginRequestDTO request) {
        // 1. Guest session must exist.
        Session session = requireActiveGuestSession(request.guestSessionId());

        // 1b. Brute-force lockout (SLR.2 #148) — same policy as admin sign-in. The attempt is
        //     counted before the password is checked, so attempts that arrive together cannot all
        //     reach the hasher before the first failure is recorded.
        Admission admission = loginAttempts.admit(Pool.MEMBER, request.username());
        if (!admission.admitted()) {
            log.warn("MEMBER AUTH BLOCKED · username={} · reason=locked-out · at={}",
                    request.username(), clock.instant());
            throw lockedOut(admission);
        }

        // 2. Authenticate. An unknown username and a wrong password raise the same exception after
        //    the same BCrypt work, so neither the answer nor its timing tells which names exist.
        Optional<User> found = userRepository.findByUsername(request.username());
        String storedHash = found.map(User::getPasswordHash).orElse(DUMMY_HASH);
        boolean passwordMatches = passwordHasher.matches(request.rawPassword(), storedHash);
        if (found.isEmpty() || !passwordMatches) {
            log.warn("MEMBER AUTH FAILED · username={} · failures={}", request.username(), admission.attempts());
            throw new AuthenticationFailedException();
        }
        User user = found.get();
        loginAttempts.clear(Pool.MEMBER, request.username());

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

        log.info("member logged in: username={} id={}", user.getUsername(), user.getUserId());

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
    // on the Jpa adapters and every admin token would fail validation as "session not found"
    // (member login is unaffected — it promotes an already-persisted guest session).
    @Transactional
    public AuthTokenDTO signInAsAdmin(String username, String rawPassword) {
        Admission admission = loginAttempts.admit(Pool.ADMIN, username);
        if (!admission.admitted()) {
            log.warn("ADMIN AUTH BLOCKED · username={} · reason=locked-out · at={}", username, clock.instant());
            throw lockedOut(admission);
        }

        // An unknown admin costs the same BCrypt work as a wrong password, as in member login.
        Admin admin = adminRepository.findByUsername(username == null ? "" : username.trim());
        String storedHash = admin == null ? DUMMY_HASH : admin.getPasswordHash();
        boolean passwordMatches = passwordHasher.matches(rawPassword, storedHash);
        if (admin == null || !passwordMatches) {
            log.warn("ADMIN AUTH FAILED · username={} · reason={} · failures={} · at={}",
                    username, admin == null ? "unknown-admin" : "bad-password", admission.attempts(),
                    clock.instant());
            throw new AuthenticationFailedException();
        }

        loginAttempts.clear(Pool.ADMIN, username);
        String token = sessionManager.generateAdminToken(admin.getId(), admin.getUsername());
        long expiresAt = sessionManager.extractExpiration(token);
        log.info("ADMIN AUTH OK · username={} · id={}", admin.getUsername(), admin.getId());
        return new AuthTokenDTO(token, expiresAt, admin.getId(), admin.getUsername(), true);
    }

    // The lock lasts until the attempt window ends, so the message gives the time left in it.
    private AccountLockedException lockedOut(Admission admission) {
        Duration left = Duration.between(clock.instant(), admission.windowEndsAt());
        long minutesLeft = Math.max(1, left.toMinutes() + (left.toSecondsPart() > 0 ? 1 : 0));
        return new AccountLockedException("Too many failed attempts. Try again in about "
                + (minutesLeft == 1 ? "a minute." : minutesLeft + " minutes."));
    }

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
        if (!session.isGuest()) {
            throw new GuestSessionRequiredException("session is not a guest session");
        }
        if (session.isExpiredAt(clock.instant())) {
            throw new GuestSessionRequiredException("guest session expired");
        }
        return session;
    }
}
