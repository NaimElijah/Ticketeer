package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ticketing.system.Core.Application.dto.AuthTokenDTO;
import com.ticketing.system.Core.Application.dto.LoginDTO;
import com.ticketing.system.Core.Application.dto.GuestSessionDTO;
import com.ticketing.system.Core.Application.dto.LoginRequestDTO;
import com.ticketing.system.Core.Application.dto.LogoutRequestDTO;
import com.ticketing.system.Core.Application.dto.RegisterRequestDTO;
import com.ticketing.system.Core.Application.interfaces.IPasswordHasher;
import com.ticketing.system.Core.Application.interfaces.ISessionManager;
import com.ticketing.system.Core.Application.interfaces.ISystemMetrics;
import com.ticketing.system.Core.Application.services.AuthenticationService;
import com.ticketing.system.Core.Application.services.NotificationDispatchService;
import com.ticketing.system.Core.Application.services.ReservationService;
import com.ticketing.system.Core.Domain.ActiveOrder.ActiveOrder;
import com.ticketing.system.Core.Domain.ActiveOrder.IActiveOrderRepository;
import com.ticketing.system.Core.Domain.Admin.Admin;
import com.ticketing.system.Core.Domain.Admin.IAdminRepository;
import com.ticketing.system.shared.exception.AccountLockedException;
import com.ticketing.system.shared.exception.AuthenticationFailedException;
import com.ticketing.system.shared.exception.DuplicateEmailException;
import com.ticketing.system.shared.exception.DuplicateUsernameException;
import com.ticketing.system.shared.exception.GuestSessionRequiredException;
import com.ticketing.system.shared.exception.InvalidEmailFormatException;
import com.ticketing.system.shared.exception.WeakPasswordException;
import com.ticketing.system.Core.Domain.users.ISessionRepository;
import com.ticketing.system.Core.Domain.users.IUserRepository;
import com.ticketing.system.Core.Domain.users.Session;
import com.ticketing.system.Core.Domain.users.User;

class AuthenticationServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final long GUEST_IDLE_MINUTES = 30;
    private static final long MEMBER_TTL_MINUTES = 1440;

    private IUserRepository mockUserRepo;
    private IPasswordHasher mockHasher;
    private ISessionManager mockSessionManager;
    private ISessionRepository mockSessionRepo;
    private IActiveOrderRepository mockActiveOrderRepo;
    private IAdminRepository mockAdminRepo;
    private Clock fixedClock;
    private AuthenticationService service;
    private NotificationDispatchService mockNotification;
    private ReservationService mockReservation;

    @BeforeEach
    void setUp() {
        mockUserRepo = mock(IUserRepository.class);
        mockHasher = mock(IPasswordHasher.class);
        mockSessionManager = mock(ISessionManager.class);
        mockNotification = mock(NotificationDispatchService.class);
        mockReservation = mock(ReservationService.class);
        mockSessionRepo = mock(ISessionRepository.class);
        mockActiveOrderRepo = mock(IActiveOrderRepository.class);
        mockAdminRepo = mock(IAdminRepository.class);
        fixedClock = Clock.fixed(T0, ZoneOffset.UTC);
        service = new AuthenticationService(
                mockUserRepo, mockHasher, mockSessionManager, mockReservation, mockNotification,
                mockSessionRepo, mockActiveOrderRepo, mock(ISystemMetrics.class),
                fixedClock, mockAdminRepo, GUEST_IDLE_MINUTES, MEMBER_TTL_MINUTES, 5, 15L);
        // Default mocks: no cart for anyone. Individual D9a tests override.
        when(mockActiveOrderRepo.getBySessionId(any())).thenReturn(Optional.empty());
        when(mockActiveOrderRepo.getByUserId(anyInt())).thenReturn(null);
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
        when(mockReservation.restoreActiveOrder(7)).thenReturn(null);
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
        when(mockReservation.restoreActiveOrder(7)).thenReturn(null);
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
    // ----------------------------------------------------------------------

    @Test
    void login_whenGuestCartExists_promotesItToMember() {
        mockValidGuestSession("preserved-sid");
        ActiveOrder guestCart = ActiveOrder.forGuest("preserved-sid");
        when(mockActiveOrderRepo.getBySessionId("preserved-sid")).thenReturn(Optional.of(guestCart));

        User user = new User(7, "alice", "alice@example.com", "STORED_HASH", 56);
        when(mockUserRepo.findByUsername("alice")).thenReturn(Optional.of(user));
        when(mockHasher.matches("Password1", "STORED_HASH")).thenReturn(true);
        when(mockSessionManager.generateTokenForSession(any(), any())).thenReturn("TOK");

        service.login(new LoginRequestDTO("alice", "Password1", "preserved-sid"));

        // Cart was promoted in place — userId now set, sessionId preserved.
        assertTrue(guestCart.isMember());
        assertEquals(7, guestCart.getUserId());
        assertEquals("preserved-sid", guestCart.getSessionId());
        verify(mockActiveOrderRepo, times(1)).save(guestCart);
        // The orphan-member-cart restoration path NOT taken.
        verify(mockActiveOrderRepo, never()).getByUserId(anyInt());
    }

    @Test
    void login_whenNoGuestCartButOrphanedMemberCartExists_restoresIt() {
        mockValidGuestSession("new-sid");
        // No Guest cart this session.
        when(mockActiveOrderRepo.getBySessionId("new-sid")).thenReturn(Optional.empty());
        // Prior Member cart from previous logout (sessionId is stale).
        ActiveOrder priorCart = ActiveOrder.forMember(7, "old-stale-sid");
        when(mockActiveOrderRepo.getByUserId(7)).thenReturn(priorCart);

        User user = new User(7, "alice", "alice@example.com", "STORED_HASH", 56);
        when(mockUserRepo.findByUsername("alice")).thenReturn(Optional.of(user));
        when(mockHasher.matches("Password1", "STORED_HASH")).thenReturn(true);
        when(mockSessionManager.generateTokenForSession(any(), any())).thenReturn("TOK");

        service.login(new LoginRequestDTO("alice", "Password1", "new-sid"));

        // Prior cart's sessionId rebound to the new session.
        assertEquals("new-sid", priorCart.getSessionId());
        assertEquals(7, priorCart.getUserId());
        verify(mockActiveOrderRepo, times(1)).save(priorCart);
    }

    @Test
    void login_whenNoCartAtAll_isNoOpOnCartRepo() {
        mockValidGuestSession("sid-1");
        // Default mocks return no cart on either lookup.

        User user = new User(7, "alice", "alice@example.com", "STORED_HASH", 56);
        when(mockUserRepo.findByUsername("alice")).thenReturn(Optional.of(user));
        when(mockHasher.matches("Password1", "STORED_HASH")).thenReturn(true);
        when(mockSessionManager.generateTokenForSession(any(), any())).thenReturn("TOK");

        service.login(new LoginRequestDTO("alice", "Password1", "sid-1"));

        // No cart-save happened.
        verify(mockActiveOrderRepo, never()).save(any());
    }

    @Test
    void logout_doesNotTouchCart() {
        // D9a: Member cart must survive logout, restored on next login.
        service.logout(new LogoutRequestDTO("SOME_TOKEN"));
        verify(mockActiveOrderRepo, never()).delete(any());
        verify(mockActiveOrderRepo, never()).save(any());
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
