package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ticketing.system.identity.adapter.out.persistence.MemorySessionRepository;
import com.ticketing.system.identity.adapter.out.security.JwtSessionManager;
import com.ticketing.system.identity.config.LockoutProperties;
import com.ticketing.system.identity.config.SessionProperties;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.LoginDTO;
import com.ticketing.system.shared.dto.GuestSessionDTO;
import com.ticketing.system.shared.dto.LoginRequestDTO;
import com.ticketing.system.shared.dto.LogoutRequestDTO;
import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.identity.application.port.out.PasswordHasher;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.shared.metrics.ISystemMetrics;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.notifications.application.service.NotificationDispatchService;
import com.ticketing.system.identity.application.port.out.CartRestorationPort;
import com.ticketing.system.identity.domain.Admin;
import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.shared.exception.AccountLockedException;
import com.ticketing.system.shared.exception.AuthenticationFailedException;
import com.ticketing.system.shared.exception.DuplicateEmailException;
import com.ticketing.system.shared.exception.DuplicateUsernameException;
import com.ticketing.system.shared.exception.GuestSessionRequiredException;
import com.ticketing.system.shared.exception.InvalidEmailFormatException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.SessionExpiredException;
import com.ticketing.system.shared.exception.WeakPasswordException;
import com.ticketing.system.identity.application.port.out.SessionRepository;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.Session;
import com.ticketing.system.identity.domain.User;

class AuthenticationServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final long GUEST_IDLE_MINUTES = 30;
    private static final long MEMBER_TTL_MINUTES = 1440;
    // At least 256 bits, as JwtSessionManager requires of its HMAC secret.
    private static final String JWT_TEST_SECRET =
            "unit-test-secret-must-be-long-enough-for-hmac-sha-256-please-ignore-me";

    private UserRepository mockUserRepo;
    private PasswordHasher mockHasher;
    private SessionManager mockSessionManager;
    private SessionRepository mockSessionRepo;
    private AdminRepository mockAdminRepo;
    private Clock fixedClock;
    private AuthenticationService service;
    private NotificationDispatchService mockNotification;
    private CartRestorationPort mockCartRestorationPort;

    @BeforeEach
    void setUp() {
        mockUserRepo = mock(UserRepository.class);
        mockHasher = mock(PasswordHasher.class);
        mockSessionManager = mock(SessionManager.class);
        mockNotification = mock(NotificationDispatchService.class);
        mockCartRestorationPort = mock(CartRestorationPort.class);
        mockSessionRepo = mock(SessionRepository.class);
        mockAdminRepo = mock(AdminRepository.class);
        fixedClock = Clock.fixed(T0, ZoneOffset.UTC);
        service = new AuthenticationService(
                mockUserRepo, mockHasher, mockSessionManager, mockCartRestorationPort, mockNotification,
                mockSessionRepo, mock(ISystemMetrics.class),
                fixedClock, mockAdminRepo,
                new SessionProperties(MEMBER_TTL_MINUTES, GUEST_IDLE_MINUTES), new LockoutProperties(5, 15L));
    }

    /**
     * Returns a valid Guest session and mocks sessionRepo.findById(sid) to return
     * it.
     */
    private Session mockValidGuestSession(String sid) {
        Instant expiry = T0.plus(GUEST_IDLE_MINUTES, ChronoUnit.MINUTES);
        Session guest = new Session(sid, null, T0, expiry);
        when(mockSessionRepo.findById(sid)).thenReturn(Optional.of(guest));
        return guest;
    }

    // ----------------------------------------------------------------------
    // startGuestSession
    // ----------------------------------------------------------------------

    @Test
    void startGuestSession_returnsDtoWithNonBlankId() {
        GuestSessionDTO dto = service.startGuestSession();
        assertNotNull(dto.sessionId());
        assertTrue(!dto.sessionId().isBlank());
        assertEquals(T0, dto.createdAt());
    }

    @Test
    void startGuestSession_savesGuestRow() {
        service.startGuestSession();
        ArgumentCaptor<Session> captured = ArgumentCaptor.forClass(Session.class);
        verify(mockSessionRepo, times(1)).save(captured.capture());
        Session s = captured.getValue();
        assertTrue(s.isGuest());
        assertEquals(T0, s.getCreatedAt());
    }

    @Test
    void startGuestSession_eachCallProducesDistinctIds() {
        GuestSessionDTO a = service.startGuestSession();
        GuestSessionDTO b = service.startGuestSession();
        assertNotEquals(a.sessionId(), b.sessionId());
    }

    // ----------------------------------------------------------------------
    // endGuestSession
    // ----------------------------------------------------------------------

    @Test
    void endGuestSession_deletesRow() {
        service.endGuestSession("guest-1");
        verify(mockSessionRepo, times(1)).delete("guest-1");
    }

    @Test
    void endGuestSession_nullIsNoOp() {
        service.endGuestSession(null);
        verify(mockSessionRepo, never()).delete(any());
    }

    @Test
    void endGuestSession_blankIsNoOp() {
        service.endGuestSession("   ");
        verify(mockSessionRepo, never()).delete(any());
    }

    // ----------------------------------------------------------------------
    // Register — format validation runs first (no session mock needed)
    // ----------------------------------------------------------------------

    @Test
    void givenMalformedEmail_whenRegister_thenInvalidEmailFormatExceptionThrown() {
        assertThrows(InvalidEmailFormatException.class,
                () -> service.register(new RegisterRequestDTO("alice", "not-an-email", "Password1", "guest-1", 56)));
        verifyNoInteractions(mockHasher);
        verify(mockUserRepo, never()).save(any(User.class));
        verify(mockSessionRepo, never()).findById(any());
    }

    @Test
    void givenNullEmail_whenRegister_thenInvalidEmailFormatExceptionThrown() {
        assertThrows(InvalidEmailFormatException.class,
                () -> service.register(new RegisterRequestDTO("alice", null, "Password1", "guest-1", 56)));
    }

    @Test
    void givenShortPassword_whenRegister_thenWeakPasswordExceptionThrown() {
        assertThrows(WeakPasswordException.class,
                () -> service.register(new RegisterRequestDTO("alice", "alice@example.com", "Pw1", "guest-1", 56)));
        verifyNoInteractions(mockHasher);
        verify(mockUserRepo, never()).save(any(User.class));
    }

    @Test
    void givenPasswordWithoutDigit_whenRegister_thenWeakPasswordExceptionThrown() {
        assertThrows(WeakPasswordException.class,
                () -> service
                        .register(new RegisterRequestDTO("alice", "alice@example.com", "Passwords", "guest-1", 56)));
    }

    @Test
    void givenPasswordWithoutLetter_whenRegister_thenWeakPasswordExceptionThrown() {
        assertThrows(WeakPasswordException.class,
                () -> service
                        .register(new RegisterRequestDTO("alice", "alice@example.com", "12345678", "guest-1", 56)));
    }

    @Test
    void givenNullPassword_whenRegister_thenWeakPasswordExceptionThrown() {
        assertThrows(WeakPasswordException.class,
                () -> service.register(new RegisterRequestDTO("alice", "alice@example.com", null, "guest-1", 56)));
    }

    // ----------------------------------------------------------------------
    // Register — session validation (after format check)
    // ----------------------------------------------------------------------

    @Test
    void givenNoGuestSessionId_whenRegister_thenGuestSessionRequiredExceptionThrown() {
        assertThrows(GuestSessionRequiredException.class,
                () -> service.register(new RegisterRequestDTO("alice", "alice@example.com", "Password1", null, 56)));
        verifyNoInteractions(mockHasher);
        verify(mockUserRepo, never()).save(any(User.class));
    }

    @Test
    void givenBlankGuestSessionId_whenRegister_thenGuestSessionRequiredExceptionThrown() {
        assertThrows(GuestSessionRequiredException.class,
                () -> service.register(new RegisterRequestDTO("alice", "alice@example.com", "Password1", "   ", 56)));
    }

    @Test
    void givenUnknownGuestSessionId_whenRegister_thenGuestSessionRequiredExceptionThrown() {
        when(mockSessionRepo.findById("ghost")).thenReturn(Optional.empty());
        assertThrows(GuestSessionRequiredException.class,
                () -> service.register(new RegisterRequestDTO("alice", "alice@example.com", "Password1", "ghost", 56)));
    }

    @Test
    void givenMemberSessionIdAsGuest_whenRegister_thenGuestSessionRequiredExceptionThrown() {
        Session memberSession = new Session("sid", 99, T0, T0.plusSeconds(3600));
        when(mockSessionRepo.findById("sid")).thenReturn(Optional.of(memberSession));
        assertThrows(GuestSessionRequiredException.class,
                () -> service.register(new RegisterRequestDTO("alice", "alice@example.com", "Password1", "sid", 56)));
    }

    @Test
    void givenExpiredGuestSession_whenRegister_thenGuestSessionRequiredExceptionThrown() {
        Session expired = new Session("sid", null, T0.minusSeconds(7200), T0.minusSeconds(60));
        when(mockSessionRepo.findById("sid")).thenReturn(Optional.of(expired));
        assertThrows(GuestSessionRequiredException.class,
                () -> service.register(new RegisterRequestDTO("alice", "alice@example.com", "Password1", "sid", 56)));
    }

    // ----------------------------------------------------------------------
    // Register — happy path
    // ----------------------------------------------------------------------

    @Test
    void givenRegistrationData_whenRegister_thenUserCreatedWithHashedPassword() {
        mockValidGuestSession("guest-1");
        when(mockUserRepo.existsByUsername("alice")).thenReturn(false);
        when(mockUserRepo.findByEmail("alice@example.com")).thenReturn(Optional.empty());
        when(mockUserRepo.nextId()).thenReturn(42);
        when(mockHasher.hash("Password1")).thenReturn("HASHED");

        service.register(new RegisterRequestDTO("alice", "alice@example.com", "Password1", "guest-1", 56));

        verify(mockHasher, times(1)).hash("Password1");
        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        verify(mockUserRepo, times(1)).save(savedUser.capture());
        assertEquals(42, savedUser.getValue().getUserId());
        assertEquals("alice", savedUser.getValue().getUsername());
        assertEquals("alice@example.com", savedUser.getValue().getEmail());
    }

    @Test
    void givenSuccessfulRegistration_whenCheckSession_thenStillGuest() {
        Session guest = mockValidGuestSession("guest-1");
        when(mockUserRepo.existsByUsername(any())).thenReturn(false);
        when(mockUserRepo.findByEmail(any())).thenReturn(Optional.empty());
        when(mockUserRepo.nextId()).thenReturn(1);
        when(mockHasher.hash(any())).thenReturn("HASHED");

        service.register(new RegisterRequestDTO("bob", "bob@example.com", "Password1", "guest-1", 56));

        // II.1.4 / D10a: session must remain Guest after register.
        assertTrue(guest.isGuest());
        // No JWT issued — session-establishing collaborators not invoked.
        verify(mockSessionManager, never()).generateToken(anyInt(), any());
        verify(mockSessionManager, never()).generateTokenForSession(any(), any());
    }

    @Test
    void givenTakenUsername_whenRegister_thenDuplicateUsernameExceptionThrown() {
        mockValidGuestSession("guest-1");
        when(mockUserRepo.existsByUsername("alice")).thenReturn(true);

        assertThrows(DuplicateUsernameException.class,
                () -> service
                        .register(new RegisterRequestDTO("alice", "alice@example.com", "Password1", "guest-1", 56)));
        verifyNoInteractions(mockHasher);
        verify(mockUserRepo, never()).save(any(User.class));
    }

    @Test
    void givenTakenEmail_whenRegister_thenDuplicateEmailExceptionThrown() {
        mockValidGuestSession("guest-1");
        when(mockUserRepo.existsByUsername("alice")).thenReturn(false);
        when(mockUserRepo.findByEmail("alice@example.com"))
                .thenReturn(Optional.of(new User(1, "other", "alice@example.com", "HASH", 59)));

        assertThrows(DuplicateEmailException.class,
                () -> service
                        .register(new RegisterRequestDTO("alice", "alice@example.com", "Password1", "guest-1", 56)));
        verifyNoInteractions(mockHasher);
        verify(mockUserRepo, never()).save(any(User.class));
    }

    @Test
    void givenBadEmailAndTakenUsername_whenRegister_thenFormatFailsFirst() {
        // Ordering check: format validators run before any repo lookup OR session
        // check.
        when(mockUserRepo.existsByUsername(any())).thenReturn(true);

        assertThrows(InvalidEmailFormatException.class,
                () -> service.register(new RegisterRequestDTO("alice", "not-an-email", "Password1", "guest-1", 56)));
        verify(mockUserRepo, never()).existsByUsername(any());
        verify(mockSessionRepo, never()).findById(any());
    }

    // ----------------------------------------------------------------------
    // Register — error messages never carry the email address (SLR.2).
    // LoggingAspect logs every application-service exception message at WARN,
    // so an address in the message would reach production logs. Usernames in
    // messages and log lines are open question OQ-26 in requirements.md.
    // ----------------------------------------------------------------------

    @Test
    void GivenTakenEmail_WhenRegister_ThenErrorMessageOmitsTheEmail() {
        String takenEmail = "dana.levi@example.com";
        String localPart = "dana.levi";
        mockValidGuestSession("guest-1");
        when(mockUserRepo.existsByUsername("alice")).thenReturn(false);
        when(mockUserRepo.findByEmail(takenEmail))
                .thenReturn(Optional.of(new User(1, "other", takenEmail, "HASH", 59)));

        DuplicateEmailException thrown = assertThrows(DuplicateEmailException.class,
                () -> service.register(new RegisterRequestDTO("alice", takenEmail, "Password1", "guest-1", 56)));

        String message = String.valueOf(thrown.getMessage());
        assertFalse(message.contains(localPart),
                "DuplicateEmailException message must not contain the email address or its local part, but was: "
                        + message);
    }

    @Test
    void GivenMalformedEmail_WhenRegister_ThenErrorMessageOmitsTheEmail() {
        String malformedEmail = "dana.levi@example";
        String localPart = "dana.levi";

        InvalidEmailFormatException thrown = assertThrows(InvalidEmailFormatException.class,
                () -> service.register(new RegisterRequestDTO("alice", malformedEmail, "Password1", "guest-1", 56)));

        String message = String.valueOf(thrown.getMessage());
        assertFalse(message.contains(localPart),
                "InvalidEmailFormatException message must not contain the submitted address or its local part, but was: "
                        + message);
    }

    // ----------------------------------------------------------------------
    // Login — guest session required (D10a + spec II.1.5)
    // ----------------------------------------------------------------------

    @Test
    void givenNoGuestSessionId_whenLogin_thenGuestSessionRequiredExceptionThrown() {
        assertThrows(GuestSessionRequiredException.class,
                () -> service.login(new LoginRequestDTO("alice", "Password1", null)));
        verifyNoInteractions(mockUserRepo);
        verifyNoInteractions(mockHasher);
    }

    @Test
    void givenUnknownGuestSessionId_whenLogin_thenGuestSessionRequiredExceptionThrown() {
        when(mockSessionRepo.findById("ghost")).thenReturn(Optional.empty());
        assertThrows(GuestSessionRequiredException.class,
                () -> service.login(new LoginRequestDTO("alice", "Password1", "ghost")));
    }

    @Test
    void givenMemberSessionIdAsGuest_whenLogin_thenGuestSessionRequiredExceptionThrown() {
        Session memberSession = new Session("sid", 99, T0, T0.plusSeconds(3600));
        when(mockSessionRepo.findById("sid")).thenReturn(Optional.of(memberSession));
        assertThrows(GuestSessionRequiredException.class,
                () -> service.login(new LoginRequestDTO("alice", "Password1", "sid")));
    }

    @Test
    void givenExpiredGuestSession_whenLogin_thenGuestSessionRequiredExceptionThrown() {
        Session expired = new Session("sid", null, T0.minusSeconds(7200), T0.minusSeconds(60));
        when(mockSessionRepo.findById("sid")).thenReturn(Optional.of(expired));
        assertThrows(GuestSessionRequiredException.class,
                () -> service.login(new LoginRequestDTO("alice", "Password1", "sid")));
    }

    // ----------------------------------------------------------------------
    // Login — happy path + promotion + credential failures
    // ----------------------------------------------------------------------

    @Test
    void givenValidCredentials_whenLogin_thenTokenIssued() {
        mockValidGuestSession("guest-1");
        User user = new User(7, "alice", "alice@example.com", "STORED_HASH", 56);
        when(mockUserRepo.findByUsername("alice")).thenReturn(Optional.of(user));
        when(mockHasher.matches("Password1", "STORED_HASH")).thenReturn(true);
        when(mockSessionManager.generateTokenForSession(any(), any())).thenReturn("ISSUED_TOKEN");
        when(mockSessionManager.extractExpiration("ISSUED_TOKEN")).thenReturn(9999L);
        when(mockCartRestorationPort.restoreActiveOrder(7)).thenReturn(null);
        when(mockNotification.deliverPending(7)).thenReturn(null);

        LoginDTO loginResult = service.login(new LoginRequestDTO("alice", "Password1", "guest-1"));
        AuthTokenDTO result = loginResult.authToken();

        assertEquals("ISSUED_TOKEN", result.token());
        assertEquals(9999L, result.expiresAtEpochMillis());
        assertEquals(7, result.userId());
        assertEquals("alice", result.username());
    }

    @Test
    void givenValidCredentials_whenLogin_thenSessionPromotedInPlace() {
        Session guest = mockValidGuestSession("preserved-sid");
        User user = new User(7, "alice", "alice@example.com", "STORED_HASH", 56);
        when(mockUserRepo.findByUsername("alice")).thenReturn(Optional.of(user));
        when(mockHasher.matches("Password1", "STORED_HASH")).thenReturn(true);
        when(mockSessionManager.generateTokenForSession(any(), any())).thenReturn("TOK");

        service.login(new LoginRequestDTO("alice", "Password1", "preserved-sid"));

        // Same Session object — userId now set, sessionId preserved.
        assertTrue(guest.isMember());
        assertEquals(7, guest.getUserId());
        assertEquals("preserved-sid", guest.getSessionId());
        // Repo save was called on the now-promoted session.
        verify(mockSessionRepo, times(1)).save(guest);
        // JWT issued via generateTokenForSession (preserving the sid), not
        // generateToken.
        verify(mockSessionManager, times(1)).generateTokenForSession(guest, "alice");
        verify(mockSessionManager, never()).generateToken(anyInt(), any());
    }

    @Test
    void givenWrongPassword_whenLogin_thenAuthenticationFailedExceptionThrown() {
        mockValidGuestSession("guest-1");
        User user = new User(7, "alice", "alice@example.com", "STORED_HASH", 77);
        when(mockUserRepo.findByUsername("alice")).thenReturn(Optional.of(user));
        when(mockHasher.matches("wrong", "STORED_HASH")).thenReturn(false);
        when(mockCartRestorationPort.restoreActiveOrder(7)).thenReturn(null);
        when(mockNotification.deliverPending(7)).thenReturn(null);

        assertThrows(AuthenticationFailedException.class,
                () -> service.login(new LoginRequestDTO("alice", "wrong", "guest-1")));
        verify(mockSessionManager, never()).generateTokenForSession(any(), any());
    }

    @Test
    void givenNoSuchUser_whenLogin_thenSameGenericRejection() {
        mockValidGuestSession("guest-1");
        when(mockUserRepo.findByUsername("ghost")).thenReturn(Optional.empty());

        // Same exception type as wrong-password — no enumeration leak.
        assertThrows(AuthenticationFailedException.class,
                () -> service.login(new LoginRequestDTO("ghost", "whatever", "guest-1")));
        verifyNoInteractions(mockHasher);
        verify(mockSessionManager, never()).generateTokenForSession(any(), any());
    }

    @Test
    void givenInvalidCredentials_whenLogin_thenRejected() {
        mockValidGuestSession("guest-1");
        User user = new User(7, "alice", "alice@example.com", "STORED_HASH", 89);
        when(mockUserRepo.findByUsername("alice")).thenReturn(Optional.of(user));
        when(mockHasher.matches("badpass", "STORED_HASH")).thenReturn(false);

        assertThrows(AuthenticationFailedException.class,
                () -> service.login(new LoginRequestDTO("alice", "badpass", "guest-1")));
    }

    // ----------------------------------------------------------------------
    // Delegates / logout — unchanged from Phase 2
    // ----------------------------------------------------------------------

    @Test
    void extractUserId_delegatesToSessionManager() {
        when(mockSessionManager.extractUserId("T")).thenReturn(42);
        assertEquals(42, service.extractUserId("T"));
        verify(mockSessionManager).extractUserId("T");
    }

    @Test
    void validateToken_delegatesToSessionManager() {
        when(mockSessionManager.validateToken("T")).thenReturn(true);
        assertTrue(service.validateToken("T"));
        verify(mockSessionManager).validateToken("T");
    }

    // liveMemberId answers "is this stored token still a member?" for the UI. An expired or revoked
    // token is an expected answer there, not a failure, so it comes back empty instead of throwing
    // (a throw would also be logged at WARN by LoggingAspect on every such check).
    @Test
    void GivenALiveToken_WhenLiveMemberIdIsAsked_ThenTheTokensUserId() {
        when(mockSessionManager.validateToken("T")).thenReturn(true);
        when(mockSessionManager.extractUserId("T")).thenReturn(42);

        assertEquals(OptionalInt.of(42), service.liveMemberId("T"));
    }

    @Test
    void GivenAnExpiredToken_WhenLiveMemberIdIsAsked_ThenEmptyWithoutThrowing() {
        when(mockSessionManager.validateToken("T")).thenThrow(new SessionExpiredException());

        OptionalInt memberId = assertDoesNotThrow(() -> service.liveMemberId("T"));

        assertEquals(OptionalInt.empty(), memberId, "an expired token names no live member");
    }

    @Test
    void GivenARevokedToken_WhenLiveMemberIdIsAsked_ThenEmptyWithoutThrowing() {
        when(mockSessionManager.validateToken("T")).thenThrow(new InvalidTokenException("revoked"));

        OptionalInt memberId = assertDoesNotThrow(() -> service.liveMemberId("T"));

        assertEquals(OptionalInt.empty(), memberId, "a revoked or invalid token names no live member");
    }

    // Admin and member ids share one id space, so an admin token must never read as the member with
    // the admin's id (#185). Runs on the real session manager: the answer depends on the token's kind.
    @Test
    void GivenAnAdminToken_WhenLiveMemberIdIsAsked_ThenEmpty() {
        int sharedId = 1;
        JwtSessionManager realSessionManager = new JwtSessionManager(JWT_TEST_SECRET,
                new SessionProperties(MEMBER_TTL_MINUTES, GUEST_IDLE_MINUTES),
                new MemorySessionRepository(fixedClock), fixedClock);
        AuthenticationService serviceOnRealSessions = new AuthenticationService(
                mockUserRepo, mockHasher, realSessionManager, mockCartRestorationPort, mockNotification,
                mockSessionRepo, mock(ISystemMetrics.class),
                fixedClock, mockAdminRepo,
                new SessionProperties(MEMBER_TTL_MINUTES, GUEST_IDLE_MINUTES), new LockoutProperties(5, 15L));
        String adminToken = realSessionManager.generateAdminToken(sharedId, "admin");

        assertEquals(OptionalInt.empty(), serviceOnRealSessions.liveMemberId(adminToken),
                "an admin token names no member, even when a member has the admin's id");
    }

    @Test
    void GivenAnUnexpectedValidationFailure_WhenLiveMemberIdIsAsked_ThenItPropagates() {
        when(mockSessionManager.validateToken("T")).thenThrow(new IllegalStateException("session store down"));

        assertThrows(IllegalStateException.class, () -> service.liveMemberId("T"),
                "only an expired or rejected token means \"no member\"; other failures must surface");
    }

    @Test
    void logout_delegatesToSessionManagerInvalidate() {
        service.logout(new LogoutRequestDTO("SOME_TOKEN"));
        verify(mockSessionManager).invalidate("SOME_TOKEN");
    }

    @Test
    void logout_idempotent_doesNotThrowForGarbageToken() {
        service.logout(new LogoutRequestDTO("not-a-real-token"));
        verify(mockSessionManager).invalidate("not-a-real-token");
    }

    // ----------------------------------------------------------------------
    // D9a — cart handling on login promotion
    //
    // The concrete guest-cart-merge / orphaned-member-cart-restore behavior now
    // lives in the sales-owned CartRestorationAdapter (see CartRestorationAdapterTest).
    // AuthenticationService's contract is only that login delegates to the port,
    // inside the login transaction, with the promoted session id and user id.
    // ----------------------------------------------------------------------

    @Test
    void login_delegatesCartMergeToPortOnPromotion() {
        mockValidGuestSession("preserved-sid");
        User user = new User(7, "alice", "alice@example.com", "STORED_HASH", 56);
        when(mockUserRepo.findByUsername("alice")).thenReturn(Optional.of(user));
        when(mockHasher.matches("Password1", "STORED_HASH")).thenReturn(true);
        when(mockSessionManager.generateTokenForSession(any(), any())).thenReturn("TOK");

        service.login(new LoginRequestDTO("alice", "Password1", "preserved-sid"));

        // Login hands the D9a merge off to the sales adapter with the promoted ids.
        verify(mockCartRestorationPort, times(1)).mergeGuestCartOnPromotion("preserved-sid", 7);
    }

    @Test
    void login_returnsRestoredCartFromPort() {
        mockValidGuestSession("sid-1");
        User user = new User(7, "alice", "alice@example.com", "STORED_HASH", 56);
        when(mockUserRepo.findByUsername("alice")).thenReturn(Optional.of(user));
        when(mockHasher.matches("Password1", "STORED_HASH")).thenReturn(true);
        when(mockSessionManager.generateTokenForSession(any(), any())).thenReturn("TOK");
        // The cart the sales adapter restores is carried through into the LoginDTO.
        com.ticketing.system.shared.dto.ActiveOrderDTO restored =
                new com.ticketing.system.shared.dto.ActiveOrderDTO(7, null, null, 0L, 0.0, java.util.List.of());
        when(mockCartRestorationPort.restoreActiveOrder(7)).thenReturn(restored);

        LoginDTO loginResult = service.login(new LoginRequestDTO("alice", "Password1", "sid-1"));

        // Restored cart flows through unchanged (behavior preserved by the port).
        assertEquals(restored, loginResult.activeOrder());
        verify(mockCartRestorationPort, times(1)).restoreActiveOrder(7);
    }

    @Test
    void logout_doesNotTouchCart() {
        // D9a: Member cart must survive logout, restored on next login — logout must
        // never invoke the cart-restoration port at all.
        service.logout(new LogoutRequestDTO("SOME_TOKEN"));
        verifyNoInteractions(mockCartRestorationPort);
    }

    // ---- admin sign-in (#290) ----

    @Test
    void signInAsAdmin_validCredentials_returnsAdminFlaggedToken() {
        when(mockAdminRepo.findByUsername("admin")).thenReturn(new Admin(1, "admin", "hashed", true));
        when(mockHasher.matches("pw", "hashed")).thenReturn(true);
        when(mockSessionManager.generateAdminToken(1, "admin")).thenReturn("admin-jwt");
        when(mockSessionManager.extractExpiration("admin-jwt")).thenReturn(999L);

        AuthTokenDTO token = service.signInAsAdmin("admin", "pw");

        assertTrue(token.isAdmin());
        assertEquals("admin", token.username());
        assertEquals("admin-jwt", token.token());
    }

    @Test
    void signInAsAdmin_unknownUsername_throwsAuthenticationFailed() {
        when(mockAdminRepo.findByUsername("ghost")).thenReturn(null);
        assertThrows(AuthenticationFailedException.class, () -> service.signInAsAdmin("ghost", "pw"));
    }

    @Test
    void signInAsAdmin_wrongPassword_throwsAuthenticationFailed() {
        when(mockAdminRepo.findByUsername("admin")).thenReturn(new Admin(1, "admin", "hashed", true));
        when(mockHasher.matches("wrong", "hashed")).thenReturn(false);
        assertThrows(AuthenticationFailedException.class, () -> service.signInAsAdmin("admin", "wrong"));
    }

    @Test
    void signInAsAdmin_locksAfterFiveFailures() {
        when(mockAdminRepo.findByUsername("admin")).thenReturn(new Admin(1, "admin", "hashed", true));
        when(mockHasher.matches(any(), any())).thenReturn(false);

        for (int i = 0; i < 5; i++) {
            assertThrows(AuthenticationFailedException.class, () -> service.signInAsAdmin("admin", "wrong"));
        }
        // 6th attempt is blocked even with the correct password.
        when(mockHasher.matches("pw", "hashed")).thenReturn(true);
        assertThrows(AccountLockedException.class, () -> service.signInAsAdmin("admin", "pw"));
    }
}
